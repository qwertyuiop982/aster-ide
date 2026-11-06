# Architecture and maintenance guide

## Module boundaries

The launcher lives in `core/launcher` and is mapped to Gradle `:app`. `core/app` owns shared workspace, settings and agent orchestration services. `ide/app` implements the workbench and initial document selection. `agent/app` implements the agent screen and its local WebView assets. `core/termux-runtime` supplies runtime integration; terminal-emulator and terminal-view are mapped to retained Termux sources. Browser, Shizuku and automation integrations live under `client`.

Keep workspace access and permission decisions in shared services rather than duplicating them in WebView callbacks. UI code should request operations through the existing controller boundary. A successful render must never be interpreted as permission to execute a tool.

## Agent data flow

Engine notifications enter the Python bridge, are normalized by engine adapters, and reach Kotlin controllers over bridge connections. `AgentController` maintains bounded message and subagent state. `AgentViewModel` merges flow invalidations and samples state for a JSON page snapshot. The JavaScript UI renders that snapshot and sends structured commands back to the native host.

Sampling reduces redundant snapshot work, not event storage. Message deltas must be applied to controller state before sampling. Never drop deltas merely because the UI cannot draw every event. A terminal event must leave the controller state valid even if it is coalesced with preceding updates.

## Conversation identity

A message ID is only unique inside its conversation. Include session, engine, child identity and role when caching message elements. This is especially important for tool details: reusing an assistant article as a tool element can leave no summary node for the next update. The current renderer scopes records and removes records that are no longer displayed, rather than keeping an unbounded per-session DOM cache.

## Runtime boundaries

Application-managed subagents inherit their parent environment and permissions, but retain independent conversation contexts. Native engine children require separate event discovery and routing. Child completion and cancellation are not equivalent to parent completion. Interactive tool requests must continue to pass through the parent approval route.

The bridge is a privileged boundary relative to the WebView. Validate command names and workspace/session identities. Do not log API keys or embed credentials in generated provenance. Native permission mode can directly modify project files; review mode must preserve its staging semantics.

## Initial document selection

An initial document is a convenience, not a workspace admission requirement. Binary files, invalid encodings, removed files and oversized candidates should not prevent opening a project. `InitialDocument.kt` centralizes selection behavior. Cancellation must propagate instead of being converted into a harmless file-open failure. Test this policy with `scripts/test_binary_startup.py`.

## Generated artifacts

Edit Agent UI source modules, not only `app.bundle.js`. Run `node scripts/build_agent_ui.mjs` and retain its manifest changes. Engine bundles have separate research inputs and provenance; do not assume the UI build refreshes them. Native binary replacements require source/version/hash review independently of JavaScript changes.

## Optimization discipline

Prefer targeted fixes with regression evidence over broad renaming or mechanical rewrites. Preserve public behavior and permission checks. For rendering, avoid invalidations for values a component does not display; for message bodies, compare content rather than count. Measure build task reuse and runtime behavior separately. A smaller diff or successful syntax check is not proof of lower latency, smaller APK size or lower memory consumption.

Future performance work should record baseline and updated measurements: snapshot size, snapshot construction time, streaming render duration, node count after repeated conversation switches, startup time and peak memory. Keep test fixtures free of production credentials and avoid paid model requests in host-only regression tests.
