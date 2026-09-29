package com.jodongbeom.voicetest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateTest {
    private fun sample() = PlayerState().apply {
        load(
            listOf(
                Line(listOf("로마자 일, 소재"), true),
                Line(listOf("점, 첫 문장.", "둘째 문장"), false),
                Line(emptyList(), false),
                Line(listOf("로마자 이, 결론"), true),
            )
        )
    }

    @Test fun `load drops empty lines and starts paused at zero`() {
        val s = sample()
        assertEquals(3, s.lines.size)
        assertEquals(0, s.pos)
        assertFalse(s.playing)
        assertNull(s.currentText())
    }

    @Test fun `empty list cannot play`() {
        val s = PlayerState()
        s.load(emptyList())
        assertFalse(s.playFrom(0))
        assertFalse(s.playing)
        assertNull(s.currentText())
    }

    @Test fun `playFrom clamps index`() {
        val s = sample()
        assertTrue(s.playFrom(99))
        assertEquals(2, s.pos)
        assertTrue(s.playFrom(-5))
        assertEquals(0, s.pos)
        assertEquals("로마자 일, 소재", s.currentText())
    }

    @Test fun `advance walks chunks then lines then ends at zero`() {
        val s = sample()
        s.playFrom(1)
        assertEquals("점, 첫 문장.", s.currentText())
        assertTrue(s.advance())
        assertEquals("둘째 문장", s.currentText())
        assertTrue(s.advance())
        assertEquals(2, s.pos)
        assertEquals("로마자 이, 결론", s.currentText())
        assertFalse(s.advance())
        assertFalse(s.playing)
        assertEquals(0, s.pos)
    }

    @Test fun `play after end restarts from zero`() {
        val s = sample()
        s.playFrom(2)
        s.advance()
        assertTrue(s.play())
        assertEquals(0, s.pos)
        assertEquals("로마자 일, 소재", s.currentText())
    }

    @Test fun `pause keeps position and silences`() {
        val s = sample()
        s.playFrom(1)
        s.advance()
        s.pause()
        assertFalse(s.playing)
        assertEquals(1, s.pos)
        assertNull(s.currentText())
        assertTrue(s.play())
        assertEquals("점, 첫 문장.", s.currentText()) // 줄 처음부터 다시
    }

    @Test fun `nextLine at last line does nothing`() {
        val s = sample()
        s.playFrom(2)
        val gen = s.generation
        assertFalse(s.nextLine())
        assertTrue(s.playing)
        assertEquals(2, s.pos)
        assertEquals(gen, s.generation)
    }

    @Test fun `prevLine at first line replays first line`() {
        val s = sample()
        s.playFrom(0)
        assertTrue(s.prevLine())
        assertEquals(0, s.pos)
    }

    @Test fun `playFrom bumps generation so stale callbacks can be ignored`() {
        val s = sample()
        s.playFrom(0)
        val old = s.generation
        s.playFrom(1)
        assertNotEquals(old, s.generation)
        val beforeLoad = s.generation
        s.load(listOf(Line(listOf("새 답안"), true)))
        assertNotEquals(beforeLoad, s.generation)
        assertFalse(s.playing)
    }
}
