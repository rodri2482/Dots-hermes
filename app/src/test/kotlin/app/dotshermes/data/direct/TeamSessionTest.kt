package app.dotshermes.data.direct

import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.SubDot
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TeamSessionTest {
    private fun dot(name: String) = SubDot(name, name, DotKind.Temporary, "Research", "Nuage", "sky", "WingedCap", Instant.EPOCH, null)

    @Test fun `a missing checker never approves completed worker output`() = runBlocking {
        lateinit var session: TeamSession
        session = TeamSession(TeamEffort.Quick, { true }, {}, { _, _ -> emptyMap() },
            work = { d, _, checking, _ ->
                if (checking) null else { session.claim(false); "Findings from ${d.name}" }
            })
        val result = JSONObject(session.run("Task", listOf(dot("A")), dot("Checker")))
        assertEquals(TeamVerification.NeedsReview, session.progress.verification)
        assertEquals(1, result.getJSONArray("work").length())
    }

    @Test fun `expired work keeps completed findings and cannot claim approval`() = runBlocking {
        val partial = CompletableDeferred<Unit>()
        lateinit var session: TeamSession
        session = TeamSession(TeamEffort.Quick, { true }, {}, { _, _ -> emptyMap() },
            work = { d, _, _, _ ->
                session.claim(false)
                if (d.name == "fast") { partial.complete(Unit); "Useful partial finding" }
                else { partial.await(); delay(1000); "Too late" }
            }, timeoutMs = 80)
        val result = JSONObject(session.run("Task", listOf(dot("fast"), dot("slow")), dot("Checker")))
        assertEquals(TeamLimit.Time, session.progress.limited)
        assertEquals(TeamVerification.NeedsReview, session.progress.verification)
        assertEquals("Useful partial finding", result.getJSONArray("work").getJSONObject(0).getString("part"))
    }

    @Test fun `superseded sessions cannot publish sources or claim more calls`() {
        var valid = true
        val seen = mutableListOf<TeamProgress>()
        val session = TeamSession(TeamEffort.Quick, { valid }, { seen += it }, { _, _ -> emptyMap() }, { _, _, _, _ -> null })
        assertTrue(session.claim(false))
        valid = false
        session.sources(listOf(TeamSource("Late", "https://example.com", true)), true)
        assertThrows(CancellationException::class.java) { session.claim(true) }
        assertEquals(1, seen.size)
        assertTrue(session.progress.sources.isEmpty())
    }

    @Test fun `quick and deep limits stay finite and distinct`() {
        assertEquals(12, TeamEffort.Quick.calls)
        assertEquals(90_000L, TeamEffort.Quick.timeoutMs)
        assertEquals(32, TeamEffort.Deep.calls)
        assertEquals(240_000L, TeamEffort.Deep.timeoutMs)
    }

    @Test fun `only finding lines block an approval, not the word fix in prose`() {
        assertEquals(TeamVerification.Verified, teamVerification("APPROVED: complete and sourced, nothing to fix", true, true))
        assertEquals(TeamVerification.NeedsReview, teamVerification("APPROVED overall\n- FIX Researcher: no source for the price", true, true))
        assertEquals(TeamVerification.NeedsReview, teamVerification("Mostly fine.\nFIX Analyst: dates are wrong", true, true))
        assertEquals(TeamVerification.NeedsReview, teamVerification("APPROVED\n1. FIX Analyst: dates are wrong", true, true))
    }

    @Test fun `pages actually read are never pushed out of the source list by search results`() {
        val session = TeamSession(TeamEffort.Deep, valid = { true }, onProgress = {}, plan = { _, _ -> emptyMap() }, work = { _, _, _, _ -> null })
        repeat(3) { q -> session.sources((0 until 10).map { TeamSource("Result $q-$it", "https://search.example/$q/$it", false) }, false) }
        session.sources(listOf(TeamSource("Evidence", "https://example.com/evidence", true)), true)
        assertEquals(24, session.progress.sources.size)
        assertEquals("https://example.com/evidence", session.progress.sources.first().url)
    }
}
