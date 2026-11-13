package app.dotshermes.data.direct

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Limites partagées par la séance entière, continuations d'outils comprises. */
enum class TeamEffort(val workers: Int, val calls: Int, val timeoutMs: Long, val checks: Int) {
    Quick(2, 12, 90_000, 1), Deep(4, 32, 240_000, 2)
}
enum class TeamStage { Planning, Research, Discussion, Checking, Correction, Done, Stopped }
enum class TeamVerification { Verified, Partial, NeedsReview }
enum class TeamLimit { Calls, Time, Rounds }
data class TeamSource(val title: String, val url: String, val read: Boolean)
data class TeamProgress(
    val stage: TeamStage = TeamStage.Planning,
    val effort: TeamEffort = TeamEffort.Deep,
    val members: List<String> = emptyList(),
    val calls: Int = 0,
    val limit: Int = effort.calls,
    val verification: TeamVerification? = null,
    val sources: List<TeamSource> = emptyList(),
    val checker: String = "",
    val limited: TeamLimit? = null,
) {
    val running get() = stage != TeamStage.Done && stage != TeamStage.Stopped
}

class TeamBudget(private val effort: TeamEffort) {
    @Volatile var used = 0
        private set
    @Synchronized fun take(checking: Boolean): Boolean {
        // Même après plusieurs chercheurs en parallèle, le contrôleur peut encore travailler.
        if (used >= effort.calls - if (checking) 0 else 4) return false
        used++
        return true
    }
}

fun teamVerification(verdict: String, readEvidence: Boolean, complete: Boolean): TeamVerification {
    // Une constatation : une ligne « - FIX <nom>: … » (le format demandé au contrôleur), ou « FIX <nom> » en majuscules
    // au fil du texte ; « nothing to fix » dans une approbation n'en est pas une.
    val approved = Regex("^APPROVED\\b", RegexOption.IGNORE_CASE).containsMatchIn(verdict.trimStart()) &&
        !Regex("(?im)^\\s*(?:(?:[-*•]|\\d+[.)])\\s*)?FIX\\b").containsMatchIn(verdict) &&
        !Regex("\\bFIX\\s+\\S").containsMatchIn(verdict)
    return when {
        !approved -> TeamVerification.NeedsReview
        !readEvidence || !complete -> TeamVerification.Partial
        else -> TeamVerification.Verified
    }
}

/** Seuls les liens structurés effectivement renvoyés par l'outil sont conservés. */
fun teamSources(tool: String, raw: String): List<TeamSource> {
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    if (json.has("error")) return emptyList()
    fun source(row: JSONObject, read: Boolean): TeamSource? {
        val url = safeTeamUrl(row.optString("url")) ?: return null
        return TeamSource(row.optString("title").ifBlank { URI(url).host }.take(120), url, read)
    }
    return when (tool) {
        "read_webpage" -> if (json.optString("text").isNotBlank()) listOfNotNull(source(json, true)) else emptyList()
        "wikipedia" -> if (json.optString("summary").isNotBlank()) listOfNotNull(source(json, true)) else emptyList()
        "search_web" -> (json.optJSONArray("results") ?: json.optJSONArray("articles"))?.let { rows ->
            (0 until minOf(rows.length(), 10)).mapNotNull { rows.optJSONObject(it)?.let { row -> source(row, false) } }
        }.orEmpty()
        else -> emptyList()
    }.distinctBy { it.url }
}

internal fun safeTeamUrl(value: String): String? = runCatching {
    val uri = URI(value.trim())
    value.trim().takeIf { it.length <= 2048 && uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
}.getOrNull()

internal fun teamProgressJson(report: TeamProgress): JSONObject = JSONObject()
    .put("stage", report.stage.name).put("effort", report.effort.name)
    .put("members", JSONArray(report.members)).put("calls", report.calls).put("limit", report.limit)
    .put("verification", report.verification?.name).put("checker", report.checker.take(1500))
    .put("limited", report.limited?.name).put("sources", JSONArray(report.sources.take(24).map {
        JSONObject().put("title", it.title).put("url", it.url).put("read", it.read)
    }))

internal fun teamProgressOf(json: JSONObject): TeamProgress? = runCatching {
    val effort = TeamEffort.valueOf(json.getString("effort"))
    val limit = json.optInt("limit", effort.calls).coerceIn(1, 32)
    TeamProgress(stage = TeamStage.valueOf(json.getString("stage")), effort = effort,
        members = json.optJSONArray("members")?.let { a -> (0 until minOf(a.length(), 4)).map { a.optString(it).take(40) } }.orEmpty(),
        calls = json.optInt("calls").coerceIn(0, limit), limit = limit,
        verification = json.optString("verification").takeIf { it.isNotBlank() }?.let(TeamVerification::valueOf),
        checker = json.optString("checker").take(1500),
        limited = json.optString("limited").takeIf { it.isNotBlank() }?.let(TeamLimit::valueOf),
        sources = json.optJSONArray("sources")?.let { a -> (0 until minOf(a.length(), 24)).mapNotNull { i ->
            a.optJSONObject(i)?.let { row -> safeTeamUrl(row.optString("url"))?.let {
                TeamSource(row.optString("title").take(120), it, row.optBoolean("read"))
            } }
        } }.orEmpty())
}.getOrNull()
