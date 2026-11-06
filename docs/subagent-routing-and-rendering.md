# Subagent event routing and rendering

## Scope

Aster has two delegation paths: application-managed subagents (`AgentSubagents.kt`) and native Codex child threads (`agent_codex.py`). Both must converge on `AgentController.subagentEvent`; displaying a tool result alone is not sufficient to expose a child's conversation.

## Application-managed delegation

Each task receives an independent identifier. The controller emits queued and started events, forwards message and tool events from the bridge connection, and emits a terminal result on success, failure, cancellation or timeout. Child tools inherit the parent's permission boundary. A child cannot recursively invoke the application subagent tool. Interactive requests are routed through the parent rather than silently approved.

## Native child discovery

The transport recognizes a parent through either `thread.parentThreadId` or the nested `thread.source.subAgent.thread_spawn.parent_thread_id` representation. Only children of the active parent or an already recognized descendant are registered. Notifications for unrelated threads remain excluded. Unknown source representations must not be treated as permission to attach arbitrary threads.

After registration, a child's message deltas are forwarded as `subagent_event`, preserving its thread identifier. Child turn completion must not mark the parent turn complete. Message start, delta and end events remain separate: deltas append, while a final message replaces the accumulated text.

## UI identity and caching

Message identifiers are conversation-local. The renderer therefore keys DOM records by session, engine, child identifier, message identifier and role. Without this boundary, switching between conversations with identical message IDs can reuse a user, assistant or tool element with the wrong structure and label.

The navigation cache includes the selected child and all visible row properties. Message text is intentionally not part of this cache: rows do not render message bodies. The conversation renderer independently compares full messages, so a delta that keeps the message count unchanged still updates the body. Using message length alone would also miss equal-length replacements.

## Verification

Run `python3 scripts/test_codex_native.py`. The regression suite includes nested-source child discovery, delta forwarding, parent completion isolation, approval decisions, cancellation-related boundaries and native process behavior. Build the shipped WebView asset with `node scripts/build_agent_ui.mjs`; this also regenerates `agent/design/UI-BUNDLE.json` using the pinned esbuild version.

Host tests do not prove Android WebView behavior or compatibility with every server version. Before release, exercise a real delegated task, open the Agent navigator, switch between parent and child while streaming, inspect tools, cancel the parent, and reopen the persisted conversation. Repeat with duplicate message IDs and with two children. Do not claim device verification until these steps have actually been performed.
