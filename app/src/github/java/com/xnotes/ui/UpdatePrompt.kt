package com.xnotes.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.update.UpdateChecker
import com.xnotes.update.UpdateInstaller
import kotlinx.coroutines.launch

/** Checks GitHub for a newer release once the editor is ready and offers to download it. */
@Composable
fun UpdatePrompt(ed: Editor, ready: Boolean, rebuilt: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateChecker.Status.Available?>(null) }
    LaunchedEffect(ready) {
        if (!ready || rebuilt || !ed.preferences.checkUpdatesOnStartup) return@LaunchedEffect
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }.getOrDefault("")
        update = UpdateChecker.check(version).getOrNull() as? UpdateChecker.Status.Available
    }
    update?.let { u ->
        var downloadProgress by remember { mutableStateOf<Float?>(null) }
        var downloadFailed by remember { mutableStateOf(false) }
        val progress = downloadProgress
        SheetDialog(
            title = stringResource(R.string.update_available),
            onDismiss = { update = null },
            text = when {
                progress != null -> stringResource(R.string.update_downloading, (progress * 100).toInt())
                downloadFailed -> stringResource(R.string.update_download_failed)
                else -> stringResource(R.string.update_is_out, u.version)
            },
            foot = {
                DialogButtons {
                    DialogButton(stringResource(R.string.update_turn_off)) {
                        update = null
                        ed.applyHomePreferences(ed.preferences.copy(checkUpdatesOnStartup = false))
                    }
                    DialogButton(stringResource(R.string.update_later)) { update = null }
                    DialogButton(stringResource(R.string.download), ButtonKind.CONFIRM, enabled = downloadProgress == null) download@{
                        val apkUrl = u.apkUrl
                        if (apkUrl == null) {
                            update = null
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.releaseUrl))) }
                            return@download
                        }
                        downloadFailed = false
                        downloadProgress = 0f
                        scope.launch {
                            val result = UpdateInstaller.downloadAndInstall(context, apkUrl) { downloadProgress = it }
                            downloadProgress = null
                            if (result.isSuccess) update = null else downloadFailed = true
                        }
                    }
                }
            },
        )
    }
}
