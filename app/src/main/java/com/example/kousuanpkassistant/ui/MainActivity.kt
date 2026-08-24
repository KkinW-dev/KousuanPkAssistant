package com.example.kousuanpkassistant.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.kousuanpkassistant.R
import com.example.kousuanpkassistant.RuntimeControl
import com.example.kousuanpkassistant.data.ConfigRepository
import com.example.kousuanpkassistant.service.KousuanAccessibilityService
import com.example.kousuanpkassistant.state.AutomationSnapshot
import com.example.kousuanpkassistant.state.AutomationStateStore
import com.example.kousuanpkassistant.model.TargetApp

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var permissionStatus: TextView
    private lateinit var targetAppStatus: TextView
    private lateinit var runtimeStatus: TextView
    private lateinit var currentQuestion: TextView
    private lateinit var counters: TextView
    private lateinit var logView: TextView
    private lateinit var autoModeSwitch: Switch
    private lateinit var configRepository: ConfigRepository
    private var syncingAutoModeSwitch = false
    private var lastRenderedState: RenderState? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderState()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configRepository = ConfigRepository(this)
        setContentView(buildContentView())
    }

    override fun onResume() {
        super.onResume()
        RuntimeControl.refreshConfiguration()
        syncAutoModeSwitch()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun buildContentView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(246, 248, 251))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(28))
        }
        scroll.addView(container)

        container.addView(text("口算 PK 助手", 24f, Color.rgb(13, 71, 161)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        container.addView(text("无障碍节点读取 · 本地判断 · 安全可停止", 14f, Color.DKGRAY).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(18))
        })

        container.addView(sectionTitle("应用模式"))
        targetAppStatus = text("", 17f, Color.rgb(30, 30, 30)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        container.addView(targetAppStatus, matchWidth())
        container.addView(buttonRow(
            button("小猿搜题") { switchTargetApp(TargetApp.XIAOYUAN) },
            button("作业帮") { switchTargetApp(TargetApp.ZUOYEBANG) }
        ))
        container.addView(text(
            "切换后会载入对应应用的专用识别区域、手写笔画和速度配置。",
            13f,
            Color.DKGRAY
        ).apply { setPadding(dp(8), 0, dp(8), dp(4)) })

        container.addView(sectionTitle("权限与连接"))
        permissionStatus = text("", 15f, Color.DKGRAY)
        container.addView(permissionStatus)
        container.addView(buttonRow(
            button("无障碍设置") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            button("屏幕开关说明") {
                Toast.makeText(
                    this,
                    "启用无障碍后，屏幕右侧会显示开始/停止开关",
                    Toast.LENGTH_LONG
                ).show()
            }
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            container.addView(button("通知权限（可选安全入口）") { requestNotificationPermission() })
        }

        container.addView(sectionTitle("运行控制"))
        autoModeSwitch = Switch(this).apply {
            text = "全自动模式"
            textSize = 17f
            isChecked = configRepository.load().autoStartEnabled
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnCheckedChangeListener { _, enabled ->
                if (syncingAutoModeSwitch) return@setOnCheckedChangeListener
                configRepository.setAutoStartEnabled(enabled)
                RuntimeControl.refreshConfiguration()
                lastRenderedState = null
                Toast.makeText(
                    this@MainActivity,
                    if (enabled) "全自动已开启：识别到比赛后自动运行" else "全自动已关闭：改为手动开始",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        container.addView(autoModeSwitch, matchWidth())
        container.addView(text(
            "开启后无需点击悬浮开始；检测到比赛计时器和完整题组后自动答题。",
            13f,
            Color.DKGRAY
        ).apply { setPadding(dp(8), 0, dp(8), dp(10)) })
        runtimeStatus = text("", 16f, Color.rgb(30, 30, 30))
        runtimeStatus.setPadding(dp(10), dp(8), dp(10), dp(8))
        runtimeStatus.setBackgroundColor(Color.WHITE)
        container.addView(runtimeStatus, matchWidth())
        container.addView(buttonRow(
            button("开始") { startAutomation() },
            button("立即停止") {
                if (!RuntimeControl.stop("主界面手动停止")) {
                    Toast.makeText(this, "服务尚未连接", Toast.LENGTH_SHORT).show()
                }
            }
        ))

        container.addView(sectionTitle("当前识别"))
        currentQuestion = text("左：-    关系：-    右：-", 22f, Color.rgb(21, 101, 192)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        container.addView(currentQuestion, matchWidth())
        counters = text("已处理：0    错误：0", 16f, Color.DKGRAY).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        }
        container.addView(counters)

        container.addView(button("配置包名、识别区域与点击策略") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        container.addView(sectionTitle("日志"))
        logView = text("暂无日志", 12f, Color.rgb(45, 45, 45)).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(235, 238, 242))
            minHeight = dp(190)
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        container.addView(logView, matchWidth())
        container.addView(button("清空日志") { AutomationStateStore.clearLogs() })

        container.addView(text(
            "提示：全自动关闭时，只有主动点击“开始”后才会执行动作；全自动开启时，" +
                "识别到比赛开始会自动执行。" +
                "通知栏、悬浮窗、次数上限、运行超时和离开目标应用超时都可停止自动化。",
            13f,
            Color.DKGRAY
        ).apply { setPadding(0, dp(12), 0, 0) })
        return scroll
    }

    private fun startAutomation() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先在系统设置中启用无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!RuntimeControl.start()) {
            Toast.makeText(
                this,
                "系统尚未连接服务，请返回无障碍设置重新开关一次",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "已启动，请切换到目标题目界面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchTargetApp(targetApp: TargetApp) {
        val current = configRepository.load().targetApp
        if (current == targetApp) return
        RuntimeControl.stop("切换应用模式")
        configRepository.setTargetApp(targetApp)
        RuntimeControl.refreshConfiguration()
        lastRenderedState = null
        renderState()
        Toast.makeText(
            this,
            "已切换到${targetApp.displayName}模式",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderState() {
        val snapshot = AutomationStateStore.snapshot()
        val loadedConfig = configRepository.load()
        val autoStartEnabled = loadedConfig.autoStartEnabled
        val accessibility = isAccessibilityServiceEnabled()
        val notification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val renderState = RenderState(
            snapshot,
            accessibility,
            notification,
            autoStartEnabled,
            loadedConfig.targetApp
        )
        if (renderState == lastRenderedState) return
        lastRenderedState = renderState
        targetAppStatus.text = "当前：${loadedConfig.targetApp.displayName}"
        permissionStatus.text = buildString {
            append("无障碍授权：${yesNo(accessibility)}\n")
            append("服务连接：${yesNo(snapshot.serviceConnected)}\n")
            append("屏幕开始开关：${if (snapshot.serviceConnected) "已显示" else "等待服务"}\n")
            append("全自动模式：${if (autoStartEnabled) "已开启" else "已关闭"}\n")
            append("通知权限：${yesNo(notification)}")
        }
        runtimeStatus.text = buildString {
            append(if (snapshot.running) "● 运行中" else "○ 已停止")
            append("\n状态：${snapshot.status}")
            append("\n当前窗口：${snapshot.activePackage}")
        }
        currentQuestion.text = getString(
            R.string.current_question_format,
            snapshot.leftValue?.toString() ?: "-",
            snapshot.relation,
            snapshot.rightValue?.toString() ?: "-"
        )
        counters.text = getString(
            R.string.counter_format,
            snapshot.handledCount,
            snapshot.errorCount
        )
        logView.text = snapshot.logLines.takeLast(80).joinToString("\n").ifEmpty { "暂无日志" }
    }

    private fun syncAutoModeSwitch() {
        if (!::autoModeSwitch.isInitialized) return
        syncingAutoModeSwitch = true
        autoModeSwitch.isChecked = configRepository.load().autoStartEnabled
        syncingAutoModeSwitch = false
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, KousuanAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    private fun sectionTitle(value: String) = text(value, 17f, Color.rgb(13, 71, 161)).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        buttons.forEach { child ->
            addView(child, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun yesNo(value: Boolean) = if (value) "已授权" else "未授权"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_INTERVAL_MS = 1_500L
    }

    private data class RenderState(
        val snapshot: AutomationSnapshot,
        val accessibility: Boolean,
        val notification: Boolean,
        val autoStartEnabled: Boolean,
        val targetApp: TargetApp
    )
}
