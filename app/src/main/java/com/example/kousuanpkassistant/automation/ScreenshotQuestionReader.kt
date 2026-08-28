package com.example.kousuanpkassistant.automation

import com.example.kousuanpkassistant.model.QuestionReadResult

/**
 * 截图/OCR 题目识别后备入口。
 *
 * 当前不会用截图识别题目，也不绑定任何 OCR 引擎。作业帮的开场画面变化检测是独立的
 * 轻量逻辑，只比较少量像素且不保存画面；未来如果实现题目 OCR，应把识别结果转换为与
 * 节点读取相同的 QuestionReadResult。
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
