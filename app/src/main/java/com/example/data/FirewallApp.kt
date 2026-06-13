package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_apps")
data class FirewallApp(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isBlocked: Boolean = false,
    val isSystemApp: Boolean = false
)
