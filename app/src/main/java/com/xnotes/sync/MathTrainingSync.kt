package com.xnotes.sync

import android.content.Context
import com.xnotes.core.text.MathTrainingData
import com.xnotes.platform.MathTraining
import org.json.JSONObject

/** Carries the math training between devices as one file that is merged, not replaced. */
object MathTrainingSync {
    const val REMOTE_FILE = "math-training.json"
    private const val STATE_PATH = "${SettingsSync.REMOTE_DIR}/$REMOTE_FILE"

    fun sync(context: Context, storage: CloudStorage, rootId: String, state: SyncState) {
        val localFile = MathTraining.file(context)
        val folder = storage.list(rootId).folders.firstOrNull { it.name == SettingsSync.REMOTE_DIR }
        val remote = folder?.let { f -> storage.list(f.id).files.firstOrNull { it.name == REMOTE_FILE } }
        val st = state.get(STATE_PATH)
        if (remote == null && !localFile.exists()) return
        if (remote != null && st != null && remote.lastModified == st.remoteLastModified && localFile.lastModified() == st.localModified) return

        val remoteText = remote?.let { String(storage.download(it)) }
        if (remoteText != null) MathTraining.merge(context, JSONObject(remoteText))
        val merged = MathTraining.json(context).toString()
        val sameAsRemote = remoteText != null && runCatching { MathTrainingData.fromJson(JSONObject(remoteText)).toJson().toString() == merged }.getOrDefault(false)
        val localMod = localFile.lastModified()
        if (sameAsRemote) {
            state.put(STATE_PATH, SyncState.Entry(remote!!.id, remote.lastModified, localMod, remote.size))
            return
        }
        val folderId = folder?.id ?: storage.ensureFolderPath(rootId, listOf(SettingsSync.REMOTE_DIR))
        val bytes = merged.toByteArray()
        val sent = storage.upload(folderId, REMOTE_FILE, bytes, localMod)
        if (remote != null) runCatching { storage.trashFile(remote.id) }
        state.put(STATE_PATH, SyncState.Entry(sent.id, sent.lastModified, localMod, bytes.size.toLong()))
    }
}
