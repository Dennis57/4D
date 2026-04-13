package com.example.a4d.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.a4d.database.entity.Users

@Dao
interface UsersDao {
    @Query("SELECT * FROM users")
    fun getAll(): List<Users>

    @Insert fun insert(users: Users)
}