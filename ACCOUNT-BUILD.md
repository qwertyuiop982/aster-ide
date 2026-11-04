# Aster account edition 1.0.4

This source accompanies `Aster-account-v1.0.4-release.apk` (versionCode 15, `dev.aster.account`). It includes the Android IDE, Agent UI, native source/build recipes and retained component notices. Backend implementation, credentials, signing keys, local SDK paths and generated native outputs are not published.

Use the prerequisites in DEVELOPMENT.md and run `./gradlew :app:assembleRelease :app:lintRelease`. On ARM hosts use host-compatible build tools and an AAPT2 override when required. Sign independent builds with your own key. The published APK retains the existing local signing certificate for upgrade compatibility.

Version 1.0.4 retains the original thinking slider, uses anchored compact engine and permission pickers, and keeps model configuration on its own full-screen page. Release assembly, shrinking, signing, 32 web regression cases and 11 native-transport unit cases passed. Device installation/UI flows have not been validated in this release.

Inherited component licenses and notices remain applicable. See LICENSE, THIRD_PARTY_NOTICES.md, client/SOURCES.json and the upstream notices.
