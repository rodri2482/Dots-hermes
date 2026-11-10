package app.dotshermes.data.direct

/**
 * Choix du modèle de conversation dans la liste renvoyée par le fournisseur. La liste de Groq mêle
 * transcription (Whisper), synthèse vocale et modèles de modération : ils ne savent pas discuter.
 */
private val NotForChat = listOf("whisper", "tts", "guard", "playai", "orpheus", "embed", "moderation", "transcribe")

/** Modèles Groq essayés dans l'ordre ; seul un identifiant présent dans la liste du compte est retenu. */
private val GroqPreferred = listOf(
    "openai/gpt-oss-120b",
    "llama-3.3-70b-versatile",
    "moonshotai/kimi-k2-instruct",
    "openai/gpt-oss-20b",
    "llama-3.1-8b-instant",
)

fun chatModels(provider: ApiProvider, ids: List<String>): List<String> =
    if (provider != ApiProvider.Groq) ids
    else ids.filter { id -> NotForChat.none { id.contains(it, ignoreCase = true) } }

/** Modèle choisi d'office quand le champ reste vide (Groq seulement) ; null si rien de convenable. */
fun defaultModel(provider: ApiProvider, ids: List<String>): String? {
    if (provider != ApiProvider.Groq) return null
    val usable = chatModels(provider, ids)
    return GroqPreferred.firstOrNull { it in usable } ?: usable.sortedBy { it.lowercase() }.firstOrNull()
}
