package com.jodongbeom.voicetest

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat

/** index.html 에서 window.AndroidSpeech 로 보이는 객체. 명령을 SpeechService 로 전달 */
class SpeechBridge(
    private val context: Context,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private fun send(action: String, fill: Intent.() -> Unit = {}) {
        val intent = Intent(context, SpeechService::class.java).setAction(action).apply(fill)
        // 이미 떠 있으면 직접 전달: 화면이 꺼진 상태(백그라운드)에서도 동작
        val service = SpeechService.instance
        if (service != null) {
            main.post { service.handle(intent) }
            return
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // 안드로이드 12+: 백그라운드에서는 서비스를 새로 띄울 수 없음
            onError("⚠ 화면이 꺼진 상태에서는 읽기를 시작할 수 없었습니다. 화면을 켜고 ▶ 재생을 눌러 주세요.")
        }
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
