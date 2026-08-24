package com.example.kousuanpkassistant.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.DetectedQuestion
import com.example.kousuanpkassistant.model.QuestionReadResult

interface QuestionReader {
    fun read(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig,
        preferredExpressionIndex: Int = 0
    ): QuestionScan
}

class QuestionScan(
    val result: QuestionReadResult,
    val orderedQuestions: List<DetectedQuestion> = emptyList(),
    val hasHandwritingArea: Boolean = false,
    val isGameplayTimerRunning: Boolean = false,
    val hasBlockingOverlay: Boolean = false,
    val completedQuestionCount: Int = 0,
    val totalQuestionCount: Int = 0,
    private val nodesToRecycle: List<AccessibilityNodeInfo> = emptyList()
) : AutoCloseable {
    @Suppress("DEPRECATION")
    override fun close() {
        nodesToRecycle.asReversed().forEach { node ->
            runCatching { node.recycle() }
        }
    }
}
