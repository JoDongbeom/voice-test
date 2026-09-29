package com.jodongbeom.voicetest

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pageReady = false
    private val pendingNotices = mutableListOf<String>()

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null

    // 촬영 결과를 <input type=file> 에 돌려줌. 취소해도 반드시 null 을 돌려줘야 다음 촬영이 됨
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        fileCallback?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notice("⚠ 알림 권한이 꺼져 있어 화면이 꺼지면 읽기가 멈출 수 있습니다. 설정 > 앱 > 답안 듣기 > 알림에서 켜 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.addJavascriptInterface(SpeechBridge(applicationContext), "AndroidSpeech")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingNotices.forEach { sendNotice(it) }
                pendingNotices.clear()
                SpeechService.instance?.reportNow()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                openCamera()
                return true
            }
        }
        setContentView(web)
        web.loadUrl(PAGE_URL)

        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        checkKoreanVoice()
        showBatteryNoticeOnce()
    }

    override fun onResume() {
        super.onResume()
        SpeechService.listener = { json ->
            runOnUiThread { web.evaluateJavascript("window.onNativeSpeech && window.onNativeSpeech($json)", null) }
        }
        if (pageReady) SpeechService.instance?.reportNow()
    }

    override fun onPause() {
        SpeechService.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun openCamera() {
        val dir = File(cacheDir, "photos").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // 지난 사진 정리
        }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            notice("⚠ 카메라 앱을 찾을 수 없습니다.")
        }
    }

    private fun checkKoreanVoice() {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS &&
                (tts?.isLanguageAvailable(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
            if (!ok) notice("⚠ 한국어 음성이 없습니다. 설정 > 텍스트 음성 변환(TTS)에서 한국어 음성 데이터를 설치하세요.")
            tts?.shutdown()
        }
    }

    private fun showBatteryNoticeOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("batteryNoticeShown", false)) return
        prefs.edit().putBoolean("batteryNoticeShown", true).apply()
        notice("ℹ 삼성 등 일부 폰은 배터리 절약 때문에 화면이 꺼진 뒤 읽기가 멈출 수 있습니다. 그러면 설정 > 애플리케이션 > 답안 듣기 > 배터리 > '제한 없음'으로 바꾸세요.")
    }

    private fun notice(text: String) {
        runOnUiThread { if (pageReady) sendNotice(text) else pendingNotices += text }
    }

    private fun sendNotice(text: String) {
        web.evaluateJavascript("window.onNativeNotice && window.onNativeNotice(${JSONObject.quote(text)})", null)
    }

    companion object {
        const val PAGE_URL = "https://appassets.androidplatform.net/assets/index.html"
    }
}
