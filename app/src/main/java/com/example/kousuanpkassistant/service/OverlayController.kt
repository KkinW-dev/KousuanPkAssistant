package com.example.kousuanpkassistant.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.example.kousuanpkassistant.state.AutomationSnapshot

class OverlayController(
    private val context: Context,
    private val onToggle: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private var toggleButton: Button? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var collapsedEdge = EDGE_RIGHT
    private var latestSnapshot: AutomationSnapshot? = null

    fun show(snapshot: AutomationSnapshot): Boolean {
        if (rootView == null) {
            val padding = dp(3)
            val background = GradientDrawable().apply {
                setColor(Color.argb(235, 25, 25, 28))
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                this.background = background
            }
            toggleButton = Button(context).apply {
                setOnClickListener {
                    if (collapsed) {
                        expandFromEdge(panel)
                    } else {
                        onToggle()
                    }
                }
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                textSize = 16f
                isAllCaps = false
                setPadding(dp(6), 0, dp(6), 0)
                setOnTouchListener(createButtonTouchListener(panel))
            }.also {
                panel.addView(it, LinearLayout.LayoutParams(dp(86), dp(46)))
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (context.resources.displayMetrics.widthPixels - dp(98)).coerceAtLeast(0)
                y = (context.resources.displayMetrics.heightPixels / 2 - dp(26)).coerceAtLeast(0)
            }
            runCatching { windowManager.addView(panel, params) }
                .onSuccess {
                    rootView = panel
                    windowParams = params
                }
                .onFailure {
                    toggleButton = null
                    windowParams = null
                }
        }
        update(snapshot)
        return rootView != null
    }

    fun update(snapshot: AutomationSnapshot) {
        if (rootView == null) return
        latestSnapshot = snapshot
        val button = toggleButton ?: return
        if (collapsed) {
            button.text = if (collapsedEdge == EDGE_LEFT) "›" else "‹"
            button.setTextColor(Color.WHITE)
            button.contentDescription = "展开悬浮开关"
        } else {
            button.text = if (snapshot.running) "停止" else "开始"
            button.setTextColor(if (snapshot.running) Color.RED else Color.rgb(0, 100, 0))
            button.contentDescription = if (snapshot.running) "停止自动作答" else "开始自动作答"
        }
    }

    fun hide() {
        rootView?.let { view -> runCatching { windowManager.removeView(view) } }
        rootView = null
        toggleButton = null
        windowParams = null
    }

    private fun createButtonTouchListener(panel: View): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var dragging = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        return View.OnTouchListener { button, event ->
            val params = windowParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - downRawX
                    val deltaY = event.rawY - downRawY
                    if (!dragging && deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
                        dragging = true
                    }
                    if (!dragging) return@OnTouchListener true
                    val metrics = context.resources.displayMetrics
                    val maxX = (metrics.widthPixels - panel.width).coerceAtLeast(0)
                    val maxY = (metrics.heightPixels - panel.height).coerceAtLeast(0)
                    params.x = (downWindowX + deltaX.toInt()).coerceIn(0, maxX)
                    params.y = (downWindowY + deltaY.toInt()).coerceIn(0, maxY)
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settleAfterDrag(panel)
                    } else {
                        button.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun settleAfterDrag(panel: View) {
        val params = windowParams ?: return
        val metrics = context.resources.displayMetrics
        val nearLeft = params.x <= dp(EDGE_COLLAPSE_DISTANCE_DP)
        val nearRight = params.x + panel.width >=
            metrics.widthPixels - dp(EDGE_COLLAPSE_DISTANCE_DP)
        when {
            nearLeft -> collapseToEdge(panel, EDGE_LEFT)
            nearRight -> collapseToEdge(panel, EDGE_RIGHT)
            collapsed -> expandAtCurrentPosition(panel)
        }
    }

    private fun collapseToEdge(panel: View, edge: Int) {
        val button = toggleButton ?: return
        val params = windowParams ?: return
        collapsed = true
        collapsedEdge = edge
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(COLLAPSED_WIDTH_DP)
        }
        val panelWidth = dp(COLLAPSED_WIDTH_DP + PANEL_HORIZONTAL_PADDING_DP)
        params.x = if (edge == EDGE_LEFT) {
            0
        } else {
            (context.resources.displayMetrics.widthPixels - panelWidth).coerceAtLeast(0)
        }
        runCatching { windowManager.updateViewLayout(panel, params) }
        update(latestSnapshot ?: return)
    }

    private fun expandFromEdge(panel: View) {
        val params = windowParams ?: return
        val panelWidth = dp(EXPANDED_WIDTH_DP + PANEL_HORIZONTAL_PADDING_DP)
        params.x = if (collapsedEdge == EDGE_LEFT) {
            0
        } else {
            (context.resources.displayMetrics.widthPixels - panelWidth).coerceAtLeast(0)
        }
        expand(panel)
    }

    private fun expandAtCurrentPosition(panel: View) {
        expand(panel)
        val params = windowParams ?: return
        val maxX = (context.resources.displayMetrics.widthPixels -
            dp(EXPANDED_WIDTH_DP + PANEL_HORIZONTAL_PADDING_DP)).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun expand(panel: View) {
        val button = toggleButton ?: return
        val params = windowParams ?: return
        collapsed = false
        button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(EXPANDED_WIDTH_DP)
        }
        runCatching { windowManager.updateViewLayout(panel, params) }
        latestSnapshot?.let(::update)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val EDGE_LEFT = -1
        const val EDGE_RIGHT = 1
        const val EDGE_COLLAPSE_DISTANCE_DP = 18
        const val COLLAPSED_WIDTH_DP = 28
        const val EXPANDED_WIDTH_DP = 86
        const val PANEL_HORIZONTAL_PADDING_DP = 6
    }
}
