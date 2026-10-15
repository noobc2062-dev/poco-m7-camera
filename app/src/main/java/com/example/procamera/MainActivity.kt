package com.example.procamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var captureButton: Button
    private lateinit var videoButton: Button
    private lateinit var ratioButton: Button
    private lateinit var flashButton: Button
    private lateinit var cameraButton: Button
    private lateinit var timerButton: Button
    private lateinit var executor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeCamera: Camera? = null
    private var recording: Recording? = null
    private var frontCamera = false
    private var flashEnabled = false
    private var timerSeconds = 0
    private val ratios = listOf("1:1", "9:16", "4:3", "16:9")
    private var ratioIndex = 0
    private var videoMode = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true) startCamera() else showMessage("Camera permission zaroori hai")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executors.newSingleThreadExecutor()
        buildUi()
        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(requiredPermissions())
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(10), dp(12), dp(10), dp(10))
        }
        status = TextView(this).apply {
            text = "PRO CAMERA  •  PHOTO"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f; gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(10))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(40)))
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))

        val ratioRow = row(root)
        ratioButton = makeButton("Ratio 1:1") { ratioIndex = (ratioIndex + 1) % ratios.size; ratioButton.text = "Ratio ${ratios[ratioIndex]}"; startCamera() }
        flashButton = makeButton("Flash OFF") { flashEnabled = !flashEnabled; flashButton.text = if (flashEnabled) "Flash ON" else "Flash OFF"; imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF; activeCamera?.cameraControl?.enableTorch(flashEnabled) }
        timerButton = makeButton("Timer OFF") { timerSeconds = when (timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }; timerButton.text = if (timerSeconds == 0) "Timer OFF" else "Timer ${timerSeconds}s" }
        ratioRow.addView(ratioButton, weighted()); ratioRow.addView(flashButton, weighted()); ratioRow.addView(timerButton, weighted())

        val modeRow = row(root)
        modeRow.addView(makeButton("PHOTO") { videoMode = false; status.text = "PRO CAMERA  •  PHOTO"; captureButton.text = "● PHOTO"; videoButton.text = "VIDEO MODE" }, weighted())
        modeRow.addView(makeButton("VIDEO MODE") { videoMode = true; status.text = "PRO CAMERA  •  VIDEO"; captureButton.text = "● RECORD"; videoButton.text = "STOP / REC" }, weighted())
        val actionRow = row(root)
        cameraButton = makeButton("↻ CAMERA") { frontCamera = !frontCamera; startCamera() }
        captureButton = makeButton("● PHOTO") { if (videoMode) toggleRecording() else takePhoto() }
        videoButton = makeButton("GALLERY") { openGallery() }
        actionRow.addView(cameraButton, weighted()); actionRow.addView(captureButton, weighted(1.3f)); actionRow.addView(videoButton, weighted())
        root.addView(TextView(this).apply { text = "Zoom: pinch preview  •  Tap preview to focus"; textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xFFBBBBBB.toInt()); setPadding(0, dp(7), 0, 0) })
        setContentView(root)
    }

    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(5), 0, dp(5)); parent.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
    }
    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; isAllCaps = false
        setOnClickListener { action() }
    }
    private fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, -1, weight).apply { setMargins(dp(3), 0, dp(3), 0) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!hasCameraPermission()) { permissionLauncher.launch(requiredPermissions()); return }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val selector = if (frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val targetRatio = if (ratios[ratioIndex] == "16:9" || ratios[ratioIndex] == "9:16") ImageCapture.Builder().setTargetAspectRatio(ImageCapture.ASPECT_RATIO_16_9).build() else ImageCapture.Builder().setTargetAspectRatio(ImageCapture.ASPECT_RATIO_4_3).build()
                targetRatio.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                imageCapture = targetRatio
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD, QualitySelector.FALLBACK_STRATEGY_LOWER_QUALITY)).build()
                videoCapture = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                activeCamera = provider.bindToLifecycle(this, selector, preview, targetRatio, videoCapture)
                flashButton.text = if (flashEnabled) "Flash ON" else "Flash OFF"
                if (frontCamera) { flashButton.isEnabled = false; flashButton.text = "Flash N/A" } else flashButton.isEnabled = true
                previewView.setOnClickListener {
                    val point = previewView.meteringPointFactory.createPoint(it.x, it.y)
                    val action = androidx.camera.core.FocusMeteringAction.Builder(point).build()
                    activeCamera?.cameraControl?.startFocusAndMetering(action)
                }
                previewView.setOnTouchListener(ZoomTouchListener { scale ->
                    activeCamera?.let { camera ->
                        val state = camera.cameraInfo.zoomState.value
                        val max = state?.maxZoomRatio ?: 1f
                        val min = state?.minZoomRatio ?: 1f
                        camera.cameraControl.setZoomRatio((scale * (state?.zoomRatio ?: 1f)).coerceIn(min, max))
                    }
                    true
                })
            } catch (e: Exception) { showMessage("Camera start nahi hua: ${e.localizedMessage ?: "unsupported camera"}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run { showMessage("Camera abhi ready nahi hai"); return }
        val doCapture = {
            val name = "ProCamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.jpg"
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg"); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCamera") }
            val output = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            capture.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (ratios[ratioIndex] == "1:1" && result.savedUri != null) cropToSquare(result.savedUri!!)
                    else showMessage("Photo gallery mein save ho gayi")
                }
                override fun onError(exception: ImageCaptureException) { showMessage("Photo error: ${exception.message}") }
            })
        }
        if (timerSeconds == 0) doCapture() else { showMessage("Photo ${timerSeconds} second mein..."); previewView.postDelayed({ doCapture() }, timerSeconds * 1000L) }
    }

    private fun cropToSquare(uri: Uri) {
        executor.execute {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw Exception("Image read failed")
                val side = minOf(bitmap.width, bitmap.height)
                val left = (bitmap.width - side) / 2; val top = (bitmap.height - side) / 2
                val square = Bitmap.createBitmap(bitmap, left, top, side, side)
                contentResolver.openOutputStream(uri, "w")?.use { out -> square.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                bitmap.recycle(); square.recycle()
                runOnUiThread { showMessage("Square photo save ho gayi") }
            } catch (_: Exception) { runOnUiThread { showMessage("Photo save ho gayi; square crop nahi ho saka") } }
        }
    }

    private fun toggleRecording() {
        val vc = videoCapture ?: run { showMessage("Video camera ready nahi"); return }
        val current = recording
        if (current != null) { current.stop(); recording = null; captureButton.text = "● RECORD"; return }
        val name = "ProCamera_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())}.mp4"
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4"); if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera") }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, options)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { captureButton.text = "■ STOP"; status.text = "RECORDING VIDEO" }
                is VideoRecordEvent.Finalize -> {
                    recording = null; captureButton.text = "● RECORD"; status.text = "PRO CAMERA  •  VIDEO"
                    if (!event.hasError()) showMessage("Video gallery mein save ho gayi") else showMessage("Video error: ${event.error}")
                }
            }
        }
    }

    private fun openGallery() {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"); addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) { showMessage("Gallery app nahi mili") }
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        recording?.stop(); executor.shutdown(); super.onDestroy()
    }

    private class ZoomTouchListener(private val onZoom: (Float) -> Boolean) : View.OnTouchListener {
        private var previousDistance = 0f
        override fun onTouch(view: View, event: android.view.MotionEvent): Boolean {
            if (event.pointerCount < 2) { previousDistance = 0f; return false }
            val dx = event.getX(0) - event.getX(1); val dy = event.getY(0) - event.getY(1)
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (previousDistance > 0f) onZoom(distance / previousDistance)
            previousDistance = distance
            return true
        }
    }
}
