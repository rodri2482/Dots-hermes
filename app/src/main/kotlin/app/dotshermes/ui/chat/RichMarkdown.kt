package app.dotshermes.ui.chat

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Réponses de l'agent avec la bibliothèque multiplatform-markdown-renderer : titres, listes,
 * citations, tableaux, et blocs de code colorés selon le langage. Couleurs et tailles suivent la bulle.
 */
@Composable
fun RichMarkdown(text: String, ink: Color, link: Color, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val body = type.bodyLarge.copy(color = ink)
    val code = type.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = ink)
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(handleColor = link, backgroundColor = ink.copy(alpha = 0.28f))) {
        Markdown(
            content = text,
            modifier = modifier,
            colors = markdownColor(
                text = ink,
                codeText = ink,
                inlineCodeText = ink,
                linkText = link,
                codeBackground = ink.copy(alpha = 0.07f),
                inlineCodeBackground = ink.copy(alpha = 0.09f),
                dividerColor = ink.copy(alpha = 0.15f),
            ),
            typography = markdownTypography(
                h1 = type.titleMedium.copy(color = ink),
                h2 = type.titleMedium.copy(color = ink),
                h3 = type.titleSmall.copy(color = ink),
                h4 = type.titleSmall.copy(color = ink),
                h5 = type.titleSmall.copy(color = ink),
                h6 = type.titleSmall.copy(color = ink),
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                quote = body.copy(color = ink.copy(alpha = 0.75f)),
                code = code,
                inlineCode = code,
            ),
            components = markdownComponents(codeBlock = highlightedCodeBlock, codeFence = highlightedCodeFence),
        )
    }
}
