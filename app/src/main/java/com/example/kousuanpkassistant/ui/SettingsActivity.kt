package com.example.kousuanpkassistant.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.kousuanpkassistant.RuntimeControl
import com.example.kousuanpkassistant.data.ConfigRepository
import com.example.kousuanpkassistant.model.AnswerMode
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.ClickStrategy
import com.example.kousuanpkassistant.model.TargetApp

class SettingsActivity : Activity() {
    private lateinit var repository: ConfigRepository

    private lateinit var packages: EditText
    private lateinit var numberPattern: EditText
    private lateinit var expressionPattern: EditText
    private lateinit var scanTop: EditText
    private lateinit var scanBottom: EditText
    private lateinit var leftMaxX: EditText
    private lateinit var rightMinX: EditText
    private lateinit var maxYDelta: EditText
    private lateinit var answerMode: Spinner
    private lateinit var clickStrategy: Spinner
    private lateinit var lessX: EditText
    private lateinit var equalX: EditText
    private lateinit var greaterX: EditText
    private lateinit var answerY: EditText
    private lateinit var drawCenterX: EditText
    private lateinit var drawCenterY: EditText
    private lateinit var drawWidth: EditText
    private lateinit var drawHeight: EditText
    private lateinit var drawStrokeDuration: EditText
    private lateinit var rapidBatchEnabled: Switch
    private lateinit var rapidStartDelay: EditText
    private lateinit var rapidStrokeDuration: EditText
    private lateinit var rapidStrokeInterval: EditText
    private lateinit var stableReads: EditText
    private lateinit var scanDebounce: EditText
    private lateinit var clickCooldown: EditText
    private lateinit var tapDuration: EditText
    private lateinit var maxActions: EditText
    private lateinit var maxRunMinutes: EditText
    private lateinit var targetLostSeconds: EditText
    private lateinit var maxErrors: EditText
    private lateinit var maxNodes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ConfigRepository(this)
        setContentView(buildView(repository.load()))
    }

    private fun buildView(config: AutomationConfig): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(248, 249, 251)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
        }
        scroll.addView(container)

        container.addView(label("识别与点击配置", 23f, Color.rgb(13, 71, 161)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        })
        container.addView(help(
            "当前应用模式：${config.targetApp.displayName}。比例值均以屏幕左上角为 (0,0)、" +
                "右下角为 (1,1)。建议先用 uiautomator dump " +
                "确认包名、数字文本和 bounds，再小幅调整。"
        ))

        packages = field(
            container,
            "目标包名（多个用英文逗号分隔）",
            config.targetPackages.joinToString(",")
        )
        numberPattern = field(
            container,
            "数字节点正则（第 1 个捕获组应为数字）",
            config.numberPattern
        ).apply { typeface = android.graphics.Typeface.MONOSPACE }
        expressionPattern = field(
            container,
            "组合题正则（第 1、2 个捕获组为左右数字）",
            config.expressionPattern
        ).apply { typeface = android.graphics.Typeface.MONOSPACE }

        container.addView(section("候选数字区域"))
        scanTop = decimalField(container, "上边界 Y 比例", config.scanTopRatio)
        scanBottom = decimalField(container, "下边界 Y 比例", config.scanBottomRatio)
        leftMaxX = decimalField(container, "左数字最大中心 X 比例", config.leftMaxXRatio)
        rightMinX = decimalField(container, "右数字最小中心 X 比例", config.rightMinXRatio)
        maxYDelta = decimalField(container, "左右数字最大纵向差比例", config.maxVerticalDeltaRatio)

        container.addView(section("答案与点击"))
        answerMode = spinner(
            container,
            "答案模式",
            AnswerMode.entries.map { it.displayName },
            config.answerMode.ordinal
        )
        clickStrategy = spinner(
            container,
            "点击策略",
            ClickStrategy.entries.map { it.displayName },
            config.clickStrategy.ordinal
        )
        container.addView(help("固定关系按钮模式：以下坐标分别对应 <、=、>，Y 坐标共用。"))
        lessX = decimalField(container, "< 按钮 X 比例", config.lessButtonXRatio)
        equalX = decimalField(container, "= 按钮 X 比例", config.equalButtonXRatio)
        greaterX = decimalField(container, "> 按钮 X 比例", config.greaterButtonXRatio)
        answerY = decimalField(container, "关系按钮 Y 比例", config.answerButtonYRatio)
        container.addView(help("手写模式：在以下中心和尺寸范围内绘制关系符号。"))
        drawCenterX = decimalField(container, "手写符号中心 X 比例", config.drawCenterXRatio)
        drawCenterY = decimalField(container, "手写符号中心 Y 比例", config.drawCenterYRatio)
        drawWidth = decimalField(container, "手写符号宽度比例", config.drawWidthRatio)
        drawHeight = decimalField(container, "手写符号高度比例", config.drawHeightRatio)
        drawStrokeDuration = integerField(
            container,
            "手写单笔时长 ms",
            config.drawStrokeDurationMs
        )
        rapidBatchEnabled = Switch(this).apply {
            text = "启用极速分批模式（每批最多 10 题）"
            isChecked = config.rapidBatchEnabled
            setPadding(0, dp(12), 0, dp(6))
        }
        container.addView(rapidBatchEnabled)
        container.addView(help("小猿模式按页面总题数持续处理；超过 10 题时自动拆成多个极速批次。仅在手写模式下生效。"))
        rapidStartDelay = integerField(
            container,
            "整局题目出现后等待开场动画 ms",
            config.rapidStartDelayMs
        )
        rapidStrokeDuration = integerField(
            container,
            "极速单笔时长 ms",
            config.rapidStrokeDurationMs
        )
        rapidStrokeInterval = integerField(
            container,
            "极速两笔起点间隔 ms",
            config.rapidStrokeIntervalMs
        )

        container.addView(section("速度与安全限制"))
        stableReads = integerField(container, "连续稳定读取次数", config.stableReadCount)
        scanDebounce = integerField(container, "界面变化后等待 ms", config.scanDebounceMs)
        clickCooldown = integerField(
            container,
            "两次动作最小间隔 ms（最低 30）",
            config.clickCooldownMs
        )
        tapDuration = integerField(container, "坐标点击按下时长 ms", config.tapDurationMs)
        maxActions = integerField(
            container,
            if (config.targetApp == TargetApp.XIAOYUAN) {
                "无题号页面备用题数（小猿正常比赛不限制）"
            } else {
                "单次最多处理题数"
            },
            config.maxActionsPerRun
        )
        maxRunMinutes = decimalField(
            container,
            "单次最长运行分钟",
            config.maxRunDurationMs / 60_000f
        )
        targetLostSeconds = decimalField(
            container,
            "离开目标应用多少秒后停止",
            config.targetLostStopMs / 1_000f
        )
        maxErrors = integerField(container, "连续点击失败上限", config.maxConsecutiveErrors)
        maxNodes = integerField(container, "单次最多扫描节点数", config.maxNodesPerScan)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@SettingsActivity).apply {
                text = "恢复默认"
                isAllCaps = false
                setOnClickListener {
                    repository.reset()
                    recreate()
                }
            }, weighted())
            addView(Button(this@SettingsActivity).apply {
                text = "保存"
                isAllCaps = false
                setOnClickListener { save() }
            }, weighted())
        }
        container.addView(buttons)
        container.addView(Button(this).apply {
            text = "取消"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        return scroll
    }

    private fun save() {
        try {
            val packageSet = packages.text.toString().split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toCollection(linkedSetOf())
            require(packageSet.isNotEmpty()) { "至少填写一个目标包名" }

            val pattern = numberPattern.text.toString()
            Regex(pattern)
            val combinedPattern = expressionPattern.text.toString()
            Regex(combinedPattern)
            val combinedGroupCount = java.util.regex.Pattern.compile(combinedPattern)
                .matcher("")
                .groupCount()
            require(combinedGroupCount >= 2) {
                "组合题正则必须至少包含两个捕获组"
            }

            val top = ratio(scanTop, "上边界")
            val bottom = ratio(scanBottom, "下边界")
            val left = ratio(leftMaxX, "左侧 X")
            val right = ratio(rightMinX, "右侧 X")
            require(top < bottom) { "上边界必须小于下边界" }
            require(left < right) { "左数字最大 X 必须小于右数字最小 X" }

            val newConfig = AutomationConfig(
                targetApp = repository.load().targetApp,
                autoStartEnabled = repository.load().autoStartEnabled,
                targetPackages = packageSet,
                numberPattern = pattern,
                expressionPattern = combinedPattern,
                scanTopRatio = top,
                scanBottomRatio = bottom,
                leftMaxXRatio = left,
                rightMinXRatio = right,
                maxVerticalDeltaRatio = ratio(maxYDelta, "纵向差"),
                answerMode = AnswerMode.entries[answerMode.selectedItemPosition],
                clickStrategy = ClickStrategy.entries[clickStrategy.selectedItemPosition],
                lessButtonXRatio = ratio(lessX, "< 按钮 X"),
                equalButtonXRatio = ratio(equalX, "= 按钮 X"),
                greaterButtonXRatio = ratio(greaterX, "> 按钮 X"),
                answerButtonYRatio = ratio(answerY, "关系按钮 Y"),
                drawCenterXRatio = ratio(drawCenterX, "手写中心 X"),
                drawCenterYRatio = ratio(drawCenterY, "手写中心 Y"),
                drawWidthRatio = positiveFloat(drawWidth, "手写宽度", 0.02f, 0.8f),
                drawHeightRatio = positiveFloat(drawHeight, "手写高度", 0.02f, 0.8f),
                drawStrokeDurationMs = positiveLong(
                    drawStrokeDuration,
                    "手写单笔时长",
                    30,
                    2_000
                ),
                rapidBatchEnabled = rapidBatchEnabled.isChecked,
                rapidStartDelayMs = positiveLong(
                    rapidStartDelay,
                    "极速开场等待",
                    0,
                    3_000
                ),
                rapidStrokeDurationMs = positiveLong(
                    rapidStrokeDuration,
                    "极速单笔时长",
                    30,
                    200
                ),
                rapidStrokeIntervalMs = positiveLong(
                    rapidStrokeInterval,
                    "极速笔画间隔",
                    30,
                    110
                ),
                stableReadCount = positiveInt(stableReads, "稳定读取次数", 1, 10),
                scanDebounceMs = positiveLong(scanDebounce, "等待时间", 0, 5_000),
                clickCooldownMs = positiveLong(clickCooldown, "点击间隔", 30, 10_000),
                tapDurationMs = positiveLong(tapDuration, "按下时长", 1, 1_000),
                maxActionsPerRun = positiveInt(maxActions, "题数上限", 1, 10_000),
                maxRunDurationMs = (positiveFloat(maxRunMinutes, "运行分钟", 0.1f, 240f) * 60_000).toLong(),
                targetLostStopMs = (positiveFloat(targetLostSeconds, "离开超时", 3f, 3_600f) * 1_000).toLong(),
                maxConsecutiveErrors = positiveInt(maxErrors, "错误上限", 1, 100),
                maxNodesPerScan = positiveInt(maxNodes, "节点上限", 50, 5_000),
                overlayEnabled = true
            )
            require(newConfig.rapidStrokeIntervalMs >= newConfig.rapidStrokeDurationMs) {
                "极速笔画间隔不能小于单笔时长"
            }
            val rapidTotal = (newConfig.maxActionsPerRun.coerceAtMost(10) - 1) *
                newConfig.rapidStrokeIntervalMs + newConfig.rapidStrokeDurationMs
            require(!newConfig.rapidBatchEnabled || rapidTotal <= 1_000L) {
                "极速批量总时长必须不超过 1000ms（当前 ${rapidTotal}ms）"
            }
            repository.save(newConfig)
            RuntimeControl.refreshConfiguration()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } catch (error: Exception) {
            Toast.makeText(this, "无法保存：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun field(parent: LinearLayout, title: String, value: Any): EditText {
        parent.addView(label(title, 14f, Color.DKGRAY).apply { setPadding(0, dp(8), 0, dp(3)) })
        return EditText(this).apply {
            setText(value.toString())
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            parent.addView(this, matchWidth())
        }
    }

    private fun decimalField(parent: LinearLayout, title: String, value: Any): EditText =
        field(parent, title, value).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

    private fun integerField(parent: LinearLayout, title: String, value: Any): EditText =
        field(parent, title, value).apply { inputType = InputType.TYPE_CLASS_NUMBER }

    private fun spinner(
        parent: LinearLayout,
        title: String,
        options: List<String>,
        selection: Int
    ): Spinner {
        parent.addView(label(title, 14f, Color.DKGRAY).apply { setPadding(0, dp(8), 0, dp(3)) })
        return Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options
            )
            setSelection(selection)
            setBackgroundColor(Color.WHITE)
            parent.addView(this, matchWidth())
        }
    }

    private fun ratio(field: EditText, name: String): Float =
        positiveFloat(field, name, 0f, 1f)

    private fun positiveFloat(
        field: EditText,
        name: String,
        min: Float,
        max: Float
    ): Float {
        val value = field.text.toString().toFloatOrNull()
            ?: throw IllegalArgumentException("$name 不是有效数字")
        require(value in min..max) { "$name 应在 $min 到 $max 之间" }
        return value
    }

    private fun positiveInt(
        field: EditText,
        name: String,
        min: Int,
        max: Int
    ): Int {
        val value = field.text.toString().toIntOrNull()
            ?: throw IllegalArgumentException("$name 不是有效整数")
        require(value in min..max) { "$name 应在 $min 到 $max 之间" }
        return value
    }

    private fun positiveLong(
        field: EditText,
        name: String,
        min: Long,
        max: Long
    ): Long {
        val value = field.text.toString().toLongOrNull()
            ?: throw IllegalArgumentException("$name 不是有效整数")
        require(value in min..max) { "$name 应在 $min 到 $max 之间" }
        return value
    }

    private fun section(value: String) = label(value, 17f, Color.rgb(13, 71, 161)).apply {
        setPadding(0, dp(20), 0, dp(5))
    }

    private fun help(value: String) = label(value, 13f, Color.DKGRAY).apply {
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun weighted() = LinearLayout.LayoutParams(
        0,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        1f
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
