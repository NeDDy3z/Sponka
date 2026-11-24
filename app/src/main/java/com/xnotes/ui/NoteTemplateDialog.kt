package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists the notes saved as templates under [root], searchable by name and sortable; a tap picks one. */
@Composable
internal fun NoteTemplateDialog(editor: Editor, root: String, onDismiss: () -> Unit, onPick: (BrowseEntry) -> Unit) {
    val palette = LocalPalette.current
    val templates by produceState<List<BrowseEntry>?>(null, root) {
        value = withContext(Dispatchers.IO) { editor.noteTemplateEntries(root) }
    }
    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf(ExplorerSortKey.NAME) }
    var descending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.use_template)) },
        text = {
            Column(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ExplorerSortKey.NAME, ExplorerSortKey.MODIFIED, ExplorerSortKey.CREATED).forEach { k ->
                        val active = sortKey == k
                        ExplorerChip(sortLabel(k), active, trailing = if (active) (if (descending) XnotesIcons.arrowDown else XnotesIcons.arrowUp) else null) {
                            if (active) descending = !descending else { sortKey = k; descending = k != ExplorerSortKey.NAME }
                        }
                    }
                }
                val list = templates
                    ?.filter { it.name.contains(query.trim(), ignoreCase = true) }
                    ?.sortedWith(explorerComparator(sortKey, descending) { it.created })
                when {
                    list == null -> EmptyNote(stringResource(R.string.loading))
                    list.isEmpty() -> EmptyNote(stringResource(R.string.note_templates_empty))
                    else -> LazyColumn(Modifier.fillMaxWidth()) {
                        items(list, key = { it.documentUri }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onPick(t) }
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(if (DocumentKind.ofName(t.name) == DocumentKind.CANVAS) XnotesIcons.canvas else XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                                Text(DocumentKind.stripSuffix(t.name), color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
