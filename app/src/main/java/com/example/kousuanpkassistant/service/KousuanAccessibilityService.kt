package com.example.kousuanpkassistant.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.kousuanpkassistant.R
import com.example.kousuanpkassistant.RuntimeControl
import com.example.kousuanpkassistant.automation.AccessibilityNodeQuestionReader
import com.example.kousuanpkassistant.automation.ClickDispatcher
import com.example.kousuanpkassistant.automation.UnimplementedScreenshotQuestionReader
import com.example.kousuanpkassistant.data.ConfigRepository
import com.example.kousuanpkassistant.model.AnswerMode
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.DetectedQuestion
import com.example.kousuanpkassistant.model.QuestionReadResult
import com.example.kousuanpkassistant.model.TargetApp
import com.example.kousuanpkassistant.receiver.SafetyStopReceiver
import com.example.kousuanpkassistant.state.AutomationStateStore
import com.example.kousuanpkassistant.ui.MainActivity

class KousuanAccessibilityService : AccessibilityService() {
    private val instanceId = Integer.toHexString(System.identityHashCode(this))
    private val handler = Handler(Looper.getMainLooper())
    private val nodeReader = AccessibilityNodeQuestionReader()
    private val screenshotReader = UnimplementedScreenshotQuestionReader()
    private val clickDispatcher = ClickDispatcher()

    private lateinit var configRepository: ConfigRepository
    private lateinit var config: AutomationConfig
    private lateinit var overlayController: OverlayController

    @Volatile
    private var running = false
    private var runStartedAt = 0L
    private var lastTargetSeenAt = 0L
    private var lastActionAt = 0L
    private var handledCount = 0
    private var actionAttemptCount = 0
    private var errorCount = 0
    private var consecutiveErrors = 0
    private var stableSignature: String? = null
    private var stableReadCount = 0
    private var lastAnsweredSignature: String? = null
    private var lastAnsweredProgress = -1
    private var lastObservedProgress = -1
    private var progressChangedAt = 0L
    private var awaitingTransition = false
    private var lastTargetEventAt = 0L
    private var adaptiveTransitionSettleMs = BASE_TRANSITION_SETTLE_MS
    private var successfulTransitionsSinceRetry = 0
    private var unconfirmedRetriesForProgress = 0
    private var lastNotFoundLogAt = 0L
    private var lastEventPackage = ""
    private var rapidBatchSignature: String? = null
    private var rapidBatchFirstSeenAt = 0L
    private var rapidBatchDispatched = false
    private var rapidBatchExpectedProgress = 0
    private var rapidBatchFinishedAt = 0L
    private var rapidBatchCompletedBefore = 0
    private var rapidBatchLastObservedProgress = 0
    private var rapidBatchProgressChangedAt = 0L
    private var currentQuestionTotal = 0
    private var xiaoyuanStartGateOpen = false
    private var xiaoyuanWasArmedBeforeGameplay = false
    private var xiaoyuanGameplayReadyAt = 0L
    private var xiaoyuanGameplayReadySignature: String? = null
    private var xiaoyuanGameplayReadyReads = 0
    private var nextScanAt = 0L
    private var nextAutoProbeAt = 0L
    private var autoStartedCurrentRun = false
    private var autoRearmPending = false
    private var autoPreviousFinalProgress = 0
    private var zuoyebangAutoRearmPending = false
    private var zuoyebangAutoSawGameplayExit = false
    private var zuoyebangAutoPreviousFinalProgress = 0
    private var zuoyebangAutoFinishedAt = 0L
    private var zuoyebangAutoGameplayExitFirstSeenAt = 0L
    private var zuoyebangAutoAllowDirectRearm = false
    private var zuoyebangCurrentMatchSignature: String? = null
    private var zuoyebangCompletedMatchSignature: String? = null
    private var zuoyebangQuestionPlanStartProgress = 0
    private var zuoyebangQuestionPlan: List<DetectedQuestion> = emptyList()

    private val scanRunnable = Runnable {
        nextScanAt = 0L
        processActiveWindow()
    }
    private val autoProbeRunnable = Runnable {
        nextAutoProbeAt = 0L
        probeForAutoStart()
    }
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (checkSafetyLimits()) {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected instance=$instanceId")
        configRepository = ConfigRepository(this)
        config = configRepository.load()
        overlayController = OverlayController(
            context = this,
            onToggle = {
                if (running) {
                    stopFromUser("悬浮窗手动停止")
                } else {
                    setAutomationRunning(true, "悬浮窗手动开始")
                }
            }
        )
        running = false
        RuntimeControl.attach(this)
        AutomationStateStore.setServiceConnected(true, "无障碍服务已连接")
        refreshOverlay()
        if (config.autoStartEnabled) scheduleAutoProbe(0L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        val wasTargetPackage = ::config.isInitialized && config.isTargetPackage(lastEventPackage)
        if (packageName.isNotEmpty()) {
            AutomationStateStore.updatePackage(packageName)
            lastEventPackage = packageName
        }
        if (event == null || !config.isTargetPackage(event.packageName)) return

        val eventAt = SystemClock.elapsedRealtime()
        lastTargetSeenAt = eventAt
        lastTargetEventAt = eventAt
        if (!running) {
            if (config.autoStartEnabled) {
                scheduleAutoProbe(if (wasTargetPackage) 0L else FOREGROUND_SETTLE_MS)
            }
            return
        }
        val delay = if (!wasTargetPackage) {
            FOREGROUND_SETTLE_MS
        } else {
            config.scanDebounceMs
        }
        scheduleScan(delay)
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt instance=$instanceId")
        setAutomationRunning(false, "无障碍服务被系统中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind instance=$instanceId")
        shutdown("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy instance=$instanceId")
        shutdown("无障碍服务已销毁")
        super.onDestroy()
    }

    @Synchronized
    fun setAutomationRunning(
        shouldRun: Boolean,
        reason: String,
        automatic: Boolean = false
    ): Boolean {
        if (!::configRepository.isInitialized) return false
        if (shouldRun == running) return true
        Log.i(TAG, "setAutomationRunning instance=$instanceId running=$shouldRun reason=$reason")

        if (shouldRun) {
            config = configRepository.load()
            if (config.targetApp == TargetApp.ZUOYEBANG && !automatic) {
                clearZuoyebangAutoRearm()
            }
            handler.removeCallbacks(autoProbeRunnable)
            nextAutoProbeAt = 0L
            autoStartedCurrentRun = automatic
            running = true
            runStartedAt = SystemClock.elapsedRealtime()
            lastTargetSeenAt = runStartedAt
            lastActionAt = 0L
            handledCount = 0
            actionAttemptCount = 0
            errorCount = 0
            consecutiveErrors = 0
            stableSignature = null
            stableReadCount = 0
            lastAnsweredSignature = null
            lastAnsweredProgress = -1
            lastObservedProgress = -1
            progressChangedAt = 0L
            awaitingTransition = false
            lastTargetEventAt = 0L
            adaptiveTransitionSettleMs = BASE_TRANSITION_SETTLE_MS
            successfulTransitionsSinceRetry = 0
            unconfirmedRetriesForProgress = 0
            lastNotFoundLogAt = 0L
            rapidBatchSignature = null
            rapidBatchFirstSeenAt = 0L
            rapidBatchDispatched = false
            rapidBatchExpectedProgress = 0
            rapidBatchFinishedAt = 0L
            rapidBatchCompletedBefore = 0
            rapidBatchLastObservedProgress = 0
            rapidBatchProgressChangedAt = 0L
            currentQuestionTotal = 0
            zuoyebangCurrentMatchSignature = null
            zuoyebangQuestionPlanStartProgress = 0
            zuoyebangQuestionPlan = emptyList()
            xiaoyuanStartGateOpen = config.targetApp != TargetApp.XIAOYUAN
            xiaoyuanWasArmedBeforeGameplay = false
            xiaoyuanGameplayReadyAt = 0L
            xiaoyuanGameplayReadySignature = null
            xiaoyuanGameplayReadyReads = 0
            nextScanAt = 0L
            AutomationStateStore.beginRun()
            AutomationStateStore.appendLog(
                "应用：${config.targetApp.displayName}；目标包：${config.targetPackages.joinToString()}；" +
                    "模式：${config.answerMode.displayName}"
            )
            if (!screenshotReader.isAvailable) {
                AutomationStateStore.appendLog("节点读取失败时不会截图：OCR 接口仅占位")
            }
            showSafetyNotification()
            refreshOverlay()
            handler.removeCallbacks(watchdogRunnable)
            handler.post(watchdogRunnable)
            scheduleScan(0L)
        } else {
            if (config.targetApp == TargetApp.ZUOYEBANG &&
                config.autoStartEnabled &&
                !zuoyebangAutoRearmPending
            ) {
                armZuoyebangAutoRearm()
            }
            running = false
            autoStartedCurrentRun = false
            handler.removeCallbacks(scanRunnable)
            nextScanAt = 0L
            handler.removeCallbacks(watchdogRunnable)
            cancelSafetyNotification()
            AutomationStateStore.endRun(reason)
            refreshOverlay()
            if (config.targetApp == TargetApp.ZUOYEBANG && config.autoStartEnabled) {
                scheduleAutoProbe(AUTO_REARM_SCAN_MS)
            }
        }
        return true
    }

    fun stopFromUser(reason: String): Boolean {
        if (!::configRepository.isInitialized) return false
        if (config.autoStartEnabled) {
            configRepository.setAutoStartEnabled(false)
            config = configRepository.load()
            handler.removeCallbacks(autoProbeRunnable)
            nextAutoProbeAt = 0L
            AutomationStateStore.appendLog("安全停止同时关闭了全自动模式")
        }
        return setAutomationRunning(false, reason)
    }

    fun refreshConfiguration() {
        if (!::configRepository.isInitialized) return
        val previousTargetApp = config.targetApp
        val updated = configRepository.load()
        val shouldStopAutomaticRun = running && autoStartedCurrentRun && !updated.autoStartEnabled
        config = updated
        if (previousTargetApp != updated.targetApp) {
            autoRearmPending = false
            autoPreviousFinalProgress = 0
            clearZuoyebangAutoRearm()
        }
        if (shouldStopAutomaticRun) {
            setAutomationRunning(false, "全自动模式已关闭")
        } else if (config.autoStartEnabled && !running) {
            scheduleAutoProbe(0L)
        } else if (!config.autoStartEnabled) {
            handler.removeCallbacks(autoProbeRunnable)
            nextAutoProbeAt = 0L
            clearZuoyebangAutoRearm()
        }
        refreshOverlay()
        AutomationStateStore.appendLog(
            if (config.autoStartEnabled) "配置已重新载入；全自动待命" else "配置已重新载入"
        )
    }

    private fun scheduleAutoProbe(delayMs: Long) {
        if (!config.autoStartEnabled || running) return
        val delay = delayMs.coerceAtLeast(0L)
        val dueAt = SystemClock.elapsedRealtime() + delay
        if (nextAutoProbeAt != 0L && nextAutoProbeAt <= dueAt) return
        handler.removeCallbacks(autoProbeRunnable)
        nextAutoProbeAt = dueAt
        if (delay == 0L) {
            handler.postAtFrontOfQueue(autoProbeRunnable)
        } else {
            handler.postDelayed(autoProbeRunnable, delay)
        }
    }

    @Suppress("DEPRECATION")
    private fun probeForAutoStart() {
        if (running || !config.autoStartEnabled) return
        val root = rootInActiveWindow
        if (root == null) {
            if (config.targetApp == TargetApp.ZUOYEBANG && zuoyebangAutoRearmPending) {
                scheduleAutoProbe(AUTO_REARM_SCAN_MS)
            }
            return
        }
        try {
            runCatching { root.refresh() }
            val packageName = root.packageName?.toString().orEmpty()
            AutomationStateStore.updatePackage(packageName)
            if (!config.isTargetPackage(packageName)) return

            val metrics = resources.displayMetrics
            nodeReader.read(
                root,
                metrics.widthPixels,
                metrics.heightPixels,
                config,
                preferredExpressionIndex = 0
            ).use { scan ->
                val total = scan.totalQuestionCount.takeIf { it > 0 }
                    ?: config.maxActionsPerRun.coerceAtLeast(1)
                val completed = scan.completedQuestionCount.coerceIn(0, total)
                val remaining = total - completed
                val hasCurrentQuestion = scan.result is QuestionReadResult.Found
                if (config.targetApp == TargetApp.XIAOYUAN && autoRearmPending) {
                    val isFreshMatch = scan.isGameplayTimerRunning &&
                        scan.totalQuestionCount > 0 &&
                        hasCurrentQuestion &&
                        completed < autoPreviousFinalProgress
                    if (!isFreshMatch) {
                        scheduleAutoProbe(AUTO_REARM_SCAN_MS)
                        return@use
                    }
                    autoRearmPending = false
                    autoPreviousFinalProgress = 0
                    AutomationStateStore.appendLog("检测到题号已重置，确认进入新一局")
                }
                if (config.targetApp == TargetApp.ZUOYEBANG &&
                    zuoyebangAutoRearmPending
                ) {
                    val hasGameplaySurface = scan.isGameplayTimerRunning &&
                        scan.hasHandwritingArea &&
                        !scan.hasBlockingOverlay &&
                        scan.orderedQuestions.isNotEmpty()
                    val matchSignature = buildMatchSignature(total, scan.orderedQuestions)
                    if (!hasGameplaySurface) {
                        val now = SystemClock.elapsedRealtime()
                        if (zuoyebangAutoGameplayExitFirstSeenAt == 0L) {
                            zuoyebangAutoGameplayExitFirstSeenAt = now
                        }
                        if (!zuoyebangAutoSawGameplayExit &&
                            now - zuoyebangAutoGameplayExitFirstSeenAt >=
                            ZUOYEBANG_STABLE_GAMEPLAY_EXIT_MS
                        ) {
                            zuoyebangAutoSawGameplayExit = true
                            AutomationStateStore.appendLog("作业帮旧题页已退出，等待下一局")
                        }
                        scheduleAutoProbe(AUTO_REARM_SCAN_MS)
                        return@use
                    }
                    if (!zuoyebangAutoSawGameplayExit) {
                        zuoyebangAutoGameplayExitFirstSeenAt = 0L
                    }

                    val readyForNewMatch = remaining > 0 &&
                        scan.orderedQuestions.size >= remaining
                    val directFreshMatch = zuoyebangAutoAllowDirectRearm &&
                        readyForNewMatch &&
                        SystemClock.elapsedRealtime() - zuoyebangAutoFinishedAt >=
                        ZUOYEBANG_DIRECT_REARM_GRACE_MS &&
                        completed < zuoyebangAutoPreviousFinalProgress &&
                        zuoyebangCompletedMatchSignature != null &&
                        matchSignature != zuoyebangCompletedMatchSignature
                    if (!readyForNewMatch ||
                        (!zuoyebangAutoSawGameplayExit && !directFreshMatch)
                    ) {
                        scheduleAutoProbe(AUTO_REARM_SCAN_MS)
                        return@use
                    }

                    clearZuoyebangAutoRearm()
                    AutomationStateStore.appendLog("检测到作业帮新一局，解除旧局锁")
                }
                val ready = if (config.targetApp == TargetApp.ZUOYEBANG &&
                    config.rapidBatchEnabled
                ) {
                    remaining > 0 &&
                        scan.isGameplayTimerRunning &&
                        scan.hasHandwritingArea &&
                        !scan.hasBlockingOverlay &&
                        scan.orderedQuestions.size >= remaining
                } else {
                    remaining > 0 &&
                        scan.isGameplayTimerRunning &&
                        hasCurrentQuestion
                }
                if (ready) {
                    setAutomationRunning(true, "全自动识别到比赛开始", automatic = true)
                } else if (remaining > 0 &&
                    hasCurrentQuestion
                ) {
                    scheduleAutoProbe(AUTO_READY_SCAN_MS)
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun scheduleScan(delayMs: Long) {
        val delay = delayMs.coerceAtLeast(0L)
        val dueAt = SystemClock.elapsedRealtime() + delay
        if (nextScanAt != 0L && nextScanAt <= dueAt) return
        handler.removeCallbacks(scanRunnable)
        nextScanAt = dueAt
        if (delay == 0L) {
            handler.postAtFrontOfQueue(scanRunnable)
        } else {
            handler.postDelayed(scanRunnable, delay)
        }
    }

    @Suppress("DEPRECATION")
    private fun processActiveWindow() {
        if (!running || !checkSafetyLimits()) return
        val root = rootInActiveWindow
        if (root == null) {
            onQuestionNotFound("当前活动窗口没有可读取的根节点")
            scheduleScan(RETRY_SCAN_MS)
            return
        }

        try {
            runCatching { root.refresh() }
            val packageName = root.packageName?.toString().orEmpty()
            AutomationStateStore.updatePackage(packageName)
            if (!config.isTargetPackage(packageName)) return

            lastTargetSeenAt = SystemClock.elapsedRealtime()
            val metrics = resources.displayMetrics
            nodeReader.read(
                root,
                metrics.widthPixels,
                metrics.heightPixels,
                config,
                preferredExpressionIndex = lastObservedProgress.coerceAtLeast(handledCount)
            ).use { scan ->
                if (scan.totalQuestionCount > 0) {
                    currentQuestionTotal = scan.totalQuestionCount
                }
                if (waitForXiaoyuanGameplayStart(
                        scan.isGameplayTimerRunning,
                        scan.result
                    )
                ) {
                    return
                }
                if (config.targetApp == TargetApp.ZUOYEBANG && scan.hasBlockingOverlay) {
                    if (rapidBatchExpectedProgress > 0 &&
                        currentQuestionTotal > 0 &&
                        rapidBatchExpectedProgress >= currentQuestionTotal &&
                        SystemClock.elapsedRealtime() - rapidBatchFinishedAt >=
                        ZUOYEBANG_FINAL_PAGE_EXIT_SETTLE_MS
                    ) {
                        finishZuoyebangRun(currentQuestionTotal, "检测到作业帮结算弹窗")
                        return
                    }
                    rapidBatchSignature = null
                    rapidBatchFirstSeenAt = 0L
                    AutomationStateStore.setStatus("已启动，等待作业帮开局弹窗消失")
                    scheduleScan(ZUOYEBANG_BLOCKING_OVERLAY_SCAN_MS)
                    return
                }
                observeCompletedProgress(
                    scan.completedQuestionCount,
                    scan.totalQuestionCount
                )
                if (config.targetApp == TargetApp.ZUOYEBANG &&
                    scan.isGameplayTimerRunning &&
                    scan.orderedQuestions.isNotEmpty() &&
                    zuoyebangCurrentMatchSignature == null
                ) {
                    val total = scan.totalQuestionCount.takeIf { it > 0 }
                        ?: config.maxActionsPerRun.coerceAtLeast(1)
                    zuoyebangCurrentMatchSignature = buildMatchSignature(
                        total,
                        scan.orderedQuestions
                    )
                }
                if (tryHandleRapidBatch(
                        scan.orderedQuestions,
                        scan.hasHandwritingArea,
                        scan.isGameplayTimerRunning,
                        scan.completedQuestionCount,
                        scan.totalQuestionCount,
                        metrics.widthPixels,
                        metrics.heightPixels
                    )
                ) {
                    return
                }
                when (val result = scan.result) {
                    is QuestionReadResult.Found -> handleQuestion(
                        result,
                        scan.completedQuestionCount,
                        metrics.widthPixels,
                        metrics.heightPixels
                    )

                    is QuestionReadResult.NotFound -> {
                        if (!finishFinalQuestionIfConfirmed()) {
                            onQuestionNotFound(result.reason)
                            scheduleScan(IDLE_SCAN_MS)
                        }
                    }
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * The XiaoYuan page exposes the questions before the opening transition is finished. If the
     * overlay is pressed early, treating those nodes as live starts the whole rapid gesture batch
     * one animation phase too soon. Arm immediately, but release the first gesture only after the
     * gameplay timer and the same first question are present in two scans. Higher XiaoYuan ranks
     * no longer expose the old "手写区" accessibility label, so that label cannot be a hard gate.
     */
    private fun waitForXiaoyuanGameplayStart(
        isGameplayTimerRunning: Boolean,
        result: QuestionReadResult
    ): Boolean {
        if (config.targetApp != TargetApp.XIAOYUAN || xiaoyuanStartGateOpen) return false

        val question = (result as? QuestionReadResult.Found)?.question
        val ready = isGameplayTimerRunning && question != null
        if (!ready) {
            xiaoyuanWasArmedBeforeGameplay = true
            xiaoyuanGameplayReadyAt = 0L
            xiaoyuanGameplayReadySignature = null
            xiaoyuanGameplayReadyReads = 0
            AutomationStateStore.setStatus("已启动，等待小猿比赛真正开始")
            scheduleScan(XIAOYUAN_START_GATE_SCAN_MS)
            return true
        }

        val now = SystemClock.elapsedRealtime()
        if (xiaoyuanGameplayReadyAt == 0L ||
            xiaoyuanGameplayReadySignature != question.signature
        ) {
            xiaoyuanGameplayReadyAt = now
            xiaoyuanGameplayReadySignature = question.signature
            xiaoyuanGameplayReadyReads = 1
            AutomationStateStore.setStatus("检测到比赛开始，等待首题画板稳定")
            scheduleScan(XIAOYUAN_START_GATE_SCAN_MS)
            return true
        }

        xiaoyuanGameplayReadyReads++
        val requiredSettleMs = if (xiaoyuanWasArmedBeforeGameplay) {
            XIAOYUAN_EARLY_START_SETTLE_MS
        } else {
            XIAOYUAN_ACTIVE_START_SETTLE_MS
        }
        val settleRemaining = requiredSettleMs - (now - xiaoyuanGameplayReadyAt)
        if (xiaoyuanGameplayReadyReads < XIAOYUAN_START_STABLE_READS || settleRemaining > 0L) {
            AutomationStateStore.setStatus("比赛已开始，正在同步首题")
            scheduleScan(
                minOf(
                    XIAOYUAN_START_GATE_SCAN_MS,
                    settleRemaining.coerceAtLeast(1L)
                )
            )
            return true
        }

        xiaoyuanStartGateOpen = true
        val gateDelay = now - xiaoyuanGameplayReadyAt
        val startKind = if (xiaoyuanWasArmedBeforeGameplay) "提前启动" else "局中启动"
        Log.i(
            TAG,
            "xiaoyuan start gate opened instance=$instanceId kind=$startKind delay=${gateDelay}ms"
        )
        AutomationStateStore.appendLog("小猿首题已就绪：$startKind，门控 ${gateDelay}ms")
        return false
    }

    private fun tryHandleRapidBatch(
        orderedQuestions: List<DetectedQuestion>,
        hasHandwritingArea: Boolean,
        isGameplayTimerRunning: Boolean,
        completedQuestionCount: Int,
        totalQuestionCount: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (!config.rapidBatchEnabled || config.answerMode != AnswerMode.DRAW_RELATION) {
            return false
        }
        if (rapidBatchDispatched) return true

        val isXiaoyuan = config.targetApp == TargetApp.XIAOYUAN
        val total = totalQuestionCount.takeIf { it > 0 }
            ?: currentQuestionTotal.takeIf { it > 0 }
            ?: if (isXiaoyuan) return false else config.maxActionsPerRun.coerceAtLeast(1)
        currentQuestionTotal = total
        val alreadyCompleted = completedQuestionCount.coerceIn(0, total)
        val now = SystemClock.elapsedRealtime()

        if (rapidBatchExpectedProgress > 0) {
            if (alreadyCompleted != rapidBatchLastObservedProgress) {
                rapidBatchLastObservedProgress = alreadyCompleted
                rapidBatchProgressChangedAt = now
            }
            if (alreadyCompleted >= rapidBatchExpectedProgress) {
                handledCount = maxOf(handledCount, alreadyCompleted)
                AutomationStateStore.appendLog(
                    "极速批次已确认：$alreadyCompleted/$total"
                )
                val confirmedProgress = rapidBatchExpectedProgress
                clearRapidBatchConfirmation()
                if (!isXiaoyuan && confirmedProgress >= total) {
                    return finishZuoyebangRun(total, "题号已确认完成")
                }
            } else {
                val finalZuoyebangBatch = !isXiaoyuan &&
                    rapidBatchExpectedProgress >= total
                val answerPageEnded = !isGameplayTimerRunning &&
                    (!hasHandwritingArea || orderedQuestions.isEmpty())
                if (finalZuoyebangBatch &&
                    answerPageEnded &&
                    now - rapidBatchFinishedAt >= ZUOYEBANG_FINAL_PAGE_EXIT_SETTLE_MS
                ) {
                    return finishZuoyebangRun(total, "答题页已结束")
                }
                if (finalZuoyebangBatch &&
                    alreadyCompleted <= rapidBatchCompletedBefore
                ) {
                    val finalConfirmRemaining = ZUOYEBANG_FINAL_CONFIRM_TIMEOUT_MS -
                        (now - rapidBatchFinishedAt)
                    if (finalConfirmRemaining > 0L) {
                        AutomationStateStore.setStatus("最后一批已提交，等待作业帮结算")
                        scheduleScan(minOf(finalConfirmRemaining, RAPID_READY_SCAN_MS))
                        return true
                    }
                    AutomationStateStore.appendLog(
                        "最后一批长时间未给出进度；为避免整批重画已停止"
                    )
                    setAutomationRunning(false, "最后一批未确认，已禁止重复整批")
                    return true
                }
                val confirmRemaining = if (isXiaoyuan) {
                    RAPID_BATCH_ACCEPT_TIMEOUT_MS - (now - rapidBatchFinishedAt)
                } else if (alreadyCompleted <= rapidBatchCompletedBefore) {
                    ZUOYEBANG_NO_PROGRESS_ACCEPT_TIMEOUT_MS -
                        (now - rapidBatchFinishedAt)
                } else {
                    ZUOYEBANG_PROGRESS_SETTLE_MS -
                        (now - rapidBatchProgressChangedAt)
                }
                if (confirmRemaining > 0L) {
                    AutomationStateStore.setStatus(
                        "等待极速批次确认：$alreadyCompleted/$rapidBatchExpectedProgress"
                    )
                    scheduleScan(
                        minOf(
                            confirmRemaining,
                            if (isXiaoyuan) TRANSITION_SCAN_MS else RAPID_READY_SCAN_MS
                        )
                    )
                    return true
                }
                AutomationStateStore.appendLog(
                    "极速批次只确认到 $alreadyCompleted/$rapidBatchExpectedProgress，" +
                        "从实际题号继续"
                )
                handledCount = alreadyCompleted
                clearRapidBatchConfirmation()
            }
        }

        val batchSize = minOf(MAX_RAPID_BATCH_SIZE, total - alreadyCompleted)
        if (batchSize <= 0) {
            if (isXiaoyuan) {
                setAutomationRunning(false, "本局已经完成 $total 题")
            } else {
                finishZuoyebangRun(total, "读取到完整进度")
            }
            return true
        }
        val hasFullQuestionList = orderedQuestions.size >= total
        val requiredQuestionCount = if (hasFullQuestionList) {
            alreadyCompleted + batchSize
        } else {
            batchSize
        }
        if (config.targetApp == TargetApp.XIAOYUAN &&
            isGameplayTimerRunning &&
            orderedQuestions.size < requiredQuestionCount
        ) {
            rapidBatchSignature = null
            rapidBatchFirstSeenAt = 0L
            return false
        }
        if (!isXiaoyuan) {
            updateZuoyebangQuestionPlan(orderedQuestions, alreadyCompleted, total)
        }
        val plannedQuestions = if (!isXiaoyuan) {
            val offset = alreadyCompleted - zuoyebangQuestionPlanStartProgress
            if (offset >= 0) {
                zuoyebangQuestionPlan.drop(offset).take(batchSize)
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
        val hasPlannedBatch = plannedQuestions.size >= batchSize
        if (!hasHandwritingArea ||
            (!hasPlannedBatch && orderedQuestions.size < requiredQuestionCount)
        ) {
            rapidBatchSignature = null
            rapidBatchFirstSeenAt = 0L
            AutomationStateStore.setStatus(
                "已答 $alreadyCompleted 题，等待剩余题目 ${orderedQuestions.size}/$requiredQuestionCount"
            )
            scheduleScan(RAPID_READY_SCAN_MS)
            return true
        }

        val questions = if (hasPlannedBatch) {
            plannedQuestions
        } else if (hasFullQuestionList) {
            orderedQuestions.drop(alreadyCompleted).take(batchSize)
        } else {
            orderedQuestions.take(batchSize)
        }
        val signature = "$alreadyCompleted|" + questions.joinToString("|") { it.signature }
        if (signature != rapidBatchSignature) {
            rapidBatchSignature = signature
            rapidBatchFirstSeenAt = now
            val first = questions.first()
            AutomationStateStore.updateDetection(
                first.left.value,
                first.right.value,
                first.relation.symbol
            )
            AutomationStateStore.setStatus("已预读 $batchSize 题，确认界面就绪")
            Log.i(
                TAG,
                "rapid-batch preloaded instance=$instanceId completed=$alreadyCompleted remaining=$batchSize"
            )
            if (!isGameplayTimerRunning) {
                scheduleScan(RAPID_READY_SCAN_MS)
                return true
            }
        }

        val readyRemaining = config.rapidStartDelayMs - (now - rapidBatchFirstSeenAt)
        val zuoyebangAutoStartRemaining = if (!isXiaoyuan &&
            autoStartedCurrentRun &&
            alreadyCompleted == 0 &&
            actionAttemptCount == 0
        ) {
            ZUOYEBANG_AUTO_START_SETTLE_MS - (now - rapidBatchFirstSeenAt)
        } else {
            0L
        }
        if (zuoyebangAutoStartRemaining > 0L) {
            AutomationStateStore.setStatus("已预读题组，等待 READY/GO 完整结束")
            scheduleScan(minOf(zuoyebangAutoStartRemaining, RAPID_READY_SCAN_MS))
            return true
        }
        if (!isGameplayTimerRunning && readyRemaining > 0L) {
            AutomationStateStore.setStatus("已预读 $batchSize 题，等待开场动画结束")
            scheduleScan(minOf(readyRemaining, RAPID_READY_SCAN_MS))
            return true
        }

        rapidBatchDispatched = true
        val plannedDuration = (questions.size - 1) * config.rapidStrokeIntervalMs +
            config.rapidStrokeDurationMs
        val result = clickDispatcher.drawRelationBatch(
            service = this,
            questions = questions,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            config = config
        ) { completed ->
            handler.post {
                finishRapidBatch(
                    questions,
                    alreadyCompleted,
                    total,
                    completed,
                    plannedDuration
                )
            }
        }
        if (result.accepted) {
            lastActionAt = now
            actionAttemptCount += questions.size
            val activationMs = now - runStartedAt
            val sequence = questions.joinToString("  ") {
                "${it.left.value}${it.relation.symbol}${it.right.value}"
            }
            Log.i(
                TAG,
                "rapid-batch start instance=$instanceId activation=${activationMs}ms " +
                    "${result.detail} sequence=$sequence"
            )
            AutomationStateStore.appendLog("开始按钮响应：${activationMs}ms")
            AutomationStateStore.appendLog("极速序列：$sequence")
            AutomationStateStore.setStatus(result.detail)
            updateSafetyNotification(result.detail)
            overlayController.update(AutomationStateStore.snapshot())
        } else {
            rapidBatchDispatched = false
            errorCount++
            consecutiveErrors++
            AutomationStateStore.recordError(errorCount, result.detail)
            setAutomationRunning(false, "极速批量手势提交失败，已安全停止")
        }
        return true
    }

    private fun finishRapidBatch(
        questions: List<DetectedQuestion>,
        completedBeforeBatch: Int,
        totalQuestionCount: Int,
        completed: Boolean,
        plannedDuration: Long
    ) {
        if (!running) return
        rapidBatchDispatched = false
        if (!completed) {
            errorCount++
            AutomationStateStore.recordError(errorCount, "极速批量手势被系统取消")
            setAutomationRunning(false, "极速批量手势被取消，已安全停止")
            return
        }

        questions.forEachIndexed { index, question ->
            val number = completedBeforeBatch + index + 1
            Log.i(
                TAG,
                "rapid-batch submitted instance=$instanceId #$number " +
                    "${question.left.value}${question.relation.symbol}${question.right.value}"
            )
        }
        val expectedProgress = completedBeforeBatch + questions.size
        val completeMessage = "极速批次已提交：$expectedProgress/$totalQuestionCount / ${plannedDuration}ms"
        Log.i(TAG, "rapid-batch complete instance=$instanceId $completeMessage")
        AutomationStateStore.appendLog(completeMessage)
        updateSafetyNotification(completeMessage)
        overlayController.update(AutomationStateStore.snapshot())
        rapidBatchExpectedProgress = expectedProgress
        rapidBatchFinishedAt = SystemClock.elapsedRealtime()
        rapidBatchCompletedBefore = completedBeforeBatch
        rapidBatchLastObservedProgress = completedBeforeBatch
        rapidBatchProgressChangedAt = rapidBatchFinishedAt
        rapidBatchSignature = null
        rapidBatchFirstSeenAt = 0L
        AutomationStateStore.setStatus(
            if (config.targetApp == TargetApp.ZUOYEBANG &&
                expectedProgress >= totalQuestionCount
            ) {
                "等待作业帮最后一题与结算确认"
            } else {
                "等待题号确认：$expectedProgress/$totalQuestionCount"
            }
        )
        scheduleScan(TRANSITION_SCAN_MS)
    }

    private fun clearRapidBatchConfirmation() {
        rapidBatchExpectedProgress = 0
        rapidBatchFinishedAt = 0L
        rapidBatchCompletedBefore = 0
        rapidBatchLastObservedProgress = 0
        rapidBatchProgressChangedAt = 0L
        rapidBatchSignature = null
        rapidBatchFirstSeenAt = 0L
    }

    private fun updateZuoyebangQuestionPlan(
        orderedQuestions: List<DetectedQuestion>,
        completedQuestionCount: Int,
        totalQuestionCount: Int
    ) {
        if (zuoyebangQuestionPlan.isNotEmpty()) return
        val remaining = (totalQuestionCount - completedQuestionCount).coerceAtLeast(0)
        when {
            orderedQuestions.size >= totalQuestionCount -> {
                zuoyebangQuestionPlanStartProgress = 0
                zuoyebangQuestionPlan = orderedQuestions.take(totalQuestionCount)
            }

            remaining > 0 && orderedQuestions.size >= remaining -> {
                zuoyebangQuestionPlanStartProgress = completedQuestionCount
                zuoyebangQuestionPlan = orderedQuestions.take(remaining)
            }
        }
        if (zuoyebangQuestionPlan.isNotEmpty()) {
            zuoyebangCurrentMatchSignature = buildMatchSignature(
                totalQuestionCount,
                zuoyebangQuestionPlan
            )
            AutomationStateStore.appendLog(
                "已锁定作业帮本局题序：起点 $zuoyebangQuestionPlanStartProgress，" +
                    "共 ${zuoyebangQuestionPlan.size} 题"
            )
        }
    }

    private fun finishZuoyebangRun(totalQuestionCount: Int, confirmation: String): Boolean {
        if (config.targetApp != TargetApp.ZUOYEBANG) return false
        val total = totalQuestionCount.coerceAtLeast(1)
        handledCount = maxOf(handledCount, total)
        AutomationStateStore.recordHandled(
            handledCount,
            "作业帮本局 $total 题完成；$confirmation"
        )
        overlayController.update(AutomationStateStore.snapshot())
        if (config.autoStartEnabled) {
            armZuoyebangAutoRearm(total, allowDirectFreshMatch = true)
        }
        clearRapidBatchConfirmation()
        setAutomationRunning(false, "作业帮本局 $total 题已全部完成")
        return true
    }

    private fun armZuoyebangAutoRearm(
        finalProgress: Int = currentQuestionTotal.takeIf { it > 0 }
            ?: handledCount.coerceAtLeast(1),
        allowDirectFreshMatch: Boolean = false
    ) {
        if (config.targetApp != TargetApp.ZUOYEBANG || !config.autoStartEnabled) return
        zuoyebangAutoRearmPending = true
        zuoyebangAutoSawGameplayExit = false
        zuoyebangAutoPreviousFinalProgress = finalProgress.coerceAtLeast(1)
        zuoyebangAutoFinishedAt = SystemClock.elapsedRealtime()
        zuoyebangAutoGameplayExitFirstSeenAt = 0L
        zuoyebangAutoAllowDirectRearm = allowDirectFreshMatch
        zuoyebangCompletedMatchSignature = zuoyebangCurrentMatchSignature
    }

    private fun clearZuoyebangAutoRearm() {
        zuoyebangAutoRearmPending = false
        zuoyebangAutoSawGameplayExit = false
        zuoyebangAutoPreviousFinalProgress = 0
        zuoyebangAutoFinishedAt = 0L
        zuoyebangAutoGameplayExitFirstSeenAt = 0L
        zuoyebangAutoAllowDirectRearm = false
        zuoyebangCompletedMatchSignature = null
    }

    private fun buildMatchSignature(
        totalQuestionCount: Int,
        questions: List<DetectedQuestion>
    ): String = "$totalQuestionCount|" + questions.joinToString(";") { it.signature }

    private fun handleQuestion(
        found: QuestionReadResult.Found,
        completedQuestionCount: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val question = found.question
        AutomationStateStore.updateDetection(
            question.left.value,
            question.right.value,
            question.relation.symbol
        )
        overlayController.update(AutomationStateStore.snapshot())

        if (awaitingTransition) {
            val now = SystemClock.elapsedRealtime()
            if (completedQuestionCount <= lastAnsweredProgress) {
                val acceptRemaining = ANSWER_ACCEPT_TIMEOUT_MS - (now - lastActionAt)
                if (acceptRemaining > 0L) {
                    AutomationStateStore.setStatus("等待小猿确认答案")
                    scheduleScan(minOf(acceptRemaining, TRANSITION_SCAN_MS))
                    return
                }
                successfulTransitionsSinceRetry = 0
                unconfirmedRetriesForProgress++
                if (unconfirmedRetriesForProgress > MAX_UNCONFIRMED_RETRIES_PER_QUESTION) {
                    setAutomationRunning(false, "答案连续未确认，为避免笔迹重叠已停止")
                    return
                }
                adaptiveTransitionSettleMs = minOf(
                    MAX_TRANSITION_SETTLE_MS,
                    adaptiveTransitionSettleMs + TRANSITION_SETTLE_STEP_MS
                )
                AutomationStateStore.appendLog(
                    "答案未确认，准备重试；切题保护调整为 ${adaptiveTransitionSettleMs}ms"
                )
            } else {
                unconfirmedRetriesForProgress = 0
                val progressAge = now - progressChangedAt
                val settleRemaining = adaptiveTransitionSettleMs - progressAge
                val quietRemaining = TARGET_EVENT_QUIET_WINDOW_MS -
                    (now - lastTargetEventAt)
                val staleNodeRemaining = if (question.signature == lastAnsweredSignature) {
                    STALE_QUESTION_MAX_WAIT_MS - progressAge
                } else {
                    0L
                }
                val waitRemaining = maxOf(
                    settleRemaining,
                    quietRemaining,
                    staleNodeRemaining
                )
                if (waitRemaining > 0L) {
                    AutomationStateStore.setStatus("题号已前进，等待画板清空")
                    scheduleScan(waitRemaining)
                    return
                }
                successfulTransitionsSinceRetry++
                if (successfulTransitionsSinceRetry >= TRANSITIONS_BEFORE_SPEEDUP &&
                    adaptiveTransitionSettleMs > BASE_TRANSITION_SETTLE_MS
                ) {
                    adaptiveTransitionSettleMs = maxOf(
                        BASE_TRANSITION_SETTLE_MS,
                        adaptiveTransitionSettleMs - TRANSITION_SETTLE_RECOVERY_MS
                    )
                    successfulTransitionsSinceRetry = 0
                }
            }
            awaitingTransition = false
            stableSignature = null
            stableReadCount = 0
        }

        if (question.signature == stableSignature) {
            stableReadCount++
        } else {
            stableSignature = question.signature
            stableReadCount = 1
        }
        if (stableReadCount < config.stableReadCount) {
            AutomationStateStore.setStatus(
                "稳定性确认 $stableReadCount/${config.stableReadCount}"
            )
            scheduleScan(config.scanDebounceMs)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val effectiveCooldownMs = if (config.answerMode == AnswerMode.DRAW_RELATION) {
            maxOf(config.clickCooldownMs, MIN_DRAW_ACTION_INTERVAL_MS)
        } else {
            config.clickCooldownMs
        }
        val cooldownRemaining = effectiveCooldownMs - (now - lastActionAt)
        if (lastActionAt > 0L && cooldownRemaining > 0L) {
            scheduleScan(cooldownRemaining)
            return
        }

        val actionConfig = if (config.targetApp == TargetApp.XIAOYUAN &&
            currentQuestionTotal >= XIAOYUAN_HIGH_RANK_MIN_QUESTIONS &&
            config.answerMode == AnswerMode.DRAW_RELATION
        ) {
            config.copy(
                drawStrokeDurationMs = maxOf(
                    config.drawStrokeDurationMs,
                    XIAOYUAN_HIGH_RANK_STROKE_DURATION_MS
                )
            )
        } else {
            config
        }
        val result = clickDispatcher.click(
            this,
            question,
            screenWidth,
            screenHeight,
            actionConfig
        )
        if (result.accepted) {
            actionAttemptCount++
            consecutiveErrors = 0
            lastActionAt = now
            lastAnsweredSignature = question.signature
            lastAnsweredProgress = completedQuestionCount
            awaitingTransition = true
            stableSignature = null
            stableReadCount = 0
            val message = "尝试#$actionAttemptCount ${question.left.value} ${question.relation.symbol} " +
                "${question.right.value}；${result.detail}"
            Log.i(TAG, "handled instance=$instanceId $message")
            AutomationStateStore.appendLog(message)
            AutomationStateStore.setStatus(message)
            updateSafetyNotification(message)
            overlayController.update(AutomationStateStore.snapshot())
            scheduleScan(TRANSITION_SCAN_MS)
        } else {
            errorCount++
            consecutiveErrors++
            AutomationStateStore.recordError(errorCount, result.detail)
            if (consecutiveErrors >= config.maxConsecutiveErrors) {
                setAutomationRunning(false, "连续点击失败达到安全上限")
            } else {
                scheduleScan(RETRY_SCAN_MS)
            }
        }
    }

    private fun onQuestionNotFound(reason: String) {
        stableSignature = null
        stableReadCount = 0

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotFoundLogAt >= NOT_FOUND_LOG_INTERVAL_MS) {
            lastNotFoundLogAt = now
            AutomationStateStore.appendLog(reason)
        }
    }

    private fun finishFinalQuestionIfConfirmed(): Boolean {
        if (config.targetApp == TargetApp.ZUOYEBANG && currentQuestionTotal > 0) {
            val finalSingleSubmitted = awaitingTransition &&
                lastAnsweredProgress >= currentQuestionTotal - 1
            if (!finalSingleSubmitted ||
                SystemClock.elapsedRealtime() - lastActionAt < FINAL_ANSWER_SETTLE_MS
            ) {
                return false
            }
            return finishZuoyebangRun(currentQuestionTotal, "最后一题提交后答题页已结束")
        }
        if (config.targetApp != TargetApp.XIAOYUAN || currentQuestionTotal <= 0) {
            return false
        }
        val finalSingleSubmitted = awaitingTransition &&
            lastAnsweredProgress >= currentQuestionTotal - 1
        val finalBatchSubmitted = rapidBatchExpectedProgress >= currentQuestionTotal &&
            rapidBatchFinishedAt > 0L
        val submittedAt = if (finalBatchSubmitted) rapidBatchFinishedAt else lastActionAt
        if ((!finalSingleSubmitted && !finalBatchSubmitted) ||
            SystemClock.elapsedRealtime() - submittedAt < FINAL_ANSWER_SETTLE_MS
        ) {
            return false
        }

        handledCount = currentQuestionTotal
        AutomationStateStore.recordHandled(
            handledCount,
            "第 $currentQuestionTotal 题已提交，答题页已结束"
        )
        overlayController.update(AutomationStateStore.snapshot())
        if (config.autoStartEnabled) {
            autoRearmPending = true
            autoPreviousFinalProgress = (currentQuestionTotal - 1).coerceAtLeast(1)
        }
        setAutomationRunning(false, "本局 $currentQuestionTotal 题已全部完成")
        if (config.autoStartEnabled) scheduleAutoProbe(AUTO_REARM_SCAN_MS)
        return true
    }

    private fun observeCompletedProgress(
        completedQuestionCount: Int,
        totalQuestionCount: Int
    ) {
        if (totalQuestionCount > 0) currentQuestionTotal = totalQuestionCount
        val upperBound = currentQuestionTotal.takeIf { it > 0 }
            ?: maxOf(completedQuestionCount, config.maxActionsPerRun)
        val progress = completedQuestionCount.coerceIn(0, upperBound)
        if (progress != lastObservedProgress) {
            lastObservedProgress = progress
            progressChangedAt = SystemClock.elapsedRealtime()
        }
        if (progress > handledCount) {
            handledCount = progress
            AutomationStateStore.recordHandled(
                handledCount,
                if (currentQuestionTotal > 0) {
                    "比赛进度：$handledCount/$currentQuestionTotal"
                } else {
                    "比赛进度：$handledCount"
                }
            )
            overlayController.update(AutomationStateStore.snapshot())
        }
    }

    private fun checkSafetyLimits(): Boolean {
        if (!running) return false
        val now = SystemClock.elapsedRealtime()
        val isXiaoyuan = config.targetApp == TargetApp.XIAOYUAN
        val attemptLimit = currentQuestionTotal.takeIf { it > 0 }
            ?.times(MAX_ATTEMPTS_PER_QUESTION)
            ?: if (isXiaoyuan) {
                XIAOYUAN_UNKNOWN_TOTAL_MAX_ATTEMPTS
            } else {
                config.maxActionsPerRun * MAX_ATTEMPTS_PER_QUESTION
            }
        when {
            !isXiaoyuan && currentQuestionTotal <= 0 &&
                handledCount >= config.maxActionsPerRun ->
                finishZuoyebangRun(config.maxActionsPerRun, "达到备用处理上限")

            actionAttemptCount >= attemptLimit ->
                setAutomationRunning(false, "动作重试达到安全上限")

            now - runStartedAt >= config.maxRunDurationMs ->
                setAutomationRunning(false, "达到单次运行时长上限")

            now - lastTargetSeenAt >= config.targetLostStopMs ->
                setAutomationRunning(false, "目标应用离开过久，已安全停止")
        }
        return running
    }

    private fun refreshOverlay() {
        if (!::overlayController.isInitialized || !::config.isInitialized) return
        val shown = overlayController.show(AutomationStateStore.snapshot())
        if (!shown) {
            AutomationStateStore.setStatus("屏幕开始开关暂时无法显示")
        }
    }

    private fun showSafetyNotification() {
        createNotificationChannel()
        updateSafetyNotification("点击“立即停止”可随时中断")
    }

    private fun updateSafetyNotification(content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val stopIntent = Intent(this, SafetyStopReceiver::class.java).apply {
            action = SafetyStopReceiver.ACTION_STOP_AUTOMATION
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1002,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stopPendingIntent
                ).build()
            )
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示运行状态与紧急停止入口"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun cancelSafetyNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun shutdown(reason: String) {
        val wasCurrentInstance = RuntimeControl.detach(this)
        if (wasCurrentInstance && running) {
            setAutomationRunning(false, reason)
        } else {
            running = false
            handler.removeCallbacksAndMessages(null)
            nextScanAt = 0L
            nextAutoProbeAt = 0L
        }
        if (::overlayController.isInitialized) overlayController.hide()
        if (wasCurrentInstance) {
            AutomationStateStore.setServiceConnected(false, reason)
        } else {
            Log.i(TAG, "ignored stale shutdown instance=$instanceId reason=$reason")
        }
    }

    private companion object {
        const val TAG = "KousuanService"
        const val NOTIFICATION_CHANNEL_ID = "automation_safety"
        const val NOTIFICATION_ID = 43001
        const val WATCHDOG_INTERVAL_MS = 1_000L
        const val RETRY_SCAN_MS = 80L
        const val IDLE_SCAN_MS = 120L
        const val TRANSITION_SCAN_MS = 5L
        const val FOREGROUND_SETTLE_MS = 80L
        const val MIN_DRAW_ACTION_INTERVAL_MS = 30L
        const val NOT_FOUND_LOG_INTERVAL_MS = 3_000L
        const val MAX_RAPID_BATCH_SIZE = 10
        const val RAPID_READY_SCAN_MS = 20L
        const val AUTO_READY_SCAN_MS = 20L
        const val AUTO_REARM_SCAN_MS = 100L
        const val ANSWER_ACCEPT_TIMEOUT_MS = 700L
        const val RAPID_BATCH_ACCEPT_TIMEOUT_MS = 700L
        const val BASE_TRANSITION_SETTLE_MS = 50L
        const val MAX_TRANSITION_SETTLE_MS = 130L
        const val TRANSITION_SETTLE_STEP_MS = 20L
        const val TRANSITION_SETTLE_RECOVERY_MS = 10L
        const val TRANSITIONS_BEFORE_SPEEDUP = 3
        const val TARGET_EVENT_QUIET_WINDOW_MS = 24L
        const val STALE_QUESTION_MAX_WAIT_MS = 650L
        const val FINAL_ANSWER_SETTLE_MS = 120L
        const val ZUOYEBANG_FINAL_PAGE_EXIT_SETTLE_MS = 180L
        const val ZUOYEBANG_DIRECT_REARM_GRACE_MS = 800L
        const val ZUOYEBANG_STABLE_GAMEPLAY_EXIT_MS = 500L
        const val ZUOYEBANG_NO_PROGRESS_ACCEPT_TIMEOUT_MS = 2_000L
        const val ZUOYEBANG_FINAL_CONFIRM_TIMEOUT_MS = 6_000L
        const val ZUOYEBANG_PROGRESS_SETTLE_MS = 220L
        const val ZUOYEBANG_AUTO_START_SETTLE_MS = 1_900L
        const val ZUOYEBANG_BLOCKING_OVERLAY_SCAN_MS = 50L
        const val MAX_ATTEMPTS_PER_QUESTION = 3
        const val XIAOYUAN_UNKNOWN_TOTAL_MAX_ATTEMPTS = 300
        const val MAX_UNCONFIRMED_RETRIES_PER_QUESTION = 1
        const val XIAOYUAN_HIGH_RANK_MIN_QUESTIONS = 20
        const val XIAOYUAN_HIGH_RANK_STROKE_DURATION_MS = 60L
        const val XIAOYUAN_START_GATE_SCAN_MS = 10L
        const val XIAOYUAN_START_STABLE_READS = 2
        const val XIAOYUAN_EARLY_START_SETTLE_MS = 50L
        const val XIAOYUAN_ACTIVE_START_SETTLE_MS = 10L
    }
}
