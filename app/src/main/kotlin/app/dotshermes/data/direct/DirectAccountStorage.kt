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
    /** Photo jointe (message de l'utilisateur) : nom du fichier JPEG dans le dossier privé du compte. */
    val image: String? = null,
    /** Réponse donnée par un autre modèle que celui du compte (quota épuisé, modèle sans outils…). */
    val model: String? = null,
    /** Message auquel l'utilisateur répond (glisser vers la gauche) : son texte, court. */
    val quote: String? = null,
    /** Carte interactive (question, accord, accès) posée par Claydot pendant une tâche. */
    val card: app.dotshermes.data.model.ChatCard? = null,
    /** Salon de groupe : le sous-dot qui a dit ce message, et son nom à ce moment-là. */
    val speaker: String? = null,
    val speakerName: String? = null,
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
    /** Compte : ses conversations (seulement dans l'enregistrement principal du compte). */
    val conversations: List<ConversationMeta> = emptyList(),
    /** Conversation ouverte ; null = la conversation principale. */
    val activeConversation: String? = null,
    /** Sous-dots du compte (seulement dans l'enregistrement principal) ; chacun a sa conversation. */
    val dots: List<app.dotshermes.data.model.SubDot> = emptyList(),
)

/** Une conversation d'un compte direct : son dossier chiffré, son titre (premier message), sa date. */
data class ConversationMeta(val id: String, val title: String, val updatedMs: Long)

interface DirectAccountStorage {
    fun key(accountId: String): String?
    fun saveKey(accountId: String, key: String)
    fun load(accountId: String): DirectTranscript
    fun save(accountId: String, transcript: DirectTranscript)
    fun removeKey(accountId: String)
    fun remove(accountId: String)
    /** Mémoire à long terme du compte : de courts faits sur l'utilisateur (chiffrés comme le reste). */
    fun facts(accountId: String): List<String> = emptyList()
    fun saveFacts(accountId: String, facts: List<String>) {}
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
                    row.optString("reaction").takeIf { it in Reactions },
                    row.optString("image").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}\\.jpg")) },
                    row.optString("model").takeIf { it.isNotBlank() && it.length <= 120 },
                    row.optString("quote").takeIf { it.isNotBlank() }?.take(200),
                    row.optJSONObject("card")?.let(::cardOf),
                    row.optString("speaker").takeIf { it.matches(DOT_ID) },
                    row.optString("speaker_name").takeIf { it.isNotBlank() }?.take(40))
            }
            val retry = if (root.isNull("retry_user_id")) null else root.getString("retry_user_id")
            val failure = if (root.isNull("failure")) null else ApiFailure.valueOf(root.getString("failure"))
            val limits = root.optJSONObject("limits")?.let { row ->
                fun number(name: String): Long? = if (row.isNull(name)) null else row.getLong(name)
                ProviderLimits(row.getLong("observed"), number("request_limit"), number("requests_remaining"),
                    number("requests_reset"), number("token_limit"), number("tokens_remaining"), number("tokens_reset"))
            }
            val chats = root.optJSONArray("conversations")?.let { rows ->
                List(rows.length()) { i -> rows.getJSONObject(i).let { ConversationMeta(it.getString("id"), it.optString("title"), it.optLong("updated")) } }
            }.orEmpty()
            DirectTranscript(entries, retry, failure, limits, root.optLong("cooldown_until_ms", 0),
                if (root.isNull("quota_scope")) null else root.getString("quota_scope"),
                chats, root.optString("active_conversation").takeIf { it.isNotEmpty() },
                root.optJSONArray("dots")?.let { rows -> (0 until minOf(rows.length(), 40)).mapNotNull { rows.optJSONObject(it)?.let(::dotOf) } }.orEmpty(),
            ).also(::validate)
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
                    .apply { row.durationMs?.let { put("duration_ms", it) }; row.tokens?.let { put("tokens", it) }; row.reaction?.let { put("reaction", it) }; row.image?.let { put("image", it) }; row.model?.let { put("model", it) }; row.quote?.let { put("quote", it) }; row.card?.let { put("card", cardJson(it)) }; row.speaker?.let { put("speaker", it) }; row.speakerName?.let { put("speaker_name", it) } })
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
            .apply {
                if (transcript.conversations.isNotEmpty()) put("conversations", JSONArray().apply {
                    transcript.conversations.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("updated", it.updatedMs)) }
                })
                transcript.activeConversation?.let { put("active_conversation", it) }
                if (transcript.dots.isNotEmpty()) put("dots", JSONArray().apply { transcript.dots.forEach { put(dotJson(it)) } })
            }
            .toString().toByteArray(Charsets.UTF_8)
        try { records.write(accountId, AccountRecord.History, bytes) }
        finally { bytes.fill(0) }
    }

    override fun removeKey(accountId: String) { records.removeRecord(accountId, AccountRecord.Credentials) }

    override fun facts(accountId: String): List<String> = try {
        val bytes = records.read(accountId, AccountRecord.Memory) ?: return emptyList()
        val rows = org.json.JSONArray(bytes.toString(Charsets.UTF_8))
        List(rows.length()) { rows.getString(it) }
    } catch (_: Exception) { emptyList() }

    override fun saveFacts(accountId: String, facts: List<String>) {
        records.write(accountId, AccountRecord.Memory, org.json.JSONArray(facts).toString().toByteArray(Charsets.UTF_8))
    }

    override fun remove(accountId: String) = records.remove(accountId)

    private fun validate(transcript: DirectTranscript) {
        if (transcript.quotaScope != null && !transcript.quotaScope.matches(Regex("[a-f0-9]{64}"))) throw ApiException(ApiFailure.Storage)
        if (transcript.cooldownUntilMs < 0 || transcript.limits?.let {
            listOfNotNull(it.observedAtMs, it.requestLimit, it.requestsRemaining, it.requestsResetAtMs,
                it.tokenLimit, it.tokensRemaining, it.tokensResetAtMs).any { value -> value < 0 }
        } == true) throw ApiException(ApiFailure.Storage)
        if (transcript.conversations.size > 500 || transcript.conversations.any { !it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || it.title.length > 200 }) {
            throw ApiException(ApiFailure.Storage)
        }
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

/** Une carte enregistrée ; une carte encore ouverte au démarrage est expirée (rien ne se relance tout seul). */
internal fun cardOf(row: JSONObject): app.dotshermes.data.model.ChatCard? = try {
    val status = app.dotshermes.data.model.CardStatus.valueOf(row.getString("status"))
    val options = row.optJSONArray("options")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
    val id = row.getString("id")
    if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) null
    else app.dotshermes.data.model.ChatCard(
        id = id,
        kind = app.dotshermes.data.model.CardKind.valueOf(row.getString("kind")),
        question = row.getString("question"), detail = row.optString("detail"), options = options,
        multi = row.optBoolean("multi"), custom = row.optBoolean("custom", true),
        access = row.optString("access").takeIf { it.isNotBlank() },
        status = if (status == app.dotshermes.data.model.CardStatus.Pending || status == app.dotshermes.data.model.CardStatus.Waiting)
            app.dotshermes.data.model.CardStatus.Expired else status,
        answer = if (row.isNull("answer")) null else row.optString("answer"),
    ).bounded()
} catch (_: Exception) { null }

internal fun cardJson(card: app.dotshermes.data.model.ChatCard): JSONObject = JSONObject().put("id", card.id).put("kind", card.kind.name)
    .put("question", card.question).put("detail", card.detail).put("options", JSONArray(card.options))
    .put("multi", card.multi).put("custom", card.custom).put("access", card.access ?: "").put("status", card.status.name)
    .put("answer", card.answer ?: JSONObject.NULL)

/** Identifiant d'un sous-dot : court, sans caractère spécial (il entre dans le nom du dossier chiffré). */
internal val DOT_ID = Regex("[a-f0-9]{10}")

internal fun dotJson(dot: app.dotshermes.data.model.SubDot): JSONObject = JSONObject().put("id", dot.id).put("name", dot.name)
    .put("kind", dot.kind.wire).put("mission", dot.mission).put("species", dot.species).put("palette", dot.palette)
    .put("accessory", dot.accessory).put("created", dot.createdAt.toEpochMilli()).put("last", dot.lastActivityAt?.toEpochMilli() ?: JSONObject.NULL)

internal fun dotOf(row: JSONObject): app.dotshermes.data.model.SubDot? = try {
    val id = row.getString("id")
    if (!id.matches(DOT_ID)) null else app.dotshermes.data.model.SubDot(
        id = id, name = row.getString("name").take(40),
        kind = app.dotshermes.data.model.DotKind.entries.firstOrNull { it.wire == row.optString("kind") } ?: app.dotshermes.data.model.DotKind.Permanent,
        mission = row.optString("mission").take(600), species = row.optString("species").take(40), palette = row.optString("palette").take(40),
        accessory = row.optString("accessory").take(40), createdAt = java.time.Instant.ofEpochMilli(row.optLong("created")),
        lastActivityAt = if (row.isNull("last")) null else java.time.Instant.ofEpochMilli(row.optLong("last")),
    )
} catch (_: Exception) { null }
