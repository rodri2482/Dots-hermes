# UnifiedPush notifications for builds without Firebase

**Status: proposal — awaiting approval. No code is written for this design until it is signed off.**

*French version available on request; the two protocol docs ([protocole-gateway.md](protocole-gateway.md),
[appairage-web.md](appairage-web.md)) show the pattern.*

Version 1, 10 November 2026. Author: agent work for TASKS.md task 7b.

## Why

Today, background notifications exist only through Firebase Cloud Messaging. A build without
`app/google-services.json` (what an F-Droid listing or a degoogled phone needs) silently has **no
push at all**: the app works, you just have to keep it in the foreground or poll it yourself. The
whole code path is already guarded for that (`HermesApplication.kt` only touches Firebase when
`FirebaseApp.getApps(this)` is non-empty; `adapter.py` turns push off when the FCM service-account
file is missing).

**Goal**: give Firebase-less builds notifications through [UnifiedPush](https://unifiedpush.org)
with [ntfy](https://ntfy.sh) as the reference distributor, without changing anything for existing
FCM users.

**Non-goals**: iOS; web push for the browser app; replacing FCM where it already works; guaranteed
real-time delivery on every OEM (see [trade-offs](#trade-offs)); writing any code before approval.

## How UnifiedPush works (what we design against)

Verified against the UnifiedPush Android spec **AND_3.1.0** and ntfy's publishing docs, November
2026:

- A **distributor** app on the phone (ntfy's app, NextPush, …) keeps the connection to a push server
  and owns one endpoint per registration. Claydot would be a **client app** using the official
  connector library (`org.unifiedpush.android:connector`, current 3.3.4, minSdk 21 — we are at 26).
- The client registers via the `org.unifiedpush.android.distributor.REGISTER` broadcast and receives
  `NEW_ENDPOINT` (a capability URL), then later `MESSAGE` broadcasts with the raw push payload. It
  must expose a `RAISE_TO_FOREGROUND` service and answer `MESSAGE_ACK` when the distributor asks for
  it.
- **The push payload MUST be WebPush-encrypted (RFC 8291), 4096 bytes max** — so the cleartext we
  can send is ≤ 3993 bytes. Some servers additionally require VAPID (RFC 8292); ntfy does not.
  ntfy's `X-UnifiedPush: 1` publish flag keeps its app from double-rendering the message and
  auto-base64s binary bodies.
- Endpoints are capability URLs: whoever holds one can post to the phone. Encryption is therefore
  not optional for us — it is what keeps the ntfy server (and any middlebox) from reading the
  notification. The sender side is standard "Web Push": `pywebpush` on the server, a generated
  P-256 key pair + auth secret on the app (the official example uses Tink's `apps-webpush` for this).

## What exists today (file map)

| Side | What | Where |
|---|---|---|
| Server | `_Push`: FCM HTTP v1 sender, data-only messages, off without credentials | `server/hermes-plugin-dots/adapter.py` (~line 547) |
| Server | Token registry: `push_tokens.json`, `MAX_PUSH_TOKENS = 4` | same file, line 72 |
| Server | Registration entry points: `hello.push_token` field and the `push_token` frame, both ignored from browsers | same file, lines 858, 922 |
| App | FCM service: `onNewToken` → repo, `onMessageReceived` → notifications | `app/.../push/HermesMessagingService.kt` |
| App | All notification UI (MessagingStyle, quick reply, approval buttons) | `app/.../push/Notifications.kt` — push-transport-agnostic already |
| App | Firebase strictly optional at build time | `app/build.gradle.kts` lines 4–10, `HermesApplication.kt` |
| App | Token fan-out to every paired gateway | `AgentRegistry.setPushToken` (line 228) → `GatewayChatRepository.setPushToken` |

## Design

### 1. Transport choice, per app install

At startup the push module picks **one** transport:

1. **FCM** if Firebase is initialized (Play build with `google-services.json`) — exactly today's
   behaviour, untouched.
2. **UnifiedPush** otherwise, if a distributor is installed (discovered by the connector library).
3. **None** — status quo for a Firebase-less build with no distributor.

The choice is not a build flavour: one binary, decided at runtime, with a manual override in
settings (see §6). FCM keeps priority where both exist because it needs no extra app.

### 2. One registration per agent, not per device

FCM today: one token, fanned out to every paired gateway. With UnifiedPush we create **one
registration (endpoint + key pair) per paired agent** instead. The spec explicitly allows many
registrations per app, and the reason is trust: the endpoint and the WebPush key are stored on each
gateway, and with per-agent keys a gateway can only ever produce notifications that decrypt as
*itself* — one gateway can never spoof another agent's notification on the phone. Registrations
stay small (N = number of paired agents, usually 1–2).

### 3. Protocol: one new frame variant, nothing else changes

The `push_token` frame gains an optional set of fields (plugin **1.5.0**):

```json
{"type": "push_token", "endpoint": "https://ntfy.example/dots-XXXX", "p256dh": "<base64url>", "auth": "<base64url>"}
```

- Presence of `endpoint` (instead of `token`) means UnifiedPush. The server stores it in a new
  `up_endpoints.json` next to `push_tokens.json`, same `MAX_PUSH_TOKENS = 4` policy (a re-registration
  replaces the agent's entry).
- `hello` keeps its optional `push_token` string field for FCM only; UnifiedPush endpoints are too
  long and too dynamic for it. A UnifiedPush app re-sends the `push_token` frame on every `hello`
  cycle, which re-anchors the registration the same way the connector library re-registers at app
  start.
- **Compatibility**: an old plugin receiving `endpoint` looks for `frame.get("push_token")`, finds
  nothing, ignores the frame — old-plugin × new-app is a silent no-op. New plugin × old app: no
  `endpoint` field is ever sent, so nothing changes. `web_revoke`, `presence`, quick reply and the
  flash connection are untouched.

### 4. Payload and send behaviour (server)

`_UnifiedPushSender`, lazily imported like `_Push` (`pywebpush` — new dependency, see §7):

- Input: the same dict the FCM sender gets today (`kind`, `id`, `title`, `body`, `chat`, `agent`).
  JSON-serialized, WebPush-encrypted per registration with `pywebpush`'s `WebPushSubscription`
  (the `p256dh`/`auth` values the app sent), POSTed to the endpoint with headers
  `TTL: 86400`, `Urgency: high` and `X-UnifiedPush: 1` (ntfy skips its own Firebase hop and the
  bytes arrive raw).
- Budget check: today's payloads are ~150 bytes; the 3993-byte clear limit only matters if `body`
  carries a very long approval command. Rule: truncate `body` at 2000 characters server-side, as
  FCM's effective data budget already forces similar discipline.
- Delivery rule identical to FCM: push only when **no** client is connected-in-foreground, for
  every agent `message`, `approval`, and `clarify`. Send to every registered transport (usually
  exactly one per phone). A 410/404 from the endpoint drops it from the registry (like FCM's
  `UNREGISTERED`).
- Push stays **off** when `pywebpush` is not importable — the same graceful degradation as the
  missing FCM credentials today. No hard dependency is added to the plugin.

### 5. App side

New files, all no-ops when no distributor exists:

| File | Role |
|---|---|
| `push/UnifiedPushReceiver.kt` | Messaging receiver: `NEW_ENDPOINT` → generate/keep WebPush keys (Tink `apps-webpush`, same helper the official example uses), send the `push_token` frame through the existing repos; `MESSAGE` → decrypt, then reuse `Notifications` exactly like `HermesMessagingService.onMessageReceived` does today (same `inForeground` early-exit, same `forPush` attribution); `UNREGISTERED`/`REGISTRATION_FAILED` → clear state, surface status |
| `push/PushTransport.kt` | The choice logic of §1, exposed for the settings row |
| Manifest additions | receiver intent-filter (`org.unifiedpush.android.connector.*`), the `RAISE_TO_FOREGROUND` service |

Settings (Connect screen, one row, **EN + FR strings**): "Push notifications: Firebase /
UnifiedPush (ntfy app) / none", tap to re-register or, with no distributor installed, open the
ntfy install page (Play Store / F-Droid). No picker UI of our own — the connector's
`unifiedpush://link` deep link handles distributor selection.

Notification behaviour is **bit-identical** across transports: `Notifications.kt` is already
transport-agnostic, so quick reply, approval buttons, per-agent threads and avatars work as today.

### 6. Trust, privacy and multi-server notes

- The ntfy server sees only ciphertext (RFC 8291); the endpoint URL is a bearer capability, so we
  treat endpoints like tokens: never logged, replaced on re-registration.
- **Self-hosted ntfy is the recommended setup** (one more container on the Hermes box); ntfy.sh
  works but rate-limits (250 messages/day, 4096-byte cap) and is a third party. The docs and the
  install summary will say: self-host or pick a trusted instance.
- The gateway advertises nothing new; the phone chooses. A future DOTS_* variable
  (`DOTS_UP_HINT`?) could let the installer print a suggested ntfy URL — open question, not needed
  for v1.

### 7. Dependencies

| Side | Added | How |
|---|---|---|
| App | `org.unifiedpush.android:connector:3.3.4` (Maven Central) + `com.google.crypto.tink:apps-webpush:1.15.0` | pinned exact in `gradle/libs.versions.toml`, like every other dependency |
| Server | `pywebpush` (pulls `cryptography`, `http-ece`) | **not** a hard plugin dependency; documented in README as an optional `pip install pywebpush` into the Hermes environment, mirroring how FCM credentials are optional |

## Trade-offs

| | FCM (today) | UnifiedPush (proposed) |
|---|---|---|
| Needs Google services on device | yes | no |
| Extra app on device | no | yes — a distributor (ntfy) |
| Transport | Google-managed, reliably woken | distributor app's persistent WebSocket; can lag under Doze/aggressive OEM battery managers |
| Content privacy vs provider | Google sees metadata (and FCM payloads are cleartext to Google) | payload end-to-end encrypted; the ntfy server sees ciphertext only |
| Lost-message risk | none: offline messages are stored server-side and caught up at next `hello` | same — push only *alerts*; nothing is ever lost, worst case the notification is late |
| Server dependency | Firebase project + service account | a reachable ntfy server |

The last-but-one row is the design's safety argument: UnifiedPush can only ever delay a
notification, never lose a message, because the gateway's SQLite outbox (see protocol doc,
"Offline messages") is still the source of truth.

## Test plan (when approved, before merge)

1. **Unit**: payload truncation rule; `_UnifiedPushSender` against a local HTTP stub asserting
   ciphertext decrypts to the expected dict (python `cryptography` WebPush helpers); registry
   add/replace/410-drop; forward-compat (old frame shapes ignored).
2. **Device/manual** (needs the real phone): ntfy app as distributor; agent in background →
   message → notification; quick reply from notification; approval buttons; foreground suppression;
   airplane-mode → notification only after reconnect; FCM build unchanged (regression).
3. The plugin side runs in the existing unittest harness (`test_ws_guard.py` pattern) with a stub
   WebPush endpoint — no real Firebase or ntfy needed for CI.

## Open questions for the owner

1. **Self-hosted ntfy as the documented default** — OK? (This puts one more service on the Hermes
   server; ntfy.sh is the zero-setup fallback.)
2. **Per-agent registrations** as designed (more secure) vs one shared registration (simpler,
   marginally less battery/network) — preference?
3. **Settings row** in the Connect screen, or keep UnifiedPush fully automatic (use it only when
   there is no Firebase)? The row costs one screen + strings; automatic-only is invisible but
   harder to debug.
4. **VAPID**: skip for v1 (ntfy doesn't require it) or generate an app VAPID key now so future
   distributors that demand it work from day one?
5. Anything the F-Droid listing would additionally require beyond "no Firebase by default, no
   non-free dependency" (the connector and Tink are Apache-2.0, fine)?
