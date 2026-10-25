# Aster public delivery

The main branch contains the full account edition client source and the current website. Release v1.0.1-free64 provides two APKs and their separate corresponding source archives. The account APK is a debug preview build, and the original APK is the earlier release build. Both contain the full IDE. Preserve the root LICENSE and component notices.

See README.md, ACCOUNT-BUILD.md, DEVELOPMENT.md and the Release validation files for exact build scope. APK source archives retain the snapshot that accompanied each APK; subsequent website and documentation changes in main do not change either APK. Private upstream configuration and Android signing keys are excluded.

Build the static website locally with python3 website/v3/build.py --release-input release-input.json --output /tmp/aster-site. Only generated static files are deployed to the web server.
