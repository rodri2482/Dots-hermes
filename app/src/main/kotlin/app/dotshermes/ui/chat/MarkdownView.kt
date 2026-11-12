package app.dotshermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import app.dotshermes.R

/** Rendu Compose des blocs de [parseMarkdown] ; [ink] et [link] suivent la couleur de la bulle. */
@Composable
fun MarkdownText(text: String, ink: Color, link: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = MdColors(ink, link, ink.copy(alpha = 0.09f))
    val body = MaterialTheme.typography.bodyLarge.copy(color = ink)
    // Les couleurs de sélection suivent le thème pour rester lisibles en thème sombre.
    // (Le SelectionContainer lui-même est posé par l'appelant, autour de la bulle.)
    val selectionColors = TextSelectionColors(
        handleColor = link,
        backgroundColor = ink.copy(alpha = 0.28f),
    )
    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block -> Block(block, colors, body) }
        }
    }
}

@Composable
private fun Block(block: MdBlock, colors: MdColors, body: TextStyle) {
    when (block) {
        is MdBlock.Paragraph -> Text(rememberInline(block.text, colors), style = body)
        is MdBlock.Heading -> Text(
            rememberInline(block.text, colors),
            style = body.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = when (block.level) { 1 -> 21.sp; 2 -> 19.sp; 3 -> 17.sp; else -> body.fontSize },
            ),
            modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
        )
        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Row(Modifier.padding(start = (item.depth * 16).dp)) {
                    when (item.checked) {
                        null -> Text(
                            if (item.marker == "•" && item.depth > 0) "◦" else item.marker,
                            style = body, modifier = Modifier.width(if (item.marker.length > 2) 30.dp else 20.dp),
                        )
                        else -> Icon(
                            if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                            tint = colors.ink.copy(alpha = 0.75f), modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp),
                        )
                    }
                    Text(
                        rememberInline(item.text, colors),
                        style = if (item.checked == true) body.copy(color = colors.ink.copy(alpha = 0.55f)) else body,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(colors.ink.copy(alpha = 0.3f)))
            Spacer(Modifier.width(10.dp))
            Text(rememberInline(block.text, colors), style = body.copy(fontStyle = FontStyle.Italic, color = colors.ink.copy(alpha = 0.78f)))
        }
        is MdBlock.Code -> CodeBlock(block, colors)
        is MdBlock.Table -> TableBlock(block, colors, body)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(colors.ink.copy(alpha = 0.15f)))
    }
}

@Composable
private fun rememberInline(text: String, colors: MdColors): AnnotatedString = remember(text, colors) { inlineMarkdown(text, colors) }

/** Bloc de code : défile horizontalement (pas de retour à la ligne qui casserait l'indentation), bouton copier. */
@Composable
private fun CodeBlock(block: MdBlock.Code, colors: MdColors) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(1_500); copied = false }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.ink.copy(alpha = 0.07f))) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.language.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = colors.ink.copy(alpha = 0.55f), modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { clipboard.setText(AnnotatedString(block.code)); copied = true }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, stringResource(R.string.code_copy), tint = colors.ink.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (copied) R.string.code_copied else R.string.code_copy), style = MaterialTheme.typography.labelSmall, color = colors.ink.copy(alpha = 0.6f))
            }
        }
        Text(
            block.code,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = colors.ink),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 2.dp),
        )
    }
}

/** Un tableau markdown seul (texte « | a | b | » + séparateur), dessiné par [TableBlock]. */
@Composable
fun MarkdownTable(text: String, ink: Color, link: Color, body: TextStyle) {
    val table = remember(text) { parseMarkdown(text).firstOrNull() as? MdBlock.Table }
    val colors = MdColors(ink, link, ink.copy(alpha = 0.09f))
    if (table != null) TableBlock(table, colors, body) else Text(rememberInline(text, colors), style = body)
}

/** Tableau : colonnes à largeur fixe (calculée sur le contenu) pour rester alignées, défilement horizontal. */
@Composable
private fun TableBlock(table: MdBlock.Table, colors: MdColors, body: TextStyle) {
    val line = colors.ink.copy(alpha = 0.14f)
    val cell = body.copy(fontSize = 14.sp, lineHeight = 19.sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widths = remember(table, cell, density) {
        table.header.indices.map { c ->
            val widest = (listOf(table.header[c]) + table.rows.map { it.getOrElse(c) { "" } }).maxOf { value ->
                measurer.measure(inlineMarkdown(value, colors), style = cell.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.width
            }
            (with(density) { widest.toDp().value } + 22f).coerceIn(64f, 220f)
        }
    }
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, line, RoundedCornerShape(10.dp))) {
            (listOf(table.header) + table.rows).forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) colors.ink.copy(alpha = 0.06f) else Color.Transparent)) {
                    row.forEachIndexed { c, value ->
                        Text(
                            rememberInline(value, colors),
                            style = if (r == 0) cell.copy(fontWeight = FontWeight.SemiBold) else cell,
                            modifier = Modifier.width(widths[c].dp).padding(horizontal = 10.dp, vertical = 7.dp),
                        )
                    }
                }
                if (r < table.rows.size) Box(Modifier.width(widths.sum().dp).height(1.dp).background(line))
            }
        }
    }
}
