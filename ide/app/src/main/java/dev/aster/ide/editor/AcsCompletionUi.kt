/*
 * ACS presentation adapted from Android Code Studio / AndroidIDE
 * CompletionListAdapter and EditorCompletionLayout, GPL-3.0-or-later.
 * Upstream revision and source files are retained in research/acs-ui.
 */
package dev.aster.ide.editor

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import io.github.rosemoe.sora.widget.component.DefaultCompletionLayout
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** Optional display metadata; requesting and applying completions stay unchanged. */
interface CompletionPresentation { val completionKind: Int }

class AcsCompletionLayout : DefaultCompletionLayout() {
    override fun onApplyColorScheme(colorScheme: EditorColorScheme) {
        super.onApplyColorScheme(colorScheme)
        val list = completionList
        val density = list.resources.displayMetrics.density
        (list.parent as? ViewGroup)?.background = GradientDrawable().apply {
            cornerRadius = 8f * density
            setStroke(density.toInt().coerceAtLeast(1), colorScheme.getColor(EditorColorScheme.COMPLETION_WND_CORNER))
            setColor(colorScheme.getColor(EditorColorScheme.COMPLETION_WND_BACKGROUND))
        }
        list.updateLayoutParams<ViewGroup.MarginLayoutParams> { setMargins(density.toInt(), density.toInt(), density.toInt(), density.toInt()) }
    }
}

class AcsCompletionAdapter : EditorCompletionAdapter() {
    private data class Holder(val badge: TextView, val label: TextView, val detail: TextView, val type: TextView)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    override fun getItemHeight() = dp(40)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?, isCurrentCursorPosition: Boolean): View {
        val root = convertView as? LinearLayout ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(6), dp(2))
            layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight)
            val badge = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            addView(badge, LinearLayout.LayoutParams(dp(22), dp(22)))
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
            val label = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
            val detail = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END }
            text.addView(label)
            text.addView(detail)
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            val type = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.START; maxWidth = dp(84) }
            addView(type)
            tag = Holder(badge, label, detail, type)
        }
        val holder = root.tag as Holder
        val item = getItem(position)
        val kind = (item as? CompletionPresentation)?.completionKind
        val type = if (kind != null) names.getOrNull(kind) ?: "Text" else if (item.desc?.toString() == "Keyword") "Keyword" else "Identifier"
        val primary = getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY)
        val secondary = getThemeColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY)
        val current = getThemeColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT)
        holder.badge.text = type.take(1)
        holder.badge.setTextColor(primary)
        holder.badge.background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(current) }
        holder.label.text = item.label
        holder.label.setTextColor(primary)
        holder.detail.text = item.desc?.toString().orEmpty()
        holder.detail.setTextColor(secondary)
        holder.detail.visibility = if (holder.detail.text.isBlank()) View.GONE else View.VISIBLE
        holder.type.text = if (kind == null) "本地 · $type" else type
        holder.type.setTextColor(secondary)
        root.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(if (isCurrentCursorPosition) current else android.graphics.Color.TRANSPARENT) }
        root.contentDescription = "${holder.label.text} · ${holder.type.text} · ${holder.detail.text}"
        return root
    }

    private val names = listOf("Text", "Text", "Method", "Function", "Constructor", "Field", "Variable", "Class", "Interface", "Module", "Property", "Unit", "Value", "Enum", "Keyword", "Snippet", "Color", "File", "Reference", "Folder", "EnumMember", "Constant", "Struct", "Event", "Operator", "TypeParameter")
}