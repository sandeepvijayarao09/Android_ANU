package io.github.sandeepvijayarao09.anu

import android.Manifest
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.sandeepvijayarao09.anu.brain.vision.CocoLabels
import io.github.sandeepvijayarao09.anu.brain.vision.QualcommLibraries
import io.github.sandeepvijayarao09.anu.brain.vision.YoloxDetector
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.feature.camera.Finder
import io.github.sandeepvijayarao09.anu.feature.camera.Frozen
import io.github.sandeepvijayarao09.anu.feature.camera.LabelVectors
import io.github.sandeepvijayarao09.anu.feature.camera.LiveCameraScreen
import io.github.sandeepvijayarao09.anu.feature.camera.LiveCameraViewModel
import io.github.sandeepvijayarao09.anu.feature.camera.LiveSearch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live camera (ADR-111), opened from Talk's "+": YOLOX finds things in the back camera's frames and EmbeddingGemma 2
 * matches a question to them, all on the phone. "Ask Anu about this" hands the frame to Talk as a photo, through
 * [LiveFrame] in memory, and closes. The camera permission is asked for here, the first time only.
 */
class LiveCameraActivity : ComponentActivity() {
    private var thermal: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A viewfinder stays on while it's open, as camera apps do.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = (application as AnuApp).graph
        // The chat models leave memory while the camera runs, after any answer still being written (ADR-111): on the
        // emulator, E4B resident beside it made the phone swap and detection 25 times slower. Talk's warm start loads
        // them again on the way back. Not while live talk goes on behind (ADR-108).
        if (savedInstanceState == null && !graph.liveTalk.on.value) graph.scope.launch { graph.onDevice.unloadAll() }
        // Debug builds only: JPEGs in the app's files/live-camera-stills are shown instead of the camera, because the
        // emulator's camera shows a test pattern. Copy them there with run-as; delete the folder to use the camera.
        val stills = if (BuildConfig.DEBUG) {
            File(filesDir, STILLS).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }?.map { it.path }?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        // Debug builds only, to compare backends (ADR-115): `--es backend GPU` (or NPU, CPU) and `--es npuMode
        // SUSTAINED_HIGH_PERFORMANCE` (or another HTP mode) on the activity's intent.
        val backend = intent.getStringExtra("backend")?.takeIf { BuildConfig.DEBUG }
        val npuMode = intent.getStringExtra("npuMode")?.takeIf { BuildConfig.DEBUG }
        if (BuildConfig.DEBUG) logPower()
        setContent {
            // Dark over the camera whatever the system setting, as camera apps are.
            AnuTheme(dark = true) {
                val live = viewModel {
                    LiveCameraViewModel(
                        openDetector = {
                            val npu = QualcommLibraries.folder(applicationInfo.nativeLibraryDir, File(noBackupFilesDir, "qualcomm-npu"))
                            YoloxDetector.openNamed(assets, npu, backend, npuMode)
                        },
                        finder = Finder(LiveSearch(CocoLabels.ALL), graph.visualEmbedder, FileLabelVectors(File(noBackupFilesDir, "live-camera"))),
                        labels = CocoLabels.ALL.map { it.name },
                        log = if (BuildConfig.DEBUG) { line -> Log.d("Anu", line) } else null,
                    )
                }
                var allowed by remember { mutableStateOf(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
                val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
                LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
                LaunchedEffect(live) { watchHeat(live) }
                val ledger by graph.ledger.entries.collectAsStateWithLifecycle()
                val today = LocalDate.now()
                LiveCameraScreen(
                    live,
                    cameraAllowed = allowed,
                    onAllowCamera = { ask.launch(Manifest.permission.CAMERA) },
                    sentToday = ledger.count { it.at.atZone(ZoneId.systemDefault()).toLocalDate() == today },
                    onBack = ::finish,
                    onAsk = { frozen ->
                        LiveFrame.hand(frozen)
                        setResult(RESULT_OK)
                        finish()
                    },
                    stills = stills,
                )
            }
        }
    }

    /** Pauses detection while Android says the phone is severely hot (P8). */
    private fun watchHeat(live: LiveCameraViewModel) {
        val power = getSystemService(PowerManager::class.java)
        live.setHot(power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
        val listener = PowerManager.OnThermalStatusChangedListener { live.setHot(it >= PowerManager.THERMAL_STATUS_SEVERE) }
        power.addThermalStatusListener(mainExecutor, listener)
        thermal = listener
    }

    /**
     * Debug builds: the battery's current every 5 seconds while live camera is open, for comparing backends' power
     * (ADR-115). On a charger it's the net current, so compare runs on the same charger and level.
     */
    private fun logPower() {
        val battery = getSystemService(BatteryManager::class.java)
        lifecycleScope.launch {
            while (true) {
                delay(5_000)
                val microamps = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val volts = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
                Log.d("Anu", "Live camera power: battery ${microamps / 1000} mA at $volts mV")
            }
        }
    }

    private companion object {
        const val STILLS = "live-camera-stills"
    }

    override fun onDestroy() {
        thermal?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        // Leaving live camera frees EmbeddingGemma 2 for the chat model (ADR-111).
        if (isFinishing) (application as AnuApp).graph.releaseVisualEmbedder()
        super.onDestroy()
    }
}

/**
 * The frame "Ask Anu about this" hands to Talk, with what the detector found in it (ADR-111): held in memory between
 * the two screens and taken once, never written to a file.
 */
object LiveFrame {
    private val frame = AtomicReference<Frozen?>(null)

    fun hand(frozen: Frozen) = frame.set(frozen)

    fun take(): Frozen? = frame.getAndSet(null)
}

/**
 * The labels' vectors kept between visits to live camera, one file per model (ADR-111). They're the label texts'
 * vectors, never the user's: the same on every phone. A change to the texts makes them again.
 */
private class FileLabelVectors(private val dir: File) : LabelVectors {
    private fun file(model: String) = File(dir, model.replace(Regex("[^A-Za-z0-9.@-]"), "_") + ".vectors")

    override fun load(model: String, texts: List<String>): List<FloatArray>? = runCatching {
        DataInputStream(file(model).inputStream().buffered()).use { input ->
            if (input.readInt() != texts.hashCode() || input.readInt() != texts.size) return null
            val size = input.readInt()
            List(texts.size) { FloatArray(size) { input.readFloat() } }
        }
    }.getOrNull()

    override fun save(model: String, texts: List<String>, vectors: List<FloatArray>) {
        runCatching {
            dir.mkdirs()
            DataOutputStream(file(model).outputStream().buffered()).use { out ->
                out.writeInt(texts.hashCode())
                out.writeInt(vectors.size)
                out.writeInt(vectors.firstOrNull()?.size ?: 0)
                vectors.forEach { v -> v.forEach(out::writeFloat) }
            }
        }
    }
}
