package com.example.a4d.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
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
import com.example.a4d.databinding.ActivityMainBinding
import com.example.a4d.ui.viewmodel.AlarmSoundViewModel
import com.example.a4d.ui.viewmodel.AlarmSoundViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AlarmSoundViewModel by viewModels {
        AlarmSoundViewModelFactory((application as AppActivity).database.alarmSoundDao())
    }

    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { showNameInputDialog(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAlarmSoundAdapter()
        setupAddButton()

        ViewCompat.setOnApplyWindowInsetsListener(binding.clMain) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val initialPaddingLeft = v.paddingLeft
            val initialPaddingTop = v.paddingTop
            val initialPaddingRight = v.paddingRight
            val initialPaddingBottom = v.paddingBottom

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

    private fun setupAlarmSoundAdapter() {
        val adapter = AlarmSoundAdapter(
            onSoundSelected = { sound ->
                viewModel.selectAlarmSound(sound)
            },
            onMoreOptionsClicked = { sound ->
                // Handle more options (e.g., show a popup menu)
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