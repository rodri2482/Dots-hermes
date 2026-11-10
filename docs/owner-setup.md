# Owner setup — from merged code to a public release

Everything in [TASKS.md](../TASKS.md) that agents must not touch, in one place and in the order
that avoids rework. The direct text testing candidate is available privately. Production signing and real-device/live-provider
acceptance remain owner tasks. Pushing code runs checks; it does not publish. The
[release workflow](../.github/workflows/android-release.yml) requires manual dispatch, an explicit
publish checkbox, and all signing secrets. Missing signing credentials stop release packaging.

*English for consistency with TASKS.md; French version on request (as for the other docs).*

## 1. Signing key (do this first)

Generate once, back up forever:

```powershell
# keytool ships with any JDK 17+; on this machine it is:
& "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot\bin\keytool.exe" -genkeypair -v -keystore release.jks -alias claydot -keyalg RSA -keysize 4096 -validity 10000
```

- Answer the prompts: choose a strong password; the "name" fields are yours, the alias is `claydot`.
- **Back up `release.jks` and both passwords** (password manager + one offline copy). The keystore
  is the app's identity: Android refuses to update an installed app from an APK signed with a
  different key, so losing it means every user has to uninstall and reinstall, and the in-app
  updater breaks.
- Keep the file out of the repo — it is not committed; never commit it.

Before adding signing credentials, create **Settings → Environments → claydot-production**.
Require the owner as the deployment reviewer and restrict deployment branches to **main**.
The personal owner dispatches and approves the release; leave self-review allowed when the owner
is the sole reviewer. Keep administrators from bypassing this gate where the repository supports it.

Put the four signing secrets in that protected environment, **not repository-level Actions secrets**.
If protected environment approval is unavailable for this private repository, keep signing keys
local and owner-sign privately; do not upload them as repository secrets. This setup is an unmet
owner release gate until verified in the repository settings.

Required environment secrets:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the `.jks`, base64-encoded |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `claydot` |
| `SIGNING_KEY_PASSWORD` | key password |

Base64 on your machine:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

(The workflow decodes it to a temp file and passes it to Gradle as `SIGNING_KEYSTORE`; without the
secrets release packaging fails. Use `assembleDebug` for private unsigned-credential testing.)

**Done when:** owner approval and main-only deployment rules are verified, and all four signing
secrets are in the protected `claydot-production` environment.

## 2. Private checks, then explicit release

Keep the code private until the owner chooses to publish. After integrating and running checks,
complete phone/live-provider acceptance using owner-entered credentials. Then open **Actions →
Android release → Run workflow**, select **main** after integrating the reviewed candidate, and explicitly enable **publish**.
Only the personal repository owner may dispatch or rerun publication; other actors and refs skip the job.
No agent has dispatched this workflow or pushed source.

- The manually confirmed workflow runs (~10–15 min) and publishes release **Claydot 0.2.0.N** with
  `claydot-0.2.0.N.apk` and its SHA-256; `N` grows with every run, so `versionCode`
  (100 + run number) always increases.
- Check the release body: it must say `Signature: release key`. The workflow cannot publish a debug-signed fallback.
- Optional: a repository **variable** (same page, *Variables* tab) `DEFAULT_GATEWAY_URL` set to
  your gateway's WSS URL pre-fills the pairing screen in the app.
- No push triggers a release; the manual publish checkbox defaults to false.

### Groq for private text testing

Choose **Groq** on the connection page, enter your own key privately on the phone, and use
**Load available models** to select a text model supported by your account. Keys are created in
[Groq Console](https://console.groq.com/keys). No shared developer key is bundled in Claydot.

Open **Activity → Usage** from the chat header for the latest reported request/token allowance,
reset times and cooldown. Empty values mean the provider has not reported them. Quotas are shared
with other apps in your organization; the API key does not tell Claydot whether you have a free
or paid plan. Expired snapshots await the next response rather than displaying an invented balance.
Send and Retry wait for known cooldowns; expiry never sends anything automatically.

### Private Android text acceptance (before publication)

Status on 10 November 2026: the APK is installed on the owner’s phone and layout feedback has
been incorporated. Live-provider text acceptance has not yet been reported. Emulator evidence
and automated tests do not complete this gate. Use the normal private testing APK (`app.claydot`),
not the canned `app.claydot.offline` fixture. The current candidate is debug-signed; a future
owner-signed release may require uninstalling it, which deletes its local accounts/history.

For each provider intended for release (OpenAI, Claude and Groq), enter an API key privately on the phone
and record these results without sharing keys or private conversation content:

- [ ] Save the account and chosen model; a short text receives a streamed answer.
- [ ] Stop a longer reply; partial text remains. Explicit Retry completes without a duplicate prompt.
- [ ] Close and reopen the app; saved history remains and no reply starts by itself.
- [ ] Switch accounts; conversations and unsent drafts stay with the correct account.
- [ ] Disconnect the network and send; a useful error appears. Restore the network and explicitly retry.
- [ ] Keyboard, scrolling and account setup remain responsive in portrait and landscape.

Record phone model, Android version, tested APK version, provider/model, date and pass/fail for each
case. If a failure occurs, record the steps and visible error with sensitive content removed.
An earlier hardware-key focus timeout on the emulator remains unresolved; report any phone freeze.
This text-mode acceptance does not require deploying the optional web app or setting up Hermes.

## 3. Web app on Vercel

Two steps (details in the README, "Host your own copy on Vercel"):

1. Import the repository on Vercel with **Root Directory = `web`** (the Vite preset is detected;
   `web/vercel.json` brings the SPA rewrite and the strict Content-Security-Policy).
2. *Storage → Marketplace → Upstash Redis* (free tier): it injects `KV_REST_API_URL` /
   `KV_REST_API_TOKEN`, which the pairing relay (`web/api/pair.ts`) is the only consumer of.

**Done when:** your URL loads and shows the pairing QR code.

## 4. Point the gateway at your copy

On the Hermes server, re-run the installer with your URL — it keeps the token and settings and
backs up the previous install — then restart the gateway:

```bash
bash server/install.sh --default-web-origin https://your-app.vercel.app --stt groq
```

Enter the Groq key at the installer’s hidden prompt; keep credentials out of shell history.
The final summary must show your origin in `DOTS_WEB_ORIGINS` (add extra origins with repeatable
`--web-origin URL`). Upstream's `dots-hermes.vercel.app` stays allowed as a fallback until you say
otherwise — **give me the URL and I'll flip the README/installer default to it**, per task 1's rule
("keep upstream's until the owner provides one").

**Done when:** pairing works by scanning *your* copy's QR code from the phone.

## 5. Firebase push (optional)

1. [console.firebase.google.com](https://console.firebase.google.com) → new project → add an
   **Android app** with package **`app.claydot`** (no SHA-1 needed; the app only receives
   data-only messages and renders the notifications itself).
2. Download `google-services.json` → base64 (same snippet as §1) → secret `GOOGLE_SERVICES_JSON`.
3. Server side: Firebase project settings → *Service accounts* → generate the Admin SDK JSON key,
   then `bash server/install.sh --fcm-key service-account.json`. It lands at
   `~/.hermes/platforms/dots/fcm-service-account.json` (mode 600). Push is silently off without it.

**Done when:** the next release body says *push enabled*, and a browser message arrives as a
notification with the app swiped away.

## 6. Real-server test — the one CI cannot do

On the real Hermes box and your phone:

- [ ] installer completes; summary shows your web origin and the Groq transcription
- [ ] gateway reachable over HTTPS/WSS through the reverse proxy
- [ ] phone: send a text, a file and a voice note; play a **browser**-recorded note on the phone
- [ ] browser: send a file (picker, drag-and-drop, paste) and record a voice note
- [ ] kill the app → send a message from the browser → notification arrives (needs §5)
- [ ] reload the browser mid-conversation: unsent uploads resend, nothing duplicates

**Done when:** the checklist passes once without surprises.

## 7. Artwork

- `assets/hero.png` and `assets/gallery.png` (referenced by the README's *Screenshots* section),
  plus `assets/screens/` and the demo videos, still show the "Hermes" name. Replace them with real
  phone screenshots after §6 — that is why this step comes last.
- Launcher icon: upstream mascot, drawn as a vector in
  `app/src/main/res/drawable/ic_launcher_foreground.xml` (adaptive icon via
  `mipmap-anydpi-v26/ic_launcher.xml`; there are no legacy PNGs because minSdk is 26). New art in
  the same file and format is the cheapest swap.

## Later, non-blocking

- **R8/minify** (task 7c): an agent can prepare it, but it only merges after an on-device pass
  (§6 covers it).
- **UnifiedPush**: [docs/unifiedpush-design.md](unifiedpush-design.md) awaits answers to its five
  open questions.
