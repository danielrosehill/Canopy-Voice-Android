package com.danielrosehill.canopyvoice.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.danielrosehill.canopyvoice.MainActivity

/**
 * Android's digital assistant framework: with these, the app appears under Settings > Apps > Default apps >
 * Digital assistant app, and the assist gesture (long-press power or home, corner swipe) opens it listening.
 */
class YossiVoiceService : VoiceInteractionService()

class YossiSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = YossiSession(this)
}

class YossiSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_LISTEN, true))
        hide()
    }
}

/** The framework requires a recognition service next to the assistant; this one declines every request. */
class YossiRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
