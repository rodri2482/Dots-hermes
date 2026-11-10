# Private release guard implementation plan

**Goal:** Keep private testing usable, while preventing an automatic/debug-signed production release.
**Requirements:** User requested privacy and production readiness; production signing belongs to owner.
No real secrets, keys, commits, pushes, releases or deployments are authorized in this run.

- [x] Recover verified source archive into isolated worktree; preserve original/main state.
- [x] Reproduce existing unsigned-credential signing validation accepting debug fallback.
- [x] Remove release debug-key fallback. Add release signing preflight to APK/AAB packaging and
      explicit verification task; offline compile/test/debug tasks continue without release keys.
- [x] Require explicit manual workflow publication and all signing variables; no push publication.
- [x] Verify credential-free release gate fails with safe error, debug tests/build/lint remain green,
      release Kotlin still compiles offline, and workflow scripts pass syntax review.
- [x] Correct owner/setup docs and record current-state source recovery and remaining owner gates.

The original owner-setup doc has been copied from newer main for preservation, then updated here;
main stays unchanged. Direct text scope is still the approved design; voice/files remain deferred.

## Evidence

- Restored checksum-verified source into managed worktree on codex/claydot-release-readiness.
  248 runtime/test source files exactly match the prior verified archive; The original checkout was clean at recovery. Later external edits to app/build.gradle.kts and a new app/proguard-rules.pro were observed and left untouched.
- Credential-free old validateSigningRelease succeeded (exit0), reproducing debug fallback.
- New verifyReleaseSigning fails with the expected safe missing-signing message. APK/AAB dry-run
  task graphs both include that check; scripts/check_release_guard.py preserves this regression.
- Full restored Android verification passed: 94 tests, zero failures/skips; debug APK, release Kotlin
  and lint successful; lint 0 errors/45 warnings. Log: claydot-restored-release-verification.log.
- Official SHA256-verified actionlint1.7.12 passed both workflows. Workflow Bash blocks syntax-checked;
  missing signing inputs rejected before writing a file, fake complete inputs decoded without leaking
  fixture passwords. No actual signing/release or GitHub configuration was performed.
- Independent review added owner/original-rerun actor + main checks, protected environment reference,
  environment-only signing secrets instructions and hidden Groq-key prompt example.
- Actual protected environment configuration, owner real-device/provider acceptance and production
  signing/distribution remain unmet external gates, recorded in release-readiness-audit.md.
