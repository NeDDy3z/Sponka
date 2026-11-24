package com.xnotes.core.tools

/** Every action the selection bar can offer. [id] is the persistence key (never rename without a migration). */
enum class SelectionAction(val id: String, val onBarByDefault: Boolean) {
    DELETE("delete", true),
    CUT("cut", true),
    COPY("copy", true),
    DUPLICATE("duplicate", true),
    ARRANGE("arrange", true),
    LOCK("lock", true),
    PLAY("play", true),
    CROP("crop", true),
    SIZE("size", true),
    MIRROR("mirror", true),
    AUDIO_RENAME("audio_rename", false),
    AUDIO_SAVE("audio_save", false),
    AUDIO_SHARE("audio_share", false),
    AUDIO_UNLINK("audio_unlink", false),
    CHANGE_STYLE("change_style", false),
    EDIT_FUNCTION("edit_function", false),
    EDIT_TEXT("edit_text", true),
    CONVERT_TEXT("convert_text", false),
    CONVERT_MATH("convert_math", false),
    LINK("link", false),
    CAPTION("caption", false),
    LABEL("label", false),
    REROUTE("reroute", false),
    GROUP("group", false),
    UNGROUP("ungroup", false),
    COPY_IMAGE("copy_image", false),
    SHARE_IMAGE("share_image", false),
    SAVE_STICKER("save_sticker", false);

    companion object {
        // "box_style" was merged into Change style; a saved bar keeps the button where it was.
        fun fromId(id: String?): SelectionAction? = entries.firstOrNull { it.id == id } ?: CHANGE_STYLE.takeIf { id == "box_style" }
    }
}

data class SelectionEntry(val action: SelectionAction, val visible: Boolean = true)

/** The selection bar ([BAR]) and its 3-dot menu ([MORE]); a hidden entry shows in neither. */
data class SelectionBarLayout(val sections: List<List<SelectionEntry>>) {

    val bar: List<SelectionEntry> get() = sections[BAR]
    val more: List<SelectionEntry> get() = sections[MORE]

    fun toggleVisible(section: Int, index: Int): SelectionBarLayout {
        val e = sections.getOrNull(section)?.getOrNull(index) ?: return this
        return copy(sections = sections.mapIndexed { s, list -> if (s == section) list.toMutableList().also { it[index] = e.copy(visible = !e.visible) } else list })
    }

    /** Move an entry; [toIndex] is read against the list after the entry is taken out. */
    fun moveItem(fromSection: Int, fromIndex: Int, toSection: Int, toIndex: Int): SelectionBarLayout {
        val entry = sections.getOrNull(fromSection)?.getOrNull(fromIndex) ?: return this
        if (toSection !in sections.indices) return this
        val lists = sections.map { it.toMutableList() }
        lists[fromSection].removeAt(fromIndex)
        val adjusted = if (toSection == fromSection && toIndex > fromIndex) toIndex - 1 else toIndex
        lists[toSection].add(adjusted.coerceIn(0, lists[toSection].size), entry)
        return SelectionBarLayout(lists)
    }

    companion object {
        const val BAR = 0
        const val MORE = 1

        val DEFAULT = SelectionBarLayout(
            listOf(
                SelectionAction.entries.filter { it.onBarByDefault }.map { SelectionEntry(it) },
                SelectionAction.entries.filter { !it.onBarByDefault }.map { SelectionEntry(it) },
            ),
        )

        /** Build from stored (id, visible) pairs: unknown ids and duplicates are dropped, missing actions join their default list. */
        fun fromRaw(raw: List<List<Pair<String, Boolean>>>): SelectionBarLayout {
            if (raw.size != 2) return DEFAULT
            val seen = HashSet<SelectionAction>()
            val lists = raw.map { list ->
                list.mapNotNull { (id, visible) ->
                    SelectionAction.fromId(id)?.takeIf { seen.add(it) }?.let { SelectionEntry(it, visible) }
                }.toMutableList()
            }
            for (a in SelectionAction.entries) if (a !in seen) lists[if (a.onBarByDefault) BAR else MORE].add(SelectionEntry(a))
            return SelectionBarLayout(lists)
        }
    }
}
