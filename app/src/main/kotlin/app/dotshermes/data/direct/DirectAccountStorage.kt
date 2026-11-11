package app.dotshermes.data.direct

import org.json.JSONArray
import org.json.JSONObject

enum class DirectEntryState { Pending, Streaming, Complete, Failed, Stopped }

data class DirectEntry(
    val id: String, val role: String, val text: String, val timestampMs: Long,
    val state: DirectEntryState, val replyTo: String? = null,
    /** Réponse terminée : temps écoulé entre l'envoi et le dernier mot (absent des anciens fils). */
    val durationMs: Long? = null,
    /** Jetons annoncés par le fournisseur pour cette réponse (entrée + sortie) ; absent s'il n'en dit rien. */
    val tokens: Long? = null,
    /** Réaction emoji : celle de Claydot sur un message de l'utilisateur, celle de l'utilisateur sur une réponse. */
    val reaction: String? = null,
) {
    init {
        require(role == "user" || role == "assistant")
        require(id.isNotBlank() && id.length <= 160 && timestampMs >= 0)
    }
}

data class DirectTranscript(
    val messages: List<DirectEntry> = emptyList(),
    val retryUserId: String? = null,
    val failure: ApiFailure? = null,
    val limits: ProviderLimits? = null,
    val cooldownUntilMs: Long = 0,
    val quotaScope: String? = null,
)

interface DirectAccountStorage {
    fun key(accountId: String): String?
    fun saveKey(accountId: String, key: String)
    fun load(accountId: String): DirectTranscript
    fun save(accountId: String, transcript: DirectTranscript)
    fun removeKey(accountId: String)
    fun remove(accountId: String)
}

/** La clé et le fil sont deux enregistrements chiffrés distincts ; aucune préférence en clair. */
class EncryptedAccountStorage(private val records: EncryptedRecords) : DirectAccountStorage {
    override fun key(accountId: String): String? {
        val bytes = records.read(accountId, AccountRecord.Credentials) ?: return null
        return try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { throw ApiException(ApiFailure.Storage) }
        finally { bytes.fill(0) }
    }

    override fun saveKey(accountId: String, key: String) {
        val normalized = key.trim()
        if (normalized.isEmpty()) throw ApiException(ApiFailure.MissingKey)
        if (normalized.length > 8192 || normalized.any { it <= ' ' || it >= '\u007f' }) throw ApiException(ApiFailure.InvalidKey)
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        try { records.write(accountId, AccountRecord.Credentials, bytes) }
        finally { bytes.fill(0) }
    }

    override fun load(accountId: String): DirectTranscript {
        val bytes = records.read(accountId, AccountRecord.History) ?: return DirectTranscript()
        return try {
            val root = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            if (root.getInt("version") != 1) throw ApiException(ApiFailure.Storage)
            val array = root.getJSONArray("messages")
            if (array.length() > 2000) throw ApiException(ApiFailure.Storage)
            val entries = List(array.length()) { index ->
                val row = array.getJSONObject(index)
                DirectEntry(row.getString("id"), row.getString("role"), row.getString("text"),
                    row.getLong("timestamp_ms"), DirectEntryState.valueOf(row.getString("state")),
                    if (row.isNull("reply_to")) null else row.getString("reply_to"),
                    if (row.has("duration_ms") && !row.isNull("duration_ms")) row.getLong("duration_ms").coerceAtLeast(0) else null,
                    if (row.has("tokens") && !row.isNull("tokens")) row.getLong("tokens").coerceAtLeast(0) else null,
                    row.optString("reaction").takeIf { it in Reactions })
            }
            val retry = if (root.isNull("retry_user_id")) null else root.getString("retry_user_id")
            val failure = if (root.isNull("failure")) null else ApiFailure.valueOf(root.getString("failure"))
            val limits = root.optJSONObject("limits")?.let { row ->
                fun number(name: String): Long? = if (row.isNull(name)) null else row.getLong(name)
                ProviderLimits(row.getLong("observed"), number("request_limit"), number("requests_remaining"),
                    number("requests_reset"), number("token_limit"), number("tokens_remaining"), number("tokens_reset"))
            }
            DirectTranscript(entries, retry, failure, limits, root.optLong("cooldown_until_ms", 0),
                if (root.isNull("quota_scope")) null else root.getString("quota_scope")).also(::validate)
        } catch (e: ApiException) { throw e }
        catch (_: Exception) { throw ApiException(ApiFailure.Storage) }
        finally { bytes.fill(0) }
    }

    override fun save(accountId: String, transcript: DirectTranscript) {
        validate(transcript)
        val rows = JSONArray().apply {
            transcript.messages.forEach { row ->
                put(JSONObject().put("id", row.id).put("role", row.role).put("text", row.text)
                    .put("timestamp_ms", row.timestampMs).put("state", row.state.name)
                    .put("reply_to", row.replyTo ?: JSONObject.NULL)
                    .apply { row.durationMs?.let { put("duration_ms", it) }; row.tokens?.let { put("tokens", it) }; row.reaction?.let { put("reaction", it) } })
            }
        }
        val limits = transcript.limits?.let { row ->
            JSONObject().put("observed", row.observedAtMs)
                .put("request_limit", row.requestLimit ?: JSONObject.NULL)
                .put("requests_remaining", row.requestsRemaining ?: JSONObject.NULL)
                .put("requests_reset", row.requestsResetAtMs ?: JSONObject.NULL)
                .put("token_limit", row.tokenLimit ?: JSONObject.NULL)
                .put("tokens_remaining", row.tokensRemaining ?: JSONObject.NULL)
                .put("tokens_reset", row.tokensResetAtMs ?: JSONObject.NULL)
        }
        val bytes = JSONObject().put("version", 1).put("messages", rows)
            .put("retry_user_id", transcript.retryUserId ?: JSONObject.NULL)
            .put("failure", transcript.failure?.name ?: JSONObject.NULL)
            .put("limits", limits ?: JSONObject.NULL).put("cooldown_until_ms", transcript.cooldownUntilMs)
            .put("quota_scope", transcript.quotaScope ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        try { records.write(accountId, AccountRecord.History, bytes) }
        finally { bytes.fill(0) }
    }

    override fun removeKey(accountId: String) { records.removeRecord(accountId, AccountRecord.Credentials) }

    override fun remove(accountId: String) = records.remove(accountId)

    private fun validate(transcript: DirectTranscript) {
        if (transcript.quotaScope != null && !transcript.quotaScope.matches(Regex("[a-f0-9]{64}"))) throw ApiException(ApiFailure.Storage)
        if (transcript.cooldownUntilMs < 0 || transcript.limits?.let {
            listOfNotNull(it.observedAtMs, it.requestLimit, it.requestsRemaining, it.requestsResetAtMs,
                it.tokenLimit, it.tokensRemaining, it.tokensResetAtMs).any { value -> value < 0 }
        } == true) throw ApiException(ApiFailure.Storage)
        val entries = transcript.messages
        if (entries.size > 2000 || entries.map { it.id }.toSet().size != entries.size || entries.any { it.text.length > 1_000_000 }) {
            throw ApiException(ApiFailure.Storage)
        }
        if (transcript.retryUserId != null && entries.none { it.id == transcript.retryUserId && it.role == "user" }) {
            throw ApiException(ApiFailure.Storage)
        }
        val users = entries.filter { it.role == "user" }.mapTo(HashSet()) { it.id }
        if (entries.any { it.replyTo != null && it.replyTo !in users }) throw ApiException(ApiFailure.Storage)
    }
}
