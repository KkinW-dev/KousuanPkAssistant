package com.example.kousuanpkassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.kousuanpkassistant.RuntimeControl

class SafetyStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_AUTOMATION) {
            RuntimeControl.stop("通知栏安全停止")
        }
    }

    companion object {
        const val ACTION_STOP_AUTOMATION =
            "com.example.kousuanpkassistant.action.STOP_AUTOMATION"
    }
}

