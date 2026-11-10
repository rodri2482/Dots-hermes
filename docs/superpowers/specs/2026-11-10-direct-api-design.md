# Claydot direct API-key mode

## User decisions

- Native Android app, preserving Claydot's mascots and conversation design.
- Add direct API-key mode so Hermes is not required.
- Keep the project private.
- Develop with offline mocks; users enter their own keys privately on the phone. Do not create,
  inspect, store on this PC, or spend a real provider credential for testing.

## Proposed first delivery

Support OpenAI and Anthropic alongside the existing Hermes connection. On the connection screen,
choose a provider, enter an API key, and choose an available model. Save each as an agent with its
own mascot. Existing Hermes pairings continue to work.

Direct mode first supports text conversations: streaming replies, Markdown, copy/select text,
Stop, retry after failure, and local conversation history that survives restarting the app.
Provider errors explain invalid keys, unavailable models, exhausted quota, rate limiting and
network interruptions. An interrupted paid request is never silently replayed.

Voice, uploads, remote tools, sub-dots, cron, browser control and QR browser pairing retain their
existing Hermes implementation. Controls requiring those capabilities are hidden for a direct
provider account; text mode must not pretend that a remote agent is executing tools. API-based
voice and image/document support can follow as separately verified capabilities.

## Privacy and security

Keys are entered on-device and encrypted using Android Keystore-backed AES-GCM. They never enter
Git, APK resources, logs, analytics, UI saved-instance state, crash reports, exported history or
the Hermes pairing store. The UI masks keys and offers explicit removal.

Encrypt direct conversation history at rest. Send conversation context only to the selected
provider over HTTPS. Fixed official provider origins, no user-supplied proxy URL in this delivery.
Do not follow redirects that could forward authorization to another host. No relay or new hosted
backend. No analytics for keys, prompts, responses, agent names or provider errors containing data.
OpenAI requests explicitly disable response storage where supported. Provider retention policies
still apply; local encryption is not a promise of zero provider retention.

This delivery uses API keys. Subscription sign-in is not part of the implementation under review.

## Implementation direction (Codex's proposed choices)

- Kotlin and Compose, retaining the current ChatRepository UI boundary and OkHttp dependency.
- Add provider-specific request/stream adapters for OpenAI Responses and Anthropic Messages.
- Keep credential storage separate from nonsecret account metadata and gateway tokens.
- Generalize agent sessions so the registry can host a gateway repository or a direct repository;
  gateway-specific pairing/notifications remain available only for gateway sessions.
- Add explicit capability metadata, with working controls only for the selected backend.
- Reuse the current mascot, theme and agent selector; no visual redesign.
- Obtain model choices from the provider when the user connects; allow an explicit model ID rather
  than embedding a paid default that might be unavailable to the account.
- Preserve partial replies on interruption, make Stop cancel the network call, and prevent output
  from an old request from entering a newly selected account/conversation.
- Serialize requests within each conversation; use complete prior user/assistant turns as context.
- No automatic inference on account creation, model selection, app startup or retryable errors.

## Verification and release gates

Offline tests cover request formats, incremental SSE decoding, completion/error/truncated streams,
401/403/429/quota errors, cancellation, retry without duplicate history, isolation between agents,
credential redaction, encryption failure handling and process-restored history.

Build the native debug APK, run the full Android test suite and lint, and exercise the new flow in
an emulator with a local mock provider. Existing gateway/web suites must remain green.
Real API acceptance is intentionally deferred until the owner enters a key on a device. Production
signing and real-device acceptance remain owner tasks; do not label a debug APK a production release.

## Current status

User approved text chat first on 10 November 2026, after approving offline testing and phone-entered
keys. A fresh builder check found no material unanswered design questions. Proceed to the native
implementation plan. Native text-mode implementation and offline acceptance are now recorded in
that plan; real-device/live-provider and owner signing gates remain open.
