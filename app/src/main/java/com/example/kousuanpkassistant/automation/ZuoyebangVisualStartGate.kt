package com.example.kousuanpkassistant.automation

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * 通过少量像素样本判断作业帮开场遮罩是否已经发生变化并重新稳定。
 *
 * 这里只分析内存中的缩略特征，不保存截图，也不识别题目文字。判断必须满足“看见明显
 * 变化、当前画面已偏离首帧、连续稳定”三个条件；证据不足时由服务层使用固定时间兜底。
 */
class ZuoyebangVisualStartGate {
    data class Decision(
        val ready: Boolean,
        val frameDelta: Int,
        val baselineDelta: Int,
        val stableFrames: Int,
        val changeSeen: Boolean
    )

    private var baseline: ByteArray? = null
    private var previous: ByteArray? = null
    private var changeSeen = false
    private var stableFrames = 0
    private var lastStrongChangeAt = 0L

    fun reset() {
        baseline = null
        previous = null
        changeSeen = false
        stableFrames = 0
        lastStrongChangeAt = 0L
    }

    fun analyze(bitmap: Bitmap, elapsedMs: Long): Decision {
        val current = sample(bitmap)
        val first = baseline
        val prior = previous
        if (first == null || prior == null) {
            baseline = current
            previous = current
            return Decision(
                ready = false,
                frameDelta = 0,
                baselineDelta = 0,
                stableFrames = 0,
                changeSeen = false
            )
        }

        val frameDelta = averageChannelDelta(prior, current)
        val baselineDelta = averageChannelDelta(first, current)
        when {
            frameDelta >= STRONG_CHANGE_DELTA -> {
                changeSeen = true
                stableFrames = 0
                lastStrongChangeAt = elapsedMs
            }

            frameDelta <= STABLE_FRAME_DELTA -> stableFrames++
            else -> stableFrames = 0
        }
        previous = current

        val ready = changeSeen &&
            baselineDelta >= CURRENT_STATE_SHIFT_DELTA &&
            stableFrames >= REQUIRED_STABLE_FRAMES &&
            elapsedMs >= MIN_VISUAL_GATE_MS &&
            elapsedMs - lastStrongChangeAt >= STABLE_AFTER_CHANGE_MS
        return Decision(
            ready = ready,
            frameDelta = frameDelta,
            baselineDelta = baselineDelta,
            stableFrames = stableFrames,
            changeSeen = changeSeen
        )
    }

    private fun sample(bitmap: Bitmap): ByteArray {
        val values = ByteArray(SAMPLE_COLUMNS * SAMPLE_ROWS * CHANNELS)
        var outputIndex = 0
        for (row in 0 until SAMPLE_ROWS) {
            val yRatio = ROI_TOP +
                (ROI_BOTTOM - ROI_TOP) * (row + 0.5f) / SAMPLE_ROWS
            val y = (bitmap.height * yRatio).toInt().coerceIn(0, bitmap.height - 1)
            for (column in 0 until SAMPLE_COLUMNS) {
                val xRatio = ROI_LEFT +
                    (ROI_RIGHT - ROI_LEFT) * (column + 0.5f) / SAMPLE_COLUMNS
                val x = (bitmap.width * xRatio).toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                values[outputIndex++] = Color.red(color).toByte()
                values[outputIndex++] = Color.green(color).toByte()
                values[outputIndex++] = Color.blue(color).toByte()
            }
        }
        return values
    }

    private fun averageChannelDelta(first: ByteArray, second: ByteArray): Int {
        if (first.size != second.size || first.isEmpty()) return Int.MAX_VALUE
        var total = 0L
        for (index in first.indices) {
            total += abs((first[index].toInt() and 0xff) - (second[index].toInt() and 0xff))
        }
        return (total / first.size).toInt()
    }

    private companion object {
        const val SAMPLE_COLUMNS = 32
        const val SAMPLE_ROWS = 32
        const val CHANNELS = 3
        const val ROI_LEFT = 0.18f
        const val ROI_RIGHT = 0.82f
        const val ROI_TOP = 0.25f
        const val ROI_BOTTOM = 0.70f
        const val STRONG_CHANGE_DELTA = 8
        const val STABLE_FRAME_DELTA = 2
        const val CURRENT_STATE_SHIFT_DELTA = 6
        const val REQUIRED_STABLE_FRAMES = 2
        const val MIN_VISUAL_GATE_MS = 850L
        const val STABLE_AFTER_CHANGE_MS = 260L
    }
}
