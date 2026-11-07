# Agent message rendering contract

## Data ownership

The renderer in `agent/app/src/main/assets/agent/message-renderer.js` receives a container, a bridge request callback and display messages. It owns only the message rows it creates. The caller selects the active conversation and child and supplies a scope that distinguishes those selections. Message IDs alone cannot identify DOM nodes because different threads can emit the same ID.

A render key combines scope, message ID and role. Role participates because a tool message uses a `details` element while user and assistant messages use articles. A role transition therefore creates the appropriate structure instead of retaining an incompatible row. IDs are expected to be unique within a scope and role.

## Incremental updates

Each retained record stores its last rendered text. A repeated snapshot with unchanged text skips rich-text rendering. Changes to labels update existing assistant labels independently. Text comparison also detects in-place mutation of a message object; callers are not required to allocate a new object to refresh the view.

The renderer walks messages in display order, moves rows only when their predecessor differs, and removes records absent from the current snapshot. Switching scopes removes previous transcript rows. This bounds retained nodes to the active transcript rather than accumulating a cache for every visited child.

The returned boolean reports whether message DOM or scope changed. It does not report network activity, model completion or changes elsewhere in the page. Callers should use it only for relevant view work such as scroll decisions.

## Tool and copy behavior

Tool and system messages use the first line for their summary, limited to 110 characters. The existing localized failure marker controls the failed dataset flag. User messages use `textContent`; other bodies use the shared rich-text renderer. Copy actions call the bridge with a structured `copy` request and a text argument.

## Verification and remaining acceptance

`node scripts/test_agent_message_renderer.mjs` exercises unchanged snapshots, same-length replacements, role transitions, identical IDs across child scopes, summaries, order changes, label refresh, copy arguments, empty transcripts, repeated scope switching and in-place text updates. The test injects a DOM implementation and does not establish Android WebView compatibility or CSS layout.

On a device, check both short and long streamed answers, expanded tool rows, parent/child switching and selection after reopening a saved conversation. A successful generated bundle hash check establishes asset consistency; it does not replace those interactions.