package com.example.a4d.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.a4d.database.dao.AlarmSoundDao
import com.example.a4d.database.entity.AlarmSound
import kotlinx.coroutines.launch

class AlarmSoundViewModel(private val dao: AlarmSoundDao) : ViewModel() {

    val allAlarmSounds = dao.getAll().asLiveData()

    fun selectAlarmSound(alarmSound: AlarmSound) {
        viewModelScope.launch {
            dao.disableAll()
            dao.enableById(alarmSound.id)
        }
    }
}

class AlarmSoundViewModelFactory(private val dao: AlarmSoundDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AlarmSoundViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AlarmSoundViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}