package dev.aster.ide.editor

import android.content.Context
import android.os.Bundle
import android.os.Looper
import dev.aster.ide.data.EditorDocument
import dev.aster.ide.data.SettingsStore
import dev.aster.ide.lsp.*
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

object Highlighter {
    @Volatile private var initialized = false
    private var currentAcs: Boolean? = null
    private var currentTheme = "aster-dark"
    private var colorScheme: TextMateColorScheme? = null
    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
        for (mode in listOf("dark", "light")) {
            ThemeRegistry.getInstance().loadTheme(IThemeSource.fromInputStream(context.assets.open("grammars/$mode.json"), "$mode.json", Charsets.UTF_8))
        }
        ThemeRegistry.getInstance().setTheme("aster-dark")
        initialized = true
    }
    @Synchronized fun prepareLanguage(context: Context, id: String?) {
        initialize(context)
        if (id == null) return
        val scope = "source.aster.$id"
        if (GrammarRegistry.getInstance().findGrammar(scope) == null) {
            val source = IGrammarSource.fromInputStream(context.assets.open("grammars/$id.json"), "$id.json", Charsets.UTF_8)
            GrammarRegistry.getInstance().loadGrammar(DefaultGrammarDefinition.withLanguageConfiguration(source, "grammars/$id-config.json", id, scope))
        }
    }
    fun language(id: String): Language {
        check(initialized) { "Highlighter must be prepared off the UI thread" }
        return TextMateLanguage.create("source.aster.$id", true)
    }
    fun colors(dark: Boolean, acs: Boolean = false): TextMateColorScheme {
        val target = if (dark) "aster-dark" else "aster-light"
        if (currentTheme != target || currentAcs != acs) {
            ThemeRegistry.getInstance().setTheme(target)
            currentTheme = target; currentAcs = acs
            colorScheme = null
        }
        val scheme = colorScheme ?: TextMateColorScheme.create(ThemeRegistry.getInstance()).also { colorScheme = it }
        if (acs) {
            scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, if (dark) 0xFF080808.toInt() else 0xFFF8F8F8.toInt())
            scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, if (dark) 0xFF171717.toInt() else 0xFFF3F3F3.toInt())
            scheme.setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER, if (dark) 0xFF888888.toInt() else 0xFF777777.toInt())
        }
        return scheme
    }
}

class EditorLanguage(
    private val base: Language,
    private val document: EditorDocument,
    private val lsp: LspManager,
    private val settings: SettingsStore
) : Language by base {
    override fun useTab() = settings.value.useTabs
    override fun getInterruptionLevel() = Language.INTERRUPTION_LEVEL_STRONG
    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        // Sora calls this synchronous API from CompletionThread; interruption cancels the LSP request.
        if (Looper.myLooper() == Looper.getMainLooper() || !settings.value.autoComplete || document.large) return
        if (!lsp.ready(document.path)) { base.requireAutoComplete(content, position, publisher, extraArguments); return }
        val snapshot = document.snapshot?.invoke() ?: document.text
        val lineStart = snapshot.lastIndexOf('\n', position.index - 1) + 1
        var start = position.index.coerceAtMost(snapshot.length)
        while (start > lineStart && (snapshot[start - 1].isLetterOrDigit() || snapshot[start - 1] == '_')) start--
        val prefix = position.index - start
        try {
            val values = runBlocking { withTimeout(2500) { lsp.completions(document.path, snapshot, LspPosition(position.line, position.column)) } }
            for (entry in values) publisher.addItem(LspCompletion(entry, snapshot, prefix))
            publisher.updateList()
        } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        catch (_: Exception) { base.requireAutoComplete(content, position, publisher, extraArguments) }
    }
}

private class LspCompletion(private val entry: CompletionEntry, private val snapshot: String, private val prefix: Int) : CompletionItem(entry.label, entry.detail, null), CompletionPresentation {
    override val completionKind get() = entry.kind
    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        if (text.toString() != snapshot) return
        val range = entry.range ?: LspRange(LspPosition(line, (column - prefix).coerceAtLeast(0)), LspPosition(line, column))
        val edits = entry.additional + LspEdit(range, entry.insert)
        val valid = runCatching { TextCoordinates.apply(snapshot, edits) }.getOrNull() ?: return
        val primaryStart = TextCoordinates.offset(snapshot, range.start)
        var caret = primaryStart + entry.insert.length
        entry.additional.forEach { edit ->
            val start = TextCoordinates.offset(snapshot, edit.range.start)
            val end = TextCoordinates.offset(snapshot, edit.range.end)
            if (end <= primaryStart) caret += edit.text.length - (end - start)
        }
        text.beginBatchEdit()
        try {
            edits.map { Triple(TextCoordinates.offset(snapshot, it.range.start), TextCoordinates.offset(snapshot, it.range.end), it.text) }
                .sortedByDescending { it.first }.forEach { text.replace(it.first, it.second, it.third) }
        } finally { text.endBatchEdit() }
        val pos = TextCoordinates.position(valid, caret)
        editor.setSelection(pos.line, pos.character)
    }
}