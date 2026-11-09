# AGENTS.md — Claydot

Context for coding agents (Codex, Claude Code…) working in this repository. Read this first, then
pick work from [TASKS.md](TASKS.md).

## What this is

**Claydot** is a fork of [Drevero/Dots-hermes](https://github.com/Drevero/Dots-hermes) (GPL-3.0):
a messenger for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com) by Nous Research.
Fork: `rodri2482/Dots-hermes`; `upstream` remote points at Drevero's repository.

Three parts, one protocol (JSON frames over one WebSocket, see `docs/protocole-gateway.md`):

| Part | Stack | Path |
|---|---|---|
| Android app | Kotlin, Jetpack Compose, OkHttp, Firebase (optional) | `app/` |
| Gateway plugin | Python, aiohttp, SQLite; runs **inside** Hermes as platform plugin `dots-platform` | `server/hermes-plugin-dots/` |
| Web app | Vite + Preact + TypeScript, static, paired with the phone by QR code | `web/` |

The apps never talk to an AI provider. Hermes does, with keys stored in `~/.hermes/.env` on the server.

## Hard rules

1. **No secrets in the repository or in client code.** API keys (Groq, OpenAI, …), `DOTS_TOKEN`,
   keystores, `google-services.json` and Firebase service-account keys stay out of git and out of
   `app/` and `web/`. Anything bundled into the APK or the web app is public.
2. **Keep the upstream-compatible internals.** User-facing identity is Claydot (`applicationId
   "app.claydot"`, app name, web title, release file names), but these stay as they are:
   - Kotlin namespace and packages `app.dotshermes` (do not move source files),
   - plugin name `dots-platform`, `DOTS_*` env variables, `/dots/ws` route, `~/.hermes/platforms/dots/`,
   - the wire protocol. Additive fields only; never rename or remove one.
   This keeps `git merge upstream/main` workable and the plugin compatible with upstream apps.
3. **"Hermes" in UI text means Nous' agent software** ("Hermes gateway", "Hermes agent", default agent
   name). "Claydot" means this app. Don't replace one with the other blindly.
4. **Every user-facing string exists in English and French**: `app/src/main/res/values/strings.xml`
   and `values-fr/strings.xml`; on the web, the `en`/`fr` objects passed to `strings()`.
5. Code comments and docs inside the code are mostly French: follow the surrounding file's language.
6. Don't commit, push or open PRs unless the user asks. Work on a feature branch, never on `main`
   (every push to `main` that touches the app publishes a GitHub release).

## Commands

```bash
# Web app (Node 20+)
cd web && npm ci && npm run typecheck && npm run build
npm run dev            # http://localhost:5173, with an in-memory pairing relay

# Gateway plugin tests (no Hermes needed; test_ws_guard needs `pip install aiohttp`, else skipped)
python -m unittest discover -s server/tests -v
python -m py_compile server/hermes-plugin-dots/*.py
bash -n server/install.sh

# Android (JDK 17 + Android SDK 36; Android Studio's bundled JBR works)
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Not every machine has the Android SDK. When you cannot run Gradle, say so in your report: never
claim an Android change compiles without having built it. CI (`.github/workflows/android-release.yml`)
builds on push to `main` only.

## Testing without a real Hermes server

- The plugin imports Hermes' `gateway.*` and `hermes_constants` modules. `server/tests/test_ws_guard.py`
  shows how to stub them and drive the real `DotsAdapter._handle_ws` through aiohttp's test server.
  Reuse that harness for new gateway tests.
- For the web client, a mock gateway speaking the protocol is enough: answer `hello` with `welcome`,
  `message`/`file`/`voice` with `ack` (same `client_id`), `file_get`/`audio_get` with the stored data.
  Seed a paired agent in the browser with
  `localStorage["dots.agents.v1"] = JSON.stringify([{url, token, session_id, agent_id, name, species, palette, accessory}])`.
- The Android app has a built-in demo mode (`MockChatRepository`): no gateway needed for UI work.

## Map of the important files

- `web/src/gateway/GatewayClient.ts`: one browser ↔ one gateway: reconnect/backoff, history merge,
  outbox for voice/file frames (resent after a drop, deduplicated by `client_id` on the gateway).
- `web/src/voice/recorder.ts`: MediaRecorder + level sampling, ported from `VoiceRecorder.kt`.
- `web/src/chat/ChatView.tsx`: header, thread, composer (attach, paste, drag-drop, voice recording).
- `app/.../data/gateway/GatewayChatRepository.kt`: Android WebSocket client. A 401 forgets the
  pairing; any other handshake failure retries with backoff.
- `server/hermes-plugin-dots/adapter.py`: the whole gateway side (~1,900 lines).
- `server/hermes-plugin-dots/guard.py`: failed-token rate limiting (10 failures / 10 min → 15 min 429).
- `server/install.sh`: idempotent installer; plugin files are listed in `PLUGIN_FILES`; add any new
  plugin module there and to the README's manual-install `cp` line.
