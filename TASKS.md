# TASKS.md — road to the first Claydot APK

Read [AGENTS.md](AGENTS.md) first. Do tasks **one at a time, in order**, each on its own branch
(`git switch -c <branch> main` once the current work is merged). For each task: make the change, run the
listed checks, and report what you ran and what you could not run. Don't commit or push unless asked.

## Done (branch `feat/web-voice-files`, not yet merged)

- Web app sends files (picker, drag and drop, paste; caption = typed text; 20 MB cap) and records
  voice notes (WebM/Opus, Ogg/Opus or MP4; live waveform; 5 min cap). Outbox resends after a drop.
- Rename to Claydot: `applicationId "app.claydot"`, app name, update notifications, release files
  `claydot-<version>.apk`, web title and pairing screen, README with fork attribution.
- Gateway: failed-token rate limiting (`guard.py`, plugin 1.4.0), unit + handshake tests.
- Installer: `--stt groq` (Groq Whisper transcription; key read from env or a hidden prompt, never printed).

## Owner only (needs accounts, secrets or a real device)

These need accounts, secrets or hardware an agent must not touch. Agents: don't attempt them, but you
may prepare docs or scripts for them.

- [ ] **Signing key**: `keytool -genkeypair -v -keystore release.jks -alias claydot -keyalg RSA -keysize 4096 -validity 10000`,
      then protected `claydot-production` environment secrets `SIGNING_KEYSTORE_BASE64`,
      `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Require owner approval
      and main-only deployments before adding secrets; do not use repository-level signing secrets.
      If environment approval is unavailable, keep signing local. See [owner setup](docs/owner-setup.md).
      Back up the `.jks` and passwords: losing them breaks every update.
- [ ] **Deploy the web app**: Vercel project with root `web/`, Upstash Redis from the Marketplace. Note the URL.
- [ ] **Firebase** (optional, push notifications): Android app with package `app.claydot`;
      `GOOGLE_SERVICES_JSON` secret (base64); service-account key on the server via `install.sh --fcm-key`.
- [ ] **Real-server test**: install the plugin on the Hermes server (`bash server/install.sh --stt groq`),
      send a file and a browser voice note, play a browser-recorded note on the phone.
- [ ] **Artwork**: README hero/screenshots still show the "Hermes" name; launcher icon is the upstream mascot.

## For Codex / agents

### 1. Point the fork at its own web app
**Why:** `install.sh` always allows `https://dots-hermes.vercel.app` (upstream's site, which lacks the
voice/file features) and prints it as "the" web app.
- `server/install.sh`: make the default origin overridable: `CLAYDOT_WEB_ORIGIN` env var and
  `--default-web-origin URL`; keep upstream's origin as the fallback default until the owner gives a URL.
  The printed summary must show the effective origin.
- README "Optional: web app": explain the override.
- **Checks:** `bash -n server/install.sh`; dry runs with and without the override against a fake
  `HERMES_HOME`/`HERMES_BIN` (see how `hermes` is wrapped in the script) showing the right `DOTS_WEB_ORIGINS`.

### 2. Android: explain HTTP 429 instead of "unreachable"
**Why:** the gateway now answers 429 (+ `Retry-After`) to an address with too many refused tokens.
The app shows a generic "gateway unreachable" and retries on its normal backoff.
- `app/.../data/gateway/GatewayChatRepository.kt` `onFailure`: on 429, show a dedicated reason and wait
  `max(Retry-After, current backoff)` before the next attempt. Never treat 429 like 401 (keep the pairing).
- New strings `gateway_error_rate_limited` in `values/` and `values-fr/`.
- Same idea on the web: `web/src/gateway/GatewayClient.ts` cannot read the status of a failed handshake,
  so leave the web as is and say so in a comment.
- **Checks:** `./gradlew testDebugUnitTest assembleDebug` if the SDK is available; add a unit test if the
  retry-delay computation is extracted into a pure function.

### 3. Web: retry or discard an upload marked "Not sent"
**Why:** a file refused by the gateway stays at the bottom of the thread with a red "Not sent" until reload.
- Clicking the receipt of a failed upload offers *Retry* (only when the failure was a dropped connection
  and the payload is still in memory) and *Remove*. Keep EN/FR strings.
- `GatewayClient`: `retry(key)` / `discard(key)`; revoke the `blob:` URL on discard.
- **Checks:** `npm run typecheck && npm run build`; exercise it against a mock gateway that answers
  `{"type":"error","code":"bad_file","client_id":…}`.

### 4. CI for the web app and the gateway plugin
**Why:** CI only builds the APK on `main`. Web and plugin regressions are caught by nobody.
- New workflow `.github/workflows/checks.yml` on pull requests and pushes to any branch:
  `web`: `npm ci`, `npm run typecheck`, `npm run build`;
  `server`: Python 3.12, `pip install aiohttp`, `python -m unittest discover -s server/tests -v`,
  `bash -n server/install.sh`; `android`: `./gradlew testDebugUnitTest` (JDK 17, no signing, no Firebase).
- Must not publish anything. Pin action versions as the existing workflow does.
- **Checks:** `actionlint` if available; otherwise review YAML carefully and say it was not run.

### 5. Web unit tests
**Why:** the web app has no tests; the new upload logic deserves some.
- Add `vitest` (dev dependency, exact pinned version) and `npm test`.
- Cover: `downsample` (`web/src/voice/recorder.ts`), `sameUpload`/`baseMime` (export them from
  `GatewayClient.ts` or move to a small module), and `buildChatRows` receipts for failed messages.
- Add `npm test` to the checks workflow from task 4.

### 6. More gateway tests
Using the stub harness in `server/tests/test_ws_guard.py`, cover: `voice` with an unsupported MIME
(silently dropped), `voice`/`file` over the size limits (`bad_voice`/`bad_file` with `client_id`),
a duplicate `client_id` (re-ack, handled once), and a refused `Origin` (403, not counted by the guard).
Stub `cache_media_bytes` and `handle_message` as needed; don't change `adapter.py` just for tests unless
the change is a clear improvement.

### 7. Optional, later
- R8/minify for release builds (`isMinifyEnabled = true` + keep rules for OkHttp, org.json, Firebase):
  only with an on-device test pass; report APK size before/after.
- Translate `docs/protocole-gateway.md` and `docs/appairage-web.md` to English (keep the French originals).
- UnifiedPush (ntfy) notifications for builds without Firebase: design doc first, no code until approved.
