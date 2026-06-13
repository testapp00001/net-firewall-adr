package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppUiModel
import com.example.ui.FilterMode
import com.example.ui.FirewallUiState
import com.example.ui.FirewallViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StateAllow
import com.example.ui.theme.StateBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FirewallScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    modifier: Modifier = Modifier,
    viewModel: FirewallViewModel = viewModel(factory = FirewallViewModel.Factory(LocalContext.current.applicationContext as android.app.Application))
) {
    val baseContext = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appLanguageState by viewModel.appLanguage.collectAsStateWithLifecycle()

    val locale = remember(appLanguageState) { java.util.Locale(appLanguageState) }
    java.util.Locale.setDefault(locale)
    val config = android.content.res.Configuration(baseContext.resources.configuration)
    config.setLocale(locale)
    val localizedContext = remember(appLanguageState) {
        baseContext.createConfigurationContext(config)
    }

    // Activity launcher for system VPN dialog permission confirmation
    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startFirewallService()
            Toast.makeText(localizedContext, localizedContext.getString(R.string.toast_shield_engaged), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(localizedContext, localizedContext.getString(R.string.toast_vpn_declined), Toast.LENGTH_LONG).show()
        }
    }

    // Process helper to initialize VPN system initiation helper
    val startVpnProcedureFlow = remember(localizedContext) {
        {
            val intent = VpnService.prepare(localizedContext)
            if (intent != null) {
                vpnPrepareLauncher.launch(intent)
            } else {
                viewModel.startFirewallService()
                Toast.makeText(localizedContext, localizedContext.getString(R.string.toast_protection_engaged), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Activity launcher for Android 13+ runtime POST_NOTIFICATIONS consent check
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(localizedContext, localizedContext.getString(R.string.toast_permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(localizedContext, localizedContext.getString(R.string.toast_permission_denied), Toast.LENGTH_LONG).show()
        }
        startVpnProcedureFlow()
    }

    CompositionLocalProvider(LocalContext provides localizedContext) {
        val context = LocalContext.current

        // Refresh state when resuming/viewing
        DisposableEffect(Unit) {
            viewModel.refreshVpnState()
            onDispose { }
        }

        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.background
        ) {
            when (val uiState = state) {
            is FirewallUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.auditing_nodes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            is FirewallUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Title Header with Language Picker
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(3.dp)
                        ) {
                            listOf("en" to "EN", "vi" to "VI").forEach { (code, label) ->
                                val isSelected = appLanguageState == code
                                val bg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                                val tc = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .clickable { viewModel.setAppLanguage(code) }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = tc
                                    )
                                }
                            }
                        }
                    }

                    // 1. Dashboard Status Panel
                    DashboardStatusPanel(
                        isVpnActive = uiState.isVpnActive,
                        blockedCount = uiState.blockedCount,
                        allowedCount = uiState.allowedCount,
                        timerOption = uiState.timerOption,
                        customMinutes = uiState.customMinutes,
                        expirationTimeMs = uiState.expirationTimeMs,
                        onTimerOptionChange = { viewModel.setTimerOption(it) },
                        onCustomMinutesChange = { viewModel.setCustomMinutes(it) },
                        onToggleMaster = {
                            if (uiState.isVpnActive) {
                                viewModel.stopFirewallService()
                                Toast.makeText(context, context.getString(R.string.toast_firewall_paused), Toast.LENGTH_SHORT).show()
                            } else {
                                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    startVpnProcedureFlow()
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 2. Battery & Efficiency Notice Card
                    EfficiencyNoticeBox()

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. App Search and Segmented Selector Node
                    SearchBarAndFilter(
                        query = uiState.searchQuery,
                        onQueryChange = { viewModel.updateSearchQuery(it) },
                        currentFilter = uiState.filterMode,
                        onFilterChange = { viewModel.updateFilterMode(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Action Command Bar (Bulk configs)
                    BulkControlBar(
                        filteredList = uiState.filteredApps,
                        onBlockAll = {
                            viewModel.blockAllFiltered(uiState.filteredApps)
                            Toast.makeText(context, context.getString(R.string.toast_blocked_apps, uiState.filteredApps.size), Toast.LENGTH_SHORT).show()
                        },
                        onUnblockAll = {
                            viewModel.unblockAll(uiState.filteredApps)
                            Toast.makeText(context, context.getString(R.string.toast_restored_apps, uiState.filteredApps.size), Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 5. Scroll View of Configurable Applications
                    if (uiState.filteredApps.isEmpty()) {
                        EmptyAppsState(uiState.searchQuery)
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(
                                items = uiState.filteredApps,
                                key = { it.packageName }
                            ) { appModel ->
                                AppFirewallRowCard(
                                    appModel = appModel,
                                    loadIcon = { viewModel.loadIcon(it) },
                                    onToggle = { viewModel.toggleAppBlock(appModel) }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * 1. Gorgeous Dashboard Status Panel
 * Custom radial visual card with gradient.
 */
@Composable
fun DashboardStatusPanel(
    isVpnActive: Boolean,
    blockedCount: Int,
    allowedCount: Int,
    timerOption: String,
    customMinutes: Int,
    expirationTimeMs: Long,
    onTimerOptionChange: (String) -> Unit,
    onCustomMinutesChange: (Int) -> Unit,
    onToggleMaster: () -> Unit
) {
    val transition = updateTransition(targetState = isVpnActive, label = "cardColorTransition")
    
    val pulseState = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseState.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    val surfaceColor by transition.animateColor(label = "bg") { active ->
        if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val statusText = if (isVpnActive) stringResource(R.string.protection_engaged) else stringResource(R.string.firewall_idle)
    val statusColor = if (isVpnActive) StateAllow else MaterialTheme.colorScheme.secondary

    var remainingSeconds by remember(expirationTimeMs, isVpnActive) {
        mutableStateOf(if (isVpnActive && expirationTimeMs > System.currentTimeMillis()) {
            maxOf(0L, (expirationTimeMs - System.currentTimeMillis()) / 1000L)
        } else 0L)
    }

    LaunchedEffect(isVpnActive, expirationTimeMs) {
        if (isVpnActive && expirationTimeMs > System.currentTimeMillis()) {
            while (true) {
                val currentRemaining = (expirationTimeMs - System.currentTimeMillis()) / 1000L
                remainingSeconds = maxOf(0L, currentRemaining)
                if (currentRemaining <= 0) break
                kotlinx.coroutines.delay(1000L)
            }
        } else {
            remainingSeconds = 0L
        }
    }

    val formattedTime = if (remainingSeconds > 0) {
        val min = remainingSeconds / 60
        val sec = remainingSeconds % 60
        String.format("%02dm %02ds", min, sec)
    } else {
        stringResource(R.string.timer_expiring)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = surfaceColor),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.5.dp, 
                if (isVpnActive) StateAllow.copy(alpha = 0.4f) else Color.Transparent, 
                RoundedCornerShape(24.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Circular active defense pulse indicator
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isVpnActive) StateAllow.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(
                                alpha = 0.1f
                            )
                        )
                        .drawBehind {
                            if (isVpnActive) {
                                drawCircle(
                                    color = StateAllow.copy(alpha = 0.35f - (pulseRadius / 20f)),
                                    radius = (21 + pulseRadius).dp.toPx(),
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isVpnActive) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = stringResource(R.string.shield_indicator_desc),
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isVpnActive) stringResource(R.string.vpn_active_desc) else stringResource(R.string.vpn_inactive_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Masters Switch Button
                Switch(
                    checked = isVpnActive,
                    onCheckedChange = { onToggleMaster() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = StateAllow,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Live active countdown sub-panel
            if (isVpnActive && expirationTimeMs > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh, // Standard progress/sync/refresh style
                            contentDescription = stringResource(R.string.timer_active_prefix),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.timer_active_prefix),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = formattedTime,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Show Interactive Custom Timer options when NOT active
            if (!isVpnActive) {
                Spacer(modifier = Modifier.height(14.dp))
                
                Text(
                    text = stringResource(R.string.timer_selection_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                
                Spacer(modifier = Modifier.height(6.dp))

                // Scrollable timer presets row
                val options = listOf(
                    "ALWAYS" to stringResource(R.string.timer_always_on),
                    "10" to "10m",
                    "20" to "20m",
                    "30" to "30m",
                    "60" to "60m",
                    "CUSTOM" to stringResource(R.string.timer_custom)
                )

                // Render preset options styled beautifully as modern Material 3 chips with dynamic indicators
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(options.size) { index ->
                        val (optValue, optLabel) = options[index]
                        val isSelected = timerOption == optValue
                        
                        val chipBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        val chipText = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        val chipBorderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(chipBg)
                                .border(1.dp, chipBorderColor, RoundedCornerShape(12.dp))
                                .clickable { onTimerOptionChange(optValue) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = optLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = chipText
                            )
                        }
                    }
                }

                // If CUSTOM selected, show user input field to enter custom minutes (with state persistence)
                if (timerOption == "CUSTOM") {
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    var textInput by remember(customMinutes) {
                        mutableStateOf(customMinutes.toString())
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { input ->
                            val cleanInput = input.filter { it.isDigit() }
                            textInput = cleanInput
                            val parsed = cleanInput.toIntOrNull() ?: 1
                            if (parsed > 0) {
                                onCustomMinutesChange(parsed)
                            }
                        },
                        label = { Text(stringResource(R.string.timer_custom_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        )
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BoxStatItem(
                    title = stringResource(R.string.filter_status_title),
                    value = if (isVpnActive) stringResource(R.string.filter_active_val) else stringResource(R.string.filter_disabled_val),
                    color = statusColor
                )
                BoxStatItem(
                    title = stringResource(R.string.blocked_apps_title),
                    value = "$blockedCount",
                    color = if (blockedCount > 0) StateBlock else MaterialTheme.colorScheme.secondary
                )
                BoxStatItem(
                    title = stringResource(R.string.secure_nodes_title),
                    value = "$allowedCount",
                    color = StateAllow
                )
            }
        }
    }
}

@Composable
fun BoxStatItem(
    title: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
    }
}

/**
 * 2. High-Fidelity Battery & Optimization Note Board
 */
@Composable
fun EfficiencyNoticeBox() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.battery_card_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.battery_card_desc),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 3. App Search and Segmented Selector Control
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchBarAndFilter(
    query: String,
    onQueryChange: (String) -> Unit,
    currentFilter: FilterMode,
    onFilterChange: (FilterMode) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Search Input Node
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_placeholder), fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_desc), modifier = Modifier.size(18.dp)) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_desc), modifier = Modifier.size(18.dp))
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // FlowRow of Filter pills
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterMode.values().forEach { mode ->
                val isSelected = currentFilter == mode
                val label = when (mode) {
                    FilterMode.ALL -> stringResource(R.string.filter_all_apps)
                    FilterMode.USER_ONLY -> stringResource(R.string.filter_user_apps)
                    FilterMode.SYSTEM_ONLY -> stringResource(R.string.filter_system)
                    FilterMode.BLOCKED_ONLY -> stringResource(R.string.filter_blocked)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(32.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = 0.4f
                            )
                        )
                        .clickable { onFilterChange(mode) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(
                            alpha = 0.8f
                        )
                    )
                }
            }
        }
    }
}

/**
 * 4. Action Command Bar (Bulk configuration buttons)
 */
@Composable
fun BulkControlBar(
    filteredList: List<AppUiModel>,
    onBlockAll: () -> Unit,
    onUnblockAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val count = filteredList.size
        Text(
            text = stringResource(R.string.apps_match_filter, count),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium
        )

        if (count > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onUnblockAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(stringResource(R.string.allow_all), fontSize = 12.sp, color = StateAllow)
                }

                Button(
                    onClick = onBlockAll,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(stringResource(R.string.block_all), fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 5. Configurable App Card Row Item
 */
@Composable
fun AppFirewallRowCard(
    appModel: AppUiModel,
    loadIcon: (String) -> Drawable?,
    onToggle: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .border(
                width = 1.dp,
                color = if (appModel.isBlocked) StateBlock.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.08f
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Asynchronous Lazy Drawable Load Icon
            AppIconLoader(
                packageName = appModel.packageName,
                loadIcon = loadIcon,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Text Metadata Frame
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = appModel.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (appModel.isSystem) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.system_label),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = appModel.packageName,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action Toggle Icon Visual Switch
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (appModel.isBlocked) StateBlock.copy(alpha = 0.12f) else StateAllow.copy(
                            alpha = 0.12f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (appModel.isBlocked) Icons.Default.Close else Icons.Default.Check,
                    contentDescription = if (appModel.isBlocked) stringResource(R.string.filter_blocked) else stringResource(R.string.allow_all),
                    tint = if (appModel.isBlocked) StateBlock else StateAllow,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Lazy Icon loader to prevent blocking draw calls on UI Thread
 */
@Composable
fun AppIconLoader(
    packageName: String,
    loadIcon: (String) -> Drawable?,
    modifier: Modifier = Modifier
) {
    var iconDrawable by remember(packageName) { mutableStateOf<Drawable?>(null) }
    var loaded by remember(packageName) { mutableStateOf(false) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            iconDrawable = loadIcon(packageName)
            loaded = true
        }
    }

    if (loaded && iconDrawable != null) {
        val imageBitmap = remember(iconDrawable) {
            try {
                val drawable = iconDrawable!!
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bitmap = android.graphics.Bitmap.createBitmap(
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }

        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(R.string.app_icon_desc),
                modifier = modifier.padding(4.dp)
            )
        } else {
            DefaultAppIconIndicator(modifier)
        }
    } else {
        DefaultAppIconIndicator(modifier)
    }
}

@Composable
fun DefaultAppIconIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = stringResource(R.string.launcher_placeholder_desc),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun EmptyAppsState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.no_results_desc),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (query.isEmpty()) stringResource(R.string.no_apps_found) else stringResource(R.string.no_matches_found, query),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
