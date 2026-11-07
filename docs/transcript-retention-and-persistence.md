# Transcript retention and persistence

## Purpose and limits

The UI transcript is a bounded presentation of an agent run. It is not an unlimited archive or the model's complete context. The retention functions live in `core/app/src/main/java/dev/aster/ide/agent/AgentTranscriptBounds.kt`; persistence is handled by `AgentConversationStore.kt`.

## Parent message budget

`boundedAgentMessages` considers the latest 60 messages. Each retained message contributes at most 24,000 characters and the complete result contributes at most 200,000 characters. Messages are considered newest first; a message that cannot fit is skipped, allowing smaller older messages within the candidate window to remain. The final result restores chronological order. Text truncation retains the beginning of an oversized message, matching the existing behavior.

These are Kotlin string lengths, not UTF-8 byte counts or model tokens. Do not use these numbers to estimate token billing. If every message already fits, the function returns the original list. Consumers must treat returned lists and their message records as immutable.

## Child message budget

`boundedSubagentConversations` retains child identities even when their transcripts become empty. It processes children from the end of the supplied list, applies the parent message bounds, then allows at most 100,000 characters per child and 800,000 across children. Priority follows list order; the function does not sort by timestamps. Removing empty children here would change discovery and selection behavior.

A copy is allocated only when a child transcript changes. Existing child objects are reused when possible. This improves snapshot processing without changing the retention policy.

## Persistence and acceptance

Loading an old conversation must preserve nonnegative step values; the former upper clamp of 64 was removed. A successful host compilation does not demonstrate Android storage permissions or process-death recovery. Device acceptance must save a parent with two children, restart the app, reopen the conversation, and check child selection, messages, status and step values.

Run `python3 scripts/test_agent_transcript_bounds.py` for differential checks against the previous retention policy. Its host benchmark measures only the retention function on that host; it does not measure end-to-end Android latency.
