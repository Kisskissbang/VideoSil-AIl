package com.videosil.ai

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.GLES20
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
import androidx.media3.common.MediaItem
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream

@UnstableApi
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
        if (videoView.width <= 0 || videoView.height <= 0) return

        btnRemove.isEnabled = false
        tvStatus.text = "Video GPU ile işleniyor, lütfen bekleyin..."

        val relX = ((selectionBox.x - videoView.x) / videoView.width.toFloat()).coerceIn(0.01f, 0.95f)
        val relW = (selectionBox.width / videoView.width.toFloat()).coerceIn(0.02f, 0.98f - relX)
        val topY = ((selectionBox.y - videoView.y) / videoView.height.toFloat()).coerceIn(0.01f, 0.95f)
        val relH = (selectionBox.height / videoView.height.toFloat()).coerceIn(0.02f, 0.98f - topY)
        val glY = (1.0f - topY - relH).coerceIn(0.01f, 0.95f)

        val outputFile = File(cacheDir, "VideoSil_${System.currentTimeMillis()}.mp4")
        val delogoEffect = DelogoGlEffect(relX, glY, relW, relH)

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(Effects(emptyList(), listOf(delogoEffect)))
            .build()

        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(outputFile)
                    tvStatus.text = "Tamamlandı! Temizlenen video galerinize kaydedildi."
                    videoView.setVideoPath(outputFile.absolutePath)
                    videoView.start()
                    selectionBox.visibility = View.GONE
                    btnRemove.isEnabled = true
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    tvStatus.text = "Hata: ${exportException.localizedMessage}"
                    btnRemove.isEnabled = true
                }
            })
            .build()

        transformer.start(editedMediaItem, outputFile.absolutePath)
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

    private class DelogoGlEffect(
        private val boxX: Float,
        private val boxY: Float,
        private val boxW: Float,
        private val boxH: Float
    ) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
            return DelogoShaderProgram(useHdr, boxX, boxY, boxW, boxH)
        }
    }

    private class DelogoShaderProgram(
        useHdr: Boolean,
        private val boxX: Float,
        private val boxY: Float,
        private val boxW: Float,
        private val boxH: Float
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """.trimIndent()

        private val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec4 uBox;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec2 uv = vTexSamplingCoord;
                float x0 = uBox.x;
                float y0 = uBox.y;
                float x1 = uBox.x + uBox.z;
                float y1 = uBox.y + uBox.w;
                if (uv.x > x0 && uv.x < x1 && uv.y > y0 && uv.y < y1) {
                    float tx = (uv.x - x0) / uBox.z;
                    float ty = (uv.y - y0) / uBox.w;
                    vec4 leftCol = texture2D(uTexSampler, vec2(max(0.0, x0 - 0.005), uv.y));
                    vec4 rightCol = texture2D(uTexSampler, vec2(min(1.0, x1 + 0.005), uv.y));
                    vec4 bottomCol = texture2D(uTexSampler, vec2(uv.x, max(0.0, y0 - 0.005)));
                    vec4 topCol = texture2D(uTexSampler, vec2(uv.x, min(1.0, y1 + 0.005)));
                    vec4 horiz = mix(leftCol, rightCol, tx);
                    vec4 vert = mix(bottomCol, topCol, ty);
                    gl_FragColor = mix(horiz, vert, 0.5);
                } else {
                    gl_FragColor = texture2D(uTexSampler, uv);
                }
            }
        """.trimIndent()

        private val glProgram = GlProgram(vertexShader, fragmentShader).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uBox", floatArrayOf(boxX, boxY, boxW, boxH))
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            glProgram.delete()
        }
    }
}
