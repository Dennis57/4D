package com.example.a4d.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.a4d.R
import com.example.a4d.database.AppDatabase
import com.example.a4d.databinding.ActivityDetectionBinding
import com.example.a4d.util.ObjectDetectorHelper
import com.example.a4d.util.TimerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionActivity : AppCompatActivity(), ObjectDetectorHelper.DetectorListener {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var cameraExecutor: ExecutorService
    private var mediaPlayer: MediaPlayer? = null
    private var tapCount = 0
    private var lastTapTime: Long = 0
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private var isAlarmShowing = false

    // Smoothing window for detection
    private var unsafeFrameCount = 0
    private val UNSAFE_THRESHOLD = 15 // Trigger alarm after ~15 consecutive frames (~0.5-1s)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbarDetection)
        supportActionBar?.apply {
            title = "Detecting Driver"
            setDisplayShowTitleEnabled(true)
        }

        val initialStatusMargin = (binding.tvDetectionStatus.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            
            // Toolbar: Use padding for sides and top to keep the white background extending to edges
            val toolbarParams = binding.toolbarDetection.layoutParams as ViewGroup.MarginLayoutParams
            toolbarParams.leftMargin = 0
            toolbarParams.rightMargin = 0
            binding.toolbarDetection.layoutParams = toolbarParams
            binding.toolbarDetection.setPadding(bars.left, bars.top, bars.right, 0)
            
            // Status text: Stay above bottom navigation bar and away from side bars
            val statusParams = binding.tvDetectionStatus.layoutParams as ViewGroup.MarginLayoutParams
            statusParams.bottomMargin = initialStatusMargin + bars.bottom
            statusParams.leftMargin = bars.left
            statusParams.rightMargin = bars.right
            binding.tvDetectionStatus.layoutParams = statusParams

            // Countdown: Keep centered relative to the visible area
            val countdownParams = binding.tvCountdown.layoutParams as ViewGroup.MarginLayoutParams
            countdownParams.leftMargin = bars.left
            countdownParams.rightMargin = bars.right
            binding.tvCountdown.layoutParams = countdownParams

            insets
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            objectDetectorListener = this
        )

        observeTimer()
        setupTestTrigger()
    }

    private fun setupTestTrigger() {
        binding.tvDetectionStatus.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime > 1000) {
                tapCount = 1
            } else {
                tapCount++
            }
            lastTapTime = currentTime

            if (tapCount >= 5) {
                tapCount = 0
                triggerAlarm()
            }
        }
    }

    private fun triggerAlarm() {
        if (isAlarmShowing) return
        isAlarmShowing = true
        
        lifecycleScope.launch {
            val selectedAlarm = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@DetectionActivity).alarmSoundDao().getSelected()
            }

            if (selectedAlarm != null) {
                playAlarm(selectedAlarm.uri)
                showAlarmDialog()
            } else {
                Toast.makeText(this@DetectionActivity, "No alarm sound selected", Toast.LENGTH_SHORT).show()
                isAlarmShowing = false
            }
        }
    }

    private fun playAlarm(uriString: String) {
        mediaPlayer?.release()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@DetectionActivity, Uri.parse(uriString))
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to play alarm", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAlarmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Alert")
            .setMessage("Drowsy / Distracted is detected!")
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                stopAlarm()
                isAlarmShowing = false
                unsafeFrameCount = 0
                dialog.dismiss()
            }
            .show()
    }

    private fun stopAlarm() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun observeTimer() {
        TimerManager.remainingTime.observe(this) { millis ->
            if (millis > 0) {
                binding.tvCountdown.visibility = View.VISIBLE
                val minutes = (millis / 1000) / 60
                val seconds = (millis / 1000) % 60
                binding.tvCountdown.text = String.format("%02d:%02d", minutes, seconds)
            } else {
                binding.tvCountdown.visibility = View.GONE
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        val bitmapBuffer = Bitmap.createBitmap(
                            image.width,
                            image.height,
                            Bitmap.Config.ARGB_8888
                        )
                        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

                        val imageRotation = image.imageInfo.rotationDegrees
                        objectDetectorHelper.detect(bitmapBuffer, imageRotation)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("DetectionActivity", "Use case binding failed", exc)
                Toast.makeText(this, "Use case binding failed", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onError(error: String) {
        runOnUiThread {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(
        result: ObjectDetectorHelper.DetectionResults?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    ) {
        runOnUiThread {
            val detections = result?.detections()
            if (detections != null && detections.isNotEmpty()) {
                val topDetection = detections[0]
                val topCategory = topDetection.categories()[0]
                val index = topCategory.index()
                val label = topCategory.categoryName()
                val score = topCategory.score()

                binding.tvDetectionStatus.text = "Currently detecting: $label (${String.format("%.2f", score)})"
                
                // Unsafe states based on model indices:
                // 1: close, 8: drinking, 10: phonecall_left, 11: phonecall_right, 12: radio
                val unsafeIndices = listOf(1, 8, 10, 11, 12)
                val isUnsafe = index in unsafeIndices && score > 0.5f

                if (isUnsafe) {
                    binding.tvDetectionStatus.setTextColor(Color.RED)
                    unsafeFrameCount++
                    binding.tvDetectionStatus.text = "Unsafe: $label (${String.format("%.2f", score)}) [$unsafeFrameCount/$UNSAFE_THRESHOLD]"
                    if (unsafeFrameCount >= UNSAFE_THRESHOLD) {
                        triggerAlarm()
                    }
                } else {
                    binding.tvDetectionStatus.setTextColor(Color.WHITE)
                    binding.tvDetectionStatus.text = "Currently detecting: $label (${String.format("%.2f", score)})"
                    if (unsafeFrameCount > 0) unsafeFrameCount--
                }
            } else {
                binding.tvDetectionStatus.setTextColor(Color.WHITE)
                binding.tvDetectionStatus.text = "Currently detecting: Nothing"
                if (unsafeFrameCount > 0) unsafeFrameCount--
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_detection, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_back -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetectorHelper.clearObjectDetector()
        stopAlarm()
    }
}
