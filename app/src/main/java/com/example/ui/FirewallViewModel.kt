package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppUiModel
import com.example.data.FirewallRepository
import com.example.service.FirewallVpnService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface FirewallUiState {
    object Loading : FirewallUiState
    data class Success(
        val apps: List<AppUiModel>,
        val filteredApps: List<AppUiModel>,
        val blockedCount: Int,
        val allowedCount: Int,
        val isVpnActive: Boolean,
        val searchQuery: String = "",
        val filterMode: FilterMode = FilterMode.USER_ONLY,
        val timerOption: String = "ALWAYS",
        val customMinutes: Int = 15,
        val expirationTimeMs: Long = 0L
    ) : FirewallUiState
}

enum class FilterMode {
    ALL,
    USER_ONLY,
    SYSTEM_ONLY,
    BLOCKED_ONLY
}

class FirewallViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FirewallRepository(application.applicationContext)
    private val context = application.applicationContext

    private val _searchQuery = MutableStateFlow("")
    private val _filterMode = MutableStateFlow(FilterMode.USER_ONLY)
    private val _isVpnActive = MutableStateFlow(FirewallVpnService.isRunning)

    private val _timerOption = MutableStateFlow("ALWAYS")
    private val _customMinutes = MutableStateFlow(15)
    private val _expirationTimeMs = MutableStateFlow(0L)
    private val _appLanguage = MutableStateFlow("en")

    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    init {
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        _timerOption.value = prefs.getString("key_timer_option_type", "ALWAYS") ?: "ALWAYS"
        _customMinutes.value = prefs.getInt("key_custom_input_minutes", 15)
        _expirationTimeMs.value = prefs.getLong("key_expiration_timestamp_ms", 0L)

        val savedLang = prefs.getString("key_app_language", null)
        if (savedLang != null) {
            _appLanguage.value = savedLang
        } else {
            val systemLocale = java.util.Locale.getDefault().language
            _appLanguage.value = if (systemLocale == "vi") "vi" else "en"
        }
    }

    // Reactive pipeline combining DB changes, PackageManager details, searching, filtering, and timer options
    val uiState: StateFlow<FirewallUiState> = combine(
        repository.getAppsFlow(),
        _searchQuery,
        _filterMode,
        _isVpnActive,
        _timerOption,
        _customMinutes,
        _expirationTimeMs
    ) { states ->
        @Suppress("UNCHECKED_CAST")
        val apps = states[0] as List<AppUiModel>
        val query = states[1] as String
        val filter = states[2] as FilterMode
        val vpnActive = states[3] as Boolean
        val timerOption = states[4] as String
        val customMinutes = states[5] as Int
        val expirationTimeMs = states[6] as Long

        val blockedList = apps.filter { it.isBlocked }
        val blockedCount = blockedList.size
        val allowedCount = apps.size - blockedCount

        val filtered = apps.filter { app ->
            // Filter by App Name or Package Name search query
            val matchesQuery = app.label.contains(query, ignoreCase = true) || 
                             app.packageName.contains(query, ignoreCase = true)
            
            // Filter by category mode
            val matchesCategory = when (filter) {
                FilterMode.ALL -> true
                FilterMode.USER_ONLY -> !app.isSystem
                FilterMode.SYSTEM_ONLY -> app.isSystem
                FilterMode.BLOCKED_ONLY -> app.isBlocked
            }

            matchesQuery && matchesCategory
        }

        FirewallUiState.Success(
            apps = apps,
            filteredApps = filtered,
            blockedCount = blockedCount,
            allowedCount = allowedCount,
            isVpnActive = vpnActive,
            searchQuery = query,
            filterMode = filter,
            timerOption = timerOption,
            customMinutes = customMinutes,
            expirationTimeMs = expirationTimeMs
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FirewallUiState.Loading
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun toggleAppBlock(app: AppUiModel) {
        viewModelScope.launch {
            repository.toggleBlockStatus(app)
        }
    }

    /**
     * Get app icon asynchronously on-demand for LazyColumn list items.
     * Keeps core memory footprint low compared to preloading hundreds of drawables into CPU memory.
     */
    fun loadIcon(packageName: String): Drawable? {
        return repository.getAppIcon(packageName)
    }

    /**
     * Set timer option with shared preferences persistence.
     */
    fun setTimerOption(option: String) {
        _timerOption.value = option
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("key_timer_option_type", option).apply()
    }

    /**
     * Change current application locale setting.
     */
    fun setAppLanguage(lang: String) {
        _appLanguage.value = lang
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("key_app_language", lang).apply()
    }

    /**
     * Set custom minutes limit with shared preferences persistence.
     */
    fun setCustomMinutes(minutes: Int) {
        _customMinutes.value = minutes
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("key_custom_input_minutes", minutes).apply()
    }

    fun getSelectedDurationMinutes(): Int {
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        val option = prefs.getString("key_timer_option_type", "ALWAYS") ?: "ALWAYS"
        return when (option) {
            "ALWAYS" -> -1
            "10" -> 10
            "20" -> 20
            "30" -> 30
            "60" -> 60
            "CUSTOM" -> prefs.getInt("key_custom_input_minutes", 15)
            else -> -1
        }
    }

    /**
     * Set VPN state internally and launch or kill background service
     */
    fun toggleVpnState() {
        val nextState = !FirewallVpnService.isRunning
        _isVpnActive.value = nextState
        if (nextState) {
            startFirewallService()
        } else {
            stopFirewallService()
        }
    }

    fun refreshVpnState() {
        _isVpnActive.value = FirewallVpnService.isRunning
        val prefs = context.getSharedPreferences("firewall_prefs", Context.MODE_PRIVATE)
        _expirationTimeMs.value = prefs.getLong("key_expiration_timestamp_ms", 0L)
    }

    fun startFirewallService() {
        val duration = getSelectedDurationMinutes()
        val intent = Intent(context, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_START
            putExtra("extra_duration_minutes", duration)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isVpnActive.value = true

        // Force reactive refresh on expiration state
        if (duration > 0) {
            _expirationTimeMs.value = System.currentTimeMillis() + duration * 60 * 1000L
        } else {
            _expirationTimeMs.value = 0L
        }
    }

    fun stopFirewallService() {
        val intent = Intent(context, FirewallVpnService::class.java).apply {
            action = FirewallVpnService.ACTION_STOP
        }
        context.startService(intent)
        _isVpnActive.value = false
        _expirationTimeMs.value = 0L
    }

    fun blockAllFiltered(appsToBlock: List<AppUiModel>) {
        viewModelScope.launch {
            repository.bulkSetBlockedStatus(appsToBlock, block = true)
        }
    }

    fun unblockAll(appsToUnblock: List<AppUiModel>) {
        viewModelScope.launch {
            repository.bulkSetBlockedStatus(appsToUnblock, block = false)
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FirewallViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return FirewallViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
