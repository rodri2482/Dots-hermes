package app.dotshermes.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {

    @Test
    fun `versions compare number by number`() {
        assertTrue(isNewer("v0.2.0.12", "0.2.0.9"))
        assertTrue(isNewer("0.3.0.1", "0.2.0.40"))
        assertTrue(isNewer("0.2.0.3", "0.2.0"))
        assertFalse(isNewer("v0.2.0.3", "0.2.0.3"))
        assertFalse(isNewer("0.2.0.2", "0.2.0.3"))
    }

    @Test
    fun `an unreadable remote version is never offered`() {
        assertFalse(isNewer("nightly", "0.2.0.3"))
        assertNull(versionParts("v1.beta"))
        assertEquals(listOf(0, 2, 0, 12), versionParts("v0.2.0.12"))
    }

    private val latest = """{"tag_name":"v0.3.0.9","html_url":"https://github.com/o/r/releases/tag/v0.3.0.9","assets":[
        {"name":"notes.txt","browser_download_url":"https://github.com/o/r/notes.txt","size":3},
        {"name":"claydot-0.3.0.9.apk","browser_download_url":"https://github.com/o/r/claydot-0.3.0.9.apk","size":14000000},
        {"name":"claydot-0.3.0.9.apk.sha256","browser_download_url":"https://github.com/o/r/claydot-0.3.0.9.apk.sha256","size":86}]}"""

    @Test fun `the latest release picks the apk and its own checksum file`() {
        val r = parseLatestRelease(latest)!!
        assertEquals("0.3.0.9", r.version)
        assertEquals("https://github.com/o/r/claydot-0.3.0.9.apk", r.apkUrl)
        assertEquals("https://github.com/o/r/claydot-0.3.0.9.apk.sha256", r.sha256Url)
        assertEquals(14000000L, r.apkSize)
        assertTrue(isNewer(r.version, "0.3.0.8"))
    }

    @Test fun `releases without an apk, a version or https links are ignored and garbage is an io error`() {
        assertEquals(null, parseLatestRelease("""{"tag_name":"v0.3.0.9","assets":[]}"""))
        assertEquals(null, parseLatestRelease(latest.replace("v0.3.0.9\",\"html", "nightly\",\"html")))
        assertEquals(null, parseLatestRelease(latest.replace("https://github.com/o/r/claydot-0.3.0.9.apk\"", "http://evil/x.apk\"")))
        assertEquals(null, parseLatestRelease(latest.replace(".apk.sha256\",\"browser_download_url\":\"https", ".apk.sha256\",\"browser_download_url\":\"http"))?.sha256Url)
        assertTrue(runCatching { parseLatestRelease("<html>rate limited</html>") }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun `a checksum file must hold a real sha256`() {
        val h = "d08c0f9b0d18b4a2a3222116c67c071545930e836a02085217f96abe975bb5da"
        assertEquals(h, sha256Of("$h  claydot-0.3.0.8.apk\n"))
        assertEquals(h, sha256Of(h.uppercase() + " *claydot.apk"))
        assertEquals(null, sha256Of("Not Found"))
        assertEquals(null, sha256Of(""))
        assertEquals(null, sha256Of(h.dropLast(1)))
    }
}
