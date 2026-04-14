package com.example.a4d.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.a4d.adapter.AlarmSoundAdapter
import com.example.a4d.databinding.ActivityMainBinding
import com.example.a4d.ui.viewmodel.AlarmSoundViewModel
import com.example.a4d.ui.viewmodel.AlarmSoundViewModelFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AlarmSoundViewModel by viewModels {
        AlarmSoundViewModelFactory((application as AppActivity).database.alarmSoundDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAlarmSoundAdapter()

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