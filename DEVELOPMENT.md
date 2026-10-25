# Building Aster

## Android build

Prerequisites: JDK 17, Python 3, Android SDK 35, build-tools 35.0.0, NDK 29.0.14206865, CMake 3.28.3, Ninja, Git, `ar`, `readelf`, `zstd`, and network access for pinned dependencies. Set `ANDROID_HOME` or your own `local.properties`; set `ANDROID_NDK_HOME` to the NDK. The Gradle wrapper selects Gradle 8.13. The repository does not contain machine-specific SDK paths.

```sh
./gradlew :app:assembleRelease
```

On Linux ARM64/Android, install host-compatible AAPT2, NDK tools and CMake. An AAPT2 override can be passed on the command line with `-Pandroid.aapt2FromMavenOverride=/absolute/path/to/aapt2`. Desktop binaries with incompatible ELF loaders cannot execute directly on Android.

## Native programs without checked-in .so files

Gradle runs `scripts/prepare_native.py` before packaging. Outputs live only in ignored build directories:

- `build/native/runtime/arm64-v8a/`: pinned PRoot, loader, talloc and Android shared-memory inputs, extracted from checksum-verified official Termux packages.
- `build/native/codex/arm64-v8a/`: Codex app-server and code-mode-host, compiled from the retained `vendor/codex/codex-rs` source closure.
- `core/termux-runtime` and the Termux terminal JNI code are compiled through CMake/Gradle.

Install the Rust toolchain and target before the first native build:

```sh
rustup toolchain install 1.95.0
rustup target add --toolchain 1.95.0 aarch64-linux-android
python3 -B scripts/prepare_native.py --component all
```

Rust is discovered on PATH. `CARGO_HOME` and `RUSTUP_HOME` are respected; otherwise the scripts use an existing Aster cache or the normal `~/.cargo` and `~/.rustup` directories. `ASTER_CODEX_TARGET_DIR` can share a local Cargo build cache. `ASTER_NATIVE_CACHE` sets the verified package cache (default `~/.cache/aster-native`). Native compilation may take substantial time and disk space.

The Codex host links against Termux's pinned librusty-v8 1:2.9.7 archive. Pinned .deb inputs are also retained as the separate `aster-native-dependencies-1.0.1.zip` GitHub Release asset, so rolling repository removals do not break these pinned builds. The preparation script verifies package SHA-256 `18ddeeff336a696a27f3d8fe5ed223c2f6e6471ab0150bd139599f85a401657f`. Set `ASTER_V8_PREFIX` when providing an already verified compatible installation containing `lib/librusty_v8.a` and `include/librusty_v8/src_binding.rs`. OpenSSL headers/libs are extracted from the downloaded, checksum-verified bootstrap; they are build inputs, and the native agents resolve the existing Termux runtime libraries on device.

To rebuild native outputs after editing Rust sources, run:

```sh
python3 -B scripts/prepare_native.py --component codex --force
./gradlew :app:assembleRelease
```

No compiled `.so` belongs in a commit or a source ZIP. Runtime binaries belong inside the APK. The source ZIP is packaged from Git-tracked files, so local build products and keys cannot enter it.

## Modules and optional agent bundles

The application launcher lives in `core/launcher`, common runtime in `core/app`, IDE in `ide/app`, Agent UI in `agent/app`, Termux runtime in `core/termux-runtime`, and clients in `client/`. Gradle maps the Termux terminal modules from `vendor/termux-app`. Qingzhou's two browser engine files are copied by the client build task. Shizuku API/provider 13.1.5 are resolved from Maven Central; the independent Shizuku app must be installed, started and authorized on the device.

Pi/Gemini runner sources, npm lockfiles and build scripts are retained under `research/pi-agent` and `research/gemini-agent`. Their final JavaScript runtime assets are included in `core/app/src/main/assets/bridge/agent_vendor`. Rebuild Pi with `python3 -B scripts/bundle_agent.py`; for Gemini run `npm ci --ignore-scripts --prefix research/gemini-agent` followed by `node research/gemini-agent/build.mjs`. Node 22.19+ is required. Standalone test/example trees, test helpers, documentation images and duplicate upstream archives have been removed. Some upstream inline Rust `#[cfg(test)]` blocks remain inside retained production files and are excluded from release compilation.

## Signing and validation

`./gradlew :app:assembleRelease :app:lintRelease` validates the application. R8 and resource shrinking remain enabled. `scripts/sign_release.py` accepts signing settings through `ASTER_RELEASE_KEYSTORE`, `ASTER_RELEASE_KEY_ALIAS`, `ASTER_RELEASE_STORE_PASSWORD` and `ASTER_RELEASE_KEY_PASSWORD`. Set `ANDROID_HOME` to find build-tools. No signing material is published.

`VERIFICATION.md` and the Release validation JSON state the observed build and device/model scope. Successful compilation does not imply that all providers, Android versions or device automation flows have been verified.
