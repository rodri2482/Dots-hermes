package app.dotshermes.data.gmail

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Gmail par IMAP avec un mot de passe d'application Google : marche pour tout compte Gmail avec la
 * validation en deux étapes, sans l'écran d'accord OAuth (qui exige la vérification de Google pour
 * la portée gmail.readonly). Lecture seule : EXAMINE, recherche Gmail (X-GM-RAW), FETCH en PEEK.
 * Les identifiants restent sur le téléphone, chiffrés ; ils ne vont qu'à imap.gmail.com.
 */
class GmailImap(private val host: String = "imap.gmail.com", private val port: Int = 993) {

    /** Vérifie l'adresse et le mot de passe (connexion puis déconnexion). */
    fun check(email: String, password: String) = session(email, password) { }

    /** Recherche avec la syntaxe Gmail ; les messages les plus récents d'abord. */
    fun search(email: String, password: String, query: String, max: Int): JSONArray = session(email, password) { s ->
        val q = query.ifBlank { "in:inbox" }
        val uids = s.command("UID SEARCH X-GM-RAW " + quote(q)).lines
            .firstOrNull { it.startsWith("* SEARCH") }?.removePrefix("* SEARCH")?.trim()?.split(' ')
            ?.mapNotNull { it.toLongOrNull() }.orEmpty().sortedDescending().take(max.coerceIn(1, 10))
        val rows = JSONArray()
        for (uid in uids) {
            val r = s.command("UID FETCH $uid (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE)] BODY.PEEK[TEXT]<0.1500>)")
            val header = r.literals.getOrNull(0)?.let { String(it, Charsets.UTF_8) }.orEmpty()
            val text = r.literals.getOrNull(1)?.let { String(it, Charsets.UTF_8) }.orEmpty()
            val h = headerMap(header)
            rows.put(JSONObject().put("id", "imap-$uid").put("from", h["from"].orEmpty()).put("subject", h["subject"].orEmpty())
                .put("date", h["date"].orEmpty()).put("snippet", snippetOf(text)))
        }
        rows
    }

    /** Un message entier : en-têtes et texte (le HTML est réduit à son texte). */
    fun read(email: String, password: String, id: String): JSONObject = session(email, password) { s ->
        val uid = id.removePrefix("imap-").toLongOrNull() ?: throw GmailException(GmailException.Reason.NotFound)
        val raw = s.command("UID FETCH $uid (BODY.PEEK[]<0.400000>)").literals.firstOrNull() ?: throw GmailException(GmailException.Reason.NotFound)
        val message = Mime.parse(raw)
        val body = (message.text("text/plain") ?: message.text("text/html")?.let(GmailAccess::htmlToText) ?: "")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
        JSONObject().put("id", id).put("from", message.header("from")).put("subject", message.header("subject"))
            .put("date", message.header("date")).put("body", body.take(MAX_BODY)).put("truncated", body.length > MAX_BODY)
    }

    private fun <T> session(email: String, password: String, block: (Session) -> T): T {
        val socket = try {
            (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).apply { soTimeout = 30_000; startHandshake() }
        } catch (_: IOException) { throw GmailException(GmailException.Reason.Network) }
        socket.use {
            val s = Session(BufferedInputStream(it.getInputStream()), it.getOutputStream())
            s.readGreeting()
            val login = s.command("LOGIN " + quote(email.trim()) + " " + quote(password.replace(" ", "")), check = false)
            if (!login.ok) throw GmailException(GmailException.Reason.NeedsConsent)
            // Le dossier « Tous les messages » a un nom traduit : on le trouve par son attribut \All.
            val all = s.command("LIST \"\" \"*\"").lines.firstOrNull { it.contains("\\All") }?.let(::mailboxName) ?: "INBOX"
            s.command("EXAMINE " + quote(all))
            val result = block(s)
            runCatching { s.command("LOGOUT", check = false) }
            return result
        }
    }

    private class Reply(val ok: Boolean, val lines: List<String>, val literals: List<ByteArray>)

    private class Session(private val input: BufferedInputStream, private val output: OutputStream) {
        private var tag = 0

        fun readGreeting() { readLine() }

        fun command(text: String, check: Boolean = true): Reply {
            val t = "c" + (++tag)
            output.write("$t $text\r\n".toByteArray(Charsets.UTF_8)); output.flush()
            val lines = ArrayList<String>(); val literals = ArrayList<ByteArray>()
            while (true) {
                var line = readLine()
                // Littéral IMAP « {123} » : 123 octets bruts suivent la ligne.
                while (true) {
                    val m = Regex("\\{(\\d+)}$").find(line) ?: break
                    val n = m.groupValues[1].toInt()
                    val bytes = ByteArray(n); var read = 0
                    while (read < n) { val k = input.read(bytes, read, n - read); if (k < 0) throw GmailException(GmailException.Reason.Network); read += k }
                    literals += bytes
                    line = line + " <literal> " + readLine()
                }
                if (line.startsWith("$t ")) {
                    val ok = line.startsWith("$t OK")
                    if (check && !ok) throw GmailException(GmailException.Reason.Network)
                    return Reply(ok, lines, literals)
                }
                lines += line
            }
        }

        private fun readLine(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw GmailException(GmailException.Reason.Network)
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString("UTF-8")
        }
    }

    companion object {
        private const val MAX_BODY = 6_000

        internal fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        internal fun mailboxName(listLine: String): String? =
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$").find(listLine)?.groupValues?.get(1)?.replace("\\\"", "\"")

        internal fun headerMap(block: String): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            // Les en-têtes repliés continuent sur la ligne suivante (espace en tête).
            block.replace(Regex("\\r?\\n[ \\t]+"), " ").lines().forEach { line ->
                val i = line.indexOf(':')
                if (i > 0) map.putIfAbsent(line.substring(0, i).trim().lowercase(), Mime.decodeWords(line.substring(i + 1).trim()))
            }
            return map
        }

        internal fun snippetOf(text: String): String =
            text.replace(Regex("<[^>]+>"), " ").replace(Regex("=\\r?\\n"), "").replace(Regex("\\s+"), " ").trim().take(200)
    }
}

/** Lecture MIME minimale : parties (multipart imbriqué), quoted-printable, base64, jeux de caractères. */
internal class Mime private constructor(private val headers: Map<String, String>, private val body: ByteArray, private val parts: List<Mime>) {
    fun header(name: String) = headers[name.lowercase()].orEmpty()

    private val type get() = header("content-type").substringBefore(';').trim().lowercase().ifEmpty { "text/plain" }

    /** Le texte de la première partie du type voulu, décodé. */
    fun text(mime: String): String? {
        if (parts.isNotEmpty()) return parts.firstNotNullOfOrNull { it.text(mime) }
        if (type != mime || header("content-disposition").startsWith("attachment", ignoreCase = true)) return null
        val bytes = when (header("content-transfer-encoding").lowercase()) {
            "base64" -> runCatching { java.util.Base64.getMimeDecoder().decode(body) }.getOrDefault(body)
            "quoted-printable" -> quotedPrintable(body)
            else -> body
        }
        val charset = Regex("charset=\"?([^\";\\s]+)", RegexOption.IGNORE_CASE).find(header("content-type"))?.groupValues?.get(1)
        return String(bytes, runCatching { Charset.forName(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }

    companion object {
        fun parse(raw: ByteArray): Mime {
            val text = String(raw, Charsets.ISO_8859_1) // un octet = un caractère : les positions restent justes
            val split = Regex("\\r?\\n\\r?\\n").find(text)
            val headerText = if (split != null) text.substring(0, split.range.first) else text
            val bodyStart = split?.range?.last?.plus(1) ?: text.length
            val headers = GmailImap.headerMap(String(headerText.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
            val bodyBytes = raw.copyOfRange(bodyStart.coerceAtMost(raw.size), raw.size)
            val type = headers["content-type"].orEmpty()
            val boundary = Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
            if (type.startsWith("multipart/", ignoreCase = true) && boundary != null) {
                val bodyText = String(bodyBytes, Charsets.ISO_8859_1)
                val parts = bodyText.split("--$boundary").drop(1).filterNot { it.startsWith("--") }.map { chunk ->
                    parse(chunk.removePrefix("\r\n").removePrefix("\n").toByteArray(Charsets.ISO_8859_1))
                }
                return Mime(headers, ByteArray(0), parts)
            }
            return Mime(headers, bodyBytes, emptyList())
        }

        fun quotedPrintable(bytes: ByteArray): ByteArray {
            val s = String(bytes, Charsets.ISO_8859_1).replace(Regex("=\\r?\\n"), "")
            val out = ByteArrayOutputStream(); var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '=' && i + 3 <= s.length && s.substring(i + 1, i + 3).matches(Regex("[0-9A-Fa-f]{2}"))) {
                    out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
                } else { out.write(c.code and 0xFF); i++ }
            }
            return out.toByteArray()
        }

        /** En-têtes encodés (RFC 2047) : « =?UTF-8?B?...?= » ou « =?UTF-8?Q?...?= ». */
        fun decodeWords(value: String): String = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=(\\s+(?==\\?))?").replace(value) { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
            val data = m.groupValues[3]
            val bytes = if (m.groupValues[2].equals("B", true)) runCatching { java.util.Base64.getDecoder().decode(data) }.getOrDefault(data.toByteArray())
                else quotedPrintable(data.replace('_', ' ').toByteArray(Charsets.ISO_8859_1))
            String(bytes, charset)
        }
    }
}
