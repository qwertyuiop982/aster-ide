package dev.aster.ide.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.aster.ide.editor.TextBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Continuous scrolling; only visible, bounded paragraphs enter text layout. */
@Composable fun ChunkedText(text: String, modifier: Modifier = Modifier) {
    val blocks by produceState<List<String>>(emptyList(), text) {
        value = withContext(Dispatchers.Default) { TextBlocks.split(text) }
    }
    SelectionContainer {
        LazyColumn(modifier.fillMaxWidth()) {
            items(blocks.size) { index ->
                val block = blocks[index]
                val display = if (index < blocks.lastIndex) block.removeSuffix("\n").removeSuffix("\r") else block
                Text(display.ifEmpty { " " }, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}