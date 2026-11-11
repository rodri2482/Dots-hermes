package app.dotshermes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SilenceDetectorTest {
    private fun SilenceDetector.feedAll(levels: List<Float>) = levels.indexOfFirst { feed(it) }

    @Test fun `silence before speaking never sends`() =
        assertFalse(SilenceDetector().let { d -> List(200) { 0.02f }.any { d.feed(it) } })

    @Test fun `speech then a pause sends after about a second and a half`() {
        val levels = List(10) { 0.5f } + List(40) { 0.05f }
        assertEquals(10 + 26, SilenceDetector().feedAll(levels))
    }

    @Test fun `a short breath in the middle does not cut the note`() {
        val levels = List(10) { 0.5f } + List(10) { 0.05f } + List(10) { 0.5f } + List(10) { 0.05f }
        assertEquals(-1, SilenceDetector().feedAll(levels))
    }

    @Test fun `a single click is not speech`() =
        assertEquals(-1, SilenceDetector().feedAll(listOf(0.9f) + List(60) { 0.02f }))
}
