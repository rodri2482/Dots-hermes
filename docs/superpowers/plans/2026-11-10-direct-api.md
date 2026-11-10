# Claydot Direct API Implementation Plan

> **For agentic workers:** Execute inline with the executing-plans skill, test-first, then an independent whole-change review. Do not commit, push, deploy or make paid API requests.

**Goal:** Ship the user-approved native text-first direct API mode alongside Hermes.
**Architecture:** Provider-specific HTTP/SSE adapters feed a ChatRepository implementation. Keys and
history use Keystore-backed encryption; only nonsecret provider/model metadata enters AgentStore.
Generalize the agent session registry and use explicit capabilities to keep unsupported controls hidden.
**Tech stack:** Kotlin, Compose, coroutines, existing OkHttp, Android Keystore, JUnit and offline mock HTTP.
**Spec:** ../specs/2026-11-10-direct-api-design.md (approved by user).

## Global constraints

- Native Android; preserve mascots, namespace and all existing gateway pairings/protocol.
- OpenAI and Anthropic; offline testing only, credentials entered on phone. Never create/read real keys here.
- No developer secrets in app resources, saved UI state, logs, analytics, exports or plaintext stores.
- All visible copy EN/FR. No automatic paid request replay. Stop cancels the actual HTTP call.
- Existing hardening changes remain part of the candidate and all suites must stay green.

## Review focus

- Split SSE lines/UTF-8, error events and EOF without a terminal event must preserve partial output.
- A cancelled or superseded response cannot mutate another account/conversation.
- Ciphertext corruption, swapped account records or missing keys must fail closed without erasing history.
- Restarted accounts preserve history; direct accounts never receive gateway push/pairing tokens.
- A wrong model/key, 429 or quota failure creates no invisible retry and exposes no provider error body.

## 1. Provider transport and stream decoding

Files: `data/direct/ApiProvider.kt`, `ProviderClient.kt`, `ProviderProtocol.kt`, matching unit tests.
Interfaces: ApiProvider (OpenAI/Anthropic, fixed HTTPS endpoint), ApiTurn(role,text), ApiFailure(code,
retryAfterSeconds), ProviderClient.models(provider,key) and stream(provider,key,model,turns,onDelta).
Cancellation must cancel its OkHttp Call; complete only on a real protocol terminal event.

- [x] Add offline request/SSE tests first: correct auth headers and provider body; OpenAI store=false;
  delta chunks combine to "Hello"; a 401 body containing a fake key never enters an exception;
  429 is surfaced once; a stream ending after "Hel" fails as interrupted; cancellation closes call.
- [x] Run tests and inspect expected failures, then implement adapters and run the suite.
- [x] Models come from API responses, with model ID entry supported; no assumed paid model default.

## 2. Encrypted key and history storage

Files: `data/direct/EncryptedRecords.kt`, `AndroidApiVault.kt`, tests.
Interfaces: per-account secret read/write/remove; history read/write of local text turns. Crypto core
accepts a SecretKey source for JVM tests; Android implementation uses a per-account Keystore key.
Use authenticated AES-GCM records with unique nonces, account/record associated data, atomic writes.

- [x] Test round-trip, tampering, cross-account/record substitution, failed atomic save retaining the
  earlier record, and absence of fake plaintext keys/prompts in stored bytes; observe failures first.
- [x] Implement fail-closed storage and distinguish missing data from decryption/IO failure.

## 3. Direct chat repository and account registry

Files: `DirectChatRepository.kt`, `AgentStore.kt`, `AgentRegistry.kt`, `ChatRepository.kt`, routing,
gateway-only web-pair/quick-reply callers, tests.
Interfaces: backward-compatible AgentLink metadata adds provider/model; vault contains API key.
ChatRepository adds capability and safe request-failure/retry state; gateway defaults preserve behavior.

- [x] Test streamed text, explicit retry without duplicate user turn, Stop, restored history, two
  independent accounts, ignored old deltas after cancellation, and encrypted-store failure.
- [x] Filter direct accounts from push routing and browser pairing. Test mixed legacy/direct stores.
- [x] Generalize registry to ChatRepository with explicit nullable gateway access for Hermes-only seams.

## 4. Compose connection and chat controls

Files: ConnectScreen, HermesViewModel/HermesApp, ChatScreen/AgentSheet/AgentMenu, EN/FR strings.

- [x] Provider choice, masked non-saveable key entry, model loading/entry, encrypted account save and
  removal. Preserve existing mascot/agent selection; keep gateway connection available.
- [x] Render streaming text and safe errors with explicit Retry/Stop. Hide voice/files/activity,
  sub-dots, browser pairing and remote-tool controls when unavailable for direct accounts.
- [ ] Verify the actual native flow against a local mock provider in an emulator, including keyboard,
  rotation, account switching, cancellation and restart. Do not treat JVM tests as UI acceptance.

## 5. Whole-candidate verification and handoff

- [x] Full Android `testDebugUnitTest assembleDebug lintDebug`; web npm test/typecheck/build/audit;
  server tests + Bash syntax. Read actual reports and count failures/skips.
- [x] Independent review, fix important findings and rerun affected checks.
- [x] Export a clearly labeled debug testing APK, checksums, source diff and accurate release gate note.
  Production signing and owner live-device/provider acceptance remain explicit unmet gates.

## Progress

- User approved text-first scope and phone-entered keys with offline testing.
- Initial builder check complete; no material unanswered design questions.
- Task 1 transport implemented. Protocol and local mock HTTP tests pass, including a regression
  for OkHttp's implicit 503/Retry-After:0 replay; redirects and automatic retries disabled.
- Task 2 encrypted records and Android Keystore key adapter implemented. Nine crypto tests cover
  round-trip, unique nonces, tampering, swapped account/record, failed save, missing key and removal.
  A key-loss overwrite regression first failed; fix refuses key creation when encrypted files exist.
  Latest full Android test run is `outputs/claydot-key-loss-green.log` outside the repository.
- Typed encrypted account/history codec implemented and tested (`DirectAccountStorage.kt`,
  `AndroidApiVault.kt`, `EncryptedAccountStorageTest.kt`). Round trips full turns/retry state,
  rejects unknown versions and invalid roles, separates credentials from history, clears temporary
  plaintext byte buffers. Full unit suite passed in `outputs/claydot-account-codec-green.log`.
- DirectChatRepository implemented; tests cover restored streamed replies without inference,
  explicit retry replacing a failed answer, Stop retaining partial output/ignoring late callbacks,
  and initial storage failure preventing inference. Full suite passed in
  `outputs/claydot-direct-repo-green.log` outside repository.
- Registry/provider metadata, gateway-only seams and capability-aware native UI are implemented.
  Direct account creation/edit/removal, explicit deletion confirmation, safe errors, Stop/retry,
  model selection and account-scoped drafts are integrated. Native acceptance remains pending.
- Independent review found account identity/lifecycle bugs. Fixes publish repository replacements,
  snapshot save targets and IO actions, serialize credential writes with rollback, preserve only
  completed turn pairs in subsequent provider context, and show older failed-message receipts.
- Debug source set has an explicit `-PdirectApiMock=true` offline fixture, isolated as package
  `app.claydot.offline`; release source set always creates the official HTTPS provider client.
  It accepts fixture credentials only for canned responses and makes no provider requests.
- Next: verify latest native integration, rerun full checks, export candidate and record unmet gates.
- Review focus during integration: repository currently persists each delta synchronously under
  its state lock; move potentially slow Stop persistence off the UI thread or provide an asynchronous
  ordered write path without allowing an old callback to overwrite a newer generation.

## Native acceptance evidence (2026-11-10)

- Fresh API 35 emulator installed debug-only offline fixture; no real keys/provider calls.
- First account Claude, masked fixture key + model ID, save, text reply: observed in UI.
- Process restart/update retained encrypted conversation; same-model key edit reconnected and
  retained history. Fixed provider default name restoration found in this check.
- Slow response showed Typing/Stop; Stop preserved partial text and explicit Retry completed it
  without adding a duplicate user prompt.
- Added OpenAI using loaded offline model list. An unsent OpenAI draft was absent after switching
  to Claude; each account retained its own conversation.
- Delete from recovery form displayed explicit permanent-key/history warning; Cancel retained
  both account history records. No destructive owner data was used.
- App-private fixture credential/history files contained none of the fake keys or test text in
  plaintext. Source unit crypto tests additionally validate tampering and cross-account swaps.
- Found direct-only agent menu still showing browser pairing. Capability gate and EN/FR account
  help/status copy corrected. Final normal candidate rebuilt with full unit/lint/release compilation checks.
- Portrait keyboard exercised for key/model/chat entry; landscape scrolling reached every form field
  and Save/Delete actions. Real-device behavior and live
  provider acceptance remain separate checks; do not claim production acceptance from this run.

- Final candidate verification: 94 Android tests (0 failures/skips), debug assembly, release Kotlin
  compilation and lint (0 errors, 45 warnings), plus 25 web and 36 gateway tests pass. Latest normal
  log is `outputs/claydot-v001-candidate.log` outside the repository.
- Final offline build reinstalled successfully. Restored Claude name, Ready status and saved reply
  observed; direct-only account menu browser pairing hidden and updated help text observed.
- Private delivery artifacts and SHA256 manifest are in the session outputs directory, including
  live-API debug testing APK, isolated offline fixture, source bundle and testing notes.
- Native acceptance is emulator-only. Owner device/live-provider acceptance and production signing
  remain open; the production-readiness goal is not complete.
