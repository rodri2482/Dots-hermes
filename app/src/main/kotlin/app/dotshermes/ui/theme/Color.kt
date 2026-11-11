package app.dotshermes.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.annotation.StringRes
import app.dotshermes.R

/**
 * Chaque dot a une seule couleur saturée qui teinte toute l'interface :
 * [base] pour le personnage et les bulles de l'utilisateur, [deep] pour l'ombre de la pâte,
 * [light] pour le fond pastel, [onBase] pour le texte posé sur [base].
 */
@Immutable
data class DotPalette(
    val id: String,
    @StringRes val name: Int,
    val base: Color,
    val deep: Color,
    val light: Color,
    val onBase: Color,
)

/**
 * Teintes dérivées en OKLCH à partir de la couleur de base (celle des illustrations, inchangée) :
 * « deep » à la même teinte, luminance ~0,55, la plus claire qui reste lisible (≥ 4,5:1 sur blanc
 * et sur « light ») ; « light » à L 0,965 et chroma 0,03, d'intensité égale pour toutes les couleurs.
 */
object DotPalettes {
    val Tangerine = DotPalette("tangerine", R.string.color_tangerine, Color(0xFFFF8A3D), Color(0xFFB35403), Color(0xFFFEF0E9), Color.White)
    val Bubblegum = DotPalette("bubblegum", R.string.color_bubblegum, Color(0xFFFF5FA2), Color(0xFFCC2B77), Color(0xFFFEEFF3), Color.White)
    val Sky = DotPalette("sky", R.string.color_sky, Color(0xFF2F8CFF), Color(0xFF026DDB), Color(0xFFEDF4FE), Color.White)
    val Lime = DotPalette("lime", R.string.color_lime, Color(0xFF9AD83A), Color(0xFF537C04), Color(0xFFEDF8E3), Color(0xFF1F3300))
    val Sun = DotPalette("sun", R.string.color_sun, Color(0xFFFFC531), Color(0xFF8D6A02), Color(0xFFFDF2DD), Color(0xFF3B2800))
    val Grape = DotPalette("grape", R.string.color_grape, Color(0xFF8E6BFF), Color(0xFF7953E5), Color(0xFFF3F2FE), Color.White)

    val all = listOf(Tangerine, Bubblegum, Sky, Lime, Sun, Grape)
}

/**
 * Neutres inspirés des couleurs système iOS, en clair et en sombre. [dark] est un état Compose :
 * le basculer recompose tout ce qui lit ces couleurs, sans les passer de composable en composable.
 */
object Ink {
    var dark by mutableStateOf(false)

    val Primary get() = if (dark) Color(0xFFF2F2F7) else Color(0xFF111114)
    val Secondary get() = if (dark) Color(0xFF98989F) else Color(0xFF8A8A8F)
    val Tertiary get() = if (dark) Color(0xFF55555B) else Color(0xFFC7C7CC)
    val Separator get() = if (dark) Color(0xFF2E2E33) else Color(0xFFE6E6EB)
    val Fill get() = if (dark) Color(0xFF26262B) else Color(0xFFF2F2F6)
    val AgentBubble get() = if (dark) Color(0xFF26262B) else Color(0xFFF0F0F4)
    val Grouped get() = if (dark) Color(0xFF0B0B0D) else Color(0xFFF5F5F8)

    /** Fond de l'écran. */
    val Surface get() = if (dark) Color(0xFF0E0E11) else Color.White

    /** Ce qui se pose dessus : cartes, pastilles, barres flottantes. */
    val Raised get() = if (dark) Color(0xFF1C1C20) else Color.White

    val Success get() = if (dark) Color(0xFF3DD26A) else Color(0xFF30C15A)
    val Warning get() = if (dark) Color(0xFFFFA62B) else Color(0xFFE08A00)
    val Danger get() = if (dark) Color(0xFFFF5A50) else Color(0xFFFF3B30)
}

/** Préférence d'affichage, réglée dans la fiche du dot. */
enum class ThemeMode(@StringRes val label: Int) { System(R.string.theme_auto), Light(R.string.theme_light), Dark(R.string.theme_dark) }
