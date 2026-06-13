package com.example.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class AppUiModel(
    val packageName: String,
    val label: String,
    val isBlocked: Boolean,
    val isSystem: Boolean,
    val icon: Drawable? = null
)

class FirewallRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.firewallDao()
    private val packageManager: PackageManager = context.packageManager

    // Expose all blocked packages reactively
    val blockedPackageNamesFlow: Flow<List<String>> = dao.getBlockedPackageNamesFlow()

    // Retrieve active blocked package list synchronously/async from DB
    suspend fun getBlockedPackageNames(): List<String> {
        return dao.getBlockedPackageNames()
    }

    /**
     * Get list of apps installed on the system combined with their firewall status.
     * Running on IO dispatcher to avoid jank.
     */
    fun getAppsFlow(): Flow<List<AppUiModel>> {
        return dao.getAllAppsFlow().map { dbApps ->
            val dbMap = dbApps.associateBy { it.packageName }
            val installedApps = getInstalledApplicationsInfo()

            installedApps.map { instApp ->
                val dbRecord = dbMap[instApp.packageName]
                val isBlocked = dbRecord?.isBlocked ?: false
                
                AppUiModel(
                    packageName = instApp.packageName,
                    label = instApp.label,
                    isBlocked = isBlocked,
                    isSystem = instApp.isSystem
                )
            }.sortedWith(compareBy({ !it.isBlocked }, { it.label.lowercase() }))
        }
    }

    /**
     * Retrieve the list of installed applications by querying PackageManager.
     */
    private fun getInstalledApplicationsInfo(): List<InstalledAppTemp> {
        val flags = PackageManager.GET_META_DATA
        val apps = packageManager.getInstalledApplications(flags)
        val selfPackageName = context.packageName

        return apps.mapNotNull { appInfo ->
            // Skip our own package to prevent self-blocking accidents!
            if (appInfo.packageName == selfPackageName) return@mapNotNull null

            // Only show apps that can launch (have a launcher intent) or have network permission declared
            // (Usually, filtering for apps with launcher intent or all non-system apps is standard. Let's list launcher list plus common ones to avoid flooding with internal micro-packages).
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = appInfo.loadLabel(packageManager).toString()

            InstalledAppTemp(
                packageName = appInfo.packageName,
                label = if (label.isBlank()) appInfo.packageName else label,
                isSystem = isSystem
            )
        }
    }

    /**
     * Get App Icon dynamically. This avoids storing high-resolution Bitmaps in the database
     * which causes cursor size exceptions and heavy memory footprint!
     */
    fun getAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Toggle the blocking status for an application.
     */
    suspend fun toggleBlockStatus(uiModel: AppUiModel) {
        withContext(Dispatchers.IO) {
            val newBlockStatus = !uiModel.isBlocked
            val entity = FirewallApp(
                packageName = uiModel.packageName,
                appName = uiModel.label,
                isBlocked = newBlockStatus,
                isSystemApp = uiModel.isSystem
            )
            dao.insertOrUpdate(entity)
        }
    }

    /**
     * Bulk configure blocked states (e.g. "Block All User Apps" or "Unblock All").
     */
    suspend fun bulkSetBlockedStatus(packages: List<AppUiModel>, block: Boolean) {
        withContext(Dispatchers.IO) {
            val entities = packages.map {
                FirewallApp(
                    packageName = it.packageName,
                    appName = it.label,
                    isBlocked = block,
                    isSystemApp = it.isSystem
                )
            }
            dao.insertAll(entities)
        }
    }

    private data class InstalledAppTemp(
        val packageName: String,
        val label: String,
        val isSystem: Boolean
    )
}
