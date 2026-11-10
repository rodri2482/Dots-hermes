# Release-readiness audit — 10 November 2026

The full production goal is **not complete**. Do not rename the private debug candidate a production
release. This table distinguishes code/testing evidence from owner acceptance and external setup.

| Requirement | Evidence/status |
|---|---|
| Native Android, existing mascots, Hermes compatibility | Kotlin/Compose app, approved direct-mode design, native emulator screenshots and API 35 acceptance recorded in direct-api plan |
| Direct OpenAI/Claude text, phone-entered keys | Provider adapters, encrypted vault, account UI; 94 Android tests; native offline model/setup/chat exercised |
| Streaming/Stop/retry/history/account isolation | Offline transport/repository tests; native Stop/retry/restart/rekey/two-account draft checks in direct-api plan |
| No real development credentials/paid inference | Fake-key mock HTTP and isolated debug source-set fixture; no live provider tests performed |
| Local data privacy | AES-GCM/Keystore storage tests, noBackupFilesDir, backup disabled; fake credential/prompt plaintext absent in native fixture files |
| Existing gateway/web hardening | Previous verified candidate: 36 gateway and 25 web tests, web typecheck/build and production audit; runtime/test sources restored byte-for-byte from checksum-verified archive |
| Private release process | New workflow only manual, publish default false, main ref and original/rerun actors restricted to personal repository owner; environment reference claydot-production |
| Owner-only stable signing | Gradle debug fallback removed; missing signing preflight fails; APK/AAB graphs include gate; actual owner key remains unconfigured/unverified |
| Workflow verification | Official SHA256-verified actionlint1.7.12 passed both workflows; Bash syntax and missing/complete fake signing-input behavior checked without signing |
| Current restored source build | Passed:94 tests/0 failures/skips, assembleDebug, compileReleaseKotlin and lintDebug; lint0 errors/45 warnings. External log: claydot-restored-release-verification.log |
| Private owner live-device/provider acceptance | **Open**, explicitly deferred until owner enters credentials privately on phone |
| Protected production environment | **Open**, owner must configure required approval, main-only deployments, environment signing secrets; remove any repository-level signing secrets |
| Production signing/distribution | **Open**, no keys generated/read, GitHub changes, push, merge, publication or deployment performed |
| Direct voice/files/subscription login | Deferred outside approved text-first delivery; Hermes capabilities retained |

Source recovery: former production-validation checkout was externally removed. New managed checkout:
`C:\Users\Rodri\.codex\worktrees\claydot-release-readiness\Dots-hermes`, branch
`codex/claydot-release-readiness`, base534fa61, uncommitted. The original checkout was clean at recovery. Later external edits to app/build.gradle.kts and a new app/proguard-rules.pro were observed and left untouched.
The original source ZIP/APKs and checksum manifest remain untouched as the recovery point.

## Editor isolation continuation

Current authoritative source is the independent local clone
`C:\Users\Rodri\Documents\Codex\2026-11-09\can\work\claydot-candidate` on
`codex/claydot-candidate` (uncommitted). Managed checkouts listed above were externally removed.

Connection editor now uses a nonsecret session/provider/model/account snapshot. Native mixed/same
failed-account deletion, failed gateway field retention, pending cancel, opening a saved account
from Add and same-id rekey all passed. Original offline fixture credentials/metadata restored.
Final94 Android tests/0 failures/skips, lint0errors/45warnings, debug/release compilation passed.

**Additional open audit:** one injected hardware-End-key input timeout in Compose focus search on
a CPU-saturated emulator; subsequent focus-ready idle case passed. Do not claim this performance
issue fixed or promote the candidate to production solely on those passes.


## Keyboard investigation and owner acceptance update

Owner explicitly confirmed that the private APK has not yet been tested on their phone.
Live OpenAI/Claude acceptance and physical-device performance remain open.

The earlier emulator input-dispatch ANR sampled Compose 1.7.6 focus search while processing
KEYCODE_MOVE_END under high CPU pressure. Seven subsequent controlled input cases produced no
new ANR: three focused, three rapid touch-to-hardware-key transitions, and one with attempted
bounded background CPU load. Input-command roundtrips ranged from 0.199 to 0.936 seconds;
these are not frame latency measurements. The load jobs were launched but their sustained
activity was not independently verified, so this is not a validated CPU stress pass.

Cumulative software-rendered emulator graphics stats reported 1195 janky frames of 4622
(25.85%). These include earlier activity and are not an isolated release benchmark.
No dependency or product-code change was made based on this investigation. Root cause remains
unproven and the timeout is NOT claimed fixed. Original offline fixture metadata was restored.
Evidence: outputs/claydot-focus-results.json, claydot-keyboard-focus-repro.log,
claydot-focus-gfxinfo.txt and claydot-focus-fixture.json in the private local workspace.
