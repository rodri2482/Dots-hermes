package app.dotshermes.data.direct

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Écrire à quelqu'un pour l'utilisateur : chercher un contact, envoyer un SMS (en arrière-plan) ou un
 * message WhatsApp (la conversation s'ouvre, Claydot touche « Envoyer » si l'écran lui est accessible,
 * puis revient). Chaque message est montré mot pour mot dans la fenêtre d'accord avant de partir.
 */
class MessagingTools(
    private val context: Context,
    private val enabled: () -> Boolean,
    private val confirm: suspend (title: String, detail: String) -> Boolean,
    private val screen: () -> ScreenDriver?,
) : DirectTools {
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun definitions(): JSONArray? = if (!enabled()) null else JSONArray()
        .put(fn("find_contact", "Look up a contact's phone numbers by name in the user's contacts.",
            JSONObject().put("name", str("Name or part of it")), "name"))
        .put(fn("send_sms", "Send a text message (SMS) in the background. The user sees the exact text and approves it first.",
            JSONObject().put("to", str("Contact name or phone number")).put("text", str("The message")), "to", "text"))
        .put(fn("send_whatsapp", "Send a WhatsApp message. The user approves the exact text first; WhatsApp opens briefly to send it.",
            JSONObject().put("to", str("Contact name or phone number with country code")).put("text", str("The message")), "to", "text"))

    override fun label(call: ApiToolCall) = "💬…"

    override suspend fun run(call: ApiToolCall): String {
        val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: JSONException) { JSONObject() }
        return when (call.name) {
            "find_contact" -> {
                if (!granted(Manifest.permission.READ_CONTACTS)) return needs("contacts")
                val found = withContext(Dispatchers.IO) { contacts(args.optString("name").trim()) }
                JSONObject().put("contacts", JSONArray().apply { found.forEach { (n, p) -> put(JSONObject().put("name", n).put("phone", p)) } }).toString()
            }
            "send_sms" -> {
                if (!granted(Manifest.permission.SEND_SMS)) return needs("sms")
                val (name, number) = resolve(args.optString("to")) ?: return err("no contact or number found for '${args.optString("to")}'")
                val text = args.optString("text").trim().take(1_000).ifEmpty { return err("text is required") }
                if (!confirm("💬 SMS → $name", text)) return declined()
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    val sms = (if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault())
                        ?: return@withContext err("no SMS on this phone")
                    sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
                    JSONObject().put("status", "sent").put("to", name).toString()
                }
            }
            "send_whatsapp" -> {
                val (name, number) = resolve(args.optString("to")) ?: return err("no contact or number found for '${args.optString("to")}'")
                val text = args.optString("text").trim().take(2_000).ifEmpty { return err("text is required") }
                val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
                    ?: return err("WhatsApp is not installed")
                if (!confirm("🟢 WhatsApp → $name", text)) return declined()
                sendWhatsApp(pkg, name, number.filter { it.isDigit() }, text)
            }
            else -> err("unknown tool")
        }
    }

    private suspend fun sendWhatsApp(pkg: String, name: String, digits: String, text: String): String = withContext(Dispatchers.Main) {
        val link = "https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}".toUri()
        try { context.startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { return@withContext err("WhatsApp could not open that chat") }
        val driver = screen()?.takeIf { it.connected() }
            ?: return@withContext JSONObject().put("status", "ready").put("detail",
                "WhatsApp is open with the message typed for $name; the user taps Send (turn on 'Let Claydot use your screen' to send it automatically).").toString()
        // La conversation s'ouvre : on cherche le bouton « Envoyer » et on le touche, puis on revient dans Claydot.
        repeat(8) {
            delay(700)
            val shot = driver.snapshot()?.takeIf { it.packageName == pkg } ?: return@repeat
            val send = shot.elements.firstOrNull { e -> e.role == "button" && SEND_LABELS.any { e.text.equals(it, ignoreCase = true) } }
                ?: return@repeat
            if (driver.tap(send.id)) {
                delay(900)
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { back ->
                    runCatching { context.startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                }
                return@withContext JSONObject().put("status", "sent").put("to", name).toString()
            }
        }
        JSONObject().put("status", "ready").put("detail", "WhatsApp is open with the message typed; Send was not found, the user taps it.").toString()
    }

    /** Un nom de contact ou un numéro → (nom affiché, numéro). */
    private suspend fun resolve(to: String): Pair<String, String>? {
        val t = to.trim()
        if (t.isEmpty()) return null
        if (t.count { it.isDigit() } >= 6 && t.all { it.isDigit() || it in "+ -()" }) return t to t
        if (!granted(Manifest.permission.READ_CONTACTS)) return null
        return withContext(Dispatchers.IO) { contacts(t).firstOrNull() }
    }

    private fun contacts(name: String): List<Pair<String, String>> {
        if (name.isEmpty()) return emptyList()
        val rows = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { c -> while (c.moveToNext() && rows.size < 10) rows += c.getString(0).orEmpty() to c.getString(1).orEmpty() }
        // Le nom exact d'abord, puis ceux qui commencent par le nom cherché.
        return rows.distinctBy { it.second.filter(Char::isDigit) }
            .sortedBy { (n, _) -> when { n.equals(name, true) -> 0; n.startsWith(name, true) -> 1; else -> 2 } }
    }

    private fun needs(what: String) = err("permission missing: ask the user to tap 'Allow contacts and texting' in Claydot settings > Phone ($what)")
    private fun declined() = JSONObject().put("status", "cancelled").put("detail", "The user declined. Do not retry unless they ask again.").toString()
    private fun err(message: String) = JSONObject().put("error", message).toString()
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun fn(name: String, description: String, properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))))

    companion object {
        private val SEND_LABELS = listOf("Send", "Envoyer", "Enviar", "Senden", "Invia")
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
    }
}
