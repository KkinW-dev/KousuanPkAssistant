package com.example.kousuanpkassistant.model

enum class AnswerMode(val displayName: String) {
    DRAW_RELATION("在手写区绘制 <、=、>"),
    CLICK_GREATER_VALUE("点击较大的数字"),
    CLICK_LESS_VALUE("点击较小的数字"),
    RELATION_BUTTONS("点击固定的 <、=、> 区域")
}

enum class ClickStrategy(val displayName: String) {
    NODE_THEN_GESTURE("优先点击节点，失败后点坐标"),
    GESTURE_ONLY("只按坐标点击")
}

enum class TargetApp(
    val displayName: String,
    val defaultPackages: Set<String>
) {
    XIAOYUAN("小猿搜题", linkedSetOf("com.fenbi.android.solar")),
    ZUOYEBANG("作业帮", linkedSetOf("com.baidu.homework", "com.zybang.parent"))
}

data class AutomationConfig(
    val targetApp: TargetApp,
    val autoStartEnabled: Boolean,
    val targetPackages: Set<String>,
    val numberPattern: String,
    val expressionPattern: String,
    val scanTopRatio: Float,
    val scanBottomRatio: Float,
    val leftMaxXRatio: Float,
    val rightMinXRatio: Float,
    val maxVerticalDeltaRatio: Float,
    val answerMode: AnswerMode,
    val clickStrategy: ClickStrategy,
    val lessButtonXRatio: Float,
    val equalButtonXRatio: Float,
    val greaterButtonXRatio: Float,
    val answerButtonYRatio: Float,
    val drawCenterXRatio: Float,
    val drawCenterYRatio: Float,
    val drawWidthRatio: Float,
    val drawHeightRatio: Float,
    val drawStrokeDurationMs: Long,
    val rapidBatchEnabled: Boolean,
    val rapidStartDelayMs: Long,
    val rapidStrokeDurationMs: Long,
    val rapidStrokeIntervalMs: Long,
    val stableReadCount: Int,
    val scanDebounceMs: Long,
    val clickCooldownMs: Long,
    val tapDurationMs: Long,
    val maxActionsPerRun: Int,
    val maxRunDurationMs: Long,
    val targetLostStopMs: Long,
    val maxConsecutiveErrors: Int,
    val maxNodesPerScan: Int,
    val overlayEnabled: Boolean
) {
    fun compiledNumberRegex(): Regex = runCatching { Regex(numberPattern) }
        .getOrElse { Regex(DEFAULT_NUMBER_PATTERN) }

    fun compiledExpressionRegex(): Regex = runCatching { Regex(expressionPattern) }
        .getOrElse { Regex(DEFAULT_EXPRESSION_PATTERN) }

    fun isTargetPackage(packageName: CharSequence?): Boolean {
        val value = packageName?.toString()?.trim().orEmpty()
        return value.isNotEmpty() && targetPackages.contains(value)
    }

    companion object {
        const val DEFAULT_NUMBER_PATTERN = "^\\s*(-?\\d{1,6})\\s*$"
        const val DEFAULT_EXPRESSION_PATTERN =
            "^\\s*(-?\\d{1,6})\\s*[?？<>＝=]\\s*(-?\\d{1,6})\\s*$"

        fun defaults(targetApp: TargetApp = TargetApp.XIAOYUAN): AutomationConfig {
            val xiaoyuan = targetApp == TargetApp.XIAOYUAN
            return AutomationConfig(
                targetApp = targetApp,
                autoStartEnabled = false,
                targetPackages = targetApp.defaultPackages,
                numberPattern = DEFAULT_NUMBER_PATTERN,
                expressionPattern = DEFAULT_EXPRESSION_PATTERN,
                scanTopRatio = if (xiaoyuan) 0.24f else 0.25f,
                scanBottomRatio = if (xiaoyuan) 0.36f else 0.78f,
                leftMaxXRatio = 0.48f,
                rightMinXRatio = 0.52f,
                maxVerticalDeltaRatio = 0.18f,
                answerMode = AnswerMode.DRAW_RELATION,
                clickStrategy = ClickStrategy.NODE_THEN_GESTURE,
                lessButtonXRatio = 0.25f,
                equalButtonXRatio = 0.50f,
                greaterButtonXRatio = 0.75f,
                answerButtonYRatio = 0.82f,
                drawCenterXRatio = 0.50f,
                drawCenterYRatio = 0.72f,
                drawWidthRatio = if (xiaoyuan) 0.22f else 0.18f,
                drawHeightRatio = if (xiaoyuan) 0.10f else 0.09f,
                drawStrokeDurationMs = if (xiaoyuan) 30L else 160L,
                rapidBatchEnabled = true,
                rapidStartDelayMs = 900L,
                rapidStrokeDurationMs = 40L,
                rapidStrokeIntervalMs = 95L,
                stableReadCount = if (xiaoyuan) 1 else 2,
                scanDebounceMs = if (xiaoyuan) 0L else 80L,
                clickCooldownMs = if (xiaoyuan) 30L else 2_000L,
                tapDurationMs = 45L,
                maxActionsPerRun = 10,
                maxRunDurationMs = 10 * 60 * 1000L,
                targetLostStopMs = 60 * 1000L,
                maxConsecutiveErrors = 8,
                maxNodesPerScan = 600,
                overlayEnabled = true
            )
        }
    }
}
