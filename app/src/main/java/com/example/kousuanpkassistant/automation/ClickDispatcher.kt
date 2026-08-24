package com.example.kousuanpkassistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.example.kousuanpkassistant.model.AnswerMode
import com.example.kousuanpkassistant.model.AutomationConfig
import com.example.kousuanpkassistant.model.ClickStrategy
import com.example.kousuanpkassistant.model.DetectedQuestion
import com.example.kousuanpkassistant.model.NumberCandidate
import com.example.kousuanpkassistant.model.Relation
import com.example.kousuanpkassistant.model.TargetApp

data class ClickResult(
    val accepted: Boolean,
    val method: String,
    val detail: String
)

class ClickDispatcher {
    fun drawRelationBatch(
        service: AccessibilityService,
        questions: List<DetectedQuestion>,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig,
        onFinished: (completed: Boolean) -> Unit
    ): ClickResult {
        if (questions.isEmpty()) {
            return ClickResult(false, "rapid-batch", "极速批量手势没有题目")
        }
        val duration = config.rapidStrokeDurationMs.coerceAtLeast(30L)
        val interval = config.rapidStrokeIntervalMs.coerceAtLeast(duration)
        val builder = GestureDescription.Builder()
        questions.take(MAX_BATCH_STROKES).forEachIndexed { index, question ->
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    relationPath(question.relation, screenWidth, screenHeight, config),
                    index * interval,
                    duration
                )
            )
        }
        val plannedDuration = (questions.size.coerceAtMost(MAX_BATCH_STROKES) - 1) * interval + duration
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onFinished(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onFinished(false)
            }
        }
        val accepted = runCatching {
            service.dispatchGesture(builder.build(), callback, null)
        }.getOrDefault(false)
        return ClickResult(
            accepted = accepted,
            method = "rapid-batch",
            detail = if (accepted) {
                "已提交 ${questions.size.coerceAtMost(MAX_BATCH_STROKES)} 题极速手势，计划 ${plannedDuration}ms"
            } else {
                "极速批量手势提交失败"
            }
        )
    }

    fun click(
        service: AccessibilityService,
        question: DetectedQuestion,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): ClickResult {
        if (config.answerMode == AnswerMode.DRAW_RELATION) {
            return drawRelation(service, question.relation, screenWidth, screenHeight, config)
        }
        if (config.answerMode == AnswerMode.RELATION_BUTTONS || question.relation == Relation.EQUAL) {
            val xRatio = when (question.relation) {
                Relation.LESS -> config.lessButtonXRatio
                Relation.EQUAL -> config.equalButtonXRatio
                Relation.GREATER -> config.greaterButtonXRatio
            }
            return gestureTap(
                service,
                screenWidth * xRatio,
                screenHeight * config.answerButtonYRatio,
                config.tapDurationMs,
                "固定关系按钮 ${question.relation.symbol}"
            )
        }

        val target = when (config.answerMode) {
            AnswerMode.CLICK_GREATER_VALUE -> if (question.relation == Relation.GREATER) {
                question.left
            } else {
                question.right
            }

            AnswerMode.CLICK_LESS_VALUE -> if (question.relation == Relation.LESS) {
                question.left
            } else {
                question.right
            }

            AnswerMode.DRAW_RELATION -> error("已在上方处理")
            AnswerMode.RELATION_BUTTONS -> error("已在上方处理")
        }

        if (config.clickStrategy == ClickStrategy.NODE_THEN_GESTURE) {
            val clickableNode = target.clickableAncestor
            if (clickableNode != null && clickableNode.isEnabled) {
                val clicked = runCatching {
                    clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) {
                    return ClickResult(true, "node", "点击数值节点 ${target.value}")
                }
            }
        }

        return gestureTap(
            service,
            target.centerX.toFloat(),
            target.centerY.toFloat(),
            config.tapDurationMs,
            "点击数值坐标 ${target.value}"
        )
    }

    private fun drawRelation(
        service: AccessibilityService,
        relation: Relation,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): ClickResult {
        val duration = config.drawStrokeDurationMs.coerceAtLeast(30L)
        val paths = relationStrokePaths(relation, screenWidth, screenHeight, config)
        val mainHandler = Handler(Looper.getMainLooper())

        fun dispatchStroke(index: Int): Boolean {
            val callback = if (index + 1 < paths.size) {
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.postDelayed(
                            { dispatchStroke(index + 1) },
                            MULTI_STROKE_GAP_MS
                        )
                    }
                }
            } else {
                null
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(paths[index], 0L, duration))
                .build()
            return runCatching {
                service.dispatchGesture(gesture, callback, mainHandler)
            }.getOrDefault(false)
        }

        val accepted = dispatchStroke(0)
        return ClickResult(
            accepted = accepted,
            method = "draw",
            detail = if (accepted) {
                "在手写区快速绘制 ${relation.symbol}"
            } else {
                "手写手势提交失败"
            }
        )
    }

    private fun relationPath(
        relation: Relation,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): Path = relationStrokePaths(relation, screenWidth, screenHeight, config).first()

    private fun relationStrokePaths(
        relation: Relation,
        screenWidth: Int,
        screenHeight: Int,
        config: AutomationConfig
    ): List<Path> {
        val centerX = screenWidth * config.drawCenterXRatio
        val centerY = screenHeight * config.drawCenterYRatio
        val halfWidth = screenWidth * config.drawWidthRatio / 2f
        val halfHeight = screenHeight * config.drawHeightRatio / 2f
        val leftX = (centerX - halfWidth).coerceIn(1f, screenWidth - 2f)
        val rightX = (centerX + halfWidth).coerceIn(1f, screenWidth - 2f)
        val topY = (centerY - halfHeight).coerceIn(1f, screenHeight - 2f)
        val bottomY = (centerY + halfHeight).coerceIn(1f, screenHeight - 2f)
        if (config.targetApp == TargetApp.ZUOYEBANG) {
            // Preserve the original Zuoyebang implementation: one short stroke per answer.
            return listOf(when (relation) {
                Relation.LESS -> Path().apply {
                    moveTo(rightX, topY)
                    lineTo(leftX, centerY)
                }

                Relation.GREATER -> Path().apply {
                    moveTo(leftX, topY)
                    lineTo(rightX, centerY)
                }

                Relation.EQUAL -> Path().apply {
                    moveTo(leftX, centerY)
                    lineTo(rightX, centerY)
                }
            })
        }
        return when (relation) {
            Relation.LESS -> listOf(Path().apply {
                moveTo(rightX, topY)
                lineTo(leftX, centerY)
                lineTo(rightX, bottomY)
            })

            Relation.GREATER -> listOf(Path().apply {
                moveTo(leftX, topY)
                lineTo(rightX, centerY)
                lineTo(leftX, bottomY)
            })

            Relation.EQUAL -> listOf(
                Path().apply {
                    moveTo(leftX, centerY - halfHeight * 0.38f)
                    lineTo(rightX, centerY - halfHeight * 0.38f)
                },
                Path().apply {
                    moveTo(leftX, centerY + halfHeight * 0.38f)
                    lineTo(rightX, centerY + halfHeight * 0.38f)
                }
            )
        }
    }

    private fun gestureTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        detail: String
    ): ClickResult {
        if (x < 0f || y < 0f) {
            return ClickResult(false, "gesture", "点击坐标无效：($x, $y)")
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        val accepted = runCatching { service.dispatchGesture(gesture, null, null) }
            .getOrDefault(false)
        return ClickResult(
            accepted = accepted,
            method = "gesture",
            detail = if (accepted) "$detail @ (${x.toInt()}, ${y.toInt()})" else "手势提交失败"
        )
    }

    private companion object {
        const val MAX_BATCH_STROKES = 10
        const val MULTI_STROKE_GAP_MS = 20L
    }
}
