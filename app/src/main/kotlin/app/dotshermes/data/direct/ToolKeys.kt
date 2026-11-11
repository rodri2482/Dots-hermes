package app.dotshermes.data.direct

/**
 * Libellés d'outils sans emoji : la couche données publie une clé (« tool:music », « tool:wait:12 »),
 * l'interface l'affiche avec une icône et un texte traduit (voir ui/components/ToolStatus.kt).
 * Le titre d'une demande d'accord porte aussi sa clé : « tool:alarm » + séparateur + texte.
 */
object ToolKeys {
    const val PREFIX = "tool:"
    private const val SEP = '\u001F'

    fun of(kind: String, argument: Any? = null) = PREFIX + kind + (argument?.let { ":$it" } ?: "")
    fun isKey(label: String) = label.startsWith(PREFIX)
    fun kind(label: String) = if (isKey(label)) label.removePrefix(PREFIX).substringBefore(':') else ""
    fun argument(label: String) = label.removePrefix(PREFIX).substringAfter(':', "")

    /** Titre de la fenêtre d'accord, avec son icône. */
    fun title(kind: String, text: String) = of(kind) + SEP + text

    /** (clé de l'icône ou null, texte) */
    fun splitTitle(title: String): Pair<String?, String> =
        if (isKey(title) && SEP in title) title.substringBefore(SEP) to title.substringAfter(SEP) else null to title
}
