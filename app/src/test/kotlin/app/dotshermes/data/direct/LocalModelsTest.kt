package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsTest {
    @Test fun `thinking is hidden even when the tags arrive in pieces`() {
        val f = ThinkFilter()
        val out = listOf("Hel", "lo <thi", "nk>plan the", " answer</th", "ink> there", "!").joinToString("") { f.feed(it) }
        assertEquals("Hello  there!", out)
    }

    @Test fun `text without thinking passes through, a lone less-than sign too`() {
        val f = ThinkFilter()
        assertEquals("2 < 3 and <b>", listOf("2 < 3 and ", "<b>").joinToString("") { f.feed(it) })
    }

    @Test fun `catalog models are public litertlm files, the large ones only for 8 GB phones`() {
        LocalModels.CATALOG.forEach { m ->
            assertTrue(m.url.startsWith("https://huggingface.co/litert-community/"))
            assertTrue(m.file.endsWith(".litertlm") && m.url.endsWith(m.file))
            assertTrue(m.sizeBytes in 100_000_000..4_000_000_000)
            if (m.sizeBytes > 2_700_000_000) assertTrue(m.minRamGb >= 8)
        }
    }
}
