# Public source verification

The 1.0.5 release includes the native collaboration event fix and bounded IDE startup:

- Codex app-server and code-mode-host reuse successful aarch64-linux-android release builds after comparing all 2,348 retained Rust source/configuration inputs and checking both binary hashes.
- Pi and Gemini production runner bundles and the Agent UI are retained with their source and lockfiles; the UI is rebuilt during release assembly.
- Shizuku API/provider 13.1.5 resolved through Maven; the Shizuku client module compiled without vendored Shizuku sources.
- Gradle release APK assembly and lint completed, with R8 and resource shrinking enabled.
- APK signature, manifest version/debuggability, native runtime entries, bootstrap and client notice assets checked.
- Public source snapshot excludes shared libraries, build intermediates, caches, standalone test/example trees, restricted SDK material and signing keys.
- Pinned runtime/V8 dependency extraction verified using the separate native-dependencies Release archive as the fallback source.

The detailed APK/source hashes and current build scope are in the Release validation JSON and SHA256SUMS. Automated checks do not establish device UI acceptance. The user reports five-agent R8 stress validation passed; the previous IDE eventually loaded after 31 minutes. The bounded startup change in this release still needs device timing. Prior provider text connectivity succeeded; the prior image-provider request returned HTTP 403 / INSUFFICIENT_BALANCE. Those records do not establish current live provider availability.

## 1.0.5 release execution record

- The four-core Release assembly and lint completed successfully in 39m 1s (435 tasks: 145 executed, 5 cached, 285 up-to-date). CPU affinity was 4-7 and Gradle workers/Java processor count were limited to four.
- Signed APK: 137,414,241 bytes; SHA-256 `9ed050313ac7822ab2a7f7e689c10808fd7aac1d686710c213467149bda7ed43`.
- Version 1.0.5 / versionCode 16, non-debuggable, R8 and resource shrinking enabled. Signature v2 and JavaScript bridge retention checks passed.
- Signing certificate SHA-256 `786fdc014aa516d0e96ee13aa028b07371ced388b0af9e145bc868be98f6864b` matches the installed 1.0.4 certificate. This remains the historical local Android Debug certificate, not a newly provisioned production key.
- Codex native regression: 21 passed; structural-DOM renderer: 13 passed; subagent navigation: passed.
- The isolated production Kotlin text-loader/startup-policy probe passed all 12 cases after the Release build ended. The earlier concurrent attempt exceeded its 90-second compiler limit; it was not an assertion failure. Logs: `logs/binary-startup-release-rerun.log`.
- The website builder derives version, download URL, size and hash from `delivery.json`; local links passed and a deliberately altered APK hash was rejected.
- Device startup timing and Android UI acceptance are not established by these host/build checks. License clearance remains false; see `docs/license-inventory.json`.
