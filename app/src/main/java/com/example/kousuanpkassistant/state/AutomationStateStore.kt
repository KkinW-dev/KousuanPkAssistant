package com.example.kousuanpkassistant.state

import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AutomationSnapshot(
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val status: String = "等待无障碍服务连接",
    val activePackage: String = "-",
    val leftValue: Int? = null,
    val rightValue: Int? = null,
    val relation: String = "-",
    val handledCount: Int = 0,
    val errorCount: Int = 0,
    val logLines: List<String> = emptyList()
)

object AutomationStateStore {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile
    private var value = AutomationSnapshot()

    fun snapshot(): AutomationSnapshot = value

    @Synchronized
    fun setServiceConnected(connected: Boolean, status: String) {
        value = value.copy(
            serviceConnected = connected,
            running = if (connected) value.running else false,
            status = if (!value.running || !connected) status else value.status
        )
        appendLogLocked(status)
    }

    @Synchronized
    fun beginRun() {
        value = value.copy(
            running = true,
            status = "已启动，等待目标界面",
            leftValue = null,
            rightValue = null,
            relation = "-",
            handledCount = 0,
            errorCount = 0,
            logLines = emptyList()
        )
        appendLogLocked("自动化已启动")
    }

    @Synchronized
    fun endRun(reason: String) {
        value = value.copy(running = false, status = reason)
        appendLogLocked("已停止：$reason")
    }

    @Synchronized
    fun updatePackage(packageName: String) {
        if (value.activePackage != packageName) {
            value = value.copy(activePackage = packageName)
        }
    }

    @Synchronized
    fun updateDetection(left: Int, right: Int, relation: String) {
        value = value.copy(
            status = "已识别题目",
            leftValue = left,
            rightValue = right,
            relation = relation
        )
    }

    @Synchronized
    fun recordHandled(count: Int, message: String) {
        value = value.copy(handledCount = count, status = message)
        appendLogLocked(message)
    }

    @Synchronized
    fun recordError(count: Int, message: String) {
        value = value.copy(errorCount = count, status = message)
        appendLogLocked("错误：$message")
    }

    @Synchronized
    fun setStatus(status: String) {
        value = value.copy(status = status)
    }

    @Synchronized
    fun appendLog(message: String) {
        appendLogLocked(message)
    }

    @Synchronized
    fun clearLogs() {
        value = value.copy(logLines = emptyList())
    }

    private fun appendLogLocked(message: String) {
        val line = "${LocalTime.now().format(timeFormatter)}  $message"
        value = value.copy(logLines = (value.logLines + line).takeLast(MAX_LOG_LINES))
    }

    private const val MAX_LOG_LINES = 120
}
