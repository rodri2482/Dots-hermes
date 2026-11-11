package app.dotshermes.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Le téléphone a-t-il Internet en ce moment ? Suivi en direct (Wi-Fi coupé, mode avion…) par le
 * réseau par défaut d'Android ; sert à la pastille « En ligne / Hors ligne » de l'en-tête.
 */
@Composable
fun rememberDeviceOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }
    var online by remember(manager) { mutableStateOf(manager?.let(::currentlyOnline) ?: true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = currentlyOnline(manager) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            override fun onLost(network: Network) { online = false }
        }
        val registered = try {
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())); true
        } catch (_: RuntimeException) { false }
        onDispose { if (registered) try { manager.unregisterNetworkCallback(callback) } catch (_: RuntimeException) {} }
    }
    return online
}

private fun currentlyOnline(manager: ConnectivityManager): Boolean = try {
    manager.activeNetwork?.let(manager::getNetworkCapabilities)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
} catch (_: RuntimeException) { true }
