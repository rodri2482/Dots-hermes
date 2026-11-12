package app.dotshermes.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun `made-up control tags such as skip never show`() {
        assertEquals("", app.dotshermes.data.direct.withoutStrayReactions("[[skip]]").trim())
        assertEquals("Opening it.", app.dotshermes.data.direct.withoutStrayReactions("Opening it. [[ None ]]").trim())
        assertEquals("[[a link]]", app.dotshermes.data.direct.withoutStrayReactions("[[a link]]"))
    }
    @Test fun `escaped punctuation such as dollar signs shows without the backslash`() {
        val colors = MdColors(ink = Color.White, link = Color.Blue, codeBackground = Color.Black)
        assertEquals("Bargain ~\$10 & 50% off", inlineMarkdown("Bargain ~\\\$10 \\& 50\\% off", colors).text)
        assertEquals("a\\b", inlineMarkdown("a\\b", colors).text)
    }
    @Test fun `expanded tables cap the widest columns to fit the screen`() {
        val fit = fitColumns(listOf(64f, 320f, 100f, 320f), 360f)
        assertEquals(64f, fit[0], 0.01f)
        assertEquals(360f, fit.sum(), 0.5f)
        assertEquals(fit[1], fit[3], 0.01f)
        assertEquals(listOf(64f, 120f), fitColumns(listOf(64f, 120f), 360f))
        // trop de colonnes : jamais sous 90 dp, le tableau défile
        assertTrue(fitColumns(List(8) { 300f }, 360f).all { it >= 89.9f })
        // jamais sous le mot le plus long : la colonne des notes garde 150 dp, la place se prend sur les autres
        val words = fitColumns(listOf(64f, 320f, 100f, 320f), 420f, mins = listOf(64f, 100f, 85f, 150f))
        assertTrue(words[3] >= 150f && words[1] >= 100f)
        assertEquals(420f, words.sum(), 0.5f)
        // même au minimum ça ne tient pas : les minimums, et le tableau défile
        assertEquals(listOf(64f, 100f, 85f, 150f), fitColumns(listOf(64f, 320f, 100f, 320f), 328f, mins = listOf(64f, 100f, 85f, 150f)))
    }
    private val colors = MdColors(Color.Black, Color.Blue, Color.LightGray)

    @Test
    fun `inline markers are removed and styled`() {
        val result = inlineMarkdown("Salut **Rémi**, lance `ls -la` *vite* et ~~pas ça~~", colors)
        assertEquals("Salut Rémi, lance ls -la vite et pas ça", result.text)
        val bold = result.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("Rémi", result.text.substring(bold.start, bold.end))
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic && result.text.substring(it.start, it.end) == "vite" })
        assertTrue(result.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `links and bare urls become clickable, snake_case stays intact`() {
        val result = inlineMarkdown("Voir [la doc](https://ex.com/a) ou https://hermes.dev/x. Variable mon_nom_de_var.", colors)
        assertEquals("Voir la doc ou https://hermes.dev/x. Variable mon_nom_de_var.", result.text)
        val urls = result.getLinkAnnotations(0, result.length).map { (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("https://ex.com/a", "https://hermes.dev/x"), urls)
    }

    @Test
    fun `blocks are recognised`() {
        val blocks = parseMarkdown(
            """
            ## Plan
            Intro sur
            deux lignes.

            - un
              - sous-point
            1. premier
            - [x] fait
            - [ ] à faire

            > citation

            ```kotlin
            val x = 1
            ```

            | Nom | Âge |
            |-----|----:|
            | Rémi | 30 |

            ---
            """.trimIndent(),
        )
        assertEquals(MdBlock.Heading(2, "Plan"), blocks[0])
        assertEquals(MdBlock.Paragraph("Intro sur\ndeux lignes."), blocks[1])
        val list = blocks[2] as MdBlock.Bullets
        assertEquals(listOf(0, 1, 0, 0, 0), list.items.map { it.depth })
        assertEquals("1.", list.items[2].marker)
        assertEquals(listOf(null, null, null, true, false), list.items.map { it.checked })
        assertEquals(MdBlock.Quote("citation"), blocks[3])
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[4])
        assertEquals(MdBlock.Table(listOf("Nom", "Âge"), listOf(listOf("Rémi", "30"))), blocks[5])
        assertEquals(MdBlock.Rule, blocks[6])
    }

    @Test fun `tables are split from the text around them, not inside code`() {
        val text = "Intro\n\n| A | B |\n|---|---|\n| Neon Genesis Evangelion (1995) | Cruel Angel's Thesis |\n\nAfter\n```\n| x | y |\n|---|---|\n```"
        val parts = splitTables(text)
        assertEquals(listOf(false, true, false), parts.map { it.first })
        assertEquals("| A | B |\n|---|---|\n| Neon Genesis Evangelion (1995) | Cruel Angel's Thesis |", parts[1].second)
        assertTrue(parts[2].second.contains("| x | y |"))
        assertEquals(listOf(false), splitTables("No table | here").map { it.first })
    }
}
