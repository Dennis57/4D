package com.example.a4d.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarm_sounds")
data class AlarmSound(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "is_enabled") var isEnabled: Boolean = false
)