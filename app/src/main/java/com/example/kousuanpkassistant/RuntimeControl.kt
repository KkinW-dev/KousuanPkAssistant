package com.example.kousuanpkassistant

import com.example.kousuanpkassistant.service.KousuanAccessibilityService

object RuntimeControl {
    @Volatile
    private var service: KousuanAccessibilityService? = null

    @Synchronized
    fun attach(service: KousuanAccessibilityService) {
        this.service = service
    }

    @Synchronized
    fun detach(service: KousuanAccessibilityService): Boolean {
        if (this.service === service) {
            this.service = null
            return true
        }
        return false
    }

    fun start(): Boolean = service?.setAutomationRunning(true, "用户启动") ?: false

    fun stop(reason: String = "用户停止"): Boolean =
        service?.stopFromUser(reason) ?: false

    fun refreshConfiguration() {
        service?.refreshConfiguration()
    }
}
