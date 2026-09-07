# V1 evaluation checkpoint — 2026-09-07

Baseline: `242ded9`. No remote push was performed.

Canonical report requested outside this Git repository:
[TalkToAI第一版评估报告](/Users/jerry/JustDoIt/OHHHHH/TalkToAI第一版评估报告.md).
The report includes raw metric tables, an SVG comparison chart, official quality references,
limitations, and prioritized remaining work. This checkpoint is not full V1 acceptance.

Evidence is under `artifacts/evaluation/`. Android/shared: 24 passing tests;
backend: 17 passing tests; Android lint: 0 errors, 50 warnings.
Build command: `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug`.
Backend commands: `npm test` and `npm run build` in `backend/functions/talktoai-api`.

The before/after directories contain five debug-AVD cold-start samples and fixed-gesture
gfxinfo samples. Their `commit.txt` identifies the last committed base, not the dirty
working tree used for the after measurements. Subsequent reactive-control and cache
fixes are included in the final APK but not in the five-sample comparison.
Host load was not controlled; do not infer causal performance gains.
An additional final install/start succeeded but took 11292 ms; investigate before acceptance.

Pending external dependencies: authorized real HTTPS market data (health still says
`marketProvider=fixture`) and verified database runtime access for durable quotas.
Local pending work remains, including tail latency, chart semantics, attachment understanding,
accessibility, and input-method stress regression. These are not claimed complete.
