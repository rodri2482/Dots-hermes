<p align="center">
  <img src="assets/hero.png" alt="Claydot" width="100%">
</p>

# Claydot

**An AI companion for Android that can use your phone.** Chat with Claydot like a friend. It can also
play music, send WhatsApp messages and SMS, search the web and the news, read your Gmail (read-only),
run routines on a schedule, and drive the screen of other apps, with a visible cursor and a Stop button.

It runs on a free [Groq](https://console.groq.com/keys) API key, or **fully offline** with a model
downloaded to the phone. No server to install.

> Claydot is a fork of [Dots-hermes](https://github.com/Drevero/Dots-hermes) by Drevero, released under
> the same [GPL-3.0](LICENSE) license. The original app is a messenger for a self-hosted Hermes agent;
> Claydot removed that server part and talks to the model directly.

The app speaks **English** and **French** and follows the phone's language.

---

## Contents

- [Install](#install)
- [Choosing a model](#choosing-a-model)
- [What it can do](#what-it-can-do)
- [Permissions and safety](#permissions-and-safety)
- [Keeping it running (Samsung, Xiaomi…)](#keeping-it-running-samsung-xiaomi)
- [Build from source](#build-from-source)
- [Releases](#releases)
- [Project structure](#project-structure)
- [License](#license)

---

## Install

Download the latest `claydot-*.apk` from
[Releases](https://github.com/rodri2482/Dots-hermes/releases/latest) and open it on the phone (allow
installs from your browser or file manager when Android asks). Android 8.0 or later; 64-bit or 32-bit ARM.

Updates: **Settings → App** shows when a newer release is out and installs it in place. Your chats,
settings and keys are kept.

## Choosing a model

The model selector at the top of the chat switches between:

| | What you need | Good for |
|---|---|---|
| **Groq** (online) | A free API key from [console.groq.com/keys](https://console.groq.com/keys) | Fast, smart answers, long phone tasks, photos, voice notes |
| **On this phone** (offline) | A one-time download (about 0.7 to 2 GB), over Wi-Fi | Privacy, no internet, no quota |

When a Groq model runs out of quota, Claydot moves to another Groq model, and then to the phone model if
one is downloaded, so you are not blocked. The phone model watches battery and free memory, and runs
gently on low battery.

## What it can do

- **Phone control** (after you enable it in Accessibility): open apps, read the screen, tap, type,
  scroll, swipe, take screenshots. An orange cursor shows where it acts; the **Stop** pill ends it.
- **Music**: plays a song or artist in Spotify, YouTube Music, YouTube and others, and taps play.
- **Messages**: SMS and WhatsApp to your contacts; reads WhatsApp chats from notifications and replies,
  even with the phone locked.
- **Routines**: at a time, every N minutes, or when a message arrives in a chat. For example, "every
  day at 8:00, post the attendance of the group". Checked before saving, with **Run now** and templates;
  a live notification shows while one runs.
- **Web**: search, news, read a page, show pictures in the answer.
- **Gmail** (read-only): search and read your mail. See [docs/gmail-setup.md](docs/gmail-setup.md).
- **Memory**: remembers what you ask it to, and forgets on request.
- **Chat**: photos with a caption, voice notes (Groq), reactions, formatted answers with images,
  several conversations, the Activity view with token usage.

## Permissions and safety

Nothing happens behind your back: every new kind of action (sending a message, controlling the screen,
reading notifications…) asks first, in a permission sheet. A routine asks once, when you create it,
for what it will do. System settings, app installs and permission screens are off limits to screen
control, and it never types into password fields. Keys stay on the phone, in encrypted storage.

## Keeping it running (Samsung, Xiaomi…)

For routines and WhatsApp listening to work around the clock, turn on **Settings → Phone → Always on**
and let Claydot ignore battery optimisation. Some brands still stop background apps: the
**guide for your phone** link (from [dontkillmyapp.com](https://dontkillmyapp.com)) shows the settings
to change.

## Build from source

JDK 17 and the Android SDK 36 (Android Studio's bundled JBR works).

```bash
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

A release build needs the signing key (see below); without it, `assembleRelease` stops with a message.

## Releases

GitHub Actions builds, signs and publishes a release when the owner pushes a version tag:

```bash
git tag -a v0.3.0.4 -m "What changed"
git push origin v0.3.0.4
```

- The tag must be `v<appVersionName>.N` (`appVersionName` is in `gradle.properties`); the version code
  is `appVersionCode + N`, so it always grows. The tag message becomes the release notes.
- Once, before the first release, store the signing key as repository secrets by running
  `scripts/set-release-secrets.ps1` yourself (it asks for the passwords and never prints them).
- Keep a backup of the keystore: without it, no later update can install over the app.

The in-app updater reads this repository's latest release.

## Project structure

| Path | What |
|---|---|
| `app/src/main/kotlin/app/dotshermes/data/direct/` | The assistant: Groq and offline clients, tool loop, phone, screen, messaging, web, routines |
| `app/src/main/kotlin/app/dotshermes/screen/` | Accessibility service, cursor and Stop overlay |
| `app/src/main/kotlin/app/dotshermes/ui/` | Jetpack Compose screens |
| `app/src/main/kotlin/app/dotshermes/update/` | In-app updates from GitHub releases |
| `web/` | The browser version (experimental) |
| `docs/` | Gmail setup, owner setup |

Internal names (`app.dotshermes` package, `HermesApp`…) are kept from the original project.

## License

[GPL-3.0](LICENSE), like the original Dots-hermes.
