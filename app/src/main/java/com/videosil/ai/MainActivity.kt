package com.videosil.ai

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var selectionBox: View
    private lateinit var tvStatus: TextView
    private lateinit var btnRemove: Button

    private var selectedVideoUri: Uri? = null
    private var dX = 0f
    private var dY = 0f

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedVideoUri = result.data?.data
            selectedVideoUri?.let { uri ->
                videoView.setVideoURI(uri)
                videoView.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    videoView.start()
                }
                selectionBox.visibility = View.VISIBLE
                btnRemove.isEnabled = true
                tvStatus.text = "Kırmızı kutuyu silmek istediğiniz yazının üzerine sürükleyin."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        videoView = findViewById(R.id.videoView)
        selectionBox = findViewById(R.id.selectionBox)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSelect: Button = findViewById(R.id.btnSelectVideo)
        btnRemove = findViewById(R.id.btnRemoveWatermark)

        btnSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            pickVideoLauncher.launch(intent)
        }

        selectionBox.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                }
            }
            true
        }

        btnRemove.setOnClickListener {
            selectedVideoUri?.let { uri -> processVideo(uri) }
        }
    }

    private fun processVideo(uri: Uri) {
        btnRemove.isEnabled = false
        tvStatus.text = "Video işleniyor, lütfen bekleyin..."

        Thread {
            try {
                val inputFile = File(cacheDir, "input_video.mp4")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(inputFile).use { output -> input.copyTo(output) }
                }

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)
                val videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
                val videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
                retriever.release()

                val scaleX = videoW.toFloat() / videoView.width.toFloat()
                val scaleY = videoH.toFloat() / videoView.height.toFloat()

                val x = (selectionBox.x * scaleX).toInt().coerceIn(2, videoW - 20)
                val y = (selectionBox.y * scaleY).toInt().coerceIn(2, videoH - 20)
                val w = (selectionBox.width * scaleX).toInt().coerceIn(10, videoW - x - 2)
                val h = (selectionBox.height * scaleY).toInt().coerceIn(10, videoH - y - 2)

                val outputFile = File(cacheDir, "VideoSil_${System.currentTimeMillis()}.mp4")
                val cmd = "-y -i \"${inputFile.absolutePath}\" -vf \"delogo=x=$x:y=$y:w=$w:h=$h:show=0\" -c:a copy \"${outputFile.absolutePath}\""

                val session = FFmpegKit.execute(cmd)
                if (ReturnCode.isSuccess(session.returnCode)) {
                    saveToGallery(outputFile)
                    runOnUiThread {
                        tvStatus.text = "Tamamlandı! Video galerinize kaydedildi."
                        videoView.setVideoPath(outputFile.absolutePath)
                        videoView.start()
                        selectionBox.visibility = View.GONE
                        btnRemove.isEnabled = true
                    }
                } else {
                    runOnUiThread {
                        tvStatus.text = "İşlem sırasında hata oluştu. Kutuyu video sınırları içinde tutun."
                        btnRemove.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Hata: ${e.message}"
                    btnRemove.isEnabled = true
                }
            }
        }.start()
    }

    private fun saveToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoSilAI")
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
        }
    }
}
