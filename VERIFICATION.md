# Aster 1.0.4 verification

- Release assembly succeeded with R8 minification and resource shrinking.
- Android default ProGuard rules were confirmed loaded after correcting the build output path initialization order.
- APK v2 signature verified; certificate matches the previously installed account edition.
- APK Agent resources and both Codex native components match their retained build inputs.
- 32 web UI regression cases and 11 native transport unit tests passed.
- Lint: 0 errors, 15 warnings.
- Version 1.0.4, versionCode 15, package dev.aster.account; ARM64, Android 7.0+.
- The APK was not installed by this release process; device UI and current live model-provider availability were not revalidated.

Release assets include the source snapshot, SHA-256 checksums and a machine-readable validation record. No signing keys, service credentials, native shared libraries or local build caches are included in the source snapshot.
