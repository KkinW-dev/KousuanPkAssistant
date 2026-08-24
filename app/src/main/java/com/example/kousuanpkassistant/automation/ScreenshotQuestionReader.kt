package com.example.kousuanpkassistant.automation

import com.example.kousuanpkassistant.model.QuestionReadResult

/**
 * 截图/OCR 后备入口。
 *
 * MVP 不申请 MediaProjection、不采集屏幕，也不绑定任何 OCR 引擎。后续实现时应由用户
 * 明确授权截图，并把识别结果转换为与节点读取相同的 QuestionReadResult。
 */
interface ScreenshotQuestionReader {
    val isAvailable: Boolean
    fun readLatestFrame(): QuestionReadResult
}

class UnimplementedScreenshotQuestionReader : ScreenshotQuestionReader {
    override val isAvailable: Boolean = false

    override fun readLatestFrame(): QuestionReadResult =
        QuestionReadResult.NotFound("截图识别接口已预留，MVP 未实现 OCR")
}

