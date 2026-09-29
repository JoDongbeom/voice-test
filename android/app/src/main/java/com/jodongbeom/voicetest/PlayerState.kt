package com.jodongbeom.voicetest

/** 화면의 한 줄. chunks = 끊어 읽을 조각, section = Ⅰ. Ⅱ. 로 시작하는 목차 줄 */
data class Line(val chunks: List<String>, val section: Boolean)

/**
 * 읽는 위치만 관리 (안드로이드 코드 없음 → JVM 단위 테스트 가능).
 * generation 은 재생 명령마다 바뀌어서, 이전 명령의 TTS 완료 알림을 무시하는 데 쓴다.
 */
class PlayerState {
    var lines: List<Line> = emptyList(); private set
    var pos = 0; private set
    var chunk = 0; private set
    var playing = false; private set
    var generation = 0; private set

    fun load(newLines: List<Line>) {
        lines = newLines.filter { it.chunks.isNotEmpty() }
        pos = 0
        chunk = 0
        playing = false
        generation++
    }

    fun playFrom(index: Int): Boolean {
        if (lines.isEmpty()) return false
        pos = index.coerceIn(0, lines.size - 1)
        chunk = 0
        playing = true
        generation++
        return true
    }

    fun play(): Boolean = playFrom(pos)

    fun pause() {
        playing = false
        generation++
    }

    fun prevLine(): Boolean = playFrom(pos - 1)

    fun nextLine(): Boolean {
        if (pos + 1 >= lines.size) return false
        return playFrom(pos + 1)
    }

    fun currentText(): String? = if (playing && lines.isNotEmpty()) lines[pos].chunks[chunk] else null

    /** 지금 조각을 다 읽었을 때 호출. 더 읽을 게 있으면 true, 전체가 끝나면 false (다음 재생은 처음부터) */
    fun advance(): Boolean {
        if (!playing) return false
        chunk++
        if (chunk < lines[pos].chunks.size) return true
        if (pos + 1 < lines.size) {
            pos++
            chunk = 0
            return true
        }
        playing = false
        pos = 0
        chunk = 0
        return false
    }
}
