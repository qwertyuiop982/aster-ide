# Aster 1.0.2 runtime and Agent build environment update

This update handles shared Pi transport disconnects, keeps routine PRoot diagnostics out of the UI, and gives Agent mode the IDE build environment controls.

Agent users can open **环境安装设置** from the sidebar, the session menu, or model settings. The native screen reuses `BuildEnvironmentSettings` with the IDE's settings store, bridge install operation, and private Termux directories. It supports base compiler packages, JDK 17/21, Android SDK / Build Tools / NDK, command-line tools, SDK paths, build memory and workers, package sources, installation progress, logs, cancellation and retry. No model prompt is needed to install the selected tools.

Pi now uses normal verified TLS negotiation without sending a HEAD probe for each model turn. The connection class makes at most three connection attempts, with a maximum ten-second connection timeout per attempt. It retries only connection establishment, before the HTTP model request is written. Certificate verification failures are never retried with weaker validation. An interruption after sending a request never replays the model request or commands. If the stream already has `finish_reason`, a missing optional usage / `[DONE]` trailer preserves the complete message and tool calls. Incomplete streams are rejected and incomplete tool calls are not executed.

PRoot verbosity `-v -1` now applies on all supported Android versions. The host logs routine PRoot warning/info output and the known already-removed `proot-*` temporary cleanup race without forwarding those lines to the UI. Permission, ptrace, loader, execve and other fatal errors remain visible. Raw diagnostics are saved under `~/.aster/logs/runtime.log` and `agent-environment.log`, and are included when copying startup diagnostics.

## Validation

- Pi transport fault injection: 12 tests passed, including errno 103 before connect and after a request is sent, certificate failures, cancellation, completed stream trailers, incomplete tools and credential redaction.
- The real bundled Pi agent loop completed under local trailer abort injection for lunara, OpenRouter and OpenAI labels: 1 parameterized test passed. These are local tests, not live provider calls.
- Shared build installer: 5 tests passed for option forwarding, package failure, concurrency lock, cancellation and build resource settings.
- Existing free-tool, account and Agent runtime installation regressions: 16 tests passed.
- The compiled Kotlin diagnostic classifier passed 3 routine and 8 fatal/child-output cases.
- A local browser fixture confirmed that sidebar and session-menu entries send `environment` for all four engine labels.
- Agent UI bundle and all 10 input hashes match the bundle manifest. All 53 bridge and Agent UI files in the APK match source content. APK signature verification passed and matches the installed 1.0.1-agent-fix2 signer.
- Kotlin compilation, `:app:assembleDebug` and `:app:lintDebug` passed (Gradle exit code 0). Lint reported 0 errors and 13 warnings about dependency versions, ChromeOS ABI support and style/build configuration suggestions.

The local ARM build uses:

```sh
./gradlew :app:assembleDebug :app:lintDebug \
  -x :app:prepareCodexNative \
  -Pandroid.aapt2FromMavenOverride=/root/Android/build-tools/35.0.0/aapt2 \
  --offline --console=plain
```

This build reuses the existing packaged Codex native artifacts. The local aapt2 override avoids launching the Maven x86 host binary on ARM.

## Delivery and remaining device checks

APK: `/sdcard/Download/Aster-v1.0.2-runtime-fix.apk`, application ID `dev.aster.account`, version code 13. The sibling `.sha256` file records the exact APK digest. Detailed evidence and the selected source files are provided alongside the APK.

Automatic device installation was blocked by the current Android process permissions; the new native screen has not been exercised on-device. Actual apt / SDK / NDK downloads, physical-device PRoot UI filtering, live supplier requests and a fresh CI/source build remain unverified. Local fault injection and mocked installation tests do not substitute for those checks.
