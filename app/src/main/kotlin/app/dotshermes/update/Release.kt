package app.dotshermes.update

/** Une release GitHub de l'app : sa version et les liens de son APK (et de son empreinte SHA-256). */
data class Release(
    /** « 0.2.0.12 », sans le « v » du tag. */
    val version: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Fichier `<apk>.sha256` publié à côté par la CI ; null pour une release sans empreinte. */
    val sha256Url: String?,
    val pageUrl: String,
)

/**
 * La release « latest » de l'API GitHub : le premier APK et son fichier `<apk>.sha256`. null sans APK ou sans tag
 * de version lisible ; une réponse illisible lève [java.io.IOException] (comme une panne réseau).
 */
internal fun parseLatestRelease(body: String): Release? {
    val json = try { org.json.JSONObject(body) } catch (e: org.json.JSONException) { throw java.io.IOException("Unreadable release", e) }
    val assets = json.optJSONArray("assets") ?: return null
    val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
    val apk = all.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
    val apkName = apk.optString("name")
    val version = json.optString("tag_name").removePrefix("v").takeIf { versionParts(it) != null } ?: return null
    return Release(
        version = version,
        apkUrl = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null,
        apkSize = apk.optLong("size"),
        sha256Url = all.firstOrNull { it.optString("name") == "$apkName.sha256" }?.optString("browser_download_url")
            ?.takeIf { it.startsWith("https://") },
        pageUrl = json.optString("html_url"),
    )
}

/** Fichier `sha256sum` (« <empreinte>  <nom> ») → empreinte en minuscules ; null si ce n'est pas un SHA-256. */
internal fun sha256Of(file: String): String? =
    file.trim().substringBefore(' ').substringBefore('\t').lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }

/** « v0.2.0.12 » → [0, 2, 0, 12] ; null si ce n'est pas un numéro de version. */
internal fun versionParts(version: String): List<Int>? =
    version.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

/**
 * [remote] est-elle plus récente que [local] ? Comparaison chiffre par chiffre, un chiffre manquant
 * valant 0 (« 0.2.0 » < « 0.2.0.3 »). Une version illisible n'est jamais proposée.
 */
internal fun isNewer(remote: String, local: String): Boolean {
    val r = versionParts(remote) ?: return false
    val l = versionParts(local) ?: return true
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
