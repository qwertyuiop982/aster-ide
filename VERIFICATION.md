# Verification scope

Account free64: successful debug APK assembly, signature verification, exact packaged runtime asset comparison, private provider configuration scan, 16 targeted offline tests and both public HTTPS gateway tiers with the actual Pi loop reading a disposable three-file chain, then finish. Wallet holds returned to zero and replay did not charge again. Call 64, call 65 and batch boundaries are covered offline. Formal protocol-recovery responses consume real billed tokens. Android installation and IDE device execution of this APK remain unverified. Fresh-machine rebuilding from the archive remains unverified.

Original 1.0.1: APK hash matches the retained release signing record; Pi/Gemini packaged assets match the retained source inputs. The Release validation record states the build and device verification limits.

Website v3: static internal links and IDs checked by the local builder; responsive browser interaction checks are recorded with the deployment. No remote development environment is used.

## Publication and website deployment — 2026-10-25

The public repository was readable anonymously. Release `v1.0.1-free64` is a prerelease with eight assets; every GitHub asset SHA-256 digest matches its local file.

Website v3 was developed, built and browser-tested locally, then deployed to https://asteride.cn. Eight public paths returned HTTP 200 and matched the local build hashes. Custom 404 behavior and API health passed; both free tiers were available. Browser checks covered preview and engine switching, edition downloads and hashes, the token calculator, FAQ, mobile navigation and Escape at widths 320, 390, 768 and 1440, with no horizontal overflow or console errors. The deployment source commit is recorded separately from this documentation update.

Accelerated APK and source downloads returned HTTP 206 for bytes 0–63 with the expected ZIP headers and total sizes. The checksum list and three validation JSON files were downloaded in full and their hashes matched. Large files were not downloaded in full through the proxy, so these checks do not establish their full proxy-download hashes.

Evidence: [website deployment](docs/website-v3-verification.json) and [public download checks](docs/public-downloads-verification.json). The APK installation/device-execution and fresh-machine rebuild limits above still apply.
