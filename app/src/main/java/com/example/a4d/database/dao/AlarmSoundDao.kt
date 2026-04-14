package com.example.a4d.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.a4d.database.entity.AlarmSound
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmSoundDao {
    @Query("SELECT * FROM alarm_sounds")
    fun getAll(): Flow<List<AlarmSound>>

    @Query("SELECT COUNT(*) FROM alarm_sounds")
    suspend fun getCount(): Int

    @Insert
    suspend fun insert(alarmSound: AlarmSound)

    @Update
    suspend fun update(alarmSound: AlarmSound)

    @Query("UPDATE alarm_sounds SET is_enabled = 0")
    suspend fun disableAll()

    @Query("UPDATE alarm_sounds SET is_enabled = 1 WHERE id = :id")
    suspend fun enableById(id: Int)
}