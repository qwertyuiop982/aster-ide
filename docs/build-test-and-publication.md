# Build, test and publication runbook

## Reproducible inputs

Use JDK 17 and the Android SDK required by the Gradle configuration. The current project maps the launcher to `:app`; running a task under `core/launcher` as a Gradle path is not equivalent. Consult `DEVELOPMENT.md` for the base toolchain and `ACCOUNT-BUILD.md` for this workstation's adaptations. Do not commit machine-local SDK paths or signing configuration.

Retain package lockfiles, pinned engine source records and UI bundle manifests together with changes to generated artifacts. A pre-existing cache is useful for local iteration but is not a substitute for a documented clean rebuild.

## Fast feedback

Run these host regressions before Android packaging:

```sh
python3 scripts/test_codex_native.py
python3 scripts/test_subagent_runtime.py
python3 scripts/test_pi_context.py
python3 scripts/test_binary_startup.py
node scripts/build_agent_ui.mjs
node --check agent/app/src/main/assets/agent/app.bundle.js
```

The binary startup probe uses the local Kotlin/JVM toolchain and may need the paths documented by its script. A passing Python suite does not prove WebView rendering, Android permissions or device runtime installation.

## Incremental Android builds

Use the existing Gradle user home and build tree; do not run `clean` for a routine code change. Build only the relevant Kotlin compile tasks during iteration. Assemble a selected APK variant when verification requires packaging. An incremental build still needs all tasks made stale by changed inputs, including assets, DEX, resource processing and, for a release, R8 when appropriate.

Do not copy stale merged assets or DEX into the output to obtain a superficially fast build. Skip a generation task only after verifying that its output corresponds to the current inputs. In particular, an updated Agent UI must be regenerated before reusing its bundle. Never exclude mandatory native work simply because it was expensive on an earlier run.

Record Gradle's executed, cached and up-to-date task counts separately. They describe task reuse, not application performance. Keep the command line and output log with the local verification record.

## Artifact verification

Check package ID, version name/code, supported ABIs and debuggability. Verify the signature and compare its certificate with the intended upgrade lineage. Check ZIP integrity and uncompressed entry alignment. If the SDK's zipalign is built for an incompatible host architecture, report that limitation; a direct ZIP offset check is useful evidence but must not be described as execution of the SDK tool.

Compare important packaged assets against source hashes, including the Agent UI bundle, bridge adapters and runtime provenance. Verify license assets in the APK rather than assuming a source directory was merged. Generate a SHA-256 sidecar only after the final signed file is in its delivery location.

## Device acceptance

Open a project with a binary file first in traversal order. Open a normal text file. Start a model task and delegate to a child; verify prompt, streaming answer, tools and terminal status. Switch repeatedly between parent and child. Cancel while a child is waiting for an interaction. Reopen a persisted conversation. Check review and native permission modes independently. Record device/API level, engine version and actual results.

## Source publication

Use the existing `aster-ide` history, review the complete staged diff and avoid force-pushing. Include new source files and matching generated manifests. Exclude node_modules, build trees, logs, Python caches, private configuration, APKs and signing keys. Test scripts intentionally retained for regression should be tracked even if an old cleanup rule ignored tests.

Authenticated writes should target github.com. A third-party read proxy is not a credential-safe authenticated push endpoint. Verify the remote branch after pushing and report its exact commit. Publishing a development commit does not mean the APK is tested or the license review is complete; unresolved release gates must remain visible.