package com.xnotes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xnotes.R
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.platform.MathOcr
import com.xnotes.settings.Preferences
import com.xnotes.sync.CloudServices
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OnboardingStep { WELCOME, STORAGE, THEME, INPUT, HOME, SYNC, PERMISSIONS, OCR, TOOLBAR, DONE }

/**
 * The first-run setup, drawn over everything until finished or skipped. Storage must be picked first;
 * every later page reuses the Preferences controls, so both edit the same settings.
 */
@Composable
fun Onboarding(editor: Editor) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = OnboardingStep.entries
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    // Quick setup stops after the storage page; full setup walks every page.
    var fullSetup by rememberSaveable { mutableStateOf(true) }
    val step = steps[stepIndex]
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val appPermissions = rememberOnResume {
        granted(Manifest.permission.RECORD_AUDIO) && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
    }
    val battery = rememberBatteryUnrestricted()
    // Moving between pages hops over Permissions when there is nothing left to grant.
    fun skips(i: Int) = when (steps[i]) {
        OnboardingStep.PERMISSIONS -> appPermissions && (battery || CloudServices.byId(editor.preferences.cloudService) == null)
        else -> false
    }
    // What each page ended as, by step index: true when set up (Continue), false when skipped.
    val outcomes = remember { mutableStateMapOf<Int, Boolean>() }
    fun forward(setUp: Boolean = true) {
        outcomes[stepIndex] = setUp
        stepIndex++
        if (skips(stepIndex)) stepIndex++
    }
    fun back() {
        stepIndex--
        if (skips(stepIndex)) stepIndex--
    }
    // Leaving the OCR page while a math model downloads: remind once where training lives, then go on.
    var reminded by rememberSaveable { mutableStateOf(false) }
    var afterReminder by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(go: () -> Unit) {
        val downloading = MathOcr.states.value[editor.preferences.mathModel] is MathOcr.State.Downloading
        if (steps[stepIndex] == OnboardingStep.OCR && downloading && !reminded) afterReminder = go else go()
    }
    var prefs by remember { mutableStateOf(editor.preferences) }
    fun update(p: Preferences) {
        prefs = p
        editor.applyPreferences(p)
    }
    fun updateHome(p: Preferences) {
        prefs = p
        editor.applyHomePreferences(p)
    }
    LaunchedEffect(editor.prefsVersion) { prefs = editor.preferences }

    // The root that held the notes before a change, while the "copy them over?" question is open.
    var copyFrom by remember { mutableStateOf<String?>(null) }
    var copying by remember { mutableStateOf(false) }
    var practiceOpen by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    fun choose(root: String) {
        val old = editor.browseRoot
        if (root == old) return
        editor.updateBrowseRoot(root)
        if (old != null) copyFrom = old
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        choose(uri.toString())
    }
    val appStorage = remember { AppStorageDocumentsProvider.treeUri(context).toString() }
    val ready = editor.browseRoot != null && !copying

    val accentButton = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor())
    val drag = remember { ToolbarDrag(editor) }
    val scroll = rememberScrollState()
    var rootTopLeft by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(drag.active) {
        while (drag.active) {
            val delta = edgeScrollDelta(drag.pointer.y, viewport)
            if (delta != 0f) {
                scroll.scrollBy(delta)
                drag.retarget()
            }
            delay(16L)
        }
    }
    LaunchedEffect(stepIndex) { scroll.scrollTo(0) }
    // Back walks the pages; on the first one it is swallowed, since storage must be picked.
    BackHandler(enabled = !practiceOpen) { if (stepIndex > 0) leave(::back) }

    if (practiceOpen) {
        MathPractice(editor.preferences.mathModel, onBack = { practiceOpen = false })
        return
    }

    Box(
        Modifier.fillMaxSize()
            .background(palette.bg.toComposeColor())
            .pointerInput(Unit) {} // keep touches off the screen underneath
            .onGloballyPositioned { rootTopLeft = it.boundsInRoot().topLeft },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            // One centered column shared by the pages and the buttons, so wide screens keep a readable width.
            Column(Modifier.fillMaxHeight().widthIn(max = 560.dp).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { viewport = it.boundsInRoot() }) {
                    // At least as tall as the screen, so a short page sits in the middle and a long one scrolls from the top.
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // One left edge for every control, matching the sections embedded from Preferences.
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (step) {
                                OnboardingStep.WELCOME -> {
                                    val icon = remember {
                                        context.packageManager.getApplicationIcon(context.packageName).toBitmap().asImageBitmap()
                                    }
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Image(icon, null, Modifier.size(64.dp))
                                            Text(stringResource(R.string.app_name), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 32.sp)
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text(stringResource(R.string.onboarding_welcome_text), color = palette.text.toComposeColor(), fontSize = 18.sp, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(40.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                OutlinedButton(
                                                    onClick = { fullSetup = false; forward() },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    border = BorderStroke(1.dp, palette.accent.toComposeColor()),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.accent.toComposeColor()),
                                                ) { Text(stringResource(R.string.onboarding_quick_setup)) }
                                                Text(stringResource(R.string.onboarding_quick_setup_text), color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center)
                                            }
                                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Button(
                                                    onClick = { fullSetup = true; forward() },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = accentButton,
                                                ) { Text(stringResource(R.string.onboarding_full_setup)) }
                                                Text(stringResource(R.string.onboarding_full_setup_text), color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center)
                                            }
                                        }
                                        Spacer(Modifier.height(40.dp))
                                        Text(stringResource(R.string.onboarding_welcome_skip_note), color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center)
                                    }
                                }
                                OnboardingStep.STORAGE -> {
                                    // Language comes first so the rest of the setup reads in it.
                                    LanguageSection()
                                    Spacer(Modifier.height(8.dp))
                                    SectionTitle(stringResource(R.string.onboarding_storage_title))
                                    val root = editor.browseRoot
                                    // Equal width from the weights, equal height from the intrinsic row.
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PrimaryButton(
                                            XnotesIcons.folder, stringResource(R.string.choose_folder), Modifier.weight(1f).fillMaxHeight(),
                                            selected = root != null && root != appStorage,
                                        ) { if (!copying) pickFolder.launch(null) }
                                        PrimaryButton(
                                            XnotesIcons.database, stringResource(R.string.use_app_storage), Modifier.weight(1f).fillMaxHeight(),
                                            selected = root == appStorage,
                                        ) { if (!copying) choose(appStorage) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        for (text in listOf(R.string.onboarding_storage_folder_text, R.string.onboarding_storage_app_text)) {
                                            Text(stringResource(text), Modifier.weight(1f), color = palette.textDim.toComposeColor(), fontSize = 12.sp, textAlign = TextAlign.Center)
                                        }
                                    }
                                    // Always laid out, only hidden, so the centered page doesn't shift when a copy starts or ends.
                                    Row(
                                        Modifier.alpha(if (copying) 1f else 0f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (copying) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Spacer(Modifier.size(20.dp))
                                        Text(stringResource(R.string.onboarding_copying), color = palette.text.toComposeColor(), fontSize = 13.sp)
                                    }
                                }
                                OnboardingStep.THEME -> {
                                    SectionTitle(stringResource(R.string.pref_appearance))
                                    ThemeControls(prefs, ::update, ::updateHome)
                                    // The same two switches as Preferences > General.
                                    CheckRow(stringResource(R.string.pref_start_fullscreen), editor.fullscreen) { editor.setFullscreenPref(it) }
                                    CheckRow(stringResource(R.string.pref_keep_screen_on), prefs.keepScreenOn) { updateHome(prefs.copy(keepScreenOn = it)) }
                                }
                                OnboardingStep.INPUT -> {
                                    SectionTitle(stringResource(R.string.pref_input))
                                    InputControls(prefs, ::update)
                                }
                                OnboardingStep.HOME -> {
                                    SectionTitle(stringResource(R.string.pref_home_explorer))
                                    HomeExplorerControls(editor, prefs, ::updateHome)
                                }
                                OnboardingStep.SYNC -> CloudSyncSection(editor, onOpenLog = null)
                                OnboardingStep.PERMISSIONS -> PermissionsSection(syncOn = CloudServices.byId(prefs.cloudService) != null)
                                OnboardingStep.OCR -> {
                                    SectionTitle(stringResource(R.string.pref_ocr))
                                    FieldLabel(stringResource(R.string.pref_ocr_handwriting_language))
                                    HandwritingLanguageControls(prefs.ocrLanguage) { update(prefs.copy(ocrLanguage = it)) }
                                    FieldLabel(stringResource(R.string.pref_ocr_math))
                                    MathAddonControls(prefs, ::update, onTrain = { practiceOpen = true })
                                }
                                OnboardingStep.TOOLBAR -> {
                                    SectionTitle(stringResource(R.string.pref_toolbar))
                                    ToolbarSettings(editor, prefs, ::updateHome, drag)
                                }
                                OnboardingStep.DONE -> {
                                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SectionTitle(stringResource(R.string.onboarding_done_title))
                                        Text(stringResource(R.string.onboarding_done_text), color = palette.textDim.toComposeColor(), fontSize = 13.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
                if (fullSetup && step != OnboardingStep.WELCOME) {
                    // Only the pages this run will show; skipped ones don't count.
                    // The closing page isn't a setting, so it gets no marker; on it every marker reads as passed.
                    val shown = (1 until steps.size).filter { steps[it] != OnboardingStep.DONE && (it == stepIndex || !skips(it)) }
                    val current = if (step == OnboardingStep.DONE) shown.size else shown.indexOf(stepIndex)
                    ProgressDots(current, shown.map { outcomes[it] }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                }
                // The welcome page answers with its own two buttons.
                if (step != OnboardingStep.WELCOME) Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Back on the leading edge, the way forward on the trailing one.
                    if (stepIndex > 0) TextButton(onClick = { leave(::back) }) { Text(stringResource(R.string.onboarding_back)) }
                    Spacer(Modifier.weight(1f))
                    if (step == OnboardingStep.DONE) {
                        Button(onClick = editor::finishOnboarding, colors = accentButton) { Text(stringResource(R.string.onboarding_finish)) }
                    } else if (!fullSetup) {
                        Button(onClick = editor::finishOnboarding, enabled = ready, colors = accentButton) { Text(stringResource(R.string.onboarding_finish)) }
                    } else {
                        TextButton(onClick = { confirmSkip = true }, enabled = ready) { Text(stringResource(R.string.onboarding_skip)) }
                        // Storage can't be skipped, so its page only offers Continue.
                        if (step != OnboardingStep.STORAGE) TextButton(onClick = { leave { forward(setUp = false) } }) { Text(stringResource(R.string.onboarding_skip_page)) }
                        Button(onClick = { leave { forward() } }, enabled = ready, colors = accentButton) { Text(stringResource(R.string.onboarding_continue)) }
                    }
                }
            }
        }
        drag.Ghost(rootTopLeft)
    }

    afterReminder?.let { go ->
        val path = listOf(R.string.preferences, R.string.pref_ocr, R.string.pref_ocr_math, R.string.math_practice_open)
            .map { stringResource(it) }.joinToString(" > ")
        val done = { reminded = true; afterReminder = null; go() }
        SheetDialog(
            title = stringResource(R.string.math_practice_open),
            onDismiss = done,
            text = stringResource(R.string.onboarding_train_reminder, path),
            foot = { DialogButtons { DialogButton(stringResource(android.R.string.ok), ButtonKind.CONFIRM, onClick = done) } },
        )
    }

    if (confirmSkip) {
        SheetDialog(
            title = stringResource(R.string.onboarding_skip_title),
            onDismiss = { confirmSkip = false },
            text = stringResource(R.string.onboarding_skip_message),
            foot = { ConfirmButtons(stringResource(R.string.onboarding_skip), { confirmSkip = false }) { confirmSkip = false; editor.finishOnboarding() } },
        )
    }

    copyFrom?.let { from ->
        SheetDialog(
            title = stringResource(R.string.onboarding_copy_title),
            onDismiss = { copyFrom = null },
            text = stringResource(R.string.onboarding_copy_message),
            foot = {
                DialogButtons {
                    DialogButton(stringResource(R.string.onboarding_copy_skip)) { copyFrom = null }
                    DialogButton(stringResource(R.string.onboarding_copy_confirm), ButtonKind.CONFIRM) copy@{
                        copyFrom = null
                        val to = editor.browseRoot ?: return@copy
                        copying = true
                        scope.launch {
                            val (copied, failed) = withContext(Dispatchers.IO) { editor.copyStorage(from, to) }
                            copying = false
                            editor.relistTree()
                            val message = if (failed == 0) context.getString(R.string.onboarding_copy_done, copied)
                            else context.getString(R.string.onboarding_copy_partial, copied, failed)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
        )
    }
}

/**
 * A row of page markers, each in the same fixed slot so nothing shifts as pages change: the current page
 * is a highlighted ring, pages already passed show a check (set up) or an X (skipped), the rest dim dots.
 */
@Composable
private fun ProgressDots(current: Int, outcomes: List<Boolean?>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        outcomes.forEachIndexed { i, outcome ->
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    i == current -> Box(
                        Modifier.size(16.dp).clip(CircleShape).border(2.dp, palette.accent.toComposeColor(), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(6.dp).clip(CircleShape).background(palette.accent.toComposeColor())) }
                    i < current && outcome != null -> Box(
                        Modifier.size(16.dp).clip(CircleShape)
                            .background((if (outcome) palette.accent else palette.surfaceHi).toComposeColor()),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (outcome) XnotesIcons.check else XnotesIcons.close, null, Modifier.size(11.dp),
                            tint = (if (outcome) palette.bg else palette.textDim).toComposeColor(),
                        )
                    }
                    else -> Box(Modifier.size(8.dp).clip(CircleShape).background(palette.border.toComposeColor()))
                }
            }
        }
    }
}
