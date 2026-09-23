---
name: ai-subtitles-smoke
description: >-
  Run NuvioTV AI subtitles emulator smoke (Smart embedded-only ladder +
  Translate with AI MANUAL). Use when the user asks to smoke-test AI
  subtitles, validate the ladder on emulator-5554, or re-run the AI
  subtitle checklist.
---

# AI subtitles — emulator smoke

## Canonical checklist

**Source of truth for cases / expected signals / last results:**
[`docs/smoke-ai-subtitles.md`](../../../docs/smoke-ai-subtitles.md)

Read that doc first. Update its **Last results** table when a smoke session finishes.

## When to use

- User asks to smoke / validate AI subtitles on the emulator
- After ladder/policy changes on `fix/ai-ladder-and-rate-limit` (or successor)
- Before claiming embedded-only Smart + MANUAL addon path are OK

**Not** for product redesign of Embedded-only. **Not** for Series Graph.

## Preconditions

| Item | Value |
|------|--------|
| Branch | usually `fix/ai-ladder-and-rate-limit` (confirm with user/handoff) |
| Package | `com.nuviodebug.com` |
| Emulator | `emulator-5554` (Android TV) |
| Engine | ExoPlayer (AI path) |
| Preferred subtitle lang | **≠ EN** (e.g. Spanish / pt) |
| Settings | Smart AI on + usable API keys |

```bash
./gradlew :app:assembleFullDebug
adb -s emulator-5554 install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk
adb -s emulator-5554 shell am start -n com.nuviodebug.com/com.nuvio.tv.launcher.AppIconDefault
```

Logcat filters: `AI ladder:` · `AI source:` · `Translate with AI` · `SubtitleFocus` · `PlayerViewModel`

## Cases (must cover)

1. **Embedded translatable** → `AI_EMBEDDED` (AI on)
2. **Preferred embedded** → `PREFERRED_EMBEDDED` (AI off)
3. **No embedded** → classic / `CLASSIC_FALLBACK` (AI off; **do not** auto-translate addon)
4. **Translate with AI on addon** → `MANUAL` + `locked=true`

See the doc for setup gotchas (stale same-series addon preference blocks the ladder).

## Diagnostics truth

Treat overlay / logs as runtime truth: `rung`, `reason`, `source`, `locked` (plus `target` / `model` when present).

## If a case fails

1. Stop guessing — collect diagnostics + relevant logcat lines
2. Follow `@ai-subtitles-bug` (rule) + read `docs/architecture-ai-subtitles.md` §3
3. Minimal policy/UI fix only; do not reopen Embedded-only product plan
4. Re-smoke the failed case

## Constraints

- No commit / PR unless the user asks
- Do not put Series Graph changes in the same AI commit
- Do not mix deferred UI-menu bugs unless asked
- After smoke: update **Last results** in `docs/smoke-ai-subtitles.md` (and one line in handoff if status changed)

## Related

- Handoff: `docs/handoff/2026-09-23-ai-subtitles.md`
- Bug rule: `.cursor/rules/ai-subtitles-bug.mdc`
- Docs hub: `docs/README.md`
