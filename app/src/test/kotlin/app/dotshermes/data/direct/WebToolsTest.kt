package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolsTest {
    @Test fun `duckduckgo results keep the real link, title and snippet`() {
        val html = """
            <div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.example.com%2Fdavido&amp;rut=x">Davido &amp; <b>news</b></a>
            <a class="result__snippet" href="x">New <b>album</b> out today</a></div>
            <div class="result"><a rel="nofollow" class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a></div>
        """.trimIndent()
        val results = WebTools.parseDuckDuckGo(html)
        assertEquals(1, results.size)
        assertEquals(Triple("Davido & news", "https://www.example.com/davido", "New album out today"), results[0])
    }

    @Test fun `rss items with cdata`() {
        val xml = "<rss><channel><item><title><![CDATA[Big win &amp; more]]></title><link>https://n.example/1</link>" +
            "<pubDate>Tue, 11 Nov 2026 10:00:00 GMT</pubDate><source url=\"x\">Punch</source></item></channel></rss>"
        val item = WebTools.parseRss(xml).single()
        assertEquals("Big win & more", item.title)
        assertEquals("Punch", item.source)
        assertEquals("https://n.example/1", item.link)
    }

    @Test fun `page text drops scripts and tags`() {
        val text = WebTools.pageText("<html><head><title>t</title></head><body><script>var x=1</script><h1>Hello</h1><p>World &amp; you</p></body></html>")
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World & you"))
        assertFalse(text.contains("var x"))
    }
}
