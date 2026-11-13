package app.dotshermes.data.direct

enum class DiagnosticState { Configured, Verified, Missing, NeedsAttention, UnableToCheck }
enum class GmailDiagnostic { NotConnected, Authorized, Reconnect, SetupMissing, Unavailable, AppPasswordConfigured }
data class ModelDiagnosticResult(val state: DiagnosticState, val failure: ApiFailure? = null, val listed: Boolean? = null)

/** Un compte configuré n'est pas une preuve d'accès au fournisseur. Aucun appel payant de diagnostic. */
fun modelDiagnostic(configured: Boolean, failure: ApiFailure?): DiagnosticState = when {
    !configured -> DiagnosticState.Missing
    failure in setOf(ApiFailure.InvalidKey, ApiFailure.MissingKey, ApiFailure.Forbidden, ApiFailure.ModelUnavailable,
        ApiFailure.Quota, ApiFailure.RateLimited, ApiFailure.DeviceHot, ApiFailure.LowBattery, ApiFailure.LowMemory) -> DiagnosticState.NeedsAttention
    failure in setOf(ApiFailure.Network, ApiFailure.Unavailable, ApiFailure.Interrupted) -> DiagnosticState.UnableToCheck
    else -> DiagnosticState.Configured
}

internal fun gmailDiagnostic(connected: Boolean, token: Boolean = false, resolution: Boolean = false, error: Int? = null): GmailDiagnostic = when {
    !connected -> GmailDiagnostic.NotConnected
    error == 10 -> GmailDiagnostic.SetupMissing
    error == 4 -> GmailDiagnostic.Reconnect
    error != null -> GmailDiagnostic.Unavailable
    resolution -> GmailDiagnostic.Reconnect
    token -> GmailDiagnostic.Authorized
    else -> GmailDiagnostic.Reconnect
}
