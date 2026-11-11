package app.dotshermes.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.model.UsageWindow
import app.dotshermes.ui.formatDate
import app.dotshermes.ui.theme.DotPalette
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields

private val CELL = 20.dp // hauteur d'une case ; sa largeur suit celle de la grille
private const val GRID_SHARE = 0.6f // part de la largeur pour la grille, le reste pour le détail
private val GAP = 4.dp
private val CELL_SHAPE = RoundedCornerShape(5.dp)

/** Combien de mois on peut remonter : au-delà, le registre du gateway est de toute façon vide. */
private const val MONTHS_BACK = 24L

/**
 * Carte « Consommation » en tête de la vue Activité : un mois en cases façon GitHub, plus foncées
 * les jours où l'agent a le plus travaillé. Les flèches remontent dans le passé ; un tap sur une
 * case donne le détail du jour, un second tap revient au total du mois.
 */
@Composable
fun UsageCard(usage: UsageWindow?, onRequest: (Instant, Instant) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val palette = LocalDotPalette.current
    val today = LocalDate.now(zone)
    val thisMonth = YearMonth.from(today)
    // Sauvegardés en texte : l'état survit à la rotation sans Parcelable maison.
    var monthText by rememberSaveable { mutableStateOf(thisMonth.toString()) }
    var metricName by rememberSaveable { mutableStateOf(UsageMetric.Tokens.name) }
    var selectedText by rememberSaveable { mutableStateOf<String?>(today.toString()) }
    val month = YearMonth.parse(monthText)
    val metric = UsageMetric.valueOf(metricName)
    val selected = selectedText?.let(LocalDate::parse)?.takeIf { YearMonth.from(it) == month }

    val start = month.atDay(1).atStartOfDay(zone).toInstant()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    // Une réponse ne vaut que pour le mois demandé : celle d'un mois quitté entre-temps est ignorée.
    val loaded = usage?.takeIf { it.from == start }
    var timedOut by remember(monthText) { mutableStateOf(false) }
    LaunchedEffect(monthText) {
        onRequest(start, end)
        delay(8_000)
        timedOut = true // un plugin trop ancien ne répond pas à usage_get
        while (month == thisMonth) { // le mois en cours continue de se remplir pendant qu'on regarde
            delay(60_000)
            onRequest(start, end)
        }
    }

    val days = remember(loaded, zone) { loaded?.let { usageByDay(it.hours, zone) }.orEmpty() }
    val inMonth = days.filterKeys { YearMonth.from(it) == month }
    val max = inMonth.values.maxOfOrNull { it.value(metric) } ?: 0L
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(true, enabled = month > thisMonth.minusMonths(MONTHS_BACK)) { monthText = month.minusMonths(1).toString() }
            Text(
                formatDate(start, "MMMMy", zone).replaceFirstChar { it.titlecase(locale) },
                style = MaterialTheme.typography.titleSmall, color = Ink.Primary,
            )
            MonthArrow(false, enabled = month < thisMonth) { monthText = month.plusMonths(1).toString() }
            Spacer(Modifier.weight(1f))
            MetricToggle(metric) { metricName = it.name }
        }
        Spacer(Modifier.height(8.dp))

        // Grille à gauche (60 % de la largeur), détail à droite : les cases s'étirent en largeur mais
        // gardent leur hauteur, la carte reste basse (7 rangées de 20 dp).
        Row(verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(GRID_SHARE), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    weekDays(firstDay).forEach { day ->
                        Box(Modifier.width(12.dp).height(CELL), contentAlignment = Alignment.CenterStart) {
                            Text(day.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
                        }
                    }
                }
                monthColumns(month, firstDay).forEach { week ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GAP)) {
                        week.forEach { date ->
                            if (date == null) {
                                Spacer(Modifier.fillMaxWidth().height(CELL))
                            } else {
                                val usageOfDay = inMonth[date] ?: DayUsage()
                                DayCell(
                                    level = if (loaded == null) 0 else heatLevel(usageOfDay.value(metric), max),
                                    future = date.isAfter(today),
                                    selected = date == selected,
                                    today = date == today,
                                    description = "${formatDate(date.atStartOfDay(zone).toInstant(), "EEEEdMMMM", zone)}, ${usageLine(usageOfDay, locale)}",
                                    palette = palette,
                                ) { selectedText = if (date == selected) null else date.toString() }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f - GRID_SHARE).height(CELL * 7 + GAP * 6)) {
                when {
                    loaded != null -> {
                        val focus = selected?.let { inMonth[it] ?: DayUsage() }
                        Text(
                            if (selected != null) {
                                formatDate(selected.atStartOfDay(zone).toInstant(), "EEEEdMMMM", zone).replaceFirstChar { it.titlecase(locale) }
                            } else {
                                stringResource(R.string.usage_month_total)
                            },
                            style = MaterialTheme.typography.labelLarge, color = Ink.Primary, maxLines = 2,
                        )
                        val total = focus ?: inMonth.values.fold(DayUsage()) { a, b -> a + b }
                        Text(tokensText(total, locale), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1, softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(messagesText(total), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1, softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (focus == null) {
                            val active = inMonth.values.count { it.value(metric) > 0 }
                            Text(pluralStringResource(R.plurals.usage_active_days, active, active), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary,
                                maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    timedOut -> Text(stringResource(R.string.usage_unavailable), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
                    else -> CircularProgressIndicator(Modifier.size(16.dp), color = palette.base, strokeWidth = 2.dp)
                }
                Spacer(Modifier.weight(1f))
                Legend(palette)
            }
        }
    }
}

/** « 1,8 M tokens » */
@Composable
private fun tokensText(usage: DayUsage, locale: java.util.Locale): String =
    pluralStringResource(R.plurals.usage_tokens_count, usage.tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compactCount(usage.tokens, locale))

/** « 34 messages » */
@Composable
private fun messagesText(usage: DayUsage): String = pluralStringResource(R.plurals.usage_messages_count, usage.messages, usage.messages)

/** « 1,8 M tokens · 34 messages », lu par TalkBack sur chaque case. */
@Composable
private fun usageLine(usage: DayUsage, locale: java.util.Locale): String = "${tokensText(usage, locale)} · ${messagesText(usage)}"

/** Teinte d'un niveau 0..4 : la couleur du dot, de plus en plus pleine. */
private fun heatColor(level: Int, palette: DotPalette): Color = when (level) {
    0 -> Ink.Fill
    1 -> palette.base.copy(alpha = 0.28f)
    2 -> palette.base.copy(alpha = 0.5f)
    3 -> palette.base.copy(alpha = 0.75f)
    else -> palette.base
}

@Composable
private fun DayCell(
    level: Int,
    future: Boolean,
    selected: Boolean,
    today: Boolean,
    description: String,
    palette: DotPalette,
    onClick: () -> Unit,
) {
    val outline = when {
        selected -> Modifier.border(2.dp, palette.deep, CELL_SHAPE)
        today -> Modifier.border(1.dp, Ink.Secondary, CELL_SHAPE)
        else -> Modifier
    }
    // Pas de numéro dans la case, trop petite : le jour s'affiche à droite quand on la touche.
    Box(
        Modifier
            .fillMaxWidth()
            .height(CELL)
            .clip(CELL_SHAPE)
            .background(if (future) Ink.Fill.copy(alpha = 0.45f) else heatColor(level, palette))
            .then(outline)
            .clickable(enabled = !future, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    )
}

@Composable
private fun MonthArrow(back: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (back) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
            stringResource(if (back) R.string.usage_previous_month else R.string.usage_next_month),
            tint = if (enabled) Ink.Primary else Ink.Tertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Petit sélecteur à deux segments : Tokens | Messages. */
@Composable
private fun MetricToggle(metric: UsageMetric, onPick: (UsageMetric) -> Unit) {
    Row(Modifier.clip(CircleShape).background(Ink.Fill).padding(2.dp)) {
        UsageMetric.entries.forEach { option ->
            val on = option == metric
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink.Raised else Color.Transparent)
                    .clickable(role = Role.Tab, onClick = { onPick(option) })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    stringResource(if (option == UsageMetric.Tokens) R.string.usage_tokens else R.string.usage_messages),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) Ink.Primary else Ink.Secondary,
                )
            }
        }
    }
}

/** « Moins ▢▢▢▢▢ Plus », comme sous le graphe de GitHub. */
@Composable
private fun Legend(palette: DotPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.usage_less), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
        Spacer(Modifier.width(2.dp))
        (0..4).forEach { level ->
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(heatColor(level, palette)))
        }
        Spacer(Modifier.width(2.dp))
        Text(stringResource(R.string.usage_more), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
    }
}
