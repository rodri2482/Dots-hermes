# AGENTS.md — Claydot

Context for coding agents (Codex, Claude Code…) working in this repository. Read this first, then
pick work from [TASKS.md](TASKS.md).

## What this is

**Claydot** is an Android AI companion that can use the phone. It started as a fork of
[Drevero/Dots-hermes](https://github.com/Drevero/Dots-hermes) (GPL-3.0), a messenger for a self-hosted
Hermes agent; Claydot removed the Hermes server side and the gateway client. The app now talks to a
model directly: a Groq API key entered on the phone, or an offline LiteRT-LM model downloaded to it.
Fork: `rodri2482/Dots-hermes`; `upstream` remote points at Drevero's repository.

| Part | Stack | Path |
|---|---|---|
| Android app | Kotlin, Jetpack Compose, OkHttp, LiteRT-LM, WorkManager | `app/` |
| Web app (experimental, not maintained with the app) | Vite + Preact + TypeScript | `web/` |

Direct account keys and history use Keystore-backed encryption in `noBackupFilesDir`; metadata holds
no API key. No real provider calls or developer credentials are authorized for testing.

## Hard rules

1. **No secrets in the repository or in client code.** API keys, keystores, signing passwords and
   `google-services.json` stay out of git. Anything bundled into the APK is public. User-entered API
   keys exist only in the encrypted on-device vault. Release signing lives in GitHub secrets
   (`SIGNING_*`), set by the owner with `scripts/set-release-secrets.ps1`.
2. **Keep the internal names.** User-facing identity is Claydot (`applicationId "app.claydot"`, app
   name, release file names), but the Kotlin namespace and packages stay `app.dotshermes` (do not move
   source files) and class names like `HermesApp` stay.
3. **Every user-facing string exists in English and French**: `app/src/main/res/values/strings.xml`
   and `values-fr/strings.xml`.
4. Code comments and docs inside the code are mostly French: follow the surrounding file's language.
5. Don't commit, push or open PRs unless the user asks. Work on a feature branch, never on `main`.
   Pushing a `v*` tag publishes a signed public release: only when the owner says so.
6. One Claydot install on the owner's phone: never install a second package beside it.

## Commands

```bash
# Android (JDK 17 + Android SDK 36; Android Studio's bundled JBR works)
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
python3 scripts/check_release_guard.py   # release build must refuse to run without signing

# Web app (Node 22.12+)
cd web && npm ci && npm run typecheck && npm test && npm run build
```

Not every machine has the Android SDK. When you cannot run Gradle, say so in your report: never
claim an Android change compiles without having built it. `checks.yml` runs on every push and PR;
`android-release.yml` runs on `v*` tags (see the README's Releases section).

## Testing

- Unit tests use fake `ProviderClient`s (see `RateLimitTest`): no network, no key.
- For UI work without a network, build the offline fixture (`-PdirectApiMock=true`, package
  `app.claydot.offline`) and connect a Groq account with any key. Prefer the emulator to a real phone.

## Map of the important files

All under `app/src/main/kotlin/app/dotshermes/`.

- `data/direct/DirectChatRepository.kt`: the conversation and the tool loop: model switching on quota
  or refused requests, offline fallback, system prompt, screenshots in replies.
- `data/direct/LocalModels.kt`: offline model catalogue, downloads, LiteRT-LM client, `DeviceGuard`
  (battery and memory checks).
- `data/direct/ScreenTools.kt`, `screen/`: phone control through the accessibility service.
- `data/direct/PhoneActions.kt`, `MessagingTools.kt`, `WebTools.kt`, `GmailTools`: the other tools.
- `data/direct/Routines.kt`, `ChatWatch.kt`, `AlwaysOn.kt`: routines (time, every N minutes, on
  message), WhatsApp notification listening, the always-on service.
- `data/AgentRegistry.kt`: accounts (Groq, phone model) and the one on screen.
- `update/`: in-app updates from this repository's GitHub releases.
