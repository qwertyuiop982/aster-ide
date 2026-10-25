# Third-Party Notices

The complete project is distributed under GNU GPL v3. Dependency licenses below retain their own terms. Matching sources and rebuild recipes are included or linked, including components with LGPL relinking rights.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Termux terminal-emulator / terminal-view | 0.118.3 | GPL-3.0 | `vendor/termux-app`, upstream `https://github.com/termux/termux-app` |
| PRoot Termux fork | 5.1.107.96 | GPL-2.0 | `research/runtime-sources/proot-5.1.107.96.zip`, `https://github.com/termux/proot` |
| libtalloc | 2.5.0 | LGPL-3.0-or-later | `research/runtime-sources/talloc-2.5.0.tar.gz`, `https://www.samba.org/ftp/talloc/` |
| libandroid-shmem | 0.7 | Apache-2.0 | `research/runtime-sources/libandroid-shmem-0.7.tar.gz`, `https://github.com/termux/libandroid-shmem` |
| Sora Editor / TextMate | 0.24.4 | LGPL-2.1-or-later | `research/editor-sources.jar`, `research/language-textmate-sources.jar`, `https://github.com/Rosemoe/sora-editor` |
| AndroidX / Compose / Material icons | See Gradle resolution | Apache-2.0 | `https://android.googlesource.com/platform/frameworks/support/` |
| Kotlin / kotlinx.coroutines | 2.1.0 compiler / 1.9.0 coroutines | Apache-2.0 | `https://github.com/JetBrains/kotlin`, `https://github.com/Kotlin/kotlinx.coroutines` |
| TM4E core | bundled by language-textmate | EPL-2.0 | bundled matching source archive, `https://github.com/eclipse-tm4e/tm4e` |
| Joni | 2.2.6 | MIT | `https://github.com/jruby/joni` |
| JCodings | 1.0.63 | MIT | `https://github.com/jruby/jcodings` |
| Gson | 2.13.2 | Apache-2.0 | `https://github.com/google/gson` |
| SnakeYAML Engine | 3.0.1 | Apache-2.0 | `https://bitbucket.org/snakeyaml/snakeyaml-engine` |

Termux bootstrap packages retain their copyright, license texts and Debian metadata under `usr/share/doc`, `usr/share/LICENSES`, and `usr/var/lib/dpkg` after unpacking. The bootstrap is unmodified; only its original symlinks are recreated. Its version and SHA-256 are recorded in `core/termux-runtime/src/main/assets/runtime/checksums.json`.

The bundled host PRoot, talloc and shmem payloads are downloaded from the official Termux package repository and SHA-256 verified against its package index. Their matching source archives are also SHA-256 verified against upstream Termux build recipes. PRoot is not modified; Aster's native launcher constructs its bind arguments.

Termux Java source is modified only for AGP/CMake integration and a bounded external-transport TerminalSession extension. The public source includes these modifications; compare it with the pinned upstream revision recorded in research/termux-revision.txt.

Sora sources are unmodified. The full Gradle project allows rebuilding and relinking with a modified compatible Sora version. No restriction is imposed on reverse engineering for debugging those library modifications.

Language servers and development tools install on demand, retain their upstream package notices and are not claimed as Aster-authored code. Optional Android SDK downloads are subject to Google's Android SDK terms.

The standard Agent uses the retained Pi, Codex and Gemini production sources and Aster adapters. Exact pins and licenses are recorded under research/pi-agent, research/agent-engines, research/gemini-agent and core/app/src/main/assets/bridge/agent_vendor. Build dependencies are restored from the retained lockfiles. Duplicate full upstream tarballs and upstream tests/examples are omitted; original snapshot URLs and hashes remain in provenance records.

Shizuku API/provider 13.1.5 (RikkaApps/Shizuku-API, MIT) are Maven dependencies. The Shizuku application/server is independently installed by the user, not bundled. Its MIT notice remains in client/shizuku/src/main/assets/licenses/shizuku-LICENSE.txt. Qingzhou browser source (myos-Lu/qingzhou, AGPL-3.0) is retained for the two compiled browser engines. Operit (AAswordman/Operit, LGPL-3.0) is referenced by immutable upstream revision; its unused full reference source tree is omitted, with attribution and license retained. Client source scope is recorded in client/SOURCES.json.


## Android Code Studio UI

ACS / AndroidIDE UI sources are pinned to `e93e7468e6482ea51ef8e1adad6b7c6de86ae7cd` from https://github.com/AndroidCSOfficial/android-code-studio, fetched through `https://v4.gh-proxy.org/`. Copyright Android Code Studio and AndroidIDE contributors, GPL-3.0-or-later. Aster adapts the workbench layout, language-service preferences and completion presentation. Exact upstream layouts/classes, source hashes, the GPL text and Aster service baseline are retained in `research/acs-ui/`; the APK contains the license and provenance under `assets/licenses/`.
