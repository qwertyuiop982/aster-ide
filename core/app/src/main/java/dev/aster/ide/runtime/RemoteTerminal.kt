package dev.aster.ide.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.UUID

class RemoteTerminal(private val context: Context, private val bridge: BridgeClient, private val scope: CoroutineScope, private val log: (String) -> Unit) : TerminalSession.ExternalTransport, Closeable {
    val session = MutableStateFlow<TerminalSession?>(null)
    val state = MutableStateFlow("未连接")
    private var view = WeakReference<TerminalView>(null)
    private var connection: BridgeConnection? = null
    private var reader: Job? = null
    private var writer: Job? = null
    private var packets = Channel<JSONObject>(64)
    private var columns = 80
    private var rows = 24
    var control = false
    private var palette = TerminalPalette.forStyle(false, true)
    private var paletteSession: TerminalSession? = null
    fun setPalette(next: TerminalPalette) {
        if (palette == next && paletteSession === session.value) return
        palette = next
        applyPalette(session.value)
    }
    private fun applyPalette(target: TerminalSession?) {
        // Termux stores defaults globally and actual colours per emulator. Update both on a UI theme change.
        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        palette.ansi.forEachIndexed { index, color -> defaults[index] = color }
        defaults[TextStyle.COLOR_INDEX_FOREGROUND] = palette.foreground
        defaults[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background
        defaults[TextStyle.COLOR_INDEX_CURSOR] = palette.cursor
        target?.emulator?.mColors?.reset()
        paletteSession = target
        view.get()?.apply { setBackgroundColor(palette.background); onScreenUpdated() }
    }

    fun attach(terminalView: TerminalView) { view = WeakReference(terminalView); terminalView.setTerminalViewClient(viewClient); session.value?.let { terminalView.attachSession(it) } }
    suspend fun connect(workspace: String, scrollback: Int) {
        close()
        state.value = "连接中"
        val outgoing = Channel<JSONObject>(64)
        packets = outgoing
        val conn = bridge.open("terminal", workspace, JSONObject().put("job", UUID.randomUUID().toString()))
        val ack = withContext(Dispatchers.IO) { conn.receive() }
        check(ack.optBoolean("ok")) { ack.optString("error") }
        connection = conn
        conn.socket.soTimeout = 0
        val next = withContext(Dispatchers.Main) {
            TerminalSession.createRemote(this@RemoteTerminal, scrollback, client).also {
                it.initializeEmulator(columns, rows, 8, 16)
                session.value = it
                applyPalette(it)
                view.get()?.attachSession(it)
            }
        }
        state.value = "bash"
        writer = scope.launch(Dispatchers.IO) {
            try { for (p in outgoing) conn.send(p) } catch (e: Exception) { if (isActive) log("终端写入: ${e.message}") }
        }
        reader = scope.launch(Dispatchers.IO) {
            var code = 0
            try {
                while (isActive) {
                    val p = conn.receive()
                    if (p.optString("type") == "output") {
                        val bytes = Base64.decode(p.getString("data"), Base64.NO_WRAP)
                        next.appendRemoteBytes(bytes, bytes.size)
                    } else if (p.optString("type") == "exit") { code = p.optInt("code"); break }
                    else if (p.optString("type") == "error") error(p.optString("error"))
                }
            } catch (e: Exception) { if (isActive) log("终端: ${e.message}") }
            finally {
                conn.close(); outgoing.close()
                withContext(NonCancellable + Dispatchers.Main) {
                    next.finishRemote(code)
                    if (session.value === next) state.value = "已断开"
                }
            }
        }
        resize(columns, rows)
    }
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (!packets.trySend(JSONObject().put("type", "input").put("data", Base64.encodeToString(data.copyOfRange(offset, offset + count), Base64.NO_WRAP))).isSuccess) log("终端输入队列已满")
    }
    fun input(value: String) { val bytes = value.toByteArray(); write(bytes, 0, bytes.size) }
    override fun resize(columns: Int, rows: Int) {
        this.columns = columns; this.rows = rows
        packets.trySend(JSONObject().put("type", "resize").put("columns", columns).put("rows", rows))
    }
    override fun close() {
        connection?.close(); connection = null
        reader?.cancel(); writer?.cancel(); packets.close()
        session.value = null; state.value = "未连接"
    }
    fun detach(expected: TerminalView? = null) { if (expected == null || view.get() === expected) view.clear() }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { if (session.value === changedSession) view.get()?.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) { if (session.value === changedSession) state.value = changedSession.title ?: "bash" }
        override fun onSessionFinished(finishedSession: TerminalSession) { if (session.value === finishedSession) state.value = "已结束" }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Terminal", text)) }
        override fun onPasteTextFromClipboard(session: TerminalSession) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::input) }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) { view.get()?.onScreenUpdated() }
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String, message: String) { log("$tag: $message") }
        override fun logWarn(tag: String, message: String) { log("$tag: $message") }
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { log("$tag: $message ${e.message}") }
        override fun logStackTrace(tag: String, e: Exception) { log("$tag: ${e.message}") }
    }
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent) { view.get()?.let { (it.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) } }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey(): Boolean = control
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean { if (control) control = false; return false }
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String, message: String) { log("$tag: $message") }
        override fun logWarn(tag: String, message: String) { log("$tag: $message") }
        override fun logInfo(tag: String, message: String) = Unit
        override fun logDebug(tag: String, message: String) = Unit
        override fun logVerbose(tag: String, message: String) = Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { log("$tag: $message ${e.message}") }
        override fun logStackTrace(tag: String, e: Exception) { log("$tag: ${e.message}") }
    }
}
