package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FirewallDao {
    @Query("SELECT * FROM blocked_apps ORDER BY appName ASC")
    fun getAllAppsFlow(): Flow<List<FirewallApp>>

    @Query("SELECT packageName FROM blocked_apps WHERE isBlocked = 1")
    fun getBlockedPackageNamesFlow(): Flow<List<String>>

    @Query("SELECT packageName FROM blocked_apps WHERE isBlocked = 1")
    suspend fun getBlockedPackageNames(): List<String>

    @Query("SELECT * FROM blocked_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppByPackage(packageName: String): FirewallApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(app: FirewallApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<FirewallApp>)

    @Query("DELETE FROM blocked_apps")
    suspend fun clearAll()
}
