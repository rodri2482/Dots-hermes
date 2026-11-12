package app.dotshermes.ui.dots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.ui.AGENT_NAME_MAX
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.theme.DotPalette
import app.dotshermes.ui.theme.DotPalettes
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import app.dotshermes.ui.DEFAULT_AGENT_NAME

/** Apparence d'un sous-dot (gardée côté gateway) traduite pour la mascotte et le thème. */
fun SubDot.appearance(theme: DotAppearance): DotAppearance = DotAppearance(
    name = name,
    palette = DotPalettes.all.firstOrNull { it.id == palette } ?: DotPalettes.Sky,
    accessory = DotAccessory.entries.firstOrNull { it.name == accessory } ?: DotAccessory.WingedCap,
    species = DotSpecies.entries.firstOrNull { it.name == species } ?: DotSpecies.Nuage,
    theme = theme.theme,
)

/** Nom d'un dot temporaire laissé vide : les premiers mots de sa mission, sinon [fallback]. */
internal fun nameFromMission(mission: String, fallback: String): String =
    mission.trim().split(Regex("\\s+")).take(3).joinToString(" ").replaceFirstChar { it.titlecase() }.take(AGENT_NAME_MAX).ifBlank { fallback }

/**
 * Fiche des dots, ouverte depuis l'en-tête : en haut la bande des dots, en dessous les réglages du
 * dot choisi (ou le formulaire de création). Une seule feuille : changer de dot remplace son contenu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotsSheet(strip: @Composable () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            strip()
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
        }
    }
}

/** Un élément du carrousel : le dot principal, un sous-dot, ou « + » (création). */
private class CarouselItem(val key: String, val look: DotAppearance?, val name: String, val temporary: Boolean)

private const val NEW_DOT = "new-dot"

/** Largeur fixe d'un emplacement : les positions ne bougent jamais, seule l'échelle varie. */
private val SlotWidth = 104.dp
private val DotSize = 132.dp
/** Échelle d'un dot à un emplacement (ou plus) du centre ; 1 au centre. */
private const val SideScale = 0.5f

/**
 * Carrousel des dots, en tête de la fiche. Le dot au centre de l'écran est le dot choisi, en grand ;
 * les suivants sont à sa droite, plus petits, et peuvent déborder de l'écran. Faire défiler amène
 * le suivant au centre et le choisit (sa conversation et ses réglages s'ouvrent) ; un tap y va aussi.
 * Le dot principal est toujours le premier, « + » le dernier.
 * [preview] : l'apparence en cours d'édition du dot choisi (ou du nouveau), affichée en direct.
 */
@Composable
fun DotsCarousel(
    main: DotAppearance,
    dots: List<SubDot>,
    activeChat: String,
    creating: Boolean,
    preview: DotAppearance?,
    activities: Map<String, AgentActivity>,
    unread: Map<String, Int>,
    showSubDots: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val ordered = remember(dots) { orderedDots(dots) }
    val newLabel = stringResource(R.string.dot_new)
    val items = buildList {
        add(CarouselItem(MAIN_CHAT, main, main.name.ifBlank { DEFAULT_AGENT_NAME }, temporary = false))
        if (showSubDots) {
            ordered.forEach { add(CarouselItem(it.id, it.appearance(main), it.name, it.kind == DotKind.Temporary)) }
            add(CarouselItem(NEW_DOT, null, newLabel, temporary = false))
        }
    }
    val selectedKey = if (creating) NEW_DOT else activeChat
    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()
    val currentKey by rememberUpdatedState(selectedKey)
    val currentItems by rememberUpdatedState(items)
    val choose: (String) -> Unit = { key -> if (key == NEW_DOT) onCreate() else onSelect(key) }
    val currentChoose by rememberUpdatedState(choose)

    /** Index de l'emplacement dont le centre est le plus proche du centre de la ligne. */
    fun centeredIndex(): Int? {
        val info = listState.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - center) }?.index
    }

    // Défilement terminé : le dot arrivé au centre devient le dot choisi.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val key = centeredIndex()?.let { currentItems.getOrNull(it)?.key } ?: return@collect
                if (key != currentKey) currentChoose(key)
            }
    }
    // Choix venu d'ailleurs (dot créé, fermé, ouvert par une notification) : on le ramène au centre.
    LaunchedEffect(selectedKey, items.size) {
        if (centeredIndex() != selectedIndex) listState.animateScrollToItem(selectedIndex)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Marges de la moitié de l'écran : le premier dot comme « + » peuvent venir au centre.
        val side = ((maxWidth - SlotWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(SnapLayoutInfoProvider(listState, SnapPosition.Center)),
            contentPadding = PaddingValues(horizontal = side),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(190.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                val selected = item.key == selectedKey
                CarouselDot(
                    look = if (selected && preview != null) preview else item.look,
                    name = if (selected && preview != null && item.key != MAIN_CHAT) preview.name.ifBlank { item.name } else item.name,
                    selected = selected,
                    temporary = item.temporary,
                    busy = activities[item.key].isBusy(),
                    unread = unread[item.key] ?: 0,
                    scale = {
                        // Proximité du centre, lue à chaque image : la taille suit le doigt pendant le défilement.
                        val info = listState.layoutInfo
                        val slot = info.visibleItemsInfo.firstOrNull { it.index == index }
                        if (slot == null) SideScale else {
                            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val distance = kotlin.math.abs(slot.offset + slot.size / 2f - center) / slot.size
                            1f - (1f - SideScale) * distance.coerceIn(0f, 1f)
                        }
                    },
                    onClick = { scope.launch { listState.animateScrollToItem(index) } },
                )
            }
        }
    }
}

private fun AgentActivity?.isBusy() = this != null && this != AgentActivity.Idle && this != AgentActivity.Reacting

/**
 * Un emplacement du carrousel, de largeur fixe. La mascotte est dessinée à sa taille pleine puis
 * réduite selon [scale] (graphicsLayer : rien ne se re-mesure, les voisins ne bougent pas).
 * [look] null : le bouton « + », qui devient l'aperçu du nouveau dot une fois choisi.
 */
@Composable
private fun CarouselDot(
    look: DotAppearance?, name: String, selected: Boolean, temporary: Boolean, busy: Boolean, unread: Int,
    scale: () -> Float, onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(SlotWidth).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier.width(SlotWidth).height(DotSize).graphicsLayer {
                val k = scale()
                scaleX = k
                scaleY = k
                // Réduit vers le bas : les petits dots restent alignés sur la même ligne que le grand.
                transformOrigin = TransformOrigin(0.5f, 0.9f)
            },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredSize(DotSize), contentAlignment = Alignment.Center) {
                if (look != null) {
                    ClayDot(look.palette, Modifier.requiredSize(DotSize), mood = if (busy) DotMood.Thinking else if (selected) DotMood.Happy else DotMood.Idle,
                        accessory = look.accessory, species = look.species, showShadow = selected)
                } else {
                    Box(Modifier.size(DotSize * 0.6f).clip(CircleShape).border(2.dp, Ink.Tertiary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.cd_new_dot), tint = Ink.Secondary, modifier = Modifier.size(36.dp))
                    }
                }
                if (temporary) {
                    // Sablier : ce dot a vocation à être fermé une fois sa mission faite.
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 18.dp).size(26.dp).clip(CircleShape).background(Ink.Raised),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.HourglassTop, stringResource(R.string.dot_kind_temporary), tint = Ink.Secondary, modifier = Modifier.size(16.dp))
                    }
                }
                if (unread > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 10.dp).heightIn(min = 26.dp).clip(CircleShape)
                            .background(Ink.Danger).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unread > 9) "9+" else "$unread", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
            }
        }
        Text(
            name,
            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            color = if (selected) Ink.Primary else Ink.Secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.requiredWidth(if (selected) 180.dp else SlotWidth).graphicsLayer { alpha = 0.45f + 0.55f * ((scale() - SideScale) / (1f - SideScale)) },
        )
    }
}

/**
 * Création ou réglages d'un sous-dot (dans la fiche des dots). Temporaire : surtout une mission (nom
 * facultatif). Permanent : un nom, une spécialisation, une mascotte et une couleur à lui.
 */
@Composable
fun DotEditor(
    existing: SubDot?,
    theme: DotAppearance,
    onSave: (SubDotDraft) -> Unit,
    onDelete: (() -> Unit)?,
    /** Apparence en cours de saisie, pour que le carrousel montre le dot tel qu'il sera. */
    onPreview: (DotAppearance) -> Unit = {},
) {
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: DotKind.Temporary) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var mission by rememberSaveable { mutableStateOf(existing?.mission.orEmpty()) }
    // Un nouveau dot reçoit une mascotte et une couleur différentes de celles du dot principal, pour qu'on les distingue.
    var species by rememberSaveable {
        mutableStateOf(existing?.species ?: DotSpecies.entries.firstOrNull { it != theme.species }?.name ?: DotSpecies.Nuage.name)
    }
    var paletteId by rememberSaveable {
        mutableStateOf(existing?.palette ?: DotPalettes.all.firstOrNull { it.id != theme.palette.id }?.id ?: DotPalettes.Sky.id)
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val palette = DotPalettes.all.firstOrNull { it.id == paletteId } ?: DotPalettes.Sky
    val speciesValue = DotSpecies.entries.firstOrNull { it.name == species } ?: DotSpecies.Nuage
    val canSave = mission.isNotBlank() && (kind == DotKind.Temporary || name.isNotBlank())
    val newLabel = stringResource(R.string.dot_new)
    val missionLabel = stringResource(R.string.dot_mission)
    LaunchedEffect(name, mission, species, paletteId) {
        onPreview(DotAppearance(name = name.ifBlank { if (mission.isBlank()) newLabel else nameFromMission(mission, missionLabel) },
            palette = palette, accessory = DotAccessory.WingedCap, species = speciesValue, theme = theme.theme))
    }

    run {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (kind == DotKind.Temporary) R.string.dot_temporary_intro else R.string.dot_permanent_intro),
                style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, textAlign = TextAlign.Center,
            )

            SectionLabel(stringResource(R.string.dot_type))
            GroupedCard { Segmented(DotKind.entries.map { stringResource(it.label) }, DotKind.entries.indexOf(kind)) { kind = DotKind.entries[it] } }

            SectionLabel(stringResource(if (kind == DotKind.Temporary) R.string.dot_mission else R.string.dot_specialty))
            GroupedCard {
                Field(
                    value = mission, onChange = { mission = it }, minLines = 3,
                    placeholder = stringResource(if (kind == DotKind.Temporary) R.string.dot_mission_hint else R.string.dot_specialty_hint),
                )
            }

            SectionLabel(stringResource(if (kind == DotKind.Temporary) R.string.dot_name_optional else R.string.settings_name))
            GroupedCard {
                Field(value = name, onChange = { name = it.take(AGENT_NAME_MAX) }, minLines = 1,
                    placeholder = if (kind == DotKind.Temporary) nameFromMission(mission, missionLabel) else stringResource(R.string.dot_name_hint))
            }

            SectionLabel(stringResource(R.string.settings_mascot))
            GroupedCard {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DotSpecies.entries.forEach { option ->
                        val chosen = option == speciesValue
                        val own = if (chosen) palette else DotPalettes.all.firstOrNull { it.id == option.signaturePalette } ?: palette
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (chosen) selectedTint(palette) else Color.Transparent)
                                .clickable { species = option.name }.padding(6.dp),
                        ) {
                            ClayDot(own, Modifier.size(58.dp), accessory = DotAccessory.WingedCap, species = option, showShadow = false)
                            Text(stringResource(option.label), style = MaterialTheme.typography.labelSmall, color = if (chosen) Ink.Primary else Ink.Secondary)
                        }
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_color))
            GroupedCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    DotPalettes.all.forEach { option -> Swatch(option, option.id == paletteId) { paletteId = option.id } }
                }
            }

            Spacer(Modifier.size(20.dp))
            PillButton(
                stringResource(if (existing == null) R.string.dot_create else R.string.dot_save),
                onClick = {
                    onSave(SubDotDraft(name.trim().ifBlank { nameFromMission(mission, missionLabel) }, kind, mission.trim(), species, paletteId, DotAccessory.WingedCap.name))
                },
                modifier = Modifier.fillMaxWidth(),
                container = palette.base, content = palette.onBase, enabled = canSave,
            )
            if (onDelete != null) {
                Spacer(Modifier.size(10.dp))
                Text(
                    stringResource(if (existing?.kind == DotKind.Temporary) R.string.dot_close_mission else R.string.dot_delete),
                    style = MaterialTheme.typography.labelLarge, color = Ink.Danger, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { confirmDelete = true }.padding(14.dp),
                )
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dot_close_title, existing?.name ?: stringResource(R.string.dot_this))) },
            text = { Text(stringResource(R.string.dot_close_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.dot_close_confirm), color = Ink.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = Ink.Primary) } },
            containerColor = Ink.Raised,
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, minLines: Int, placeholder: String) {
    val palette = LocalDotPalette.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ink.Tertiary)
        BasicTextField(
            value = value, onValueChange = onChange, minLines = minLines, maxLines = if (minLines > 1) 8 else 1,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Primary),
            cursorBrush = SolidColor(palette.base),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Text(
                label, style = MaterialTheme.typography.labelLarge, color = if (i == selected) Ink.Primary else Ink.Secondary, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (i == selected) Ink.Raised else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** Fond d'une mascotte choisie : pastel en clair, teinte profonde en sombre (le pastel y faisait une tache claire). */
private fun selectedTint(palette: DotPalette): Color =
    if (Ink.dark) androidx.compose.ui.graphics.lerp(Color(0xFF1C1C20), palette.base, 0.26f) else palette.light

@Composable
private fun Swatch(palette: DotPalette, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).border(2.dp, if (selected) palette.base else Color.Transparent, CircleShape)
            .clickable(onClick = onClick).padding(5.dp)
            .background(Brush.radialGradient(listOf(lerp(palette.base, Color.White, 0.4f), palette.base, palette.deep)), CircleShape),
    )
}
