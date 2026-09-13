# TalkToAI UI redesign — 2026-09-14

## Design assets

Generated using built-in ImageGen (no CLI fallback). The tool does not expose a selectable model ID, so these files are not claimed to be a verified `gpt-image-2` run.

- `talktoai-three-screen-prototype.png`: three-screen visual reference, 1475×1067.
- `assistant-orb-v2.png`: transparent blue glass assistant ornament, 1254×1254, alpha verified.
- `query-empty-v2.png`: transparent document/magnifier empty-state illustration, 1254×1254, alpha verified.

Generation brief summary: create a blue/white Chinese mobile AI market assistant with three screens (chat welcome, compact market dashboard, individual query empty state); readable Chinese hierarchy, rounded cards, four suggestion shortcuts, bottom composer, explicit simulated-data labels. Generate the blue glass assistant and document/magnifier separately on transparent backgrounds without text or background plates. Ordinary icons and interactive UI remain code-native.

Generated numbers and dates are layout examples, not a source of market facts. Runtime data still comes from the existing adapters; empty data never draws a fake K-line.

Figma: https://www.figma.com/design/LGsYxAlz8vbbuvTLjc3952?node-id=5-5

Figma status: the reference and both transparent PNG layers were imported and verified in the layer tree. The requested three-screen editable reconstruction was interrupted by the Starter-plan message “You’re out of daily credits for this beta feature”. Full editable assembly is NOT verified complete. No plan upgrade or purchase was made. Resume assembly when credits are available, or assemble the existing imported layers manually.

## Ownership and integration

- TalkToAI owns chat welcome, suggestion cards, input placeholder, query form and unified request-state illustration.
- `kuikly-market-ui` owns shared metric summaries: units now sit in the heading and breadth counts omit `.00`.
- The default Gradle composite build consumes the `market-ui` submodule; there is no copied app-side implementation of these components.
- SDK changes are committed and pushed as `f8853f99dbc92c3b0b9fe7031c0d93c5516fb2bf` on `codex/ui-polish-20260914`. The app pins that exact commit. Fresh checkouts use `git submodule update --init --recursive`; no sibling development directory is required.
- Runtime image files live in `shared/src/commonMain/assets/talk_to_ai/`, matching the `talk_to_ai` page used by `ImageUri.pageAssets`.

## Verification

- App: `:shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug` passed (`../build.log`).
- SDK: `testDebugUnitTest apiCheck` passed (`../sdk-tests.log`).
- Rebuilt after correcting suggestion caption clipping (`../rebuild.log`).
- Installed on emulator-5554 only; inspected `../home-after.png`, `../market-after.png`, `../detail-after.png`.
- Before screenshots are retained alongside the after screenshots. These after images are Android runtime captures, not generated mockups.

No claim of full pixel-level design parity, live-market-provider validation, or full large-font/dark-mode regression coverage is made for this round.
