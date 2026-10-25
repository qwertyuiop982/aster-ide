# Standalone Agent UI Modernization

## Scope

The production WebView assets in agent/app/src/main/assets/agent are the source of truth.
The older agent/design prototype is not the application UI.
The native IDE and the original auxiliary Agent remain unchanged.

Codex, Gemini CLI, OpenClaude and Pi share the same controls and responsive layout.
Their colors, engine marks, welcome text and default desktop panel preferences remain distinct.

## Visual Baseline

- Edge-docked, full-width composer with a square outer edge and no floating shadow.
- Primarily 8px controls, messages and panels; 6px file chips and notices.
- Per-engine local vector linework around the existing vendored Lucide marks.
- Short drawer, selection, focus, disclosure and welcome transitions.
- Stable message nodes during streaming; token updates do not restart entry animations.
- Both system reduced-motion preferences and the app animation preferences are respected.
- Narrow layouts use dialogs; short landscape uses a tool rail and compact input.
- At 1080px, only one side panel is pinned; both can be pinned from 1360px.

## Native Boundary

Engine, permission mode and thinking choices use a WebView-owned listbox.
Commands retain the existing token, workspace, engine and session authentication.
Models and credentials, file selection, change review and command approval remain native.
The local browser preview cannot run native operations or contact a model.

## Thinking Controls

Profiles persist thinkingLevel independently for each engine; old profiles default to auto.
Invalid selections and incompatible model/output-budget combinations fail explicitly.

| Engine | Request mapping |
| --- | --- |
| Pi | Python Chat Completions reasoning_effort; auto omits the override |
| Codex | Native process model_reasoning_effort and turn/start effort |
| Gemini CLI | Native thinkingBudget for 2.5, or thinkingLevel for recognized 3.x families |
| OpenClaude | Public SDK thinking control plus scoped effort; adaptive models retain native adaptive behavior |

OpenClaude legacy fixed-budget models and adaptive models are tested separately.
Its provider configuration is applied before SDK initialization and repeated in query scope.
The shared Python policy is deployed with the runtime bridge, not just included in APK assets.
Provider capabilities can be narrower than the exposed protocol; remote models are not probed.

## Validation

- Kotlin and Python read the same policy golden cases.
- JVM tests cover profile persistence, isolation, policy and authenticated web commands.
- Local HTTP fixtures exercise real Pi, Gemini and OpenClaude host requests.
- Codex tests inspect its host configuration and JSON-RPC protocol; they do not run the Android binary.
- Browser fixtures run with the unmodified production CSP and detect manual interference.
- Use node scripts/wait_agent_ui_report.mjs SCENARIO to require current-asset reports.
- Final evidence is recorded in logs/ui-modernization-verification.json.

## Preview And Delivery

Local preview: http://127.0.0.1:8767/?preview=1
Select another engine in the top bar, or add engine=gemini, engine=openclaude or engine=pi.
Visual samples are under /sdcard/Download/Operit/Aster/ui-modern.

This work does not overwrite or certify the previously exported fix3 APKs.
No new APK is exported by this UI-only handoff. Device installation and real-provider acceptance remain pending.
Restricted OpenClaude SDK material remains local-only and is not served by the preview.
