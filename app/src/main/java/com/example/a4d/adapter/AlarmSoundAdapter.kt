package com.example.a4d.adapter

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.a4d.database.entity.AlarmSound
import com.example.a4d.databinding.ItemAlarmSoundBinding
import androidx.core.net.toUri

class AlarmSoundAdapter(
    private val onSoundSelected: (AlarmSound) -> Unit,
    private val onMoreOptionsClicked: (AlarmSound) -> Unit
) : ListAdapter<AlarmSound, AlarmSoundAdapter.ViewHolder>(AlarmSoundDiffCallback()) {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ItemAlarmSoundBinding.inflate(layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, { selectedItem ->
            playPreview(holder.itemView.context, selectedItem.uri)
            onSoundSelected(selectedItem)
        }, onMoreOptionsClicked)
    }

    private fun playPreview(context: android.content.Context, uriString: String) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val uri = uriString.toUri()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = false
                setOnPreparedListener { it.start() }
                setOnCompletionListener { 
                    it.stop()
                    it.release()
                    if (mediaPlayer == it) mediaPlayer = null
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    class ViewHolder(private val binding: ItemAlarmSoundBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: AlarmSound,
            onSoundSelected: (AlarmSound) -> Unit,
            onMoreOptionsClicked: (AlarmSound) -> Unit
        ) {
            binding.alarmSound = item
            
            // Set listener on the container for better touch target
            binding.clItemAlarmSound.setOnClickListener {
                onSoundSelected(item)
            }
            
            // Still allow the RadioButton to trigger selection if it receives the click
            binding.rbAlarmSound.setOnClickListener {
                onSoundSelected(item)
            }

            binding.btnMore.setOnClickListener {
                onMoreOptionsClicked(item)
            }

            binding.rbAlarmSound.text = item.name

            binding.executePendingBindings()
        }
    }

    class AlarmSoundDiffCallback : DiffUtil.ItemCallback<AlarmSound>() {
        override fun areItemsTheSame(oldItem: AlarmSound, newItem: AlarmSound): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AlarmSound, newItem: AlarmSound): Boolean {
            return oldItem == newItem
        }
    }
}