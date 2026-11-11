package app.dotshermes.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.launch
import java.net.URI
import androidx.compose.ui.res.stringResource
import app.dotshermes.R

/** Décode une image d'écran une seule fois par trame reçue. */
@Composable
fun rememberScreenBitmap(screen: ScreenState): ImageBitmap? = remember(screen.receivedAt) {
    screen.frame?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
}

fun hostOf(url: String): String = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: url

/**
 * L'écran du navigateur de l'agent, en direct (le serveur ne capture que tant que quelqu'un regarde).
 * « Prendre la main » rend l'image interactive : tap = clic, glisser = défiler, champ = frappe —
 * pour une connexion, un captcha ou une bannière, sans que le mot de passe passe par la conversation.
 */
@Composable
fun ScreenViewer(
    screen: ScreenState,
    agentName: String,
    appearance: DotAppearance,
    onWatch: (on: Boolean, takeover: Boolean) -> Unit,
    onInput: (ScreenInput) -> Unit,
    onHandBack: () -> Unit,
    onClose: () -> Unit,
    /** Ouvert depuis la demande de l'agent : on arrive directement en prise en main. */
    startInTakeover: Boolean = false,
) {
    var takeover by rememberSaveable { mutableStateOf(startInTakeover) }
    DisposableEffect(takeover) {
        onWatch(true, takeover)
        onDispose { onWatch(false, false) }
    }
    val bitmap = rememberScreenBitmap(screen)
    val palette = LocalDotPalette.current

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF111114)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.Rounded.Close, stringResource(R.string.action_close), onClose)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (takeover) stringResource(R.string.screen_you_drive) else stringResource(R.string.screen_of, agentName), style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        screen.title.ifBlank { stringResource(R.string.activity_browser) },
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                LiveBadge(live = screen.live && !screen.idle, takeover = takeover)
            }
            if (screen.url.isNotBlank()) AddressBar(screen.url)

            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = bitmap != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { hasImage ->
                    if (hasImage && bitmap != null) {
                        PageImage(bitmap, takeover, onInput, highlight = palette.base)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ClayDot(palette, Modifier.size(120.dp), mood = DotMood.Sleeping,
                                accessory = appearance.accessory, species = appearance.species, showShadow = false)
                            Text(
                                stringResource(R.string.screen_empty),
                                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            if (takeover) {
                TakeoverControls(onInput, onDone = {
                    takeover = false
                    onHandBack()
                    onClose()
                })
            } else if (bitmap != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.clip(CircleShape).background(palette.base).clickable { takeover = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PanTool, null, tint = palette.onBase, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_take_over), style = MaterialTheme.typography.labelLarge, color = palette.onBase)
                    }
                    Text(
                        stringResource(R.string.screen_take_over_hint),
                        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** L'image de la page ; en prise en main, tap = clic (avec un cercle à l'endroit touché) et glisser = défiler. */
@Composable
private fun PageImage(bitmap: ImageBitmap, takeover: Boolean, onInput: (ScreenInput) -> Unit, highlight: Color) {
    val scope = rememberCoroutineScope()
    var tapAt by remember { mutableStateOf<Offset?>(null) }
    val ripple = remember { Animatable(1f) }
    var dragged by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    Box(
        Modifier.fillMaxWidth().aspectRatio(bitmap.width / bitmap.height.toFloat())
            .shadow(18.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .border(if (takeover) 2.dp else 1.dp, if (takeover) highlight else Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .then(
                if (!takeover) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onInput(ScreenInput.Tap(offset.x / size.width, offset.y / size.height))
                            tapAt = offset
                            scope.launch {
                                ripple.snapTo(0f)
                                ripple.animateTo(1f, tween(450))
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        // Glisser vers le haut fait descendre la page, comme sur un écran tactile.
                        detectVerticalDragGestures(
                            onDragEnd = { dragged = 0f },
                            onVerticalDrag = { change, delta ->
                                dragged -= delta / size.height
                                if (kotlin.math.abs(dragged) > 0.04f) {
                                    onInput(ScreenInput.Scroll(change.position.x / size.width, change.position.y / size.height, dragged))
                                    dragged = 0f
                                }
                            },
                        )
                    },
            ),
    ) {
        Image(bitmap, stringResource(R.string.cd_browser_screen), Modifier.fillMaxSize())
        tapAt?.let { at ->
            Canvas(Modifier.fillMaxSize()) {
                val p = ripple.value
                if (p < 1f) drawCircle(highlight.copy(alpha = 1f - p), radius = 10.dp.toPx() + 22.dp.toPx() * p, center = at, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

/** Frappe et touches envoyées à la page de l'agent, et le bouton pour lui rendre la main. */
@Composable
private fun TakeoverControls(onInput: (ScreenInput) -> Unit, onDone: () -> Unit) {
    val palette = LocalDotPalette.current
    var text by remember { mutableStateOf("") }
    fun sendText() {
        if (text.isNotEmpty()) onInput(ScreenInput.Type(text))
        text = ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.screen_type_hint),
            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text(stringResource(R.string.screen_type_placeholder), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.4f))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(palette.base),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.AutoMirrored.Rounded.KeyboardReturn, stringResource(R.string.cd_send_enter)) {
                sendText()
                onInput(ScreenInput.Key("Enter"))
            }
            Spacer(Modifier.width(6.dp))
            RoundButton(Icons.Rounded.Backspace, stringResource(R.string.cd_erase)) { onInput(ScreenInput.Key("Backspace")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            KeyChip(stringResource(R.string.key_tab)) { onInput(ScreenInput.Key("Tab")) }
            KeyChip(stringResource(R.string.key_escape)) { onInput(ScreenInput.Key("Escape")) }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.screen_hand_back),
                style = MaterialTheme.typography.labelLarge,
                color = palette.onBase,
                modifier = Modifier.clip(CircleShape).background(palette.base).clickable(onClick = onDone).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AddressBar(url: String) {
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (url.startsWith("https")) Icons.Rounded.Lock else Icons.Rounded.Language, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(url, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.1f)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun LiveBadge(live: Boolean, takeover: Boolean) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "live-a",
    )
    val on = live || takeover
    Row(
        Modifier.clip(CircleShape).background(if (on) Ink.Danger.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).graphicsLayer { alpha = if (on) pulse else 1f }.background(if (on) Ink.Danger else Color.White.copy(alpha = 0.5f), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            when {
                takeover -> stringResource(R.string.badge_driving)
                live -> stringResource(R.string.badge_live)
                else -> stringResource(R.string.badge_paused)
            },
            style = MaterialTheme.typography.labelSmall, color = if (on) Color(0xFFFF8A8A) else Color.White.copy(alpha = 0.6f),
        )
    }
}
