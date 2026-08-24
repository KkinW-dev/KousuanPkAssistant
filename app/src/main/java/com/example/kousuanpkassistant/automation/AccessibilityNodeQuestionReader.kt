package com.example.kousuanpkassistant.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.DetectedQuestion
import com.example.kousuanpkassistant.model.NumberCandidate
import com.example.kousuanpkassistant.model.QuestionReadResult
import com.example.kousuanpkassistant.model.TargetApp
import kotlin.math.abs

class AccessibilityNodeQuestionReader : QuestionReader {
    override fun read(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig,
        preferredExpressionIndex: Int
    ): QuestionScan {
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()
        val candidates = mutableListOf<NumberCandidate>()
        val expressions = mutableListOf<ExpressionCandidate>()
        val regex = config.compiledNumberRegex()
        val expressionRegex = config.compiledExpressionRegex()
        var visited = 0
        var hasHandwritingArea = false
        var isGameplayTimerRunning = false
        var hasBlockingOverlay = false
        var currentQuestionNumber: Int? = null
        var totalQuestionCount: Int? = null

        fun visit(node: AccessibilityNodeInfo, inheritedClickable: AccessibilityNodeInfo?) {
            if (++visited > config.maxNodesPerScan) return

            val clickable = if (node.isClickable && node.isEnabled) node else inheritedClickable
            val nodeText = node.text?.toString().orEmpty()
            val nodeDescription = node.contentDescription?.toString().orEmpty()
            if (nodeText.contains(HANDWRITING_AREA_TEXT) ||
                nodeDescription.contains(HANDWRITING_AREA_TEXT)
            ) {
                hasHandwritingArea = true
            }
            if (GAMEPLAY_TIMER_REGEX.matches(nodeText.trim()) ||
                GAMEPLAY_TIMER_REGEX.matches(nodeDescription.trim())
            ) {
                isGameplayTimerRunning = true
            }
            if (node.isVisibleToUser &&
                config.targetApp == TargetApp.ZUOYEBANG &&
                (isZuoyebangBlockingText(nodeText) ||
                    isZuoyebangBlockingText(nodeDescription))
            ) {
                hasBlockingOverlay = true
            }
            if (currentQuestionNumber == null) {
                val progressMatch = SCORE_REGEX.matchEntire(nodeText.trim())
                    ?: SCORE_REGEX.matchEntire(nodeDescription.trim())
                currentQuestionNumber = progressMatch
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                totalQuestionCount = progressMatch
                    ?.groupValues?.getOrNull(2)?.toIntOrNull()
            }
            candidateFrom(node, clickable, "text", node.text, regex)?.let(candidates::add)
            expressionFrom(
                node,
                clickable,
                "text",
                node.text,
                expressionRegex,
                config.targetApp == TargetApp.XIAOYUAN
            )?.let(expressions::add)
            candidateFrom(
                node,
                clickable,
                "contentDescription",
                node.contentDescription,
                regex
            )?.let(candidates::add)
            expressionFrom(
                node,
                clickable,
                "contentDescription",
                node.contentDescription,
                expressionRegex,
                config.targetApp == TargetApp.XIAOYUAN
            )?.let(expressions::add)

            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                nodesToRecycle += child
                visit(child, clickable)
                if (visited >= config.maxNodesPerScan) break
            }
        }

        visit(root, if (root.isClickable && root.isEnabled) root else null)

        val unique = candidates.distinctBy {
            "${it.value}|${it.bounds.flattenToString()}"
        }
        val uniqueExpressions = expressions.distinctBy {
            "${it.node.hashCode()}|${it.left}|${it.right}"
        }
        val orderedExpressions = visibleOrderedExpressions(
            uniqueExpressions,
            screenWidth,
            screenHeight,
            config
        )
        val orderedQuestions = orderedExpressions.map(::toDetectedQuestion)
        val question = selectActiveExpression(
            orderedExpressions,
            preferredExpressionIndex
        ) ?: selectBestPair(unique, screenWidth, screenHeight, config)
        val result = if (question != null) {
            QuestionReadResult.Found(question)
        } else {
            QuestionReadResult.NotFound(
                "扫描 $visited 个节点，找到 ${uniqueExpressions.size} 个组合题、" +
                    "${unique.size} 个数字候选，但未形成左右配对"
            )
        }
        return QuestionScan(
            result = result,
            orderedQuestions = orderedQuestions,
            hasHandwritingArea = hasHandwritingArea,
            isGameplayTimerRunning = isGameplayTimerRunning,
            hasBlockingOverlay = hasBlockingOverlay,
            // XiaoYuan displays the current question number (the final N/N still needs an answer).
            // Zuoyebang's original profile keeps its prior completed-count behavior.
            completedQuestionCount = if (config.targetApp == TargetApp.XIAOYUAN) {
                currentQuestionNumber?.minus(1)?.coerceAtLeast(0)
                    ?.coerceAtMost(totalQuestionCount?.minus(1)?.coerceAtLeast(0) ?: Int.MAX_VALUE)
                    ?: 0
            } else {
                currentQuestionNumber?.coerceAtLeast(0)
                    ?.coerceAtMost(totalQuestionCount ?: Int.MAX_VALUE)
                    ?: 0
            },
            totalQuestionCount = totalQuestionCount?.coerceAtLeast(1) ?: 0,
            nodesToRecycle = nodesToRecycle
        )
    }

    private fun expressionFrom(
        node: AccessibilityNodeInfo,
        clickable: AccessibilityNodeInfo?,
        source: String,
        raw: CharSequence?,
        regex: Regex,
        allowSeparatedNumbers: Boolean
    ): ExpressionCandidate? {
        val text = normalizeNodeText(raw) ?: return null
        val match = regex.matchEntire(text)
            ?: (if (allowSeparatedNumbers) {
                SEPARATED_NUMBER_PAIR_REGEX.matchEntire(text)
            } else {
                null
            })
            ?: return null
        if (match.groups.size < 3) return null
        val left = match.groups[1]?.value?.toIntOrNull() ?: return null
        val right = match.groups[2]?.value?.toIntOrNull() ?: return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return null
        return ExpressionCandidate(left, right, text, bounds, node, clickable, source)
    }

    private fun selectActiveExpression(
        orderedExpressions: List<ExpressionCandidate>,
        preferredExpressionIndex: Int
    ): DetectedQuestion? {
        val active = orderedExpressions.getOrNull(preferredExpressionIndex.coerceAtLeast(0))
            ?: orderedExpressions.firstOrNull()
            ?: return null
        return toDetectedQuestion(active)
    }

    private fun visibleOrderedExpressions(
        expressions: List<ExpressionCandidate>,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): List<ExpressionCandidate> {
        val top = screenHeight * config.scanTopRatio
        val bottom = screenHeight * config.scanBottomRatio
        return expressions.filter {
            it.bounds.centerY() in top.toInt()..bottom.toInt() &&
                it.bounds.centerX() in 0..screenWidth &&
                it.bounds.top >= 0 && it.bounds.bottom <= screenHeight
        }.sortedWith(
            compareBy<ExpressionCandidate> { it.bounds.centerY() }
                .thenByDescending { it.bounds.width() }
        )
    }

    private fun toDetectedQuestion(active: ExpressionCandidate): DetectedQuestion {
        val middle = active.bounds.centerX()
        val leftBounds = Rect(active.bounds.left, active.bounds.top, middle, active.bounds.bottom)
        val rightBounds = Rect(middle, active.bounds.top, active.bounds.right, active.bounds.bottom)
        return DetectedQuestion(
            left = NumberCandidate(
                value = active.left,
                rawText = active.rawText,
                bounds = leftBounds,
                node = active.node,
                clickableAncestor = active.clickableAncestor,
                source = "${active.source}:expression-left"
            ),
            right = NumberCandidate(
                value = active.right,
                rawText = active.rawText,
                bounds = rightBounds,
                node = active.node,
                clickableAncestor = active.clickableAncestor,
                source = "${active.source}:expression-right"
            )
        )
    }

    private fun candidateFrom(
        node: AccessibilityNodeInfo,
        clickable: AccessibilityNodeInfo?,
        source: String,
        raw: CharSequence?,
        regex: Regex
    ): NumberCandidate? {
        val text = normalizeNodeText(raw) ?: return null
        val match = regex.matchEntire(text) ?: return null
        val numberText = if (match.groups.size > 1) {
            match.groups[1]?.value ?: match.value.trim()
        } else {
            match.value.trim()
        }
        val value = numberText.toIntOrNull() ?: return null
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return null
        return NumberCandidate(
            value = value,
            rawText = text,
            bounds = bounds,
            node = node,
            clickableAncestor = clickable,
            source = source
        )
    }

    private fun selectBestPair(
        candidates: List<NumberCandidate>,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): DetectedQuestion? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val top = screenHeight * config.scanTopRatio
        val bottom = screenHeight * config.scanBottomRatio
        val leftLimit = screenWidth * config.leftMaxXRatio
        val rightLimit = screenWidth * config.rightMinXRatio
        val maxYDelta = screenHeight * config.maxVerticalDeltaRatio

        val visible = candidates.filter {
            it.centerY in top.toInt()..bottom.toInt() &&
                it.centerX in 0..screenWidth &&
                it.bounds.top >= 0 && it.bounds.bottom <= screenHeight
        }
        val leftCandidates = visible.filter { it.centerX < leftLimit }
        val rightCandidates = visible.filter { it.centerX > rightLimit }

        return leftCandidates.flatMap { left ->
            rightCandidates.mapNotNull { right ->
                val yDelta = abs(left.centerY - right.centerY).toFloat()
                if (yDelta > maxYDelta) return@mapNotNull null

                val pairMidX = (left.centerX + right.centerX) / 2f
                val symmetryPenalty = abs(pairMidX - screenWidth / 2f)
                val centerYPenalty = abs(
                    (left.centerY + right.centerY) / 2f - screenHeight * 0.48f
                )
                val horizontalGap = right.centerX - left.centerX
                val score = yDelta * 4f + symmetryPenalty + centerYPenalty * 0.15f - horizontalGap * 0.03f
                score to DetectedQuestion(left, right)
            }
        }.minByOrNull { it.first }?.second
    }

    private data class ExpressionCandidate(
        val left: Int,
        val right: Int,
        val rawText: String,
        val bounds: Rect,
        val node: AccessibilityNodeInfo,
        val clickableAncestor: AccessibilityNodeInfo?,
        val source: String
    )

    private fun normalizeNodeText(raw: CharSequence?): String? {
        val value = raw?.toString()
            ?.replace('\u00A0', ' ')
            ?.replace('\u202F', ' ')
            ?.trim()
            .orEmpty()
        return value.takeIf { it.isNotEmpty() }
    }

    private fun isZuoyebangBlockingText(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        return ZUOYEBANG_BLOCKING_TEXTS.any(text::contains) ||
            ZUOYEBANG_READY_GO_REGEX.matches(text)
    }

    private companion object {
        const val HANDWRITING_AREA_TEXT = "手写区"
        val GAMEPLAY_TIMER_REGEX = Regex("^\\d{1,2}:[0-5]\\d$")
        val SCORE_REGEX = Regex("^(\\d+)\\s*/\\s*(\\d+)题$")
        val ZUOYEBANG_BLOCKING_TEXTS = listOf(
            "题目数量",
            "本轮PK目标",
            "比谁快",
            "恭喜获胜",
            "继续PK",
            "本局答题记录"
        )
        val ZUOYEBANG_READY_GO_REGEX = Regex(
            "^(?:READY|GO|READY\\s*GO)[!！]?$",
            RegexOption.IGNORE_CASE
        )
        val SEPARATED_NUMBER_PAIR_REGEX = Regex(
            "^\\s*(-?\\d{1,6})[\\s\\u00A0\\u202F]+(-?\\d{1,6})\\s*$"
        )
    }
}
