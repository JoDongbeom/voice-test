package com.jodongbeom.voicetest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 화면이 꺼져도 답안을 끝까지 읽는 포그라운드 서비스. 읽는 위치는 PlayerState 가 관리. */
class SpeechService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_LOAD = "com.jodongbeom.voicetest.LOAD"
        const val ACTION_PLAY_FROM = "com.jodongbeom.voicetest.PLAY_FROM"
        const val ACTION_PLAY = "com.jodongbeom.voicetest.PLAY"
        const val ACTION_PAUSE = "com.jodongbeom.voicetest.PAUSE"
        const val ACTION_TOGGLE = "com.jodongbeom.voicetest.TOGGLE"
        const val ACTION_PREV = "com.jodongbeom.voicetest.PREV"
        const val ACTION_NEXT = "com.jodongbeom.voicetest.NEXT"
        const val ACTION_RATE = "com.jodongbeom.voicetest.RATE"
        const val ACTION_CLOSE = "com.jodongbeom.voicetest.CLOSE"
        /** 화면이 보일 때 미리 서비스를 띄워 둠 (화면이 꺼진 뒤에는 새로 띄울 수 없어서) */
        const val ACTION_WARMUP = "com.jodongbeom.voicetest.WARMUP"
        private const val CUSTOM_CLOSE = "close"
        const val EXTRA_LINES = "lines"
        const val EXTRA_INDEX = "index"
        const val EXTRA_RATE = "rate"

        private const val CHANNEL_ID = "reading"
        private const val NOTIF_ID = 1

        /** 화면(MainActivity)이 보일 때만 설정됨. 상태 JSON 을 받음 */
        @Volatile var listener: ((String) -> Unit)? = null
        @Volatile var instance: SpeechService? = null
    }

    private val state = PlayerState()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var rate = 0.8f
    private var ended = false

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private lateinit var audio: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private lateinit var silence: AudioTrack
    private lateinit var session: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null

    // 이어폰 연결이 끊기면 스피커로 크게 나오지 않게 일시정지
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "답안 읽기", NotificationManager.IMPORTANCE_LOW))

        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change ->
                // 전화, 다른 앱 음악 → 일시정지 (자동 재개하지 않음)
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }, main)
            .build()
        silence = buildSilentTrack()

        session = MediaSessionCompat(this, "voice-test").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = prev()
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action == CUSTOM_CLOSE) close()
                }
            })
            isActive = true
        }

        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            report("음성 엔진을 시작할 수 없습니다")
            return
        }
        tts.setLanguage(Locale.KOREAN)
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { main.post { onChunkDone(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { main.post { onChunkDone(utteranceId) } }
            override fun onError(utteranceId: String, errorCode: Int) { main.post { onChunkDone(utteranceId) } }
        })
        ttsReady = true
        if (state.playing) speakCurrent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 서비스가 살아 있는 동안은 항상 포그라운드 (안드로이드 12+ 백그라운드 재시작 제한 회피)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        handle(intent)
        return START_NOT_STICKY
    }

    /** 명령 처리. 이미 떠 있는 서비스에는 SpeechBridge 가 메인 스레드에서 직접 호출함 */
    fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_LOAD -> {
                stopSpeaking()
                state.load(parseLines(intent.getStringExtra(EXTRA_LINES) ?: "[]"))
                rate = intent.getFloatExtra(EXTRA_RATE, rate)
                ended = false
                report()
            }
            ACTION_PLAY_FROM -> playFrom(intent.getIntExtra(EXTRA_INDEX, 0))
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (state.playing) pause() else play()
            ACTION_PREV -> prev()
            ACTION_NEXT -> next()
            ACTION_RATE -> rate = intent.getFloatExtra(EXTRA_RATE, rate)
            ACTION_CLOSE -> close()
        }
    }

    private fun close() {
        pause()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stopSpeaking()
        tts.shutdown()
        session.release()
        silence.release()
        unregisterReceiver(noisyReceiver)
        super.onDestroy()
    }

    // ---------- 재생 명령 ----------
    private fun playFrom(index: Int) { if (state.playFrom(index)) startSpeaking() else report() }
    private fun play() { if (state.play()) startSpeaking() }
    private fun prev() { if (state.prevLine()) startSpeaking() }
    private fun next() { if (state.nextLine()) startSpeaking() else report("마지막 줄입니다") }
    private fun pause() {
        if (!state.playing) return
        state.pause()
        stopSpeaking()
        report()
    }

    private fun startSpeaking() {
        ended = false
        audio.requestAudioFocus(focusRequest)
        if (silence.playState != AudioTrack.PLAYSTATE_PLAYING) silence.play()
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicetest:reading").apply {
                acquire(3 * 60 * 60 * 1000L) // 최대 3시간 안전장치
            }
        }
        speakCurrent()
        report()
    }

    private fun stopSpeaking() {
        if (ttsReady) tts.stop()
        if (silence.playState == AudioTrack.PLAYSTATE_PLAYING) silence.pause()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        audio.abandonAudioFocusRequest(focusRequest)
    }

    private fun speakCurrent() {
        if (!ttsReady) return // 초기화가 끝나면 onInit 에서 이어서 읽음
        val text = state.currentText() ?: return
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "${state.generation}:${state.pos}:${state.chunk}")
    }

    private fun onChunkDone(utteranceId: String) {
        // 이동/일시정지/새 답안 이전에 시작된 조각의 알림은 무시
        if (!state.playing || !utteranceId.startsWith("${state.generation}:")) return
        if (state.advance()) {
            speakCurrent()
            report()
        } else {
            stopSpeaking()
            ended = true
            report()
        }
    }

    // ---------- 상태 알림 (화면, 잠금화면) ----------
    fun reportNow() { main.post { report() } }

    private fun report(message: String? = null) {
        val json = JSONObject()
            .put("state", when { state.playing -> "playing"; ended -> "ended"; else -> "paused" })
            .put("pos", state.pos)
            .put("total", state.lines.size)
        if (message != null) json.put("message", message)
        listener?.invoke(json.toString())
        updateSession()
    }

    private fun lineLabel(): String =
        if (state.lines.isEmpty()) "대기"
        else "${state.pos + 1}/${state.lines.size}줄 · " + (if (state.playing) "읽는 중" else "일시정지")

    private fun updateSession() {
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                // 안드로이드 13+ 잠금화면 조작은 알림 버튼이 아니라 여기서 만들어짐 → 닫기 버튼도 여기 추가
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_CLOSE, "닫기", android.R.drawable.ic_menu_close_clear_cancel
                    ).build()
                )
                .setState(
                    if (state.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "시험 답안 듣기")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, lineLabel())
                .build()
        )
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 표시만 안 됨 (읽기는 계속)
        }
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, requestCode,
            Intent(this, SpeechService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = if (state.playing)
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "일시정지", commandIntent(ACTION_TOGGLE, 2))
        else
            NotificationCompat.Action(android.R.drawable.ic_media_play, "재생", commandIntent(ACTION_TOGGLE, 2))
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("시험 답안 듣기")
            .setContentText(lineLabel())
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_previous, "이전 줄", commandIntent(ACTION_PREV, 1))
            .addAction(playPause)
            .addAction(android.R.drawable.ic_media_next, "다음 줄", commandIntent(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "닫기", commandIntent(ACTION_CLOSE, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // ---------- 도우미 ----------
    private fun parseLines(json: String): List<Line> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("chunks")
            Line((0 until c.length()).map { c.getString(it) }, o.optBoolean("section"))
        }
    }

    /** 무음 1초를 반복 재생. TTS 소리는 TTS 엔진 앱이 내므로, 이게 있어야 이어폰 버튼이 우리 앱으로 옴 */
    private fun buildSilentTrack(): AudioTrack {
        val sampleRate = 8000
        val frames = sampleRate
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        track.write(ShortArray(frames), 0, frames)
        track.setLoopPoints(0, frames, -1)
        return track
    }
}
