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

object DotPalettes {
    val Tangerine = DotPalette("tangerine", R.string.color_tangerine, Color(0xFFFF8A3D), Color(0xFFE5611C), Color(0xFFFFEDE0), Color.White)
    val Bubblegum = DotPalette("bubblegum", R.string.color_bubblegum, Color(0xFFFF5FA2), Color(0xFFDD3A80), Color(0xFFFFE6F0), Color.White)
    val Sky = DotPalette("sky", R.string.color_sky, Color(0xFF2F8CFF), Color(0xFF1764D6), Color(0xFFE3EEFF), Color.White)
    val Lime = DotPalette("lime", R.string.color_lime, Color(0xFF9AD83A), Color(0xFF6AAE18), Color(0xFFEFF8DE), Color(0xFF1F3300))
    val Sun = DotPalette("sun", R.string.color_sun, Color(0xFFFFC531), Color(0xFFE29E00), Color(0xFFFFF5D9), Color(0xFF3B2800))
    val Grape = DotPalette("grape", R.string.color_grape, Color(0xFF8E6BFF), Color(0xFF6844E8), Color(0xFFEEE8FF), Color.White)

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

/**
 * Accent du fil de discussion : un bleu commun à toutes les mascottes (bulles de l'utilisateur, boutons, choix),
 * plus clair en thème sombre pour rester lisible. La couleur choisie dans la fiche teinte la mascotte elle-même.
 */
fun chatAccent(dark: Boolean): DotPalette = if (dark)
    DotPalette("accent", R.string.color_sky, Color(0xFF2F8CFF), Color(0xFF7DB6FF), Color(0xFF15263D), Color.White)
else DotPalette("accent", R.string.color_sky, Color(0xFF1677F2), Color(0xFF0E62D0), Color(0xFFE7F0FE), Color.White)

/** Préférence d'affichage, réglée dans la fiche du dot. */
enum class ThemeMode(@StringRes val label: Int) { System(R.string.theme_auto), Light(R.string.theme_light), Dark(R.string.theme_dark) }
