

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var videoUri: Uri? = null
    private var cues: List<SubtitleCue> = emptyList()
    private var outputFile: File? = null

    private lateinit var btnPickVideo: Button
    private lateinit var btnPickSrt: Button
    private lateinit var btnBurn: Button
    private lateinit var btnShare: Button
    private lateinit var tvVideoInfo: TextView
    private lateinit var tvSrtInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var spinnerFont: Spinner
    private lateinit var seekFontSize: SeekBar
    private lateinit var etColor: EditText
    private lateinit var etWatermark: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var videoPreview: VideoView

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        videoUri = uri
        val info = try { VideoProbe.probe(uri) } catch (e: Exception) { null }
        tvVideoInfo.text = info?.let { "${it.width}×${it.height} • ${it.fps}fps" } ?: "تعذّر قراءة الفيديو"
        updateBurnButton()
    }

    private val pickSrt = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri).use { stream ->
                stream?.let { cues = SrtParser.parse(it) }
            }
            tvSrtInfo.text = "${cues.size} مقطع ترجمة"
        } catch (e: Exception) {
            tvSrtInfo.text = "فشل تحليل الملف"
        }
        updateBurnButton()
  }
       
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPickVideo = findViewById(R.id.btnPickVideo)
        btnPickSrt = findViewById(R.id.btnPickSrt)
        btnBurn = findViewById(R.id.btnBurn)
        btnShare = findViewById(R.id.btnShare)
        tvVideoInfo = findViewById(R.id.tvVideoInfo)
        tvSrtInfo = findViewById(R.id.tvSrtInfo)
        tvStatus = findViewById(R.id.tvStatus)
        spinnerFont = findViewById(R.id.spinnerFont)
        seekFontSize = findViewById(R.id.seekFontSize)
        etColor = findViewById(R.id.etColor)
        etWatermark = findViewById(R.id.etWatermark)
        progressBar = findViewById(R.id.progressBar)
        videoPreview = findViewById(R.id.videoPreview)

        val fonts = arrayOf("sans-serif", "serif", "sans-serif-condensed", "monospace")
        spinnerFont.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fonts)

        btnPickVideo.setOnClickListener { pickVideo.launch("video/*") }
        btnPickSrt.setOnClickListener { pickSrt.launch("*/*") }
        btnBurn.setOnClickListener { startBurn() }
        btnShare.setOnClickListener { shareResult() }
    }

    private fun updateBurnButton() {
        btnBurn.isEnabled = videoUri != null && cues.isNotEmpty()
    }

    private fun startBurn() {
        val uri = videoUri ?: return
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        btnBurn.isEnabled = false
        tvStatus.text = "جارٍ الحرق..."

        val outDir = File(getExternalFilesDir(null), "output")
        outDir.mkdirs()
        val out = File(outDir, "subtitle_burned.mp4")
        outputFile = out

        val probe = VideoProbe.probe(uri)
        val overlay = SubtitleOverlay(probe.width, probe.height).apply {
            fontFamily = spinnerFont.selectedItem as String
            fontSizePercent = seekFontSize.progress
            textColor = try { Color.parseColor(etColor.text.toString()) } catch (e: Exception) { Color.WHITE }
            watermarkText = etWatermark.text.toString()
                 }
      Thread {
            val tempInput = File(cacheDir, "input_video")
            try {
                contentResolver.openInputStream(uri).use { input ->
                    FileOutputStream(tempInput).use { output -> input?.copyTo(output) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "فشل قراءة الفيديو"
                    progressBar.visibility = View.GONE
                    btnBurn.isEnabled = true
                }
                return@Thread
            }

            val burner = SubtitleBurner(Uri.fromFile(tempInput), out, cues, overlay) { progress ->
                runOnUiThread { progressBar.progress = (progress * 100).toInt() }
            }

            val success = burner.burn()
            runOnUiThread {
                progressBar.visibility = View.GONE
                btnBurn.isEnabled = true
                if (success) {
                    tvStatus.text = "تم الحرق بنجاح ✓"
                    btnShare.visibility = View.VISIBLE
                    videoPreview.visibility = View.VISIBLE
                    videoPreview.setVideoURI(Uri.fromFile(out))
                    videoPreview.start()
                } else {
                    tvStatus.text = "فشل الحرق"
                }
            }
        }.start()
    }

    private fun shareResult() {
        val file = outputFile ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
                  }
