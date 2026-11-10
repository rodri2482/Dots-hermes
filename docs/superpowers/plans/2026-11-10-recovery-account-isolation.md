# Recovery form account isolation

Approved direct mode isolates credentials and UI state by account. Native baseline used two empty
failed fixture accounts A(OpenAI)/B(Claude), same initial model. Typed an unsaved fake key in A,
confirmed deletion of empty A, and observed metadata selected B while form still showed OpenAI
and retained the masked key. No real credentials or original history were involved.

- [x] Reproduce against prior verified APK; save baseline XML/evidence outside repository.
- [x] Use one nonsecret connection-editor snapshot (session key/account/provider/model/URL). Explicit
      add, cancellation, saved-account selection and active-account deletion create fresh sessions;
      internal gateway pending creation preserves the current session/fields. Remove metadata/navigation
      publishes atomically on Main. Saves reject an outdated editor key before capturing their target.
      Direct saves remain VM-owned and publish only after ready.
- [x] Full Android test/build/lint/release compile and read-only targeted review.
- [x] Repeat identical native two-failed-account deletion transition: B must show Claude, empty key
      and original model; test same-provider transition too. Restore original fixture metadata.
- [x] Export private debug testing candidate/source/checksums. Owner release gates stay open.

Source: independent local clone at C:\Users\Rodri\Documents\Codex\2026-11-09\can\work\claydot-candidate,
branch codex/claydot-candidate, uncommitted base534fa61. No real keys/inference, commits or publication.

Review rejected the first beforePending identity fallback: pending forms could still share a key
with a prior failed account. Replaced it with explicit editor sessions and matching metadata snapshot;
no raw key/draft enters that state. Native validation must use the revised build, not the first build.

Editor callbacks capture one immutable snapshot per composition; outdated callbacks are rejected
by key before selecting an API target. Removal belongs only to the matching saved provider;
selecting a different provider to add cannot offer deletion of the prior saved account.

Targeted review also found legacy `rememberSaveable(adding)` resetting Hermes inputs and stored
selection abandoning a pending id without clearing it. Removed adding from local field keys
(explicit editor sessions own reset), clear/drop pending on saved selection, and guard late gateway
welcome publication by pending id, active id and selection revision. Native checks now include
failed first gateway fields/cancel and open-existing-from-add followed by same-id rekey.

## Final evidence

- Final code compiled/tests/lint/release Kotlin passed in claydot-editor-scope-verified.log:
  94 tests,0 failures/errors/skips; lint0 errors/45 warnings. Latest offline assembly/install passed.
- Native baseline selected failed Claude B after deleting failed OpenAI A but displayed OpenAI and
  retained A’s unsaved key. XML evidence: claydot-form-isolation-baseline.xml outside repository.
- Mixed and same-provider deletion cases now show B’s correct provider, empty key and original model.
- Failed localhost gateway first attempt retained the fully validated URL and masked token after
  pending creation. Changing to API while pending hides prior-account deletion; Cancel clears its
  unsaved draft when returning to the failed saved account.
- Opening a saved account from Add cleared the pending add. Editing its key afterward had no Cancel
  affordance and retained the original id/account count after Save. The original fixture ciphertext
  credential was restored and its SHA256 matched; original two-account metadata restored afterward.
- Targeted independent review confirmed pending cleanup, stale welcome guard and gateway field keys.
- Native helpers first encountered UI-bridge timing issues; one run timed out during injected
  MOVE_END while the emulator was CPU saturated. Trace showed Compose FocusOwnerImpl.focusSearch,
  not a storage operation. Focus-ready idle rerun passed. This is recorded as an unresolved keyboard/
  performance audit; no dependency upgrade or performance-fix claim was made.
- Fast ADB URI injection initially produced a partial URL before connection. Gateway fixture now
  enters in observed chunks and validates the complete field before testing retention.

- [ ] Follow-up: reproduce/assess hardware-key focus ANR independently of emulator/host contention.
Production signing/protected environment and owner device/live-provider acceptance remain open.
