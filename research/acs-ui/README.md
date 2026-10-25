# ACS UI analysis and Aster adaptation

Source: [AndroidCSOfficial/android-code-studio](https://github.com/AndroidCSOfficial/android-code-studio), revision `e93e7468e6482ea51ef8e1adad6b7c6de86ae7cd`, fetched with the user-requested `https://v4.gh-proxy.org/` proxy. The full checkout remains at `vendor/android-code-studio` in the development workspace. `upstream/` retains the twelve inspected source files; `SOURCE.json` records their original paths and SHA-256 hashes. `ACS-LICENSE.txt` retains GPL-3.0. The delivery contains the Aster implementation, these exact relevant upstream files, their hashes and the license.

| Upstream source | Observed UI design | Aster implementation |
| --- | --- | --- |
| `activity_editor.xml` | Translating navigation drawer and file navigation | ACS toolbar drawer button and animated overlay using the existing `Explorer` callbacks |
| `content_editor.xml` | Toolbar, tabs, editor container, lower tool area | `AcsHeader`, retained tabs/editor, rounded editor surface and existing adaptive dock |
| `layout_editor_bottom_sheet.xml` | Material card with 24 dp corners, selected tool tabs, bottom sheet | `AcsToolShelf`, drag/click handle, rounded dock; same seven `WorkbenchPanels` entries |
| `layout_editor_bottom_action.xml` | Bottom action area and editor commands | Existing editor keys and language actions, reachable in both styles |
| `layout_diagnostic_info.xml` | Error details inside a rounded Material surface | Existing diagnostic content/actions and common rounded information surfaces |
| `lspPrefs.kt` | Grouped language preferences and per-language configuration | `AcsLspOptions` and `AcsServerSettings`, always used by both styles |
| `layout_completion_item.xml`, `CompletionListAdapter.kt` | 40 dp completion rows, kind badge, label/detail/type | `AcsCompletionAdapter`; local and LSP candidates use the same presentation |
| `dimens.xml`, `EditorCompletionLayout.kt` | 8 dp window corners and 1 dp outline | `AcsCompletionLayout`, using the existing editor color scheme |
| `BaseEditorWindow.java`, `SignatureHelpWindow.java` | Rounded hover/signature windows | Rounded, outlined common information surfaces displaying the existing hover/signature results |

ACS uses `BlurView` in its original bottom sheet. Aster's Compose adaptation uses translucent Material surfaces and the existing theme without introducing another rendering dependency. ACS toolbar/menu/tab/drawer geometry is adapted to the existing portrait and landscape dock policy, with the same motion and reduced-motion settings.

Both workbench styles use one `AsterApp`, `IdeViewModel`, `WorkspaceRepository`, `EditorPool`, `Panels`, `CommandPalette`, settings host and language-dialog host. Switching the presentation keeps the same cached editor views and document buffers. `ProjectMenu` supplies the same complete project/history, feature and orientation actions to both header presentations. `WorkbenchPanels` supplies Agent, terminal, problems, build output, artifacts, document symbols and logs to the common menu/panel host and ACS shelf.

| Existing feature group | VS Code style | ACS style | Shared implementation |
| --- | --- | --- | --- |
| Workspace import/create/history and file create/rename/delete | Available | Available | `IdeViewModel`, repository and `Explorer` |
| Tabs, save, undo/redo, find/replace and project search | Available | Available | `EditorPool`, editor surface and common controls |
| Completion, hover, signatures, definition/references and symbols | Available | Available | Existing LSP manager/client and TextMate fallback |
| Formatting, rename preview, code actions and diagnostics | Available | Available | Existing language actions and edit-preview workflow |
| Terminal, build profiles, custom commands and output/logs | Available | Available | Existing runtime/build services and `Panels` |
| Build artifacts and export | Available | Available | Existing artifact panel and document export callback |
| Agent, model settings, command approvals and staged edit previews | Available | Available | Existing Agent controller/panel/settings |
| All settings, motion/reduced motion and orientation controls | Available | Available | Common settings, `LocalMotion` and `ProjectMenu` |

`CORE-BASELINE.json` pins seven existing LSP/runtime/build/Agent/catalog files before the UI adaptation. Packaging checks every hash. `CompletionPresentation` adds optional display metadata to the existing completion item; requesting, sorting, accepting and applying completion edits stays in the existing implementation. Aster has not imported ACS language-server processes, protocol logic or toolchain services.

`UiPreferences` stores the style and explicit confirmation flag. Fresh installations and upgrades without a previous choice show the chooser before environment preparation; existing projects and general settings remain available. A confirmed choice is persistent, appearance settings can change it, and a general settings reset preserves it. OpenJDK choices are 17 and 21, with 17 as the Android-development default; an existing explicit 21 selection is retained.

Verification artifacts are `logs/acs-ui-verification.json`, `logs/host-checks.json`, the release test/lint reports and `logs/device-verification.json`. The final device report distinguishes UI checks from host language-server checks and from on-demand toolchain installation.
For 0.1.7 the user requested a separate Agent engine replacement. AgentController is the sole intentional exception to the earlier service baseline; LSP manager/client/protocol, BridgeClient, BuildProfiles and the server catalog still match those hashes. Both UI styles use the same LangGraph Agent panel/controller.
