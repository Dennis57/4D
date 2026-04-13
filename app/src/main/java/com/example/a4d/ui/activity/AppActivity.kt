package com.example.a4d.ui.activity

import android.app.Application
import com.example.a4d.database.AppDatabase

class AppActivity : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        database
    }

}