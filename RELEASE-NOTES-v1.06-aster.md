# Aster v1.06-aster service update

This server-side update keeps Android `versionCode 17` and the APK unchanged. The account API now reports `v1.06-aster`.

## Model service
- Free Go uses `gpt-6.1-terra` through Lunora and costs 5 points per 1,000 input and output tokens.
- Free uses `glm-5.3-flash` through Lunora at the unchanged rate of 1 point per 1,000 tokens.
- Model metadata, wallet pricing and settlement use the same server-side rate constants.
- Provider credentials are stored only in the private server environment.

The account service source is included in `source-v1.06-aster.zip` and `services/account/`.

## Verification
- The server test suite passed 45 checks using the dedicated PostgreSQL test database.
- A live Free completion returned glm-5.3-flash; 469 tokens settled at 0.469 points and replay was cached.
- Free Go is configured in the running service as gpt-6.1-terra at the Lunora endpoint. Its model listing is accessible, but live completion checks returned provider HTTP 502 (upstream forbidden) and HTTP 429 (rate limited). Successful Free Go inference remains unverified.
- Website language tests passed 27 checks; mobile and desktop scene checks reported no collisions. Both languages show the updated prices.

The existing v1.06-aster Git tag and APK remain unchanged. This source update is published as a later main-branch commit and a separate source archive on the same Release.
