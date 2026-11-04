# Aster 1.0.4

Aster is an Android ARM64 development workbench with a Compose interface, code editor, embedded Termux environment, language services, native Android builds and Pi/Codex/Gemini agents. The first launch offers VS Code and ACS interface styles. Version 1.0.4 adds compact Agent controls, native transport improvements and a dedicated model configuration page.

- Android 7.0+ (API 24), arm64-v8a; target SDK 28 allows app-private compiler executables.
- Qingzhou's BrowserEngine and WebViewEngine provide the embedded browser tools.
- Device tools call the separately installed, running **Shizuku app** through `dev.rikka.shizuku:api` and `provider` 13.1.5. Install/start Shizuku and grant Aster permission. Shizuku's server and API source trees are not included in this repository.
- Model providers, API keys and any paid model capacity are supplied by the user.

Get the APK, compact corresponding source, checksums and validation record from [GitHub Releases](https://github.com/qwertyuiop982/aster-ide/releases/tag/v1.0.4). Download links on the static website use `https://gh-proxy.org/https://github.com/...`.

## Build

Use JDK 17, Python 3, Android SDK 35 / build-tools 35.0.0, NDK 29.0.14206865, CMake 3.28.3 and Ninja. Set `ANDROID_HOME` (or `sdk.dir` in your private `local.properties`) and `ANDROID_NDK_HOME`. An ARM host requires host-compatible Android build tools; pass `-Pandroid.aapt2FromMavenOverride=/your/native/aapt2` if necessary.

```sh
git clone https://github.com/qwertyuiop982/aster-ide.git
cd aster-ide
./gradlew :app:assembleRelease
```

The repository contains no `.so`, generated build intermediates, test or example source trees. Gradle prepares native runtime dependencies and builds the two Codex programs into the ignored `build/native/` directory. A fresh native build additionally needs Rust 1.95.0, its `aarch64-linux-android` target, `ar`, `readelf`, `zstd` and network access. See [DEVELOPMENT.md](DEVELOPMENT.md) for setup and caching. The Termux bootstrap and pinned .deb dependencies are fetched and checksum-verified at build time; the separate native-dependencies Release asset retains them when upstream rolling repositories remove old versions.

The release build enables R8/resource shrinking and produces an unsigned APK. Sign your own builds with your own key; release signing keys are excluded. A different signing key cannot replace an existing Aster installation without migration.

## Source and licenses

[PUBLIC-RELEASE.md](PUBLIC-RELEASE.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [client/SOURCES.json](client/SOURCES.json) describe source provenance and component licenses. Third-party licenses remain in their respective directories and APK notice assets. `SOURCE-CLEANUP.json` records the compact-source scope. Restricted OpenClaude SDK material is excluded.

[website/](website/) contains the static product site. It can be published on GitHub Pages; APKs and source archives are GitHub Release assets. The compact production landing page lives in `website/v5/`; it contains no account or credit UI. Account backend deployment is separate.
