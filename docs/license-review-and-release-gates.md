# License review and release gates

## Status and limitation

This document is an engineering checklist, not a legal opinion or a declaration that every distribution obligation has been satisfied. The existing root LICENSE describes upstream Termux rather than the entire Aster distribution. The existing third-party notices must be reconciled against actual source files, dependency artifacts and embedded runtime archives before a new release is called compliant. Do not relicense third-party code by replacing its original notices.

## Preserve provenance

For every embedded component, record its upstream repository, pinned version or commit, local modifications, license expression, copyright notices and location of corresponding source. Preserve MIT and Apache notices, applicable NOTICE files, and licenses embedded in generated JavaScript. A bundle manifest or an upstream homepage alone is not corresponding source.

`vendor/pi/LICENSE`, Termux component notices, ACS notices, the Lucide notice and bridge runtime license files need explicit inclusion in publication review. The previous publication tree does not track vendor/pi; copying only its generated runtime requires checking that the appropriate source and notices are still available. Never assume an ignored directory was published.

## Copyleft review

GPL components require review of the complete corresponding source, build scripts and distribution method. LGPL dependencies additionally require reviewing modification and relinking obligations for the actual linking arrangement. EPL-covered source and modifications need their own notice and availability review. GPLv3 section 13 expressly permits combining GPLv3 and AGPLv3 works under its conditions: each part retains its terms and AGPL section 13 network-interaction requirements apply to the combination. AGPL presence alone is not incompatibility. Review the actual usage, derivation boundary and corresponding-source obligations before a binary release. A list containing the word AGPL does not establish either compliance or infringement.

The current notices include entries that may distinguish an integrated dependency from reference or research material incorrectly. Resolve these individually; do not mechanically label the entire repository GPL-3.0-only while contradictory dependencies remain unexplained.

## Source and APK checklist

1. Inventory tracked source, Maven dependencies, bundled npm output and native binaries separately.
2. Match each binary/archive to an exact source revision and local build recipe.
3. Keep original notices and add Aster modification attribution where needed.
4. Include applicable full license texts and notices in source and Android assets.
5. Inspect the built APK itself; source-tree assets are not proof of packaged notices.
6. Publish the corresponding source and verify that recipients can obtain it.
7. Check restricted SDK materials independently; exclude materials without redistribution rights.
8. Exclude signing keys, API credentials, private settings, logs and caches.
9. Save the reviewed inventory, unresolved questions and artifact hashes with the release record.

## Git publication policy

Preserve the existing public repository history. Stage only reviewed files; do not force-push or publish a wholesale working-directory copy. Inspect staged diffs and scan for credentials before committing. A GitHub mirror URL is not automatically a safe authenticated write endpoint: avoid sending GitHub credentials to a third-party proxy. Prefer authenticated writes to github.com, and report network failures rather than exposing a token to a mirror.

## Acceptance

A release is not license-cleared while provenance gaps, AGPL compatibility questions, missing full texts or corresponding-source gaps remain open. Retaining this checklist does not close those issues. Obtain qualified legal review when the component boundary or license compatibility is uncertain.