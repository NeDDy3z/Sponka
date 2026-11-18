package com.xnotes.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xnotes.R
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.text.RecognizedLatex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/**
 * Handwritten maths to LaTeX, fully on device, as optional add-ons. Each model is a release of
 * the Sponka-Math-Models repo whose model.json says what it is and how to run it; ONNX Runtime's
 * core library is one more download shared by all of them. Preferences picks, downloads and
 * deletes models. Recognition renders the selected strokes to an image, runs the model's encoder
 * once, then beam-searches its decoder for a few readings, best first.
 */
object MathOcr {

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val message: String) : State
    }

    const val DEFAULT_MODEL = "pix2text-mfr"

    /** The one the picker marks as recommended. */
    const val RECOMMENDED_MODEL = "texteller"

    /** Picker order, best pick first; models not listed follow by name. */
    private val ORDER = listOf("texteller", "pix2text-mfr", "pp-formulanet-l")

    private const val REPO = "NeDDy3z/Sponka-Math-Models"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO/releases"
    private const val RUNTIME = "https://github.com/$REPO/releases/download/onnxruntime-v1.30.0/"
    private const val RUNTIME_LIB = "libonnxruntime.so"
    private const val MANIFEST = "model.json"
    private const val BUNDLED = "math/pix2text-mfr.json"

    /** ONNX Runtime 1.30.0's core library, matching the bridge in the APK, per CPU type. */
    private val RUNTIME_FILES = mapOf(
        "arm64-v8a" to MathModel.File(RUNTIME_LIB, 12_381_936, "e5eefba49721afb5b17d55f7d8eb16ec0f64c384c6ff08d49950393fcf086f24", 32_990_480, RUNTIME + "libonnxruntime-1.30.0-arm64-v8a.so.gz"),
        "armeabi-v7a" to MathModel.File(RUNTIME_LIB, 11_179_985, "081ce89dd67b4c5e664e4a24695b3826e43c15c91d55ea817714998394a89c85", 23_311_344, RUNTIME + "libonnxruntime-1.30.0-armeabi-v7a.so.gz"),
        "x86_64" to MathModel.File(RUNTIME_LIB, 14_373_545, "104769accac47a9459de4355c693048b6e80408a21449fc892c874b5054cba62", 39_348_488, RUNTIME + "libonnxruntime-1.30.0-x86_64.so.gz"),
    )

    /** The runtime build this process can load: its own bitness, the device's preferred CPU type. */
    private val runtimeFile: MathModel.File? by lazy {
        val abis = if (android.os.Process.is64Bit()) android.os.Build.SUPPORTED_64_BIT_ABIS else android.os.Build.SUPPORTED_32_BIT_ABIS
        abis.firstNotNullOfOrNull { RUNTIME_FILES[it] }
    }

    /** False on a CPU type with no runtime build; Preferences then says so instead of offering it. */
    val supported: Boolean get() = runtimeFile != null

    @Volatile private var runtimeLoaded = false

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** Per model id; a model missing from the map is [State.Missing]. */
    val states: StateFlow<Map<String, State>> = _states

    private val _catalog = MutableStateFlow<List<MathModel>>(emptyList())
    /** Every model known here, newest version of each: from the repo, the cached listing, installed copies and the APK. */
    val catalog: StateFlow<List<MathModel>> = _catalog

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var download: Job? = null
    private var downloading: String? = null

    private fun root(context: Context) = File(context.filesDir, "addons/math-ocr")
    private fun runtimeDir(context: Context) = File(root(context), "runtime")
    private fun modelDir(context: Context, id: String) = File(root(context), "models/$id")
    private fun catalogFile(context: Context) = File(root(context), "catalog.json")

    /** The model being downloaded, left behind if the process dies so the next launch carries on. */
    private fun pendingFile(context: Context) = File(root(context), "downloading.json")

    private fun runtimeInstalled(context: Context) = runtimeFile?.let { File(runtimeDir(context), it.name).length() == it.installedSize } == true

    /** Parsed model.json files by path, valid while the file's modification time stays the same. */
    private val manifests = HashMap<String, Pair<Long, MathModel?>>()

    /** The installed copy of model [id], read from its own model.json, or null when it is not all here. */
    fun installed(context: Context, id: String): MathModel? {
        migrate(context)
        if (!runtimeInstalled(context)) return null
        val dir = modelDir(context, id)
        val file = File(dir, MANIFEST)
        val stamp = file.lastModified()
        val model = synchronized(manifests) {
            manifests[file.path]?.takeIf { it.first == stamp }?.second
                ?: runCatching { MathModel.parse(JSONObject(file.readText())) }.getOrNull().also { manifests[file.path] = stamp to it }
        } ?: return null
        return model.takeIf { m -> m.files.all { File(dir, it.name).length() == it.installedSize } }
    }

    fun isInstalled(context: Context, id: String): Boolean = supported && installed(context, id) != null

    /** Sync [states] and [catalog] with what is on disk; a download in flight is left to report itself. */
    fun refresh(context: Context) {
        migrate(context)
        val known = LinkedHashMap<String, MathModel>()
        fun offer(m: MathModel) {
            val have = known[m.id]
            if (have == null || MathModel.newer(m.version, have.version)) known[m.id] = m
        }
        bundled(context)?.let(::offer)
        runCatching { JSONArray(catalogFile(context).readText()) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) runCatching { MathModel.parse(arr.getJSONObject(i)) }.getOrNull()?.let(::offer)
        }
        root(context).resolve("models").listFiles()?.forEach { dir -> installed(context, dir.name)?.let(::offer) }
        _catalog.value = known.values.sortedWith(compareBy({ ORDER.indexOf(it.id).let { i -> if (i < 0) ORDER.size else i } }, { it.name.lowercase() }))
        val states = HashMap(_states.value)
        for (id in known.keys) {
            if (states[id] is State.Downloading && id == downloading) continue
            if (states[id] is State.Failed) continue
            states[id] = if (isInstalled(context, id)) State.Installed else State.Missing
        }
        _states.value = states
    }

    /** Ask the repo for its releases and cache their model.json files; offline, the cache simply stays. */
    fun refreshCatalog(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val releases = JSONArray(get(RELEASES_API, json = true))
                val out = JSONArray()
                val seen = HashSet<String>()
                for ((_, manifest, assets) in MathModel.releaseAssets(releases)) {
                    val model = MathModel.withUrls(JSONObject(get(manifest)), assets)
                    if (seen.add(model.getString("id"))) out.put(model)
                }
                root(app).mkdirs()
                catalogFile(app).writeText(out.toString())
            }
            refresh(app)
        }
    }

    private fun get(url: String, json: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        if (json) conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun bundled(context: Context): MathModel? =
        runCatching { MathModel.parse(JSONObject(context.assets.open(BUNDLED).bufferedReader().use { it.readText() })) }.getOrNull()

    /**
     * Before models were picked, pix2text-mfr sat loose in the add-on folder with the runtime. Its
     * files are the same as the release's, so they move into the new layout instead of downloading again.
     */
    private fun migrate(context: Context) {
        val root = root(context)
        val old = File(root, "encoder_model.onnx")
        if (!old.exists()) return
        val dir = modelDir(context, DEFAULT_MODEL).apply { mkdirs() }
        for (name in listOf("encoder_model.onnx", "decoder_model.onnx", "tokenizer.json")) File(root, name).renameTo(File(dir, name))
        runtimeDir(context).mkdirs()
        File(root, RUNTIME_LIB).renameTo(File(runtimeDir(context), RUNTIME_LIB))
        bundled(context)?.let { File(dir, MANIFEST).writeText(it.json.toString()) }
    }

    /** Fetch [model] (and the runtime, if it is not here yet); its model.json lands last, marking it whole. */
    fun startDownload(context: Context, model: MathModel) {
        if (download?.isActive == true) return
        val app = context.applicationContext
        val runtime = runtimeFile ?: return
        downloading = model.id
        downloadName = "${model.name} ${model.version}"
        lastProgress = null
        shownPercent = -1
        download = scope.launch {
            synchronized(lock) { release() }
            val dir = modelDir(app, model.id).apply { mkdirs() }
            pendingFile(app).writeText(model.json.toString())
            File(dir, MANIFEST).delete()
            val jobs = buildList {
                if (!runtimeInstalled(app)) add(runtimeDir(app).apply { mkdirs() } to runtime)
                for (f in model.files) add(dir to f)
            }
            val total = jobs.sumOf { it.second.size }
            var done = 0L
            fun report(s: State) {
                _states.value = _states.value + (model.id to s)
                if (s is State.Downloading) notifyProgress(app, s)
            }
            try {
                for ((target, f) in jobs) {
                    val out = File(target, f.name)
                    if (out.length() == f.installedSize && (f.installedSize != f.size || sha256(out) == f.sha256)) { done += f.size; continue }
                    val url = f.url ?: error("${f.name} has no download link")
                    val part = File(target, f.name + ".part")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val gzipped = f.installedSize != f.size
                    // A plain file cut off earlier (app killed, network gone) goes on from where it stopped.
                    val have = if (!gzipped && part.length() in 1 until f.size) part.length() else 0L
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                    try {
                        val resumed = have > 0 && conn.responseCode == 206
                        if (conn.responseCode != 200 && !resumed) error("HTTP ${conn.responseCode}")
                        if (resumed) {
                            hash(part, digest)
                            done += have
                        }
                        val hashed = java.security.DigestInputStream(conn.inputStream, digest)
                        val counted = object : java.io.FilterInputStream(hashed) {
                            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                                if (n > 0) {
                                    done += n
                                    report(State.Downloading(done, total))
                                }
                            }
                        }
                        val input = if (f.installedSize != f.size) java.util.zip.GZIPInputStream(counted, 64 * 1024) else counted
                        input.use { src ->
                            java.io.FileOutputStream(part, resumed).use { dst ->
                                val buf = ByteArray(64 * 1024)
                                while (isActive) {
                                    val n = src.read(buf)
                                    if (n < 0) break
                                    dst.write(buf, 0, n)
                                }
                                // Whatever gunzip left unread still counts toward the checksum.
                                while (isActive && counted.read(buf) >= 0) Unit
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (!isActive) { part.delete(); return@launch }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (sha != f.sha256 || part.length() != f.installedSize) {
                        part.delete()
                        error("${f.name} is corrupt, try again")
                    }
                    out.delete()
                    part.renameTo(out)
                }
                File(dir, MANIFEST).writeText(model.json.toString())
                report(State.Installed)
            } catch (e: Exception) {
                report(State.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                downloading = null
                pendingFile(app).delete()
                service?.finish()
            }
        }
        startService(app)
    }

    /** Carry on with a download the app was closed during; call at launch. */
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (download?.isActive == true) return@launch
            val model = runCatching { MathModel.parse(JSONObject(pendingFile(app).readText())) }.getOrNull() ?: return@launch
            startDownload(app, model)
        }
    }

    /** A kept file is re-checked so an update with same-sized files still fetches; gzipped ones are trusted by size. */
    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        hash(f, digest)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(f: File, digest: MessageDigest) {
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
    }

    // --- the service that keeps a download going with the app closed ---

    internal const val NOTIFICATION_ID = 43
    private const val CHANNEL = "math_download"

    @Volatile private var service: MathDownloadService? = null
    @Volatile private var downloadName = ""
    @Volatile private var shownPercent = -1
    @Volatile private var lastProgress: State.Downloading? = null

    private fun startService(app: Context) {
        if (service != null) return
        // Refused when the app is already in the background; the download then goes on while the process lives.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, MathDownloadService::class.java)) }
    }

    internal fun attached(s: MathDownloadService) {
        service = s
        if (download?.isActive != true) s.finish()
    }

    internal fun detached(s: MathDownloadService) {
        if (service === s) service = null
    }

    private fun notifyProgress(app: Context, s: State.Downloading) {
        lastProgress = s
        val percent = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0
        if (percent == shownPercent) return
        shownPercent = percent
        val svc = service ?: return
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(svc)) }
    }

    internal fun notification(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.math_download_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val p = lastProgress
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.math_download_title, downloadName))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
        if (p != null && p.total > 0) b.setProgress(1000, (p.done * 1000 / p.total).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    fun cancelDownload(context: Context) {
        download?.cancel()
        download = null
        val id = downloading
        downloading = null
        pendingFile(context).delete()
        service?.finish()
        root(context).walkTopDown().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        if (id != null) _states.value = _states.value - id
        refresh(context)
    }

    /** Remove model [id]; the runtime goes too once no model is left. Training data stays. */
    fun delete(context: Context, id: String) {
        if (downloading == id) cancelDownload(context)
        val app = context.applicationContext
        _states.value = _states.value - id
        // Off the main thread: a recognition in progress holds the model until it finishes.
        scope.launch {
            synchronized(lock) { release() }
            modelDir(app, id).deleteRecursively()
            if (root(app).resolve("models").listFiles().isNullOrEmpty()) runtimeDir(app).deleteRecursively()
            refresh(app)
        }
    }

    // --- recognition ---

    private class Loaded(val model: MathModel, val sessions: List<OrtSession>, val vocab: Map<Int, String>) {
        fun close() = sessions.forEach { it.close() }
    }

    private val lock = Any()
    private var loaded: Loaded? = null
    private var idle: Job? = null

    /** Free the model; it stays loaded a minute after each use, since loading a big one takes seconds. */
    private fun release() {
        idle?.cancel()
        loaded?.close()
        loaded = null
    }

    private fun load(context: Context, id: String): Loaded {
        loaded?.let { if (it.model.id == id) return it }
        release()
        val model = installed(context, id) ?: error("$id is not downloaded")
        val dir = modelDir(context, id)
        if (!runtimeLoaded) {
            System.load(File(runtimeDir(context), RUNTIME_LIB).path)
            runtimeLoaded = true
        }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            // KleidiAI's SME kernels trap (SIGILL) where a CPU reports SME it cannot run, as
            // emulators on SME hosts do; the plain kernels are plenty here.
            addConfigEntry("mlas.disable_kleidiai", "1")
        }
        val graphs = if (model.pipeline == "end-to-end") listOf(model.onnx.model) else listOf(model.onnx.encoder, model.onnx.decoder)
        val sessions = graphs.map { env.createSession(File(dir, it).path, opts) }
        return Loaded(model, sessions, MathTokens.vocab(JSONObject(File(dir, "tokenizer.json").readText()))).also { loaded = it }
    }

    /**
     * Recognize [strokes] (content px, inside [bounds]) with model [id] as LaTeX readings, best
     * first, or none when nothing came out. Blocks; call off the main thread.
     */
    fun recognize(context: Context, id: String, strokes: List<Stroke>, bounds: Rect): List<String> {
        val start = System.nanoTime()
        val out = synchronized(lock) {
            idle?.cancel()
            val l = load(context, id)
            try {
                val ids = if (l.model.pipeline == "end-to-end") listOf(runWhole(l, strokes, bounds)) else beamSearch(l, strokes, bounds)
                ids.map { RecognizedLatex.clean(MathTokens.decode(it, l.vocab, l.model.tokenizer, l.model.decode.skipBelow)) }
                    .filter { it.isNotBlank() }
                    .distinct()
            } finally {
                idle = scope.launch {
                    delay(60_000)
                    synchronized(lock) { release() }
                }
            }
        }
        recordSpeed(context, id, (System.nanoTime() - start) / 1e9)
        return out
    }

    private fun runWhole(l: Loaded, strokes: List<Stroke>, bounds: Rect): List<Long> {
        val env = OrtEnvironment.getEnvironment()
        val i = l.model.input
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(render(strokes, bounds, i)), longArrayOf(1, i.channels.toLong(), i.height.toLong(), i.width.toLong())).use { image ->
            l.sessions[0].run(mapOf(l.model.onnx.pixels to image)).use { out ->
                val t = out[0] as OnnxTensor
                val buf = t.longBuffer
                List(buf.remaining()) { buf.get(it) }.dropWhile { it == l.model.decode.bos }.takeWhile { it != l.model.decode.eos }
            }
        }
    }

    private class Beam(val ids: List<Long>, val score: Double)

    /**
     * Up to `beam` token sequences, best first. Every live beam runs as one row of a single
     * decoder batch per step; there is no cache, so each step reruns the sequence so far.
     */
    private fun beamSearch(l: Loaded, strokes: List<Stroke>, bounds: Rect): List<List<Long>> {
        val env = OrtEnvironment.getEnvironment()
        val m = l.model
        val i = m.input
        val (enc, dec) = l.sessions
        val width = m.decode.beam
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(render(strokes, bounds, i)), longArrayOf(1, i.channels.toLong(), i.height.toLong(), i.width.toLong())).use { image ->
            enc.run(mapOf(m.onnx.pixels to image)).use { encoded ->
                val hidden = encoded[0] as OnnxTensor
                val shape = hidden.info.shape
                val one = FloatArray(hidden.floatBuffer.remaining()).also { hidden.floatBuffer.get(it) }
                var tiled: Pair<Int, OnnxTensor>? = null
                var alive = listOf(Beam(listOf(m.decode.bos), 0.0))
                val done = ArrayList<Beam>()
                try {
                    while (alive.isNotEmpty() && alive[0].ids.size < m.decode.maxTokens && done.size < width) {
                        val b = alive.size
                        if (tiled?.first != b) {
                            tiled?.second?.close()
                            val all = FloatArray(one.size * b).also { a -> for (k in 0 until b) one.copyInto(a, k * one.size) }
                            tiled = b to OnnxTensor.createTensor(env, FloatBuffer.wrap(all), longArrayOf(b.toLong(), shape[1], shape[2]))
                        }
                        val steps = alive[0].ids.size
                        val flat = LongArray(b * steps).also { a -> alive.forEachIndexed { k, beam -> beam.ids.forEachIndexed { j, id -> a[k * steps + j] = id } } }
                        val next = OnnxTensor.createTensor(env, LongBuffer.wrap(flat), longArrayOf(b.toLong(), steps.toLong())).use { ids ->
                            dec.run(mapOf(m.onnx.ids to ids, m.onnx.hidden to tiled!!.second)).use { out ->
                                val logits = out[0] as OnnxTensor
                                MathTokens.extend(logits.floatBuffer, logits.info.shape[2].toInt(), alive.map { it.score }, steps, width)
                            }
                        }
                        val grown = ArrayList<Beam>()
                        for ((k, token, score) in next) {
                            if (token == m.decode.eos) done += Beam(alive[k].ids, score)
                            else if (grown.size < width) grown += Beam(alive[k].ids + token, score)
                        }
                        alive = grown
                    }
                } finally {
                    tiled?.second?.close()
                }
                // Longer readings gather more negative log-probability; dividing by length keeps them in the race.
                (done + alive).sortedByDescending { it.score / it.ids.size }.take(width).map { it.ids.drop(1) }
            }
        }
    }

    // --- speed on this device ---

    private fun speedFile(context: Context, id: String) = File(modelDir(context, id), "speed.json")

    private fun recordSpeed(context: Context, id: String, seconds: Double) {
        runCatching {
            val f = speedFile(context, id)
            val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            val n = o.optInt("n", 0)
            val avg = (o.optDouble("avg", 0.0) * n + seconds) / (n + 1)
            f.writeText(JSONObject().put("n", n + 1).put("avg", avg).toString())
        }
    }

    /** Average seconds per formula measured here, or null before the first one. */
    fun measuredSeconds(context: Context, id: String): Double? =
        runCatching { JSONObject(speedFile(context, id).readText()).getDouble("avg") }.getOrNull()

    // --- image ---

    /**
     * The strokes drawn black on white at a scale the models read best (glyphs roughly 100 to
     * 200 px tall, 4 px lines), then fitted to the model's input: squeezed, or scaled and
     * centered on white when it keeps the aspect, as a normalized CHW float image.
     */
    private fun render(strokes: List<Stroke>, bounds: Rect, input: MathModel.Input): FloatArray {
        val scale = minOf(160.0 / maxOf(bounds.h, bounds.w / 10.0, 1.0), 2400.0 / maxOf(bounds.w, 1.0))
        val pad = maxOf(input.margin, 2) // at least half the 4 px line, so edge strokes are not cut
        val w = (bounds.w * scale).toInt() + 2 * pad
        val h = (bounds.h * scale).toInt() + 2 * pad
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in strokes) {
            val n = s.sampleCount
            if (n == 0) continue
            fun x(i: Int) = ((s.xAt(i) - bounds.left) * scale + pad).toFloat()
            fun y(i: Int) = ((s.yAt(i) - bounds.top) * scale + pad).toFloat()
            if (n == 1) {
                canvas.drawPoint(x(0), y(0), paint)
                continue
            }
            val path = Path().apply { moveTo(x(0), y(0)) }
            for (i in 1 until n) path.lineTo(x(i), y(i))
            canvas.drawPath(path, paint)
        }
        var fitted: Pair<Int, Int>? = null
        val sized = if (input.keepAspect) {
            val fit = minOf(input.width.toFloat() / w, input.height.toFloat() / h)
            val sw = (w * fit).toInt().coerceIn(1, input.width)
            val sh = (h * fit).toInt().coerceIn(1, input.height)
            fitted = sw to sh
            val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
            Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(if (input.padding == MathModel.Padding.CENTER_BLACK) Color.BLACK else Color.WHITE)
                    if (input.padding == MathModel.Padding.TOP_LEFT_MEAN) drawBitmap(small, 0f, 0f, null)
                    else drawBitmap(small, ((input.width - sw) / 2).toFloat(), ((input.height - sh) / 2).toFloat(), null)
                }
                small.recycle()
            }
        } else {
            Bitmap.createScaledBitmap(bmp, input.width, input.height, true)
        }
        bmp.recycle()
        val px = IntArray(input.width * input.height)
        sized.getPixels(px, 0, input.width, 0, 0, input.width, input.height)
        sized.recycle()
        return MathTokens.normalize(px, input, fitted.takeIf { input.padding == MathModel.Padding.TOP_LEFT_MEAN })
    }
}
