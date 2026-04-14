package com.example.a4d.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.media.RingtoneManager
import com.example.a4d.database.dao.AlarmSoundDao
import com.example.a4d.database.dao.UsersDao
import com.example.a4d.database.entity.AlarmSound
import com.example.a4d.database.entity.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [
    Users::class,
    AlarmSound::class],
    version = 2)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usersDao(): UsersDao
    abstract fun alarmSoundDao(): AlarmSoundDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "4D_database"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()

                INSTANCE = instance

                CoroutineScope(Dispatchers.IO).launch {
                    populateInitialData(context, instance)
                }

                instance
            }
        }

        private suspend fun populateInitialData(context: Context, db: AppDatabase) {
            val dao = db.alarmSoundDao()
            if (dao.getCount() == 0) {
                val ringtoneManager = RingtoneManager(context)
                ringtoneManager.setType(RingtoneManager.TYPE_ALARM)
                val cursor = ringtoneManager.cursor

                var count = 0
                while (cursor.moveToNext() && count < 2) {
                    val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                    val uri = ringtoneManager.getRingtoneUri(cursor.position)
                    
                    val alarmSound = AlarmSound(
                        name = title,
                        uri = uri.toString(),
                        isEnabled = count == 0 // Enable the first one by default
                    )
                    dao.insert(alarmSound)
                    count++
                }
                
                // Fallback if no alarms found via RingtoneManager
                if (count == 0) {
                    val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    dao.insert(AlarmSound(name = "Default Alarm", uri = defaultUri.toString(), isEnabled = true))
                    
                    val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    dao.insert(AlarmSound(name = "Default Notification", uri = notificationUri.toString(), isEnabled = false))
                }
            }
        }
    }
}