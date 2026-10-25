# Embedded Client Capabilities

The client modules connect the Agent to approved device and browser operations.

- `:client:browser` compiles the two Qingzhou engine source files from `vendor/qingzhou`; Aster owns the embedded activity and bounded DOM operations.
- `:client:shizuku` uses the MIT-licensed Maven artifacts `dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5`. It calls the separate Shizuku app/service installed on the device. This repository does not contain or build the Shizuku app/server/API source. A running service and explicit user authorization are required; the dependency cannot grant ADB/root privileges by itself.
- `:client:automation` validates device snapshots and dispatches bounded actions. Only fixed device operations are exposed, not arbitrary privileged shell execution.
- `vendor/operit` retains LGPL-3.0 attribution, its license and a pinned upstream source URL. The unused full reference tree is omitted; Aster's adapters remain in `client/`, and full feature parity is not claimed.

Device screenshots can contain personal information. Review them before approving an upload to the configured vision model. Permission revocation and real-device interactions still require device testing.

## Source and licenses

Pinned sources, Maven coordinates and license metadata are recorded in `SOURCES.json`. Qingzhou is AGPL-3.0, Shizuku API/provider artifacts are MIT, and Operit is LGPL-3.0. Read `THIRD_PARTY_NOTICES.md` and the corresponding license files before modifying or redistributing.
