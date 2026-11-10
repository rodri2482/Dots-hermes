# Native direct-mode offline acceptance

The user approved phone-entered credentials and offline development only. Do not enter real API
keys in an emulator, command line, repository or test fixture. No live inference is required here.

Build the isolated fixture with JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug -PdirectApiMock=true --max-workers=2
```

This APK uses package `app.claydot.offline` and version suffix `-offline-fixture`. Its debug-only
provider client returns canned text locally, including model list `offline-text` and `offline-slow`.
Use `fixture-key` for normal responses or `invalid-fixture` for a safe authentication error.
Sending exactly `offline error` produces a retryable unavailable error. `offline-slow` allows Stop
while text streams. These are fixture values, not working API credentials.

Acceptance checklist:

- First account: select Claude, enter fixture key, load models, save, send text.
- Stop a slow reply, retry explicitly, restart, confirm persisted history with no automatic request.
- Add OpenAI; a private unsent draft must not appear in the other account.
- Edit a saved key with the same model; reconnect without losing history.
- Delete an account: explicit irreversible-history warning; Cancel preserves it.
- Rotate and open the keyboard; all fields/buttons remain reachable.
- Inspect app-private files using `run-as` on this disposable fixture only: ciphertext contains no
  fixture key or test prompt; metadata contains only provider/model for direct accounts.

A normal build omits `directApiMock`; it uses package `app.claydot` and actual HTTPS endpoints.
The release source set has no mock implementation. Do not distribute the fixture as a live client.
Production signing and owner device/provider acceptance remain separate unmet release gates.
