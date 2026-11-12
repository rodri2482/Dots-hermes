package app.dotshermes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.ToolKeys

/**
 * Ce que fait Claydot, en icône + texte traduit, à la place des emojis : la couche données publie une
 * clé (« tool:music », « tool:wait:12 »), l'interface la traduit. Un libellé venu d'un gateway Hermes
 * (texte libre) s'affiche tel quel, sans icône.
 */
fun toolIcon(key: String): ImageVector? = when (ToolKeys.kind(key)) {
    "memory" -> Icons.Rounded.Psychology
    "agent" -> Icons.Rounded.Forum
    "wiki" -> Icons.Rounded.Language
    "draw" -> Icons.Rounded.Image
    "model" -> Icons.Rounded.Forum
    "wait" -> Icons.Rounded.HourglassTop
    "voice" -> Icons.Rounded.Mic
    "message", "sms" -> Icons.Rounded.Sms
    "whatsapp" -> Icons.Rounded.Chat
    "alarm" -> Icons.Rounded.Alarm
    "timer" -> Icons.Rounded.Timer
    "app" -> Icons.Rounded.Apps
    "calendar" -> Icons.Rounded.Event
    "music" -> Icons.Rounded.MusicNote
    "routine" -> Icons.Rounded.Schedule
    "delete" -> Icons.Rounded.Delete
    "screen" -> Icons.Rounded.TouchApp
    "news" -> Icons.Rounded.Newspaper
    "image" -> Icons.Rounded.Image
    "web" -> Icons.Rounded.Language
    "phone" -> Icons.Rounded.PhoneAndroid
    "mail" -> Icons.Rounded.Mail
    "ask" -> Icons.Rounded.Forum
    else -> null
}

@Composable
fun toolText(key: String): String {
    if (!ToolKeys.isKey(key)) return key
    return when (ToolKeys.kind(key)) {
        "memory" -> stringResource(R.string.doing_memory)
        "agent" -> stringResource(R.string.doing_agent)
        "wiki" -> stringResource(R.string.doing_wiki)
        "draw" -> stringResource(R.string.doing_draw)
        "model" -> stringResource(R.string.doing_model)
        "wait" -> stringResource(R.string.doing_wait, ToolKeys.argument(key).toIntOrNull() ?: 0)
        "voice" -> stringResource(R.string.doing_voice)
        "message", "sms", "whatsapp" -> stringResource(R.string.doing_message)
        "alarm" -> stringResource(R.string.doing_alarm)
        "timer" -> stringResource(R.string.doing_timer)
        "app" -> stringResource(R.string.doing_app)
        "calendar" -> stringResource(R.string.doing_calendar)
        "music" -> stringResource(R.string.doing_music)
        "routine" -> stringResource(R.string.doing_routine)
        "screen" -> stringResource(R.string.doing_screen)
        "news" -> stringResource(R.string.doing_news)
        "image" -> stringResource(R.string.doing_image)
        "web" -> stringResource(R.string.doing_web)
        "phone" -> stringResource(R.string.doing_phone)
        "mail" -> stringResource(R.string.doing_mail)
        "ask" -> stringResource(R.string.doing_ask)
        else -> stringResource(R.string.status_thinking)
    }
}

/** Une ligne d'état : petite icône puis texte. */
@Composable
fun ToolStatusLine(key: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        toolIcon(key)?.let {
            Icon(it, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(toolText(key), style = style, color = color, maxLines = 1)
    }
}
