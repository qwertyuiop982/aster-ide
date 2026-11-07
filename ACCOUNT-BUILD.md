# Aster account edition — internal free64 build

This source snapshot accompanies the full IDE and Agent account test APK `Aster-account-free64-internal-debug.apk`. It includes the account client changes, the Pi host source and generated bundle, the retained upstream source, build scripts and license notices. Signing secrets, service credentials, local SDK paths, dependencies installed in node_modules and build outputs are not part of the snapshot.

The client contains inherited GPL/AGPL components. Preserve `LICENSE`, `THIRD_PARTY_NOTICES.md` and upstream notices. An independently published open-source APK is not corresponding source for this edition. This snapshot does not change the inherited license terms or turn the account client into a closed-source distribution. Server implementation and private provider configuration are separate from the Android source.

Build prerequisites and native source recipes are documented in `DEVELOPMENT.md`. Use JDK 17, the pinned Gradle wrapper and Android SDK/NDK versions listed there. A normal source build uses `./gradlew :app:assembleDebug`; use your own Android signing key. The delivered internal APK was assembled from already verified native outputs and the retained UI bundle. On the ARM64 build host it used:

```sh
./gradlew :app:assembleDebug \
  -x :app:prepareCodexNative -x :agent:app:bundleAgentUi \
  --offline --console=plain \
  -Pandroid.aapt2FromMavenOverride=/root/Android/build-tools/35.0.0/aapt2
```

Those skipped inputs are local build outputs, not a replacement for the native build recipes when building on a new host. `scripts/bundle_agent.py` rebuilds the Pi bundle from `research/pi-agent/runner.ts` and the pinned Pi sources. Its provenance is retained under `core/app/src/main/assets/bridge/agent_vendor/PI-SOURCE.json`.

Run the targeted offline regression checks with Node available:

```sh
python3 -B -m unittest scripts.test_free_tool_loop scripts.test_account_free scripts.test_agent_runtime_install -v
```

Free and Go use the public Aster gateway and a user's Aster login session. The real model, provider URL and key are configured only on the server. Go may require fixed reasoning; the client displays final content and discloses that output billing includes reasoning tokens. Both Pi profiles force a per-task maximum of 64 actual tool calls. Plain tool instructions are repaired at most twice after the initial completion; those real completions use the existing actual-token billing. Replays of the same gateway request do not charge again.

Verification records next to the APK distinguish the live public-gateway Pi test on a disposable Linux project from Android device execution. On-device installation and UI execution of this updated APK have not been verified by this build process.
