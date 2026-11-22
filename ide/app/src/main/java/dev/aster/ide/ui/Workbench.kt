package dev.aster.ide.ui

import android.content.Intent
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.EditorDocument
import dev.aster.ide.data.FileNode
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.data.UiStyle
import dev.aster.ide.feature.R
import dev.aster.ide.lsp.LspPosition
import dev.aster.core.R as CoreR

@Composable fun AsterApp(vm: IdeViewModel, settings: IdeSettings) {
    if (!settings.uiStyleChosen) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { UiStyleChooser(vm.settings::chooseUiStyle) }
        }
        return
    }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val editors = remember(context, vm) { EditorPool(context, vm) }
    val motion = LocalMotion.current
    val settingsVisible = vm.settingsPage != null
    val terminalPageVisible = vm.editorReady && vm.showTerminalPage
    val terminalScene = remember { MutableTransitionState(terminalPageVisible) }
    terminalScene.targetState = terminalPageVisible
    // Keep the dock view detached until the full-screen terminal finishes its exit animation.
    val terminalOwnsView = terminalScene.currentState || terminalScene.targetState || !terminalScene.isIdle
    val sceneVisible = settingsVisible || vm.showAgent || vm.showTerminalPage
    val openTerminalPage: () -> Unit = {
        focus.clearFocus()
        keyboard?.hide()
        vm.openTerminalPage()
    }
    val closeTerminalPage: () -> Unit = {
        keyboard?.hide()
        vm.closeTerminalPage()
    }
    var lastSettingsPage by remember { mutableStateOf("editor") }
    SideEffect { vm.settingsPage?.let { lastSettingsPage = it } }
    val workbenchOpacity by animateFloatAsState(if (sceneVisible) 0f else 1f, tween(motion.standard), label = "workbenchScene")
    val sceneShift = with(LocalDensity.current) { 12.dp.toPx() }
    DisposableEffect(editors) { onDispose { editors.close() } }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            vm.importProject(it)
        }
    }
    var buildDialog by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf<String?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<FileNode?>(null) }
    var deleteFile by remember { mutableStateOf<FileNode?>(null) }
    var closing by remember { mutableStateOf<EditorDocument?>(null) }
    var renameSymbol by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(settingsVisible || vm.showAgent || vm.showTerminalPage || vm.showSidebar || vm.showFind || vm.showPanel) {
        when {
            settingsVisible -> vm.settingsPage = null
            vm.showAgent -> vm.showAgent = false
            vm.showTerminalPage -> closeTerminalPage()
            vm.showFind -> vm.showFind = false
            vm.showSidebar -> vm.showSidebar = false
            else -> vm.showPanel = false
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (vm.editorReady) BoxWithConstraints(Modifier.fillMaxSize()
                .graphicsLayer { alpha = workbenchOpacity; translationX = -motion.offset(sceneShift.toInt()) * (1f - workbenchOpacity) }
                .then(if (sceneVisible) Modifier.clearAndSetSemantics { } else Modifier)) {
                val acs = settings.uiStyle == UiStyle.ACS
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val sideDock = DockPolicy.sideBySide(maxWidth.value, landscape)
                val wide = !acs && maxWidth >= if (sideDock && vm.showPanel) 1100.dp else 900.dp
                val compact = landscape && maxHeight < 500.dp
                val cramped = landscape && maxHeight < 260.dp
                Column(Modifier.fillMaxSize()) {
                    Header(vm, settings.uiStyle, sideDock, compact, onBuild = { buildDialog = true }, onImport = { importer.launch(null) }, onNewProject = { newProject = true })
                    Line()
                    Row(Modifier.weight(1f)) {
                        if (!acs) {
                            Rail(vm)
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Box(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxSize()) {
                                if (wide && vm.showSidebar) {
                                    Explorer(vm, Modifier.width(252.dp), onImport = { importer.launch(null) }, onNew = { folder -> createFolder = folder; newFile = "" }, onRename = { renameFile = it }, onDelete = { deleteFile = it }, onOpenTerminal = openTerminalPage)
                                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                AdaptiveDock(side = sideDock, visible = vm.showPanel, expanded = vm.dockExpanded, agent = vm.panel == "agent", agentFraction = settings.agentDockFraction, modifier = Modifier.weight(1f).fillMaxHeight(), editor = {
                                    Column(Modifier.fillMaxSize().then(if (acs) Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface) else Modifier)) {
                                        Tabs(vm, compact) { doc -> if (doc.dirty) closing = doc else vm.closeTab(doc) }
                                        androidx.compose.animation.AnimatedVisibility(vm.showFind, enter = fadeIn(tween(motion.quick)) + expandVertically(tween(motion.resize)), exit = fadeOut(tween(motion.quick)) + shrinkVertically(tween(motion.resize))) { FindBar(vm) }
                                        if (!cramped) Breadcrumb(vm, compact, onRename = { renameSymbol = true })
                                        Box(Modifier.weight(1f)) {
                                            EditorSurface(vm, settings, editors, obscured = sceneVisible && workbenchOpacity < 0.01f)
                                            if (vm.active == null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Outlined.Code, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                                                Spacer(Modifier.height(16.dp)); Text(vm.workspace?.name ?: "Aster", style = MaterialTheme.typography.titleLarge)
                                                Spacer(Modifier.height(16.dp))
                                                TextButton(onClick = { newFile = ""; createFolder = false }) { Icon(Icons.Outlined.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.workbench_new_file)) }
                                                TextButton(onClick = { importer.launch(null) }) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.workbench_open_project)) }
                                            }
                                        }
                                        if (!cramped) EditorKeys(vm, compact)
                                    }
                                }, dock = {
                                    Row(Modifier.fillMaxSize()) {
                                        if (sideDock) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Surface(Modifier.weight(1f).fillMaxHeight().then(if (acs) Modifier.padding(horizontal = 8.dp, vertical = 4.dp) else Modifier), shape = if (acs) RoundedCornerShape(if (sideDock) 12.dp else 24.dp) else RoundedCornerShape(0.dp), color = MaterialTheme.colorScheme.surface) {
                                            Panels(vm, settings, Modifier.fillMaxSize(), side = sideDock, terminalInPage = terminalOwnsView)
                                        }
                                    }
                                })
                            }
                            androidx.compose.animation.AnimatedVisibility(!wide && vm.showSidebar, Modifier.fillMaxSize(), enter = fadeIn(tween(motion.standard)), exit = fadeOut(tween(motion.standard))) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f)).clickable { vm.showSidebar = false })
                            }
                            androidx.compose.animation.AnimatedVisibility(!wide && vm.showSidebar, enter = slideInHorizontally(tween(motion.standard)) { motion.offset(-it) } + fadeIn(tween(motion.quick)), exit = slideOutHorizontally(tween(motion.standard)) { motion.offset(-it) } + fadeOut(tween(motion.standard))) {
                                Surface(Modifier.width(280.dp).fillMaxHeight(), shape = if (acs) RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp) else RoundedCornerShape(0.dp), tonalElevation = if (acs) 4.dp else 0.dp, color = MaterialTheme.colorScheme.surface) {
                                    Explorer(vm, Modifier.fillMaxSize(), onImport = { importer.launch(null) }, onNew = { folder -> createFolder = folder; newFile = "" }, onRename = { renameFile = it }, onDelete = { deleteFile = it }, onOpenTerminal = openTerminalPage, onOpened = { vm.showSidebar = false })
                                }
                            }
                        }
                    }
                    if (acs && !cramped) AcsToolShelf(vm, compact)
                    Line(); StatusBar(vm, settings, sideDock)
                }
            }
            androidx.compose.animation.AnimatedVisibility(!vm.editorReady, Modifier.fillMaxSize(), enter = fadeIn(tween(motion.quick)), exit = fadeOut(tween(motion.standard))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { StartupScreen(vm) }
            }
            if (vm.editorReady && vm.showAgent) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AgentScreen(vm, settings)
            }
            AnimatedVisibility(
                visibleState = terminalScene,
                modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(tween(motion.standard)) { motion.offset(it) } + fadeIn(tween(motion.quick)),
                exit = slideOutHorizontally(tween(motion.standard)) { motion.offset(it) } + fadeOut(tween(motion.quick)),
                label = "terminalScene",
            ) {
                TerminalPage(vm, settings, closeTerminalPage)
            }
            androidx.compose.animation.AnimatedVisibility(vm.editorReady && settingsVisible, Modifier.fillMaxSize(), enter = fadeIn(tween(motion.standard)) + slideInHorizontally(tween(motion.standard)) { motion.offset(it / 24) }, exit = fadeOut(tween(motion.standard)) + slideOutHorizontally(tween(motion.standard)) { motion.offset(it / 24) }) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { })
                        SettingsScreen(vm, settings, lastSettingsPage)
                    }
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
        }
    }
    if (buildDialog) BuildDialog(vm) { buildDialog = false }
    if (newProject) InputDialog(stringResource(R.string.workbench_new_project), stringResource(R.string.workbench_project_name), onDismiss = { newProject = false }, onConfirm = vm::newProject)
    newFile?.let { initial -> InputDialog(if (createFolder) stringResource(R.string.workbench_new_directory) else stringResource(R.string.workbench_new_file), stringResource(R.string.workbench_relative_path), initial, onDismiss = { newFile = null }, onConfirm = { vm.createFile(it, createFolder) }) }
    renameFile?.let { file -> InputDialog(stringResource(R.string.workbench_rename), stringResource(R.string.workbench_name), file.name, onDismiss = { renameFile = null }, onConfirm = { vm.renameFile(file.path, it) }) }
    deleteFile?.let { file -> ConfirmDialog(stringResource(R.string.workbench_confirm_delete, file.name), stringResource(R.string.workbench_delete_detail, file.path), stringResource(CoreR.string.action_delete), true, { deleteFile = null }) { vm.deleteFile(file.path) } }
    closing?.let { doc -> AlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.workbench_confirm_save, doc.name)) }, shape = RoundedCornerShape(8.dp),
        confirmButton = { TextButton(onClick = { vm.closeTab(doc); closing = null }) { Text(stringResource(R.string.workbench_save_and_close)) } },
        dismissButton = { Row { TextButton(onClick = { vm.closeTab(doc, true); closing = null }) { Text(stringResource(R.string.workbench_dont_save)) }; TextButton(onClick = { closing = null }) { Text(stringResource(CoreR.string.action_cancel)) } } }) }
    if (renameSymbol) InputDialog(stringResource(R.string.workbench_rename_symbol), stringResource(R.string.workbench_new_name), onDismiss = { renameSymbol = false }, onConfirm = vm::renameSymbol)
    if (vm.commandPalette) CommandPalette(vm, onImport = { importer.launch(null) }, onBuild = { buildDialog = true }, onNew = { newFile = ""; createFolder = false })
    LanguageDialogs(vm)
}

@Composable private fun Header(vm: IdeViewModel, style: UiStyle, wide: Boolean, compact: Boolean, onBuild: () -> Unit, onImport: () -> Unit, onNewProject: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(false) }
    if (style == UiStyle.ACS) {
        AcsHeader(vm, compact, onBuild) { dismiss -> ProjectMenu(vm, onImport, onNewProject, dismiss) }
        return
    }
    Row(Modifier.fillMaxWidth().height(if (compact) 44.dp else 56.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Code, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp)); Text("Aster", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (wide) {
            Spacer(Modifier.width(28.dp))
            Box {
                Row(Modifier.clickable { projects = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp)); Text(vm.workspace?.name ?: stringResource(R.string.workbench_workspace), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(projects, { projects = false }) { vm.recent.forEach { w -> DropdownMenuItem(text = { Text(w.name) }, onClick = { vm.switchProject(w); projects = false }) } }
            }
        }
        Spacer(Modifier.weight(1f))
        if (wide) ToolButton(Icons.Outlined.Search, stringResource(R.string.workbench_command_palette)) { vm.commandPalette = true }
        ToolButton(Icons.Outlined.Save, stringResource(CoreR.string.action_save), enabled = vm.active != null) { vm.save() }
        ToolButton(if (vm.buildRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, if (vm.buildRunning) stringResource(R.string.workbench_stop_task) else stringResource(R.string.workbench_build_run), tint = if (vm.buildRunning) Coral else MaterialTheme.colorScheme.primary) { if (vm.buildRunning) vm.cancelBuild() else onBuild() }
        Box {
            ToolButton(Icons.Outlined.MoreVert, stringResource(R.string.workbench_project_menu)) { more = true }
            DropdownMenu(more, { more = false }) { ProjectMenu(vm, onImport, onNewProject) { more = false } }
        }
    }
}

@android.annotation.SuppressLint("SourceLockedOrientationActivity")
@Composable private fun ProjectMenu(vm: IdeViewModel, onImport: () -> Unit, onNewProject: () -> Unit, dismiss: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DropdownMenuItem(text = { Text(stringResource(R.string.workbench_open_project)) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { onImport(); dismiss() })
    DropdownMenuItem(text = { Text(stringResource(R.string.workbench_new_project)) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = { onNewProject(); dismiss() })
    DropdownMenuItem(text = { Text(stringResource(R.string.workbench_command_palette)) }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { vm.commandPalette = true; dismiss() })
    vm.recent.forEach { w -> DropdownMenuItem(text = { Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { vm.switchProject(w); dismiss() }) }
    Line()
    WorkbenchFeatureMenu(vm, dismiss)
    Line()
    if (activity != null) {
        DropdownMenuItem(text = { Text(if (landscape) stringResource(R.string.workbench_portrait) else stringResource(R.string.workbench_landscape)) }, leadingIcon = { Icon(Icons.Outlined.ScreenRotation, null) }, onClick = {
            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            dismiss()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.workbench_system_rotation)) }, onClick = { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; dismiss() })
    }
    DropdownMenuItem(text = { Text(stringResource(CoreR.string.action_settings)) }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, onClick = { vm.settingsPage = "index"; dismiss() })
}

@Composable private fun Rail(vm: IdeViewModel) {
    BoxWithConstraints(Modifier.width(44.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        val compact = maxHeight < 340.dp
        val size = if (compact) 30 else 36
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
            ToolButton(Icons.Outlined.FolderCopy, stringResource(R.string.settings_files), vm.showSidebar && vm.sidebar == "files", size = size) { vm.showSidebar = if (vm.sidebar == "files") !vm.showSidebar else true; vm.sidebar = "files" }
            ToolButton(Icons.Outlined.Search, stringResource(R.string.workbench_project_search), vm.showSidebar && vm.sidebar == "search", size = size) { vm.showSidebar = if (vm.sidebar == "search") !vm.showSidebar else true; vm.sidebar = "search" }
            ToolButton(Icons.Outlined.SmartToy, stringResource(R.string.workbench_agent_workspace), vm.showAgent, size = size) { vm.openAgent() }
            ToolButton(Icons.Outlined.Terminal, stringResource(R.string.workbench_terminal), vm.showPanel && vm.panel == "terminal", size = size) { vm.showPanel = if (vm.panel == "terminal") !vm.showPanel else true; vm.panel = "terminal" }
            ToolButton(Icons.Outlined.ErrorOutline, stringResource(R.string.workbench_problems), vm.showPanel && vm.panel == "problems", size = size) { vm.showPanel = if (vm.panel == "problems") !vm.showPanel else true; vm.panel = "problems" }
            Spacer(Modifier.height(if (compact) 4.dp else 24.dp))
            ToolButton(Icons.Outlined.DataObject, stringResource(R.string.settings_lsp), size = size) { vm.settingsPage = "lsp" }
            ToolButton(Icons.Outlined.Settings, stringResource(CoreR.string.action_settings), size = size) { vm.settingsPage = "index" }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Explorer(
    vm: IdeViewModel,
    modifier: Modifier,
    onImport: () -> Unit,
    onNew: (Boolean) -> Unit,
    onRename: (FileNode) -> Unit,
    onDelete: (FileNode) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpened: () -> Unit = {},
) {
    var expanded by remember(vm.workspace?.id) { mutableStateOf(setOf("src")) }
    var filter by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.sidebar == "search") stringResource(R.string.workbench_project_search) else stringResource(R.string.workbench_explorer), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolButton(Icons.Outlined.NoteAdd, stringResource(R.string.workbench_new_file), size = 32) { onNew(false) }
            ToolButton(Icons.Outlined.CreateNewFolder, stringResource(R.string.workbench_new_directory), size = 32) { onNew(true) }
            ToolButton(Icons.Outlined.FolderOpen, stringResource(R.string.workbench_import_directory), size = 32, onClick = onImport)
            ToolButton(ImageVector.vectorResource(R.drawable.ic_sidebar_terminal), stringResource(R.string.terminal_page_open), size = 40, onClick = onOpenTerminal)
        }
        if (vm.sidebar == "search") {
            OutlinedTextField(search, { search = it; vm.searchWorkspace(it) }, singleLine = true, placeholder = { Text(stringResource(R.string.workbench_search_project_contents)) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) }, modifier = Modifier.fillMaxWidth().padding(10.dp))
            if (vm.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.workbench_search_results, vm.workspaceHits.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.weight(1f)) { items(vm.workspaceHits) { hit ->
                Column(Modifier.fillMaxWidth().clickable { vm.navigate(hit.path, LspPosition(hit.line, hit.column)); onOpened() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("${hit.path}:${hit.line + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(hit.preview.trim(), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } }
        } else {
            Row(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(vm.workspace?.name ?: stringResource(R.string.workbench_workspace), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ToolButton(Icons.Outlined.Refresh, stringResource(R.string.workbench_refresh_files), size = 28) { vm.refresh() }
            }
            TextField(filter, { filter = it }, singleLine = true, placeholder = { Text(stringResource(R.string.workbench_filter_files), style = MaterialTheme.typography.bodySmall) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(16.dp)) }, colors = TextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
            val visible = remember(vm.nodes, expanded, filter) { vm.nodes.filter { node ->
                if (filter.isNotBlank()) node.path.contains(filter, true)
                else {
                    val parts = node.path.split('/').dropLast(1)
                    parts.indices.all { index -> parts.take(index + 1).joinToString("/") in expanded }
                }
            } }
            LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) { items(visible, key = { it.path }) { node ->
                Box {
                    val selected = vm.activePath == node.path
                    Row(Modifier.fillMaxWidth().height(36.dp).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .combinedClickable(onClick = {
                            if (node.directory) expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
                            else { vm.open(node.path); onOpened() }
                        }, onLongClick = { menu = node.path }).padding(start = (12 + node.depth * 14).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (node.directory) Icon(if (node.path in expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Spacer(Modifier.width(14.dp))
                        Icon(if (node.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null, Modifier.size(16.dp), tint = if (node.directory) Amber else fileColor(node.path))
                        Spacer(Modifier.width(8.dp))
                        Text(node.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vm.documents.any { it.path == node.path && it.dirty }) Box(Modifier.size(5.dp).background(Amber, RoundedCornerShape(3.dp)))
                        ToolButton(Icons.Outlined.MoreVert, stringResource(R.string.workbench_node_menu, node.name), size = 28) { menu = node.path }
                    }
                    DropdownMenu(menu == node.path, { menu = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.workbench_rename)) }, onClick = { onRename(node); menu = null })
                        DropdownMenuItem(text = { Text(stringResource(CoreR.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = { onDelete(node); menu = null })
                    }
                }
            } }
            Line()
            Text(stringResource(R.string.workbench_files_tabs, vm.nodes.count { !it.directory }, vm.documents.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable private fun Tabs(vm: IdeViewModel, compact: Boolean, onClose: (EditorDocument) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().height(if (compact) 34.dp else 42.dp).background(MaterialTheme.colorScheme.surface)) {
        items(vm.documents, key = { it.path }) { doc ->
            val selected = vm.activePath == doc.path
            val indicator by animateFloatAsState(if (selected) 1f else 0f, tween(LocalMotion.current.quick), label = "tabIndicator")
            Column(Modifier.widthIn(min = 110.dp, max = 220.dp).fillMaxHeight().background(if (selected) MaterialTheme.colorScheme.background else Color.Transparent).clickable { vm.open(doc.path) }) {
                Row(Modifier.weight(1f).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Description, null, Modifier.size(14.dp), tint = fileColor(doc.path)); Spacer(Modifier.width(7.dp))
                    Text(doc.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    if (doc.dirty) Box(Modifier.size(5.dp).background(Amber, RoundedCornerShape(3.dp)))
                    ToolButton(Icons.Outlined.Close, stringResource(R.string.workbench_close_document, doc.name), size = 28) { onClose(doc) }
                }
                Box(Modifier.height(2.dp).fillMaxWidth().graphicsLayer { alpha = indicator }.background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

@Composable private fun Breadcrumb(vm: IdeViewModel, compact: Boolean, onRename: () -> Unit) {
    var actions by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(if (compact) 28.dp else 36.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vm.activePath?.replace("/", "  /  ") ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (vm.active?.large == true) Text(stringResource(R.string.workbench_large_file), style = MaterialTheme.typography.labelSmall, color = Amber)
        ToolButton(Icons.Outlined.FindInPage, stringResource(R.string.workbench_find_replace), vm.showFind, vm.active != null, size = 32) { vm.showFind = !vm.showFind }
        Box {
            ToolButton(Icons.Outlined.MoreHoriz, stringResource(R.string.workbench_code_actions), enabled = vm.active != null, size = 32) { actions = true }
            DropdownMenu(actions, { actions = false }) {
                listOf(
                    "hover" to R.string.workbench_type_and_docs, "definition" to R.string.workbench_go_to_definition,
                    "references" to R.string.workbench_find_references, "signature" to R.string.workbench_signature,
                    "format" to R.string.workbench_format_document, "symbols" to R.string.workbench_document_symbols,
                    "actions" to R.string.workbench_code_actions
                ).forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { vm.languageAction(id); actions = false })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.workbench_rename_symbol)) }, onClick = { onRename(); actions = false })
                Line()
                DropdownMenuItem(text = { Text(if (vm.active?.readOnly == true) stringResource(R.string.workbench_allow_editing) else stringResource(R.string.workbench_read_only)) }, onClick = { vm.active?.let { it.readOnly = !it.readOnly }; actions = false })
            }
        }
    }
}

@Composable private fun EditorKeys(vm: IdeViewModel, compact: Boolean) {
    val settings by vm.settings.flow.collectAsState()
    Line()
    Row(Modifier.fillMaxWidth().height(if (compact) 32.dp else 40.dp).background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(Icons.Outlined.Undo, stringResource(R.string.workbench_undo), enabled = vm.active != null, size = 36) { vm.editor?.undo() }
        ToolButton(Icons.Outlined.Redo, stringResource(R.string.workbench_redo), enabled = vm.active != null, size = 36) { vm.editor?.redo() }
        Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
        ToolButton(Icons.Outlined.KeyboardTab, stringResource(R.string.workbench_indent), enabled = vm.active?.readOnly == false, size = 36) { vm.editor?.insertText(if (settings.useTabs) "\t" else " ".repeat(settings.tabWidth), 0) }
        listOf("{", "}", "(", ")", "[", "]", ";", "=", "_", "/").forEach { symbol ->
            TextButton(onClick = { vm.editor?.insertText(symbol, 0) }, enabled = vm.active?.readOnly == false, modifier = Modifier.width(34.dp).fillMaxHeight(), contentPadding = PaddingValues(0.dp)) { Text(symbol, fontFamily = Mono, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun StatusBar(vm: IdeViewModel, settings: IdeSettings, wide: Boolean) {
    val errors = vm.diagnostics.values.flatten().count { it.severity == 1 }
    val warnings = vm.diagnostics.values.flatten().count { it.severity == 2 }
    val language = vm.activePath?.let { vm.catalog.forPath(it)?.languageId(it) } ?: "text"
    Row(Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable { vm.settingsPage = "runtime" }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Terminal, null, Modifier.size(13.dp), tint = if (vm.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(5.dp)); Text(if (vm.connected) "Termux" else stringResource(R.string.workbench_local), style = MaterialTheme.typography.labelSmall, color = if (vm.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Row(Modifier.clickable { vm.panel = "problems"; vm.showPanel = true }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(12.dp), tint = if (errors > 0) Coral else MaterialTheme.colorScheme.onSurfaceVariant); Text(" $errors", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(7.dp)); Icon(Icons.Outlined.WarningAmber, null, Modifier.size(12.dp), tint = if (warnings > 0) Amber else MaterialTheme.colorScheme.onSurfaceVariant); Text(" $warnings", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        if (wide) { Text("${if (settings.useTabs) "Tab" else "Spaces"}: ${settings.tabWidth}   UTF-8   ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("${vm.caret.line + 1}:${vm.caret.character + 1}", style = MaterialTheme.typography.labelSmall, fontFamily = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp)); Text(language, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { vm.settingsPage = "lsp" })
    }
}

@Composable private fun FindBar(vm: IdeViewModel) {
    var replace by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(vm.findQuery, { vm.findQuery = it; vm.searchInFile() }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.workbench_find)) }, textStyle = MaterialTheme.typography.bodyMedium)
            ToolButton(Icons.Outlined.ExpandMore, stringResource(R.string.workbench_replace), replace, size = 32) { replace = !replace }
            ToolButton(Icons.Outlined.Close, stringResource(R.string.workbench_close_find), size = 32) { vm.showFind = false }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = vm.matchCase, onClick = { vm.matchCase = !vm.matchCase; vm.searchInFile() }, label = { Text("Aa", fontFamily = Mono) })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = vm.regexSearch, onClick = { vm.regexSearch = !vm.regexSearch; vm.searchInFile() }, label = { Text(".*", fontFamily = Mono) })
            Spacer(Modifier.weight(1f)); Text("${if (vm.matches.isEmpty()) 0 else vm.matchIndex + 1}/${vm.matches.size}", style = MaterialTheme.typography.bodySmall)
            ToolButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.workbench_previous_match), enabled = vm.matches.isNotEmpty(), size = 32) { vm.nextMatch(-1) }
            ToolButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.workbench_next_match), enabled = vm.matches.isNotEmpty(), size = 32) { vm.nextMatch(1) }
        }
        if (replace) Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(vm.replaceQuery, { vm.replaceQuery = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.workbench_replace_with)) }, textStyle = MaterialTheme.typography.bodyMedium)
            ToolButton(Icons.Outlined.FindReplace, stringResource(R.string.workbench_replace_current), enabled = vm.matches.isNotEmpty(), size = 36) { vm.replace(false) }
            ToolButton(Icons.Outlined.DoneAll, stringResource(R.string.workbench_replace_all), enabled = vm.matches.isNotEmpty(), size = 36) { vm.replace(true) }
        }
    }
}
