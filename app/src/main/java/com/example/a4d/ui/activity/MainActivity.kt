package com.example.a4d.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.a4d.R
import com.example.a4d.adapter.AlarmSoundAdapter
import com.example.a4d.database.entity.AlarmSound
import com.example.a4d.databinding.ActivityMainBinding
import com.example.a4d.ui.viewmodel.AlarmSoundViewModel
import com.example.a4d.ui.viewmodel.AlarmSoundViewModelFactory
import com.example.a4d.util.TimerManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AlarmSoundViewModel by viewModels {
        AlarmSoundViewModelFactory((application as AppActivity).database.alarmSoundDao())
    }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { showNameInputDialog(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAlarmSoundAdapter()
        setupAddButton()
        setupTimerButtons()
        setupNavigationButton()
        observeTimer()

        // Make sure that the initializations are not inside the setOnAppWindowInsetsListener
        val initialPaddingLeft = binding.clMain.paddingLeft
        val initialPaddingTop = binding.clMain.paddingTop
        val initialPaddingRight = binding.clMain.paddingRight
        val initialPaddingBottom = binding.clMain.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.clMain) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            v.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )

            insets
        }
    }

    private fun setupAddButton() {
        binding.btnAddAlarmSound.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }
    }

    private fun setupTimerButtons() {
        binding.cFiveMin.setOnClickListener { binding.etCustomNumber.setText("5") }
        binding.cTenMin.setOnClickListener { binding.etCustomNumber.setText("10") }
        binding.cTwentyMin.setOnClickListener { binding.etCustomNumber.setText("20") }
        binding.cThirtyMin.setOnClickListener { binding.etCustomNumber.setText("30") }

        binding.btnStartTimer.setOnClickListener {
            val minutesStr = binding.etCustomNumber.text.toString()
            if (minutesStr.isNotEmpty()) {
                val minutes = minutesStr.toInt()
                TimerManager.startTimer(minutes)
                Toast.makeText(this, "Timer started for $minutes minutes", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Toast.makeText(this, "Please enter minutes", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnStopTimer.setOnClickListener {
            TimerManager.stopTimer()
            Toast.makeText(this, "Timer stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNavigationButton() {
        binding.btnGoToDetection.setOnClickListener {
            val intent = Intent(this, DetectionActivity::class.java)
            startActivity(intent)
        }
    }

    private fun observeTimer() {
        TimerManager.isTimerRunning.observe(this) { isRunning ->
            binding.btnStartTimer.isEnabled = !isRunning
            binding.btnStopTimer.isEnabled = isRunning
        }

        TimerManager.remainingTime.observe(this) { millis ->
            if (millis > 0) {
                val minutes = (millis / 1000) / 60
                val seconds = (millis / 1000) % 60
                binding.btnStartTimer.text =
                    String.format(Locale.getDefault(), "Running (%02d:%02d)", minutes, seconds)
            } else {
                binding.btnStartTimer.text = "Start Timer"
            }
        }
    }

    private fun showNameInputDialog(uri: Uri) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_alarm_sound, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_alarm_name)

        AlertDialog.Builder(this)
            .setTitle("Add Alarm Sound")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = etName.text.toString().ifBlank { "Custom Sound" }
                // Take persistable permission if possible to ensure we can access the file later
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Fallback if the URI is not persistable
                }
                viewModel.addAlarmSound(name, uri.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoreOptionsPopup(sound: AlarmSound) {
        // Find the view to anchor the popup.
        val view = binding.rvAlarmSound.findViewWithTag<android.view.View>(sound.id)
            ?: binding.btnAddAlarmSound

        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_alarm_sound_options, popup.menu)

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_edit -> {
                    showEditNameDialog(sound)
                    true
                }

                R.id.action_delete -> {
                    showDeleteConfirmationDialog(sound)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showEditNameDialog(sound: AlarmSound) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_alarm_sound, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_alarm_name)
        etName.setText(sound.name)

        AlertDialog.Builder(this)
            .setTitle("Edit Alarm Sound Name")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newName = etName.text.toString().ifBlank { sound.name }
                viewModel.updateAlarmSound(sound.copy(name = newName))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmationDialog(sound: AlarmSound) {
        AlertDialog.Builder(this)
            .setTitle("Delete Alarm Sound")
            .setMessage("Are you sure you want to delete '${sound.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteAlarmSound(sound)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupAlarmSoundAdapter() {
        val adapter = AlarmSoundAdapter(
            onSoundSelected = { sound ->
                viewModel.selectAlarmSound(sound)
            },
            onMoreOptionsClicked = { sound ->
                showMoreOptionsPopup(sound)
            }
        )

        binding.rvAlarmSound.apply {
            this.adapter = adapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        viewModel.allAlarmSounds.observe(this) { sounds ->
            adapter.submitList(sounds)
        }
    }
}