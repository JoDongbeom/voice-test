package com.jodongbeom.voicetest

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat

/** index.html 에서 window.AndroidSpeech 로 보이는 객체. 명령을 SpeechService 로 전달 */
class SpeechBridge(private val context: Context) {

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        val intent = Intent(context, SpeechService::class.java).setAction(action).apply(fill)
        ContextCompat.startForegroundService(context, intent)
    }

    @JavascriptInterface
    fun load(json: String, rate: Float) = send(SpeechService.ACTION_LOAD) {
        putExtra(SpeechService.EXTRA_LINES, json)
        putExtra(SpeechService.EXTRA_RATE, rate)
    }

    @JavascriptInterface
    fun playFrom(index: Int) = send(SpeechService.ACTION_PLAY_FROM) {
        putExtra(SpeechService.EXTRA_INDEX, index)
    }

    // 서비스가 없을 때 일시정지/속도 변경으로 알림을 띄우지 않도록
    @JavascriptInterface
    fun pause() {
        if (SpeechService.instance != null) send(SpeechService.ACTION_PAUSE)
    }

    @JavascriptInterface
    fun setRate(rate: Float) {
        if (SpeechService.instance != null) send(SpeechService.ACTION_RATE) { putExtra(SpeechService.EXTRA_RATE, rate) }
    }
}
