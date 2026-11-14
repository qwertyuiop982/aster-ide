# Agent engine fix validation status

The seven requested feature changes are present in the working source. Validation of the latest source is pending: the editing session has file access but no working command execution tool. The previously built `Aster-1.0.5-agent-fix-candidate.apk` predates the latest lifecycle and transport changes and cannot validate them. No new APK or successful test result is recorded here.

## Latest changes requiring verification

- Pi and Gemini send `result` followed by `idle` only after the turn and host requests settle; Python waits for `idle` before making a resident process available again.
- Engine shutdown reserves its scope before waiting for active operations. A generation counter invalidates already queued operations even when they resume after shutdown finishes. Admission uses a separate condition lock so disposing a process cannot delay shutdown rejection.
- `AgentController.run` holds the same lifecycle mutex as workspace, conversation, engine selection and prewarming, including startup and cleanup. It rechecks the workspace after acquiring the mutex. Runtime configuration is constructed before setting the job to running; the running state and subagent assignment are inside the existing error/cleanup block.
- Free Pi prewarming uses the free profile while retaining the project's MCP configuration.
- Codex prewarming initializes app-server without creating or persisting an empty conversation thread. The first real turn creates its thread.
- Deleting a conversation closes its runtime session ID. Gemini saves the session pointer before disposing its native configuration.
- The Gemini manual thinking hint describes the submitted override even when the model name is a custom alias. The settings page now derives its hint from the current edits, including thinking mode, level and budget.
- The web protocol accepts a custom thinking level only when Gemini's saved mode is manual, using the same identifier validation as the native settings page. Level selection preserves manual mode; choosing auto or off still selects the corresponding built-in mode.
- Detaching a workspace no longer checks its own action's busy flag a second time. The shared action entry point still rejects changes while another task is running.

## Unified verification and build

`scripts/validate_agent_engine_fix.py` runs the regressions below, rebuilds Pi, Gemini and the UI, then invokes `:agent:app:testReleaseUnitTest`, Android release lint and assembly through `./gradlew`. It checks Node 22.19+ before the integration suite, stops at the first failing command, and records each stage's exit code and log under a new `logs/agent-engine-fix-<UTC timestamp>/` directory. The `result.json` report records toolchain paths once configured; bundle and APK hashes are added only after every command succeeds. Signing and device validation remain separate pending steps.

On the retained Android/proot host, with pinned Node dependencies installed, run from the repository root:

```sh
python3 -B scripts/validate_agent_engine_fix.py \
  --java-home /usr/lib/jvm/java-17-openjdk-arm64 \
  --android-sdk /root/Android \
  --aapt2 /root/Android/build-tools/35.0.0/aapt2 \
  --workers 4
```

Other hosts can use `--java-home`, `--android-sdk` and `--aapt2` for their own paths, or omit those options when the environment already supplies a compatible toolchain. `--java-home` sets both `JAVA_HOME` and the child processes' Java path; it does not rely on interactive shell initialization. The retained workspace's ignored `local.properties` now points to `/root/Android`. The Gradle wrapper continues to select Gradle 8.13. The runner itself has not been executed in this editing session; its presence is not a test or build result.

## Local regressions

`test_engine_lifecycle.py` contains 14 test methods: six lifecycle synchronization cases and eight transport integration cases. It launches a local fixture engine through the production transport and process manager, without provider credentials. The integration cases require Node.js 22.19+ and are skipped when that runtime is missing; skipped integration cases are not a successful verification of resident engines.

Run from the repository root:

```sh
python3 -B -m unittest discover -s scripts -p 'test_engine_lifecycle.py'
python3 -B -m unittest discover -s scripts -p 'test_agent_thinking.py'
python3 -B -m unittest discover -s scripts -p 'test_codex_native.py'
python3 -B -m unittest discover -s scripts -p 'test_codex_recovery.py'
python3 -B -m unittest discover -s scripts -p 'test_pi_transport.py'
python3 -B -m unittest discover -s scripts -p 'test_subagent_runtime.py'
node --test scripts/test_gemini_native_paths.mjs
node scripts/test_gemini_subagents.mjs
```

The lifecycle tests cover queued warmups that wake only after shutdown completes, shutdown admission during process disposal, timeout/cancellation cleanup, warmup and consecutive turn PID reuse, configuration changes, engine/conversation isolation, disposal, and invalid `result`/`idle` ordering. The Codex regression additionally covers prewarming followed immediately by the first real reply.

`test_agent_thinking.py` adds ten credential-free policy cases for native Gemini and OpenAI manual overrides, custom model aliases and levels, length and character validation, engine restrictions, and budget/strength exclusivity. `test_gemini_native_paths.mjs` adds four adapter cases for native cross-directory access, scoped contexts, malformed paths, and retention of project-rooted directory enumeration. Those adapter cases use a fixture Config; they do not replace the upstream Gemini bundle build or Android filesystem checks. Both new test files are unexecuted in this session.

`AgentThinkingProtocolTest.kt` adds seven JVM test methods that call the production web protocol and thinking policy. They cover the full request-to-profile path for native Gemini and OpenAI aliases, saved-mode gating, invalid identifiers, stale session/engine/workspace rejection, unexpected fields, returning to auto mode, and built-in Codex/Gemini modes. The tests use JUnit 4.13.2 and the test-only JSON-java implementation 20240303 so JSONObject is functional on the JVM. These tests are included in the unified wrapper command and remain unexecuted in this session.

## Rebuild assets before Android compilation

The checked-in engine bundles contain copies of the shared transport. Regenerate both Pi and Gemini assets before building an APK; otherwise Python may wait for `idle` while an older JavaScript bundle never sends it. The Agent UI bundle also needs regeneration for the model selection menu.

With the pinned Node dependencies installed:

```sh
python3 -B scripts/bundle_agent.py --no-install
node research/gemini-agent/build.mjs
node scripts/build_agent_ui.mjs
./gradlew :agent:app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon --max-workers=4 --no-configuration-cache --console=plain
```

Use the existing Android SDK, NDK and host-compatible AAPT2 setup described in `DEVELOPMENT.md`. On the retained Android/proot build host, the existing build wrapper is `/tmp/aster_release_build_v105.py`; its Gradle invocation supplies the local AAPT2 override. Dependency installation and signing follow the existing project scripts and environment; no signing credentials belong in this document.

After successful compilation, sign the new APK with the existing release identity and record its actual file path and SHA-256. Keep the prior candidate distinct from the rebuilt artifact.

## Device checks still required

1. Bind a new project using Codex, allow local prewarming to finish, and send the first message without creating another conversation. Confirm one actual model reply appears and the next message continues the same conversation.
2. Send two consecutive messages using Pi and Gemini. Confirm the engine PID stays the same until configuration, workspace, or engine changes require a restart.
3. Switch engines after warmup and after a completed turn. Confirm the previous engine and its shell/MCP descendants exit before the new engine starts; repeat while an old prewarm is queued.
4. In native Gemini mode, read and edit an Android-accessible path outside the bound project and exercise a shell command there. Confirm behavior follows Android filesystem access and the selected command review setting.
5. Add two API configurations with distinct endpoints, model lists and credentials. Switch between them, restart the app and remove one configuration. Confirm models and credentials stay associated with the selected API.
6. Save comma-separated model IDs, switch through the model-name popup, and merge a cloud model list. Confirm the next request uses the chosen model and failures appear in the UI.
7. Set a manual Gemini thinking level with a custom model alias, submit a request and confirm the outgoing provider parameter. A provider rejection should appear as a request error. While manual mode is active, changing to a supported preset through the thinking bubble must preserve manual mode.
8. Delete a conversation after its resident engine finishes a turn. Confirm the runtime session and shell/MCP descendants close and the deleted history does not reappear.
9. Detach a bound workspace while idle. Confirm the general conversation opens without a busy error; attempt the same action during a task and confirm it remains blocked.
