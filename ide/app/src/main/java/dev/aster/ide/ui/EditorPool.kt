package dev.aster.ide.ui

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import dev.aster.ide.IdeViewModel
import dev.aster.ide.data.EditorDocument
import dev.aster.ide.data.IdeSettings
import dev.aster.ide.editor.EditorLanguage
import dev.aster.ide.editor.AcsCompletionAdapter
import dev.aster.ide.editor.AcsCompletionLayout
import dev.aster.ide.editor.Highlighter
import dev.aster.ide.lsp.LspPosition
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Lives for the activity, including visits to settings. Each analyzer belongs to exactly one view. */
class EditorPool(private val context: Context, private val vm: IdeViewModel) : Closeable {
    private val entries = LinkedHashMap<EditorDocument, Entry>(4, 0.75f, true)

    suspend fun acquire(document: EditorDocument, settings: IdeSettings, dark: Boolean): Entry {
        entries[document]?.let {
            vm.editorCacheHits++
            return it
        }
        val id = if (document.large) null else vm.catalog.forPath(document.path)?.id
        withContext(Dispatchers.IO) { Highlighter.prepareLanguage(context, id) }
        if (document.buffer == null) document.buffer = withContext(Dispatchers.Default) { Content(document.text) }
        val entry = Entry(document, settings, dark, id)
        entries[document] = entry
        vm.editorInstancesCreated++
        trim(vm.documents.toList(), document, settings.editorCacheSize)
        vm.editorCachedCount = entries.size
        return entry
    }
    fun trim(documents: List<EditorDocument>, active: EditorDocument?, limit: Int) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in documents) { entry.value.close(); iterator.remove() }
        }
        while (entries.size > limit) {
            val victim = entries.entries.firstOrNull { it.key !== active } ?: break
            victim.value.close()
            entries.remove(victim.key)
        }
        vm.editorCachedCount = entries.size
    }
    override fun close() {
        if (entries.values.any { it.editor === vm.editor }) vm.editor = null
        entries.values.forEach { it.close() }
        entries.clear()
        vm.editorCachedCount = 0
    }

    inner class Entry(val document: EditorDocument, settings: IdeSettings, dark: Boolean, id: String?) : Closeable {
        private var binding = true
        private var applied: IdeSettings? = null
        private val language = if (id == null) EmptyLanguage() else Highlighter.language(id)
        val editor = CodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            setTextLetterSpacing(0f)
            props.allowFullscreen = false
            props.cacheRenderNodeForLongLines = (document.buffer?.length ?: 0) <= 131072
            props.stickyScroll = false
            props.highlightMatchingDelimiters = true
            props.autoCompletionOnComposing = false
            props.clipboardTextLengthLimit = 512 * 1024
            isHighlightCurrentLine = true
        }
        private val edits = editor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            if (!binding && event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) vm.edited(document)
        }
        private val selections = editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            if (!binding) {
                saveCursor()
                if (vm.active === document) vm.caret = LspPosition(document.line, document.column)
            }
        }
        init {
            editor.colorScheme = Highlighter.colors(dark, vm.settings.value.uiStyle == dev.aster.ide.data.UiStyle.ACS)
            val content = checkNotNull(document.buffer)
            content.undoManager.setMaxUndoStackSize(if (document.large) 30 else 200)
            editor.setEditorLanguage(EditorLanguage(language, document, vm.lsp, vm.settings))
            editor.getComponent(EditorAutoCompletion::class.java).apply {
                setLayout(AcsCompletionLayout())
                setAdapter(AcsCompletionAdapter())
            }
            editor.setText(content, true, null)
            document.snapshot = { content.toString() }
            applySettings(settings, false)
            val line = document.line.coerceIn(0, content.lineCount - 1)
            editor.setSelection(line, document.column.coerceIn(0, content.getColumnCount(line)))
            if (document.scrollX != 0 || document.scrollY != 0) {
                editor.scroller.startScroll(document.scrollX, document.scrollY, 0, 0, 0)
                editor.scroller.abortAnimation()
            }
            binding = false
            Log.d("AsterEditor", "create ${document.path}")
        }
        fun applySettings(settings: IdeSettings, paused: Boolean) {
            val previous = applied
            if (previous?.fontSize != settings.fontSize) editor.setTextSize(settings.fontSize.toFloat())
            if (previous?.tabWidth != settings.tabWidth) {
                editor.tabWidth = settings.tabWidth
                (language as? TextMateLanguage)?.tabSize = settings.tabWidth
            }
            if (previous?.wordWrap != settings.wordWrap) editor.isWordwrap = settings.wordWrap
            if (previous?.lineNumbers != settings.lineNumbers) editor.isLineNumberEnabled = settings.lineNumbers
            if (previous?.whitespace != settings.whitespace) editor.nonPrintablePaintingFlags = if (settings.whitespace)
                CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_INNER or CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING else 0
            editor.isEditable = !paused && !document.readOnly
            editor.getComponent(EditorAutoCompletion::class.java).apply {
                isEnabled = settings.autoComplete && !paused && !document.large
                if (previous?.animations != settings.animations || previous?.reducedMotion != settings.reducedMotion) {
                    setEnabledAnimation(settings.animations && !settings.reducedMotion)
                }
            }
            applied = settings
        }
        private fun saveCursor() {
            document.line = editor.cursor.leftLine
            document.column = editor.cursor.leftColumn
            document.scrollX = editor.offsetX
            document.scrollY = editor.offsetY
        }
        fun hide() {
            if (editor.isReleased) return
            saveCursor()
            editor.getComponent(EditorAutoCompletion::class.java).apply { cancelCompletion(); hide() }
            editor.clearFocus()
        }
        override fun close() {
            if (editor.isReleased) return
            hide()
            edits.unsubscribe()
            selections.unsubscribe()
            editor.release()
            if (vm.editor === editor) vm.editor = null
            Log.d("AsterEditor", "release ${document.path}")
        }
    }
}