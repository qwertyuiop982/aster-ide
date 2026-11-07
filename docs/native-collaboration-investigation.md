# Native collaboration investigation

## Scope

Investigated Aster's Codex configuration, native thread lifecycle, event adapter, Android child-event forwarding, and production Web navigator/renderer. The user's mini-agent transcript is evidence of the observed behavior, not a project inspected by this investigation. No mini-agent project files were changed.

## Confirmed implementation defects

1. **Ultra lost its native coordination semantics.** The adapter translated Ultra into Max before handing it to native Codex. `core/src/session/multi_agents.rs` selects proactive hints only for `ReasoningEffort::Ultra`. Native `protocol/src/openai_models/reasoning_effort.rs` already resolves Ultra to a provider-supported inference effort. The adapter now preserves Ultra in native settings and turn requests.
2. **Warm resume could ignore changed developer instructions.** Native `resume_running_thread` explicitly preserves loaded subscribed threads and warns that mismatched resume overrides are ignored. The process cache previously hashed command/environment/config.toml but not current request configuration or system/project instructions. The cache now invalidates on those changes, allowing a cold resume with the current contract while preserving the conversation ID/history. User task text alone does not invalidate the cache.
3. **Native role hints could compete with the application contract.** Team/extreme contracts now populate native root/subagent usage hints, a mandatory delegation mode hint, and subagent-specific developer instructions. The child contract does not copy the root-only prohibition on execution.
4. **Parent completion stopped event consumption before active children finished.** The adapter now continues polling while known child turns remain active, subject to existing cancellation/deadline checks. Child closure marks completion.
5. **Native parent tools lacked stable call IDs.** Native start/result events now carry the same native item ID, including spawn_agent calls.
6. **Client-owned children omitted tool_delta forwarding.** Added tool_delta to the forwarding whitelist and aligned unlimited dispatch with the explicit extreme-mode condition.

## Verification

- Real HTTPS request to the user-designated provider's `/v1/responses`, model `gpt-6.1-sol`: returned a `spawn_agent` function call when supplied a minimal orchestration contract and tool schema. No repository contents or credentials other than that provider's own authorization were sent. This is a provider capability probe, NOT an end-to-end native Aster test.
- Python discovery: 101 tests, 100 passed and 1 skipped.
- Native tests capture generated launch overrides and thread/start developerInstructions; process-cache tests use actual local subprocesses to verify policy-change restarts and ordinary-turn reuse.
- Message renderer: 13 cases passed.
- Production navigator + renderer structural-DOM integration: count, child selection, child tool output, late updates, parent return, grandchild selection and session isolation passed. This is not a browser screenshot or Android visual test.
- Debug APK build succeeded in 1m 2s.

## Still unverified / limits

- The reported Android UI has not been visually verified with this build.
- The provider probe does not prove all production requests contain the policy or that a model always follows it.
- The latest known device execution identity is an ordinary untrusted application UID, not a working adb/shizuku installation channel.
- Existing extreme-without-Ultra fallback behavior has not been redesigned in this patch; full extreme coordination requires selecting Ultra as well.
- No GitHub release, website download change or production server restart was performed for this investigation.

## Device acceptance scenario

Install the new build and restart Aster. Select Codex, the configured provider/model, extreme collaboration and Ultra. In a fresh conversation ask for read-only repository discovery. Verify: the parent transcript includes spawn_agent; Agent count becomes nonzero; each selected child displays its own task, tool arguments/output and assistant messages; child events continue after a parent progress report; switching back preserves the parent transcript. Repeat after switching assist -> team within an existing conversation to exercise cold resume. Any missing layer remains an open defect until this device scenario is observed.

No API credential is stored in this report or in source files.
