# Embedded Client Capabilities

The client modules connect the Agent to approved device and browser operations.

- `:client:browser` uses Android WebView for the manual browser, rendered DOM snapshots and Bing web or image search. Browser automation and search run separately from the manual browser.
- `:client:shizuku` uses the MIT-licensed Maven artifacts `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`. It calls the separate Shizuku app/service installed on the device. This repository does not contain or build the Shizuku app/server/API source. A running service and explicit user authorization are required; the dependency cannot grant ADB/root privileges by itself.
- `:client:automation` validates device snapshots and dispatches fixed device actions.
- `vendor/operit` retains the license and pinned upstream revision for the Operit workflows used as references.

Device screenshots can contain personal information. Review them before approving an upload to the configured vision model. Permission revocation and real-device interactions still require device testing.

## Source and licenses

Pinned sources, Maven coordinates and license metadata are recorded in `SOURCES.json`. Historical Qingzhou source is AGPL-3.0, Shizuku API/provider artifacts are MIT, and Operit is LGPL-3.0. Read `THIRD_PARTY_NOTICES.md` and the corresponding license files before modifying or redistributing.
