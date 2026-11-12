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
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
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

/**
 * Tableau : colonnes à largeur fixe (calculée sur le contenu) pour rester alignées, défilement horizontal. Un bouton
 * « Agrandir » au coin ouvre le tableau entier en plein écran, plus grand, comme dans ChatGPT.
 */
@Composable
private fun TableBlock(table: MdBlock.Table, colors: MdColors, body: TextStyle) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TableGrid(table, colors, body, cellSize = 14, maxCell = 220f, modifier = Modifier.padding(top = 30.dp))
        val label = stringResource(R.string.table_expand)
        Box(
            Modifier.align(Alignment.TopEnd).size(30.dp).clip(RoundedCornerShape(9.dp)).background(colors.ink.copy(alpha = 0.07f))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(androidx.compose.material.icons.Icons.Rounded.OpenInFull, label, tint = colors.ink.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
    if (expanded) TableDialog(table, colors, body) { expanded = false }
}

/** Le tableau en plein écran : texte plus grand, colonnes plus larges, défilement dans les deux sens. */
@Composable
private fun TableDialog(table: MdBlock.Table, colors: MdColors, body: TextStyle, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(app.dotshermes.ui.theme.Ink.Surface).padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.action_close),
                        tint = app.dotshermes.ui.theme.Ink.Primary, modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                TableGrid(table, colors.copy(ink = app.dotshermes.ui.theme.Ink.Primary), body.copy(color = app.dotshermes.ui.theme.Ink.Primary),
                    cellSize = 16, maxCell = 320f)
            }
        }
    }
}

/** La grille du tableau : en-tête en gras, filets entre les lignes, colonnes mesurées sur leur texte. */
@Composable
private fun TableGrid(table: MdBlock.Table, colors: MdColors, body: TextStyle, cellSize: Int, maxCell: Float, modifier: Modifier = Modifier) {
    val line = colors.ink.copy(alpha = 0.14f)
    val cell = body.copy(fontSize = cellSize.sp, lineHeight = (cellSize + 5).sp)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widths = remember(table, cell, density) {
        table.header.indices.map { c ->
            val widest = (listOf(table.header[c]) + table.rows.map { it.getOrElse(c) { "" } }).maxOf { value ->
                measurer.measure(inlineMarkdown(value, colors), style = cell.copy(fontWeight = FontWeight.SemiBold), maxLines = 1).size.width
            }
            (with(density) { widest.toDp().value } + 22f).coerceIn(64f, maxCell)
        }
    }
    Box(modifier.horizontalScroll(rememberScrollState())) {
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
