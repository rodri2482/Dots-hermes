# Direct text capability and recovery polish

Approved direct text design requires unsupported controls hidden, safe recovery errors and
working text selection/copy. Existing direct reaction methods do nothing but shared bubble
padding long-press opens their emoji picker. Native baseline reproduced the six emoji controls.
Account restore key/storage failure also never reached the connection form.

- [x] Inspect shared gestures and recovery error routing; reproduce unsupported picker on emulator.
- [x] Omit reaction gestures/picker for direct accounts, preserve gateway defaults and text selection.
- [x] Show existing localized key/storage errors on matching provider recovery form; local errors win.
- [x] Android tests/build/lint; native offline long-press copy and recovery-form verification.
- [x] Export candidate and handoff. No real credentials/inference, commit, push or publication.

Managed checkouts were externally removed during compilation twice. Source recovered from the
checksum-verified release-readiness archive into an independent local clone at
C:\Users\Rodri\Documents\Codex\2026-11-09\can\work\claydot-candidate,
branch codex/claydot-candidate, base534fa61. Original repository remains untouched by this work.

## Verification

- Normal APK/test/lint successful in claydot-chat-polish-validation.log:94 tests, 0 failures/errors/
  skips; lint0 errors/45 warnings. Offline APK and release Kotlin compiled successfully in
  claydot-chat-polish-offline.log. Targeted read-only review found no important issue.
- Native old APK reproduced six unsupported reaction items on direct bubble padding long press.
  New APK retains the restored fixture conversation and no longer exposes that picker.
- Native text long press displayed Copy/Select all. Copy then KEYCODE_PASTE inserted exactly the
  selected fixture word into the composer, without sending; process restart cleared the test draft.
- Temporarily moved only the disposable app's encrypted fixture credential. Old UI lacked guidance;
  new UI visibly shows the existing valid-key error. Credential restored afterward.
- Backed up and tampered only the disposable encrypted fixture history. New UI shows safe storage
  failure, no silent reset. Original ciphertext restored; conversation visible again. Restoration
  flags confirmed in external outputs/claydot-fixture-recovery.json.
- Emulator System UI briefly showed its own unresponsive dialog after cold boot; closed that system
  process, then native checks completed. This is not owner phone acceptance.

Open follow-up audit: connection form remember state is not explicitly keyed by account identity.
Check transitions between failed accounts (including deletion/recovery) before calling the full
production goal complete. Owner device/live-provider/signing gates also remain open.
