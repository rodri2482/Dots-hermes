package app.dotshermes.ui

import androidx.core.content.edit
import android.content.Context
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.theme.DotPalette
import app.dotshermes.ui.theme.DotPalettes
import app.dotshermes.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Personnalisation du dot : nom affiché, couleur, accessoire. */
data class DotAppearance(
    val name: String = DEFAULT_AGENT_NAME,
    val palette: DotPalette = DotPalettes.Tangerine,
    val accessory: DotAccessory = DotAccessory.WingedCap,
    val species: DotSpecies = DotSpecies.Galet,
    val theme: ThemeMode = ThemeMode.System,
)

const val DEFAULT_AGENT_NAME = "Claydot"
const val AGENT_NAME_MAX = 24

/**
 * Mémorisée sur l'appareil : le nom choisi sert aussi aux notifications. Une par agent
 * ([prefsName]) ; celle de l'agent historique garde aussi le thème, commun à toute l'app.
 */
class AppearanceStore(context: Context, prefsName: String = "appearance") {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        DotAppearance(
            name = prefs.getString(KEY_NAME, null) ?: DEFAULT_AGENT_NAME,
            palette = DotPalettes.all.firstOrNull { it.id == prefs.getString(KEY_PALETTE, null) } ?: DotPalettes.Tangerine,
            accessory = DotAccessory.entries.firstOrNull { it.name == prefs.getString(KEY_ACCESSORY, null) } ?: DotAccessory.WingedCap,
            species = DotSpecies.entries.firstOrNull { it.name == prefs.getString(KEY_SPECIES, null) } ?: DotSpecies.Galet,
            theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
        ),
    )
    val state: StateFlow<DotAppearance> = _state.asStateFlow()

    /** Saisie libre : vide pendant la frappe, le nom par défaut revient à l'affichage via [displayName]. */
    fun setName(name: String) {
        val clean = name.replace('\n', ' ').take(AGENT_NAME_MAX)
        _state.update { it.copy(name = clean) }
        prefs.edit { putString(KEY_NAME, clean) }
    }

    fun setPalette(palette: DotPalette) {
        _state.update { it.copy(palette = palette) }
        prefs.edit { putString(KEY_PALETTE, palette.id) }
    }

    fun setAccessory(accessory: DotAccessory) {
        _state.update { it.copy(accessory = accessory) }
        prefs.edit { putString(KEY_ACCESSORY, accessory.name) }
    }

    /** Changer de mascotte reprend sa couleur d'origine (Nuage bleu, Pavé violet) ; on peut la rechanger ensuite. */
    fun setSpecies(species: DotSpecies) {
        val palette = DotPalettes.all.firstOrNull { it.id == species.signaturePalette } ?: _state.value.palette
        _state.update { it.copy(species = species, palette = palette) }
        prefs.edit { putString(KEY_SPECIES, species.name).putString(KEY_PALETTE, palette.id) }
    }

    /**
     * Nom annoncé par le gateway (profil Hermes) : pris tant que l'utilisateur n'en a pas choisi un.
     * Pas mémorisé : si le profil change de nom, l'app suit.
     */
    fun adoptServerName(name: String) {
        val clean = name.trim().take(AGENT_NAME_MAX)
        if (clean.isEmpty() || prefs.contains(KEY_NAME)) return
        _state.update { it.copy(name = clean) }
    }

    /** Nouvel agent : une autre mascotte que les agents existants, pour les distinguer d'un coup d'œil. */
    fun initialize(species: DotSpecies) {
        if (prefs.contains(KEY_SPECIES)) return
        setSpecies(species)
    }

    /** Agent retiré : on oublie son apparence (le thème, commun, reste). */
    fun forget() {
        prefs.edit { remove(KEY_NAME).remove(KEY_PALETTE).remove(KEY_ACCESSORY).remove(KEY_SPECIES) }
        _state.update { DotAppearance(theme = it.theme) }
    }

    fun setTheme(theme: ThemeMode) {
        _state.update { it.copy(theme = theme) }
        prefs.edit { putString(KEY_THEME, theme.name) }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_SPECIES = "species"
        const val KEY_NAME = "name"
        const val KEY_PALETTE = "palette"
        const val KEY_ACCESSORY = "accessory"
    }
}

/** Nom à afficher : jamais vide, même si le champ l'est pendant l'édition. */
val DotAppearance.displayName: String get() = name.trim().ifEmpty { DEFAULT_AGENT_NAME }
