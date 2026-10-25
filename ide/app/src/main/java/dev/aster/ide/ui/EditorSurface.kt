package dev.aster.ide.ui

import android.view.ViewGroup
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.editor.Highlighter
import dev.aster.ide.lsp.LspPosition
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun EditorSurface(vm: IdeViewModel, settings: IdeSettings, pool: EditorPool, modifier: Modifier = Modifier, obscured: Boolean = false) {
    val dark = LocalDark.current
    val motion = LocalMotion.current
    val document = vm.active
    var entry by remember { mutableStateOf<EditorPool.Entry?>(null) }
    var loading by remember { mutableStateOf(false) }
    val current = entry?.takeIf { it.document === document }
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(current?.editor, vm.loadingDocument, motion) {
        opacity.snapTo(0f)
        if (current != null && !vm.loadingDocument) opacity.animateTo(1f, tween(motion.quick))
    }

    LaunchedEffect(document, pool) {
        entry?.hide()
        vm.editor = null
        if (document == null) { entry = null; return@LaunchedEffect }
        loading = true
        try {
            val next = pool.acquire(document, settings, dark)
            entry = next
            next.applySettings(settings, vm.connecting || vm.loadingDocument)
            vm.editor = next.editor
            vm.caret = LspPosition(next.editor.cursor.leftLine, next.editor.cursor.leftColumn)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { vm.log("编辑器加载: ${e.message}"); vm.notify("文件加载失败：${e.message}") }
        finally { loading = false }
    }
    DisposableEffect(current) {
        onDispose {
            current?.hide()
            if (current != null && vm.editor === current.editor) vm.editor = null
        }
    }
    LaunchedEffect(vm.documents.toList(), settings.editorCacheSize) { pool.trim(vm.documents.toList(), document, settings.editorCacheSize) }
    LaunchedEffect(current, dark) {
        current?.editor?.let { editor ->
            val colors = Highlighter.colors(dark, vm.settings.value.uiStyle == dev.aster.ide.data.UiStyle.ACS)
            if (editor.colorScheme !== colors) editor.colorScheme = colors
        }
    }
    LaunchedEffect(current, settings, document?.readOnly, vm.connecting, vm.loadingDocument, vm.settingsPage, obscured) {
        current?.let { selected ->
            val showingSettings = vm.settingsPage != null
            selected.editor.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
            selected.editor.importantForAccessibility = if (showingSettings) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (showingSettings) {
                selected.hide()
                selected.editor.isEditable = false
                if (vm.editor === selected.editor) vm.editor = null
            } else {
                selected.applySettings(settings, vm.connecting || vm.loadingDocument)
                vm.editor = if (vm.connecting || vm.loadingDocument) null else selected.editor
            }
        }
    }
    LaunchedEffect(current, vm.diagnostics[document?.path], settings.diagnostics) {
        val selected = current ?: return@LaunchedEffect
        val content = selected.editor.text
        val diagnostics = vm.diagnostics[document?.path].orEmpty()
        val regions = withContext(Dispatchers.Default) {
            if (settings.diagnostics) diagnostics.mapNotNull { d ->
                runCatching {
                    val start = content.indexer.getCharIndex(d.range.start.line, d.range.start.character)
                    val end = content.indexer.getCharIndex(d.range.end.line, d.range.end.character)
                    if (start >= content.length) null else DiagnosticRegion(start, maxOf(start + 1, end).coerceAtMost(content.length), when (d.severity) {
                        1 -> DiagnosticRegion.SEVERITY_ERROR
                        2 -> DiagnosticRegion.SEVERITY_WARNING
                        else -> DiagnosticRegion.SEVERITY_TYPO
                    })
                }.getOrNull()
            } else emptyList()
        }
        selected.editor.diagnostics = DiagnosticsContainer().apply { addDiagnostics(regions) }
    }
    LaunchedEffect(vm.jump, current) {
        val target = vm.jump ?: return@LaunchedEffect
        val editor = current?.editor ?: return@LaunchedEffect
        if (target.path == document?.path) {
            val line = target.position.line.coerceIn(0, editor.text.lineCount - 1)
            editor.setSelection(line, target.position.character.coerceIn(0, editor.text.getColumnCount(line)))
            vm.jump = null
        }
    }
    Box(modifier.fillMaxSize()) {
        if (current != null) key(current.editor) {
            AndroidView(factory = {
                (current.editor.parent as? ViewGroup)?.removeView(current.editor)
                current.editor
            }, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value })
        }
        if (loading || vm.loadingDocument || (document != null && current == null)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(12.dp))
                Text(if (vm.loadingDocument) "读取文件…" else "准备文档…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}