package app.dotshermes.data.direct

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TeamReliabilityTest {
    @Test fun `parallel calls never exceed the shared budget and reserve checking calls`() {
        val budget = TeamBudget(TeamEffort.Quick)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..40).map { pool.submit<Boolean> { budget.take(checking = false) } }
            assertEquals(8, calls.count { it.get(2, TimeUnit.SECONDS) })
            assertTrue(budget.take(checking = true))
            assertTrue(budget.take(checking = true))
            assertTrue(budget.take(checking = true))
            assertTrue(budget.take(checking = true))
            assertFalse(budget.take(checking = true))
            assertEquals(12, budget.used)
        } finally { pool.shutdownNow() }
    }

    @Test fun `unresolved missing or partial checks never become verified`() {
        assertEquals(TeamVerification.NeedsReview, teamVerification("- FIX Researcher: unsupported", true, true))
        assertEquals(TeamVerification.NeedsReview, teamVerification("", true, true))
        assertEquals(TeamVerification.NeedsReview, teamVerification("APPROVED but - FIX Researcher: wrong", true, true))
        assertEquals(TeamVerification.Partial, teamVerification("APPROVED: complete", false, true))
        assertEquals(TeamVerification.Partial, teamVerification("APPROVED: complete", true, false))
        assertEquals(TeamVerification.Verified, teamVerification("APPROVED: complete", true, true))
    }

    @Test fun `only tool metadata supplies sources and search is not read evidence`() {
        val raw = """{"results":[{"title":"A","url":"https://example.com/a"},{"url":"javascript:alert(1)"},{"url":"https://name:password@example.com"}],"note":"https://invented.test"}"""
        val search = teamSources("search_web", raw)
        assertEquals(listOf("https://example.com/a"), search.map { it.url })
        assertFalse(search.single().read)
        assertTrue(teamSources("read_webpage", """{"url":"https://example.com/a","text":"Evidence"}""").single().read)
        assertTrue(teamSources("read_webpage", """{"url":"https://example.com/a","text":"","error":"failed"}""").isEmpty())
    }

    @Test fun `reports survive storage serialization with status and safe links`() {
        val report = TeamProgress(stage = TeamStage.Done, verification = TeamVerification.NeedsReview,
            calls = 12, limit = 12, sources = listOf(TeamSource("Evidence", "https://example.com", true)), checker = "- FIX: date")
        assertEquals(report, teamProgressOf(JSONObject(teamProgressJson(report).toString())))
    }

    @Test fun `diagnostics never call a configured model verified without a real check`() {
        assertEquals(DiagnosticState.Configured, modelDiagnostic(true, null))
        assertEquals(DiagnosticState.Missing, modelDiagnostic(false, null))
        assertEquals(DiagnosticState.NeedsAttention, modelDiagnostic(true, ApiFailure.InvalidKey))
        assertEquals(DiagnosticState.UnableToCheck, modelDiagnostic(true, ApiFailure.Network))
        assertEquals(DiagnosticState.NeedsAttention, modelDiagnostic(true, ApiFailure.ModelUnavailable))
    }

    @Test fun `Gmail diagnostics distinguish consent setup network and authorization without opening consent`() {
        assertEquals(GmailDiagnostic.NotConnected, gmailDiagnostic(false))
        assertEquals(GmailDiagnostic.Reconnect, gmailDiagnostic(true, resolution = true))
        assertEquals(GmailDiagnostic.Reconnect, gmailDiagnostic(true, error = 4))
        assertEquals(GmailDiagnostic.SetupMissing, gmailDiagnostic(true, error = 10))
        assertEquals(GmailDiagnostic.Unavailable, gmailDiagnostic(true, error = 7))
        assertEquals(GmailDiagnostic.Authorized, gmailDiagnostic(true, token = true))
        assertEquals(GmailDiagnostic.Reconnect, gmailDiagnostic(true))
    }
}
