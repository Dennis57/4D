package com.example.a4d.ui.activity

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.a4d.R
import com.example.a4d.database.AppDatabase
import com.example.a4d.databinding.ActivityDetectionBinding
import com.example.a4d.util.TimerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var cameraExecutor: ExecutorService
    private var mediaPlayer: MediaPlayer? = null
    private var tapCount = 0
    private var lastTapTime: Long = 0

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
            // while pushing content away from navigation bars, status bar and cutouts
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
        lifecycleScope.launch {
            val selectedAlarm = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(this@DetectionActivity).alarmSoundDao().getSelected()
            }

            if (selectedAlarm != null) {
                playAlarm(selectedAlarm.uri)
                showAlarmDialog()
            } else {
                Toast.makeText(this@DetectionActivity, "No alarm sound selected", Toast.LENGTH_SHORT).show()
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

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Use case binding failed", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
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
        stopAlarm()
    }
}