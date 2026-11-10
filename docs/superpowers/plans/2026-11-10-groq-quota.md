# Groq text, provider allowance and cooldowns

User authorized continuing with Groq plus cooldown/free-quota display in existing Activity → Usage.
Chat UI redesign remains deferred to Claude. Keep native styling and Hermes capabilities.

- Add fixed official `https://api.groq.com/openai/v1` bearer-auth model listing and streaming chat completions.
- Retain encrypted phone-entered keys, no automatic request replay, no paid fallback or embedded key.
- Display provider-reported daily-request/minute-token remaining, total when available, reset times and observation timestamp.
- Never assume a free plan from an API key or hardcode a marketing quota. Unknown fields remain unknown;
  expired snapshots await a fresh provider response. Organization usage may change in other apps.
- Make Activity accessible for direct accounts; reuse its Usage section, hide gateway-only scheduled jobs.
- Enforce both Send and Retry cooldowns; save deadlines and quota observations in encrypted history.
  Scope observations to provider/model/credential fingerprint kept only inside encrypted history.
- Respect Retry-After and exhausted-window reset, use a conservative one-minute wait for an unqualified 429.
  No automatic send when waiting ends. Preserve unsent drafts during cooldown.
- Fail closed in the current repository if durable cooldown save fails. Keep deadline in memory for safe recovery.
  If the process is lost before a failed write can recover, the new deadline cannot be recovered from disk;
  provider-side limits remain authoritative. Do not claim disk-failure/process-loss durability.

Validation: fake credentials only; loopback HTTP tests, encrypted codec tests, deterministic clock/repository
regressions, isolated offline fixture/native checks. No live provider calls or owner credentials read.
Official protocol references: https://console.groq.com/docs/text-chat and https://console.groq.com/docs/rate-limits.
Verification logs and APK/source snapshots are in the private workspace outputs directory.
