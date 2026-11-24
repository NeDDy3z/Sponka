package com.xnotes.ui

import com.xnotes.settings.ExplorerSortKey

/**
 * Orders explorer entries: folders first when [foldersFirst] (rendered on their own), then files,
 * with the chosen [key]/[descending] sort applied within each group. [createdOf] supplies the
 * creation time (see [com.xnotes.platform.CreationTimeStore]); it breaks ties on the modified/size
 * keys, and name is the final ascending tiebreaker so the order is always stable.
 *
 * Pure (no Android), so it's unit-tested directly on [BrowseEntry] lists.
 */
internal fun explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (BrowseEntry) -> Long,
): Comparator<BrowseEntry> {
    val base: Comparator<BrowseEntry> = when (key) {
        ExplorerSortKey.NAME -> compareBy(naturalOrder) { it.name }
        ExplorerSortKey.MODIFIED -> compareBy({ it.modified }, { createdOf(it) })
        ExplorerSortKey.CREATED -> compareBy({ createdOf(it) }, { it.modified })
        ExplorerSortKey.SIZE -> compareBy({ it.size }, { createdOf(it) })
    }
    val within = (if (descending) base.reversed() else base).thenBy(naturalOrder) { it.name }
    if (!foldersFirst) return within
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            else -> within.compare(a, b)
        }
    }
}

internal val naturalOrder = Comparator<String> { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i] in '0'..'9' && b[j] in '0'..'9') {
            val si = i
            val sj = j
            while (i < a.length && a[i] in '0'..'9') i++
            while (j < b.length && b[j] in '0'..'9') j++
            val x = a.substring(si, i).trimStart('0')
            val y = b.substring(sj, j).trimStart('0')
            val c = if (x.length != y.length) x.length - y.length else x.compareTo(y)
            if (c != 0) return@Comparator c
        } else {
            val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (c != 0) return@Comparator c
            i++
            j++
        }
    }
    val rest = (a.length - i) - (b.length - j)
    if (rest != 0) rest else a.compareTo(b)
}
