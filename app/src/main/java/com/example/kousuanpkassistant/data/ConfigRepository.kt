package com.example.kousuanpkassistant.data

import android.content.Context
import com.example.kousuanpkassistant.model.AnswerMode
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.ClickStrategy
import com.example.kousuanpkassistant.model.TargetApp

class ConfigRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): AutomationConfig {
        val storedPackages = preferences.getString(KEY_PACKAGES, null)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toCollection(linkedSetOf())
            ?.takeIf { it.isNotEmpty() }
        val targetApp = enumValueOrDefault(
            preferences.getString(KEY_TARGET_APP, null),
            if (storedPackages?.any(TargetApp.ZUOYEBANG.defaultPackages::contains) == true) {
                TargetApp.ZUOYEBANG
            } else {
                TargetApp.XIAOYUAN
            }
        )
        val defaults = AutomationConfig.defaults(targetApp)
        val packages = storedPackages ?: defaults.targetPackages

        return AutomationConfig(
            targetApp = targetApp,
            autoStartEnabled = preferences.getBoolean(
                KEY_AUTO_START_ENABLED,
                defaults.autoStartEnabled
            ),
            targetPackages = packages,
            numberPattern = preferences.getString(KEY_NUMBER_PATTERN, defaults.numberPattern)
                ?: defaults.numberPattern,
            expressionPattern = preferences.getString(
                KEY_EXPRESSION_PATTERN,
                defaults.expressionPattern
            ) ?: defaults.expressionPattern,
            scanTopRatio = preferences.getFloat(KEY_SCAN_TOP, defaults.scanTopRatio),
            scanBottomRatio = preferences.getFloat(KEY_SCAN_BOTTOM, defaults.scanBottomRatio),
            leftMaxXRatio = preferences.getFloat(KEY_LEFT_MAX_X, defaults.leftMaxXRatio),
            rightMinXRatio = preferences.getFloat(KEY_RIGHT_MIN_X, defaults.rightMinXRatio),
            maxVerticalDeltaRatio = preferences.getFloat(
                KEY_MAX_VERTICAL_DELTA,
                defaults.maxVerticalDeltaRatio
            ),
            answerMode = enumValueOrDefault(
                preferences.getString(KEY_ANSWER_MODE, null),
                defaults.answerMode
            ),
            clickStrategy = enumValueOrDefault(
                preferences.getString(KEY_CLICK_STRATEGY, null),
                defaults.clickStrategy
            ),
            lessButtonXRatio = preferences.getFloat(KEY_LESS_X, defaults.lessButtonXRatio),
            equalButtonXRatio = preferences.getFloat(KEY_EQUAL_X, defaults.equalButtonXRatio),
            greaterButtonXRatio = preferences.getFloat(
                KEY_GREATER_X,
                defaults.greaterButtonXRatio
            ),
            answerButtonYRatio = preferences.getFloat(KEY_ANSWER_Y, defaults.answerButtonYRatio),
            drawCenterXRatio = preferences.getFloat(
                KEY_DRAW_CENTER_X,
                defaults.drawCenterXRatio
            ),
            drawCenterYRatio = preferences.getFloat(
                KEY_DRAW_CENTER_Y,
                defaults.drawCenterYRatio
            ),
            drawWidthRatio = preferences.getFloat(KEY_DRAW_WIDTH, defaults.drawWidthRatio),
            drawHeightRatio = preferences.getFloat(KEY_DRAW_HEIGHT, defaults.drawHeightRatio),
            drawStrokeDurationMs = preferences.getLong(
                KEY_DRAW_STROKE_DURATION,
                defaults.drawStrokeDurationMs
            ),
            rapidBatchEnabled = preferences.getBoolean(
                KEY_RAPID_BATCH_ENABLED,
                defaults.rapidBatchEnabled
            ),
            rapidStartDelayMs = preferences.getLong(
                KEY_RAPID_START_DELAY,
                defaults.rapidStartDelayMs
            ),
            rapidStrokeDurationMs = preferences.getLong(
                KEY_RAPID_STROKE_DURATION,
                defaults.rapidStrokeDurationMs
            ),
            rapidStrokeIntervalMs = preferences.getLong(
                KEY_RAPID_STROKE_INTERVAL,
                defaults.rapidStrokeIntervalMs
            ),
            stableReadCount = preferences.getInt(KEY_STABLE_READS, defaults.stableReadCount),
            scanDebounceMs = preferences.getLong(KEY_SCAN_DEBOUNCE, defaults.scanDebounceMs),
            clickCooldownMs = preferences.getLong(KEY_CLICK_COOLDOWN, defaults.clickCooldownMs),
            tapDurationMs = preferences.getLong(KEY_TAP_DURATION, defaults.tapDurationMs),
            maxActionsPerRun = preferences.getInt(KEY_MAX_ACTIONS, defaults.maxActionsPerRun),
            maxRunDurationMs = preferences.getLong(
                KEY_MAX_RUN_DURATION,
                defaults.maxRunDurationMs
            ),
            targetLostStopMs = preferences.getLong(
                KEY_TARGET_LOST_STOP,
                defaults.targetLostStopMs
            ),
            maxConsecutiveErrors = preferences.getInt(
                KEY_MAX_ERRORS,
                defaults.maxConsecutiveErrors
            ),
            maxNodesPerScan = preferences.getInt(KEY_MAX_NODES, defaults.maxNodesPerScan),
            overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, defaults.overlayEnabled)
        )
    }

    fun save(config: AutomationConfig) {
        preferences.edit()
            .putString(KEY_TARGET_APP, config.targetApp.name)
            .putBoolean(KEY_AUTO_START_ENABLED, config.autoStartEnabled)
            .putString(KEY_PACKAGES, config.targetPackages.joinToString(","))
            .putString(KEY_NUMBER_PATTERN, config.numberPattern)
            .putString(KEY_EXPRESSION_PATTERN, config.expressionPattern)
            .putFloat(KEY_SCAN_TOP, config.scanTopRatio)
            .putFloat(KEY_SCAN_BOTTOM, config.scanBottomRatio)
            .putFloat(KEY_LEFT_MAX_X, config.leftMaxXRatio)
            .putFloat(KEY_RIGHT_MIN_X, config.rightMinXRatio)
            .putFloat(KEY_MAX_VERTICAL_DELTA, config.maxVerticalDeltaRatio)
            .putString(KEY_ANSWER_MODE, config.answerMode.name)
            .putString(KEY_CLICK_STRATEGY, config.clickStrategy.name)
            .putFloat(KEY_LESS_X, config.lessButtonXRatio)
            .putFloat(KEY_EQUAL_X, config.equalButtonXRatio)
            .putFloat(KEY_GREATER_X, config.greaterButtonXRatio)
            .putFloat(KEY_ANSWER_Y, config.answerButtonYRatio)
            .putFloat(KEY_DRAW_CENTER_X, config.drawCenterXRatio)
            .putFloat(KEY_DRAW_CENTER_Y, config.drawCenterYRatio)
            .putFloat(KEY_DRAW_WIDTH, config.drawWidthRatio)
            .putFloat(KEY_DRAW_HEIGHT, config.drawHeightRatio)
            .putLong(KEY_DRAW_STROKE_DURATION, config.drawStrokeDurationMs)
            .putBoolean(KEY_RAPID_BATCH_ENABLED, config.rapidBatchEnabled)
            .putLong(KEY_RAPID_START_DELAY, config.rapidStartDelayMs)
            .putLong(KEY_RAPID_STROKE_DURATION, config.rapidStrokeDurationMs)
            .putLong(KEY_RAPID_STROKE_INTERVAL, config.rapidStrokeIntervalMs)
            .putInt(KEY_STABLE_READS, config.stableReadCount)
            .putLong(KEY_SCAN_DEBOUNCE, config.scanDebounceMs)
            .putLong(KEY_CLICK_COOLDOWN, config.clickCooldownMs)
            .putLong(KEY_TAP_DURATION, config.tapDurationMs)
            .putInt(KEY_MAX_ACTIONS, config.maxActionsPerRun)
            .putLong(KEY_MAX_RUN_DURATION, config.maxRunDurationMs)
            .putLong(KEY_TARGET_LOST_STOP, config.targetLostStopMs)
            .putInt(KEY_MAX_ERRORS, config.maxConsecutiveErrors)
            .putInt(KEY_MAX_NODES, config.maxNodesPerScan)
            .putBoolean(KEY_OVERLAY_ENABLED, config.overlayEnabled)
            .apply()
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_START_ENABLED, enabled).apply()
    }

    fun setTargetApp(targetApp: TargetApp) {
        val current = load()
        save(
            AutomationConfig.defaults(targetApp).copy(
                autoStartEnabled = current.autoStartEnabled,
                overlayEnabled = current.overlayEnabled
            )
        )
    }

    fun reset() {
        val current = load()
        save(
            AutomationConfig.defaults(current.targetApp).copy(
                autoStartEnabled = current.autoStartEnabled
            )
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        return value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }

    private companion object {
        const val PREFS_NAME = "automation_config"
        const val KEY_TARGET_APP = "target_app"
        const val KEY_AUTO_START_ENABLED = "auto_start_enabled"
        const val KEY_PACKAGES = "packages"
        const val KEY_NUMBER_PATTERN = "number_pattern"
        const val KEY_EXPRESSION_PATTERN = "expression_pattern"
        const val KEY_SCAN_TOP = "scan_top"
        const val KEY_SCAN_BOTTOM = "scan_bottom"
        const val KEY_LEFT_MAX_X = "left_max_x"
        const val KEY_RIGHT_MIN_X = "right_min_x"
        const val KEY_MAX_VERTICAL_DELTA = "max_vertical_delta"
        const val KEY_ANSWER_MODE = "answer_mode"
        const val KEY_CLICK_STRATEGY = "click_strategy"
        const val KEY_LESS_X = "less_x"
        const val KEY_EQUAL_X = "equal_x"
        const val KEY_GREATER_X = "greater_x"
        const val KEY_ANSWER_Y = "answer_y"
        const val KEY_DRAW_CENTER_X = "draw_center_x"
        const val KEY_DRAW_CENTER_Y = "draw_center_y"
        const val KEY_DRAW_WIDTH = "draw_width"
        const val KEY_DRAW_HEIGHT = "draw_height"
        const val KEY_DRAW_STROKE_DURATION = "draw_stroke_duration"
        const val KEY_RAPID_BATCH_ENABLED = "rapid_batch_enabled"
        const val KEY_RAPID_START_DELAY = "rapid_start_delay"
        const val KEY_RAPID_STROKE_DURATION = "rapid_stroke_duration"
        const val KEY_RAPID_STROKE_INTERVAL = "rapid_stroke_interval"
        const val KEY_STABLE_READS = "stable_reads"
        const val KEY_SCAN_DEBOUNCE = "scan_debounce"
        const val KEY_CLICK_COOLDOWN = "click_cooldown"
        const val KEY_TAP_DURATION = "tap_duration"
        const val KEY_MAX_ACTIONS = "max_actions"
        const val KEY_MAX_RUN_DURATION = "max_run_duration"
        const val KEY_TARGET_LOST_STOP = "target_lost_stop"
        const val KEY_MAX_ERRORS = "max_errors"
        const val KEY_MAX_NODES = "max_nodes"
        const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    }
}
