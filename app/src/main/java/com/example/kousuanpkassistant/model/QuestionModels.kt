package com.example.kousuanpkassistant.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class Relation(val symbol: String, val description: String) {
    LESS("<", "左边小"),
    EQUAL("=", "两边相等"),
    GREATER(">", "左边大")
}

data class NumberCandidate(
    val value: Int,
    val rawText: String,
    val bounds: Rect,
    val node: AccessibilityNodeInfo,
    val clickableAncestor: AccessibilityNodeInfo?,
    val source: String
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}

data class DetectedQuestion(
    val left: NumberCandidate,
    val right: NumberCandidate
) {
    val relation: Relation = when {
        left.value < right.value -> Relation.LESS
        left.value > right.value -> Relation.GREATER
        else -> Relation.EQUAL
    }

    val signature: String = listOf(
        left.value,
        right.value
    ).joinToString("|")
}

sealed interface QuestionReadResult {
    data class Found(val question: DetectedQuestion) : QuestionReadResult
    data class NotFound(val reason: String) : QuestionReadResult
}
