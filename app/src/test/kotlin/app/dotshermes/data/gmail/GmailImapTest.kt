package app.dotshermes.data.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailImapTest {
    @Test fun `encoded headers are decoded`() {
        assertEquals("Café ☕ demain", Mime.decodeWords("=?UTF-8?B?Q2Fmw6kg4piVIGRlbWFpbg==?="))
        assertEquals("Réunion à 9h", Mime.decodeWords("=?ISO-8859-1?Q?R=E9union_=E0_9h?="))
        assertEquals("Plain subject", Mime.decodeWords("Plain subject"))
    }

    @Test fun `folded headers are joined`() {
        val h = GmailImap.headerMap("From: Ada <ada@example.com>\r\nSubject: A long\r\n subject line\r\nDate: Thu, 12 Nov 2026 08:00:00 +0100\r\n")
        assertEquals("Ada <ada@example.com>", h["from"])
        assertEquals("A long subject line", h["subject"])
    }

    @Test fun `multipart message gives its plain text, quoted-printable decoded`() {
        val raw = ("From: Bank <alerts@bank.example>\r\nSubject: =?UTF-8?Q?Re=C3=A7u?=\r\n" +
            "Content-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n" +
            "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n" +
            "Montant : 12 =E2=82=AC, merci.=\r\n Bonne journ=C3=A9e\r\n" +
            "--b1\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>ignored</p>\r\n--b1--\r\n").toByteArray(Charsets.UTF_8)
        val m = Mime.parse(raw)
        assertEquals("Reçu", m.header("subject"))
        val text = m.text("text/plain")!!
        assertTrue(text, text.contains("Montant : 12 €, merci. Bonne journée"))
    }

    @Test fun `base64 html part when there is no plain text`() {
        val html = java.util.Base64.getEncoder().encodeToString("<p>Hello <b>there</b></p>".toByteArray())
        val raw = "Content-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$html\r\n".toByteArray()
        assertEquals("<p>Hello <b>there</b></p>", Mime.parse(raw).text("text/html")!!.trim())
    }

    @Test fun `the All Mail folder is found whatever its language`() {
        assertEquals("[Gmail]/Tous les messages", GmailImap.mailboxName("""* LIST (\All \HasNoChildren) "/" "[Gmail]/Tous les messages""""))
        assertEquals("\"a\\\\b\"", GmailImap.quote("a\\b"))
    }
}
