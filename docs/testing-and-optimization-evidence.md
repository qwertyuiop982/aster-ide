# Regression strategy and optimization evidence

## Test layers

Aster uses different test layers for different boundaries. They must not be reported as interchangeable:

1. Python unittest suites exercise bridge contracts, provider transport fault handling, installation sequencing, delegated-task isolation, terminal protocols and context behavior.
2. Node structural-DOM tests exercise the actual message renderer with a deterministic DOM implementation. They verify element identity, hierarchy, text and command callbacks, not browser layout or accessibility APIs.
3. Offline Kotlin/JVM probes compile production policy source against minimal fixtures or cached libraries. They are independent of stale Android build intermediates.
4. Gradle Kotlin compilation checks integration with the actual Android module graph.
5. APK inspection and device acceptance cover packaging, signing, permissions and actual WebView/runtime behavior.

## Full Python suite

Use a dedicated environment rather than modifying the workstation's system Python:

```sh
python3 -m venv /path/to/aster-test-venv
/path/to/aster-test-venv/bin/python -m pip install -r services/terminal-mcp/requirements.txt
/path/to/aster-test-venv/bin/python -m unittest discover -s scripts -p 'test_*.py' -v
```

The Terminal MCP suite starts a loopback-only test service with a synthetic token and temporary state. It verifies authentication, protocol behavior, independent sessions, interactive input, restart persistence and the actual Aster MCP client. A missing `mcp` dependency is an environment failure, not a reason to report that integration test passed.

On this workstation the complete discovered suite ran 92 tests with one intentionally skipped wall-clock test. The skip requires `ASTER_LONG_TERMINAL_TEST=1` and must remain visible in reports. JVM probes and Node tests are separate commands and are not included in that count.

## Message renderer regressions

Run `node scripts/test_agent_message_renderer.mjs`. The test bundles production `message-renderer.js` using the project's existing esbuild dependency; it does not download another DOM library. Thirteen scenarios cover initial rendering, unchanged snapshots, equal-length text replacement, parent/child ID collisions, assistant/tool/user role transitions, summary status, ordering, removal, label changes, copy callbacks, empty transcripts, repeated conversation switches and in-place text mutation.

The unchanged-snapshot assertion measures allocations of mock DOM nodes, insertions and rich-text invocations: all remain unchanged. This directly verifies the optimization boundary. It does not measure Android frame rate, CSS behavior or a complete WebView.

## Transcript bounds equivalence

Run `python3 scripts/test_agent_transcript_bounds.py`. It compiles the production `AgentTranscriptBounds.kt`; fixture data classes replace unrelated Android model dependencies. The reference functions preserve the previous implementation. A fixed seed produces 1,000 message cases and 160 child-conversation cases, comparing results structurally. Inputs include empty strings, text around the 24,000-character boundary, more than 60 messages and transcripts exceeding shared limits.

Budgets remain unchanged: 60 messages, at most 24,000 characters per message, 200,000 characters per main transcript, 100,000 per child and 800,000 across children. Child identities remain retained even if their transcripts cannot fit. These are display/persistence memory budgets, not tool-call or engine-context limits.

Unchanged bounded inputs preserve list and message identity. Oversized text is truncated in a copy and input data remains unchanged. For a stable 60-message short-text transcript, one host microbenchmark of 10,000 calls measured approximately 14.5 ms for the reference and 1.93 ms for the optimized function. This is a narrow JVM microbenchmark with order/JIT effects; do not advertise it as application-wide acceleration.

## Android integration

The core, agent and IDE debug Kotlin compile tasks passed after using this ARM workstation's existing aapt2 override. Gradle reported 86 actionable tasks: five executed and 81 up-to-date. The initial offline attempt without that override failed because the Maven aapt2 artifact was not cached. Keep the failure and recovery in the verification record rather than hiding it.

Changes after that integration run require another build. In particular, tests of a pure function do not establish correctness of later persistence edits.

## Remaining acceptance

Run a real child task and switch views while messages stream. Exercise two children, duplicate IDs, failures, cancellation and restored conversations. Inspect native approval routing. Validate the latest packaged notices using `python3 scripts/audit_licenses.py --apk /path/to/current.apk --output /path/to/report.json`. A technical license inventory pass is not legal clearance or confirmation that all corresponding-source obligations are fulfilled.