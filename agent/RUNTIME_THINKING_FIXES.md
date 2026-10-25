# Runtime and exclusive thinking modes

Changes after v1.0.0 release (not included in the already exported APKs):

- Shared guest environment binds APK native libraries at /aster-native and retains ASTER_NATIVE_LIB_DIR through env -i, so Codex discovery can find its packaged binary.
- Standalone connection readiness requires the selected engine to be available. This is local readiness, not remote API success.
- OpenClaude missing SDK and missing host produce different messages. Its directory validation checks containment and parent symlinks; actual reported installation failure still needs device logs to establish the cause.
- Gemini and OpenClaude expose auto, off, budget, and strength in native model settings. Budget and strength are mutually exclusive; inactive values are retained. Unsupported model/mode combinations fail explicitly.
- OpenClaude budget mode uses the public SDK budget control, disables adaptive thinking, and unsets effort. Strength mode uses native adaptive thinking and effort without a fixed token budget.
- Gemini 2.5 budget mode and recognized 3.x strength mode are checked against pinned transports. Server capabilities can differ.
- Pi retries the specific TLSV1_ALERT_INTERNAL_ERROR once with verified TLS 1.2 only before opening a response. Certificate errors are never bypassed; response-read errors do not replay the request. Real endpoint validation remains pending.

Evidence: logs/runtime-modes-verification.json, logs/runtime-modes-final.json, logs/pi-tls-tests.log, logs/exclusive-modes-browser.log.

No updated APK has been exported for these changes. Existing v1.0.0 exports are unchanged.
