package com.example.a4d.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.a4d.database.dao.UsersDao
import com.example.a4d.database.entity.Users
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [
    Users::class],
    version = 1)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usersDao(): UsersDao

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
                    populateInitialData(instance)
                }

                instance
            }
        }

        private suspend fun populateInitialData(db: AppDatabase) {

        }
    }
}