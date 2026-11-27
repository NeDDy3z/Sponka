package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.platform.ReaderHtml
import com.xnotes.settings.ReaderMark
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The tabs of a read-only file's side panel. */
internal enum class FileSideTab { CONTENTS, BOOKMARKS, SEARCH }

/**
 * A read-only file's side panel, the notes' side panel without its Pages: the file's headings, its
 * bookmarks and a search through all of it. [onHeading], [onMark] and [onHit] go to what was tapped.
 */
@Composable
internal fun ReaderSidePanel(
    modifier: Modifier,
    tab: FileSideTab,
    onTab: (FileSideTab) -> Unit,
    parts: List<String>,
    at: Float,
    marks: List<ReaderMark>,
    onAddMark: (String) -> Unit,
    onRenameMark: (ReaderMark, String) -> Unit,
    onRemoveMark: (ReaderMark) -> Unit,
    defaultMarkLabel: String,
    onHeading: (ReaderHtml.PartHeading) -> Unit,
    onMark: (ReaderMark) -> Unit,
    onHit: (ReaderHtml.PartHit, String) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.width(224.dp).fillMaxHeight().background(palette.panel.toComposeColor()).then(modifier)) {
        SideTabStrip(FileSideTab.entries.size, tab.ordinal) {
            SideTab(tab == FileSideTab.CONTENTS, XnotesIcons.contents, stringResource(R.string.contents)) { onTab(FileSideTab.CONTENTS) }
            SideTab(tab == FileSideTab.BOOKMARKS, XnotesIcons.bookmark, stringResource(R.string.bookmarks)) { onTab(FileSideTab.BOOKMARKS) }
            SideTab(tab == FileSideTab.SEARCH, XnotesIcons.search, stringResource(R.string.search)) { onTab(FileSideTab.SEARCH) }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                FileSideTab.CONTENTS -> ContentsList(parts, onHeading)
                FileSideTab.BOOKMARKS -> MarksList(marks, at, defaultMarkLabel, onAddMark, onRenameMark, onRemoveMark, onMark)
                FileSideTab.SEARCH -> SearchList(parts, onHit)
            }
        }
    }
}

@Composable
private fun ContentsList(parts: List<String>, onHeading: (ReaderHtml.PartHeading) -> Unit) {
    val palette = LocalPalette.current
    val headings by produceHeadings(parts)
    val list = headings ?: return
    if (list.isEmpty()) { EmptyHint(stringResource(R.string.no_contents)); return }
    val top = list.minOf { it.level }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
        itemsIndexed(list) { i, h ->
            val depth = (h.level - top).coerceIn(0, 4)
            Text(
                h.title,
                color = (if (depth >= 2) palette.textDim else palette.text).toComposeColor(),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = if (depth == 0 && i > 0) 6.dp else 0.dp)
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onHeading(h) }
                    .padding(start = 10.dp + 14.dp * depth, end = 10.dp, top = 9.dp, bottom = 9.dp),
            )
        }
    }
}

/** The headings, read off the main thread: a book has thousands of paragraphs to look through. */
@Composable
private fun produceHeadings(parts: List<String>) = androidx.compose.runtime.produceState<List<ReaderHtml.PartHeading>?>(null, parts) {
    value = withContext(Dispatchers.Default) { ReaderHtml.headings(parts) }
}

@Composable
private fun MarksList(
    marks: List<ReaderMark>,
    at: Float,
    defaultLabel: String,
    onAdd: (String) -> Unit,
    onRename: (ReaderMark, String) -> Unit,
    onRemove: (ReaderMark) -> Unit,
    onMark: (ReaderMark) -> Unit,
) {
    val palette = LocalPalette.current
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ReaderMark?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(44.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(palette.selectionBackground.toComposeColor())
                .clickable(role = Role.Button) { adding = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(XnotesIcons.plus, null, tint = palette.selectionForeground.toComposeColor(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_bookmark), color = palette.selectionForeground.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (marks.isEmpty()) EmptyHint(stringResource(R.string.no_bookmarks))
        else LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(marks.sortedBy { it.at }) { i, m ->
                if (i > 0) Box(Modifier.padding(start = 44.dp, end = 8.dp).fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
                MarkRow(m, here = kotlin.math.abs(m.at - at) < 0.002f, onClick = { onMark(m) }, onRename = { renaming = m }, onRemove = { onRemove(m) })
            }
        }
    }
    if (adding) {
        var label by remember { mutableStateOf(defaultLabel) }
        val add = { onAdd(label.ifBlank { defaultLabel }); adding = false }
        SheetDialog(
            title = stringResource(R.string.add_bookmark),
            onDismiss = { adding = false },
            foot = { ConfirmButtons(stringResource(R.string.add), { adding = false }, onConfirm = add) },
        ) { DialogField(label, { label = it }, onDone = add) }
    }
    renaming?.let { m ->
        var label by remember(m) { mutableStateOf(m.label) }
        val rename = { if (label.isNotBlank()) { onRename(m, label); renaming = null } }
        SheetDialog(
            title = stringResource(R.string.rename_bookmark),
            onDismiss = { renaming = null },
            foot = { ConfirmButtons(stringResource(R.string.rename), { renaming = null }, enabled = label.isNotBlank(), onConfirm = rename) },
        ) { DialogField(label, { label = it }, onDone = rename) }
    }
}

/** One bookmark: its ribbon, filled when the reader is there, its name and how far in, rename and remove. */
@Composable
private fun MarkRow(mark: ReaderMark, here: Boolean, onClick: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalPalette.current
    val accent = palette.accent.toComposeColor()
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (here) XnotesIcons.bookmarkFilled else XnotesIcons.bookmark, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            mark.label, color = if (here) accent else palette.text.toComposeColor(), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text("${(mark.at * 100).roundToInt()}%", color = palette.textDim.toComposeColor(), fontSize = 12.sp, style = tabular(), modifier = Modifier.padding(horizontal = 6.dp))
        Icon(
            XnotesIcons.edit, stringResource(R.string.rename), tint = palette.textDim.toComposeColor(),
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRename).padding(9.dp),
        )
        Icon(
            XnotesIcons.close, stringResource(R.string.remove), tint = palette.textDim.toComposeColor(),
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove).padding(9.dp),
        )
    }
}

@Composable
private fun SearchList(parts: List<String>, onHit: (ReaderHtml.PartHit, String) -> Unit) {
    val palette = LocalPalette.current
    val focus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ReaderHtml.PartHit>?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query, parts) {
        hits = null
        if (query.isBlank()) return@LaunchedEffect
        delay(250) // typing on, the search waits for a pause
        hits = withContext(Dispatchers.Default) { ReaderHtml.search(parts, query) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)
                .height(36.dp)
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(XnotesIcons.search, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(stringResource(R.string.find_in_page), color = palette.textDim.toComposeColor(), fontSize = 13.sp, maxLines = 1)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = palette.text.toComposeColor(), fontSize = 13.sp),
                    cursorBrush = SolidColor(palette.accent.toComposeColor()),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    XnotesIcons.close, stringResource(R.string.clear_search), tint = palette.textDim.toComposeColor(),
                    modifier = Modifier.size(16.dp).clip(CircleShape).clickable { query = "" },
                )
            }
        }
        val list = hits
        when {
            query.isBlank() -> Unit
            list == null -> EmptyHint(stringResource(R.string.searching))
            list.isEmpty() -> EmptyHint(stringResource(R.string.no_matches))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                itemsIndexed(list) { _, h ->
                    Text(
                        buildAnnotatedString {
                            append(h.before)
                            withStyle(SpanStyle(color = palette.accent.toComposeColor(), fontWeight = FontWeight.SemiBold)) { append(h.match) }
                            append(h.after)
                        },
                        color = palette.text.toComposeColor(), fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onHit(h, query) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
