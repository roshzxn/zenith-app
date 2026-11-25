package com.zenith.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.ui.components.BackupRestoreModal
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.ExportDataModal
import com.zenith.app.ui.components.ImportDataModal
import com.zenith.app.ui.components.PaymentMethodDropdown
import com.zenith.app.ui.components.ZenithLogo
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.theme.ZenithThemeMode
import com.zenith.app.ui.viewmodel.CashFlowUiState
import com.zenith.app.ui.viewmodel.CashFlowViewModel

@Composable
fun ProfileScreen(
    state: CashFlowUiState,
    viewModel: CashFlowViewModel,
    onCurrencySelect: (String) -> Unit,
    onDeleteScannedItem: (com.zenith.app.data.models.ScannedItemEntity) -> Unit,
    onNavigateToActivity: () -> Unit = {}
) {
    val context = LocalContext.current
    val currencies = listOf("₹", "$", "€", "£", "¥")

    // Reactive State from ViewModel
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val isGuestMode by viewModel.isGuestMode.collectAsStateWithLifecycle()
    val activeUserEmail by viewModel.activeUserEmail.collectAsStateWithLifecycle()
    val activeUserName by viewModel.activeUserName.collectAsStateWithLifecycle()
    val defaultPaymentMethod by viewModel.defaultPaymentMethod.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val isOledBlack by viewModel.isOledBlack.collectAsStateWithLifecycle()
    val isHapticsOn by viewModel.isHapticsEnabled.collectAsStateWithLifecycle()
    val isNotificationsOn by viewModel.isNotificationsEnabled.collectAsStateWithLifecycle()
    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()
    val isUpiAutoDetectionEnabled by viewModel.isUpiAutoDetectionEnabled.collectAsStateWithLifecycle()
    val isUpiInstantAutoSaveEnabled by viewModel.isUpiInstantAutoSaveEnabled.collectAsStateWithLifecycle()

    val syncState by viewModel.syncUiState.collectAsStateWithLifecycle()

    var showAuthDialog by remember { mutableStateOf(false) }
    var initialAuthModeIsSignUp by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showUpiSettingsDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showExportModal by remember { mutableStateOf(false) }
    var showImportModal by remember { mutableStateOf(false) }
    var showBackupModal by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isUpiNotifGranted by remember { mutableStateOf(UpiNotificationListenerService.isPermissionGranted(context)) }
    var isUpiListenerBound by remember {
        mutableStateOf(
            isUpiNotifGranted && UpiNotificationListenerService.isListenerActivelyBound(context)
        )
    }
    var isPostNotificationsGranted by remember {
        mutableStateOf(UpiNotificationListenerService.hasPostNotificationPermission(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        isPostNotificationsGranted = granted
        if (!granted) {
            Toast.makeText(context, "Notification permission declined. Enable in Android Settings for alert shade.", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = UpiNotificationListenerService.isPermissionGranted(context)
                isUpiNotifGranted = granted
                if (granted) {
                    UpiNotificationListenerService.ensureListenerBound(context)
                }
                isUpiListenerBound = granted && UpiNotificationListenerService.isListenerActivelyBound(context)
                isPostNotificationsGranted = UpiNotificationListenerService.hasPostNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 144.dp)
    ) {
        // --- 1. USER PROFILE HEADER CARD ---
        item {
            UserProfileGlassHeader(
                userName = activeUserName ?: viewModel.currentUserName,
                userEmail = activeUserEmail,
                isGuest = isGuestMode || !isAuthenticated,
                onEditNameClick = { showEditNameDialog = true },
                onSignInClick = {
                    initialAuthModeIsSignUp = false
                    showAuthDialog = true
                },
                onCreateAccountClick = {
                    initialAuthModeIsSignUp = true
                    showAuthDialog = true
                },
                onSyncClick = {
                    if (isGuestMode || !isAuthenticated) {
                        Toast.makeText(context, "Please sign in or create an account to sync with Zenith Cloud.", Toast.LENGTH_LONG).show()
                    } else {
                        viewModel.syncNow { success ->
                            if (success) {
                                Toast.makeText(context, "Cloud sync completed successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Cloud sync failed. Please check your network connection.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onSignOutClick = {
                    viewModel.signOut()
                    Toast.makeText(context, "Signed out of Zenith account.", Toast.LENGTH_SHORT).show()
                },
                isSyncing = syncState is com.zenith.app.ui.viewmodel.SyncUiState.Syncing
            )
            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 2. FINANCE PREFERENCES SECTION ---
        item {
            ProfileSectionTitle("Finance Preferences")

            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                Text(
                    text = "Display Currency",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Applies across ledgers, budgets & insights",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    currencies.forEach { curr ->
                        val isSelected = state.currencySymbol == curr
                        FilterChip(
                            selected = isSelected,
                            onClick = { onCurrencySelect(curr) },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = curr,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                PaymentMethodDropdown(
                    selectedMethod = defaultPaymentMethod,
                    onMethodSelected = { method ->
                        viewModel.setDefaultPaymentMethod(method)
                        Toast.makeText(context, "Default payment method set to $method", Toast.LENGTH_SHORT).show()
                    },
                    label = "Default Payment Method"
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 3. APP PREFERENCES SECTION (3-Mode Theme + Security + Notifications) ---
        item {
            ProfileSectionTitle("Appearance & Security")

            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                Text(
                    text = "Theme Mode",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Switch cleanly between Crisp Light, Obsidian Navy, or OLED Black",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                val currentMode = ZenithTheme.mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        Triple(ZenithThemeMode.LIGHT, "Light", Icons.Default.LightMode),
                        Triple(ZenithThemeMode.OBSIDIAN_NAVY, "Obsidian", Icons.Default.DarkMode),
                        Triple(ZenithThemeMode.OLED_BLACK, "OLED", Icons.Default.Brightness2)
                    ).forEach { (mode, label, icon) ->
                        val isSelected = currentMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) ZenithTheme.solidIndigo else Color.Transparent)
                                .clickable {
                                    when (mode) {
                                        ZenithThemeMode.LIGHT -> viewModel.setDarkMode(false)
                                        ZenithThemeMode.OBSIDIAN_NAVY -> {
                                            viewModel.setOledBlack(false)
                                            viewModel.setDarkMode(true)
                                        }
                                        ZenithThemeMode.OLED_BLACK -> viewModel.setOledBlack(true)
                                    }
                                }
                                .testTag("theme_mode_${mode.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = if (isSelected) Color.White else ZenithTheme.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else ZenithTheme.textSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceSwitchRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Biometric / PIN App Lock",
                    subtitle = "Require device authentication on launch",
                    checked = isAppLockEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setAppLockEnabled(enabled)
                        Toast.makeText(
                            context,
                            if (enabled) "App Lock enabled for next launch." else "App Lock disabled.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceSwitchRow(
                    icon = Icons.Default.Vibration,
                    title = "Haptic Touch Feedback",
                    subtitle = "Vibrate on voice and quick transaction actions",
                    checked = isHapticsOn,
                    onCheckedChange = { viewModel.setHapticsEnabled(it) }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceSwitchRow(
                    icon = Icons.Default.Notifications,
                    title = "Bills & Budget Alerts",
                    subtitle = if (isPostNotificationsGranted) {
                        "Notify for upcoming bills & spending limits"
                    } else {
                        "Tap to grant notification permission for alerts"
                    },
                    checked = isNotificationsOn && isPostNotificationsGranted,
                    onCheckedChange = { enabled ->
                        if (enabled && !isPostNotificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        viewModel.setNotificationsEnabled(enabled)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                // Automatic UPI Payment Tracking (Notification Access)
                val upiCardAccent = when {
                    !isUpiNotifGranted -> ZenithTheme.accentAmber
                    !isUpiAutoDetectionEnabled -> ZenithTheme.textSecondary
                    !isUpiListenerBound -> ZenithTheme.accentAmber
                    else -> ZenithTheme.accentGreen
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        !isUpiNotifGranted -> ZenithTheme.accentAmber.copy(alpha = 0.12f)
                        !isUpiAutoDetectionEnabled -> ZenithTheme.cardVariantSurface
                        !isUpiListenerBound -> ZenithTheme.accentAmber.copy(alpha = 0.12f)
                        else -> ZenithTheme.accentGreen.copy(alpha = 0.12f)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            !isUpiNotifGranted -> ZenithTheme.accentAmber.copy(alpha = 0.35f)
                            !isUpiAutoDetectionEnabled -> ZenithTheme.borderColor
                            !isUpiListenerBound -> ZenithTheme.accentAmber.copy(alpha = 0.35f)
                            else -> ZenithTheme.accentGreen.copy(alpha = 0.35f)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showUpiSettingsDialog = true }
                        .testTag("row_upi_notification_settings")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(upiCardAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = upiCardAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Auto-Track UPI Payments",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = upiCardAccent.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = when {
                                            !isUpiNotifGranted -> "SETUP NEEDED"
                                            !isUpiAutoDetectionEnabled -> "PAUSED"
                                            !isUpiListenerBound -> "RECONNECT"
                                            else -> "ACTIVE"
                                        },
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = upiCardAccent,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    !isUpiNotifGranted -> "Tap to grant Android Notification Access so Zenith can auto-detect receipts."
                                    !isUpiAutoDetectionEnabled -> "Detection is temporarily paused in settings. Tap to configure."
                                    !isUpiListenerBound -> "Notification Access granted, but background listener is disconnected. Tap to reconnect."
                                    else -> "Active: Intercepts GPay, PhonePe, Paytm, CRED, Bank & SMS alerts on-device."
                                },
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary,
                                lineHeight = 14.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = ZenithTheme.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 4. DATA & PRIVACY SECTION ---
        item {
            ProfileSectionTitle("Data & Privacy")

            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                PreferenceActionRow(
                    icon = Icons.Default.FileDownload,
                    title = "Export Financial Data",
                    subtitle = "Download PDF report, Excel, CSV or JSON",
                    actionLabel = "Export",
                    onClick = { showExportModal = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceActionRow(
                    icon = Icons.Default.FileUpload,
                    title = "Import Transactions",
                    subtitle = "Load CSV, Excel or JSON records into Zenith",
                    actionLabel = "Import",
                    onClick = { showImportModal = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceActionRow(
                    icon = Icons.Default.CloudSync,
                    title = "Backup & Restore",
                    subtitle = "Full JSON snapshot of budgets, goals & history",
                    actionLabel = "Backup",
                    onClick = { showBackupModal = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceActionRow(
                    icon = Icons.Default.DeleteOutline,
                    title = "Clear Local Transactions",
                    subtitle = "Reset device ledger history",
                    actionLabel = "Clear",
                    accentColor = ZenithTheme.accentRed,
                    onClick = { showClearDataDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 5. SUPPORT & ABOUT SECTION ---
        item {
            ProfileSectionTitle("Support & About")

            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                PreferenceActionRow(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = "Help & Voice Guide",
                    subtitle = "How to speak natural voice expense entries",
                    actionLabel = "Guide",
                    onClick = { showHelpDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceActionRow(
                    icon = Icons.Default.Info,
                    title = "About Zenith",
                    subtitle = "v2.0.0 • Your money. Your clarity.",
                    actionLabel = "Info",
                    onClick = { showAboutDialog = true }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 6. ACCOUNT ACTIONS SECTION ---
        item {
            ProfileSectionTitle("Account Actions")

            CleanCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                if (isAuthenticated && !isGuestMode) {
                    PreferenceActionRow(
                        icon = Icons.Default.DeleteForever,
                        title = "Reset Device Account",
                        subtitle = "Clear account session and local ledger records",
                        actionLabel = "Reset",
                        accentColor = ZenithTheme.accentRed,
                        onClick = {
                            viewModel.signOut()
                            viewModel.clearAllLocalData()
                            Toast.makeText(context, "Account data reset.", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    PreferenceActionRow(
                        icon = Icons.Default.AccountCircle,
                        title = "Sign In / Register",
                        subtitle = "Sync your personal wealth ledger across devices",
                        actionLabel = "Sign In",
                        accentColor = ZenithTheme.accentGreen,
                        onClick = {
                            initialAuthModeIsSignUp = false
                            showAuthDialog = true
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // --- MODALS & DIALOGS ---

    if (showEditNameDialog) {
        EditNameDialog(
            currentName = activeUserName ?: viewModel.currentUserName,
            onDismiss = { showEditNameDialog = false },
            onSave = { newName ->
                viewModel.updateUserName(newName)
                showEditNameDialog = false
                Toast.makeText(context, "Name updated to $newName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showUpiSettingsDialog) {
        UpiNotificationSettingsDialog(
            isPermissionGranted = isUpiNotifGranted,
            isListenerBound = isUpiListenerBound,
            isAutoDetectEnabled = isUpiAutoDetectionEnabled,
            isInstantAutoSaveEnabled = isUpiInstantAutoSaveEnabled,
            isPostNotificationsGranted = isPostNotificationsGranted,
            onRequestPostNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onToggleAutoDetect = { enabled ->
                viewModel.setUpiAutoDetectionEnabled(enabled)
                if (enabled && isUpiNotifGranted) {
                    isUpiListenerBound = UpiNotificationListenerService.isListenerActivelyBound(context)
                }
                Toast.makeText(context, if (enabled) "UPI auto-detection enabled." else "UPI auto-detection paused.", Toast.LENGTH_SHORT).show()
            },
            onToggleInstantAutoSave = { enabled ->
                viewModel.setUpiInstantAutoSaveEnabled(enabled)
                Toast.makeText(
                    context,
                    if (enabled) "Hands-free auto-save enabled." else "Switched back to 1-tap confirmation mode.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onOpenSystemSettings = {
                UpiNotificationListenerService.openPermissionSettings(context)
            },
            onOpenAppInfoSettings = {
                UpiNotificationListenerService.openAppInfoSettings(context)
            },
            onReconnectListener = {
                val requested = UpiNotificationListenerService.ensureListenerBound(context, forceRebind = true)
                isUpiListenerBound = UpiNotificationListenerService.isListenerActivelyBound(context)
                Toast.makeText(
                    context,
                    if (requested) "Requested background listener reconnect." else "Grant Notification Access in Settings first.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onSimulateTestPayment = {
                viewModel.simulateTestUpiPayment()
                Toast.makeText(context, "Simulated payment notification posted! Check modal prompt.", Toast.LENGTH_SHORT).show()
            },
            onTestShadeNotification = {
                val posted = viewModel.simulateTestShadeNotification()
                if (!posted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (posted) {
                    Toast.makeText(context, "Posted test alert to your notification shade.", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showUpiSettingsDialog = false }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("Reset Local Database?", color = ZenithTheme.textPrimary) },
            text = {
                Text(
                    "This will permanently wipe all local transactions, budgets, goals, and auto-pay reminders, and re-initialize clean default categories.",
                    color = ZenithTheme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllLocalData()
                        showClearDataDialog = false
                        Toast.makeText(context, "Local database reset successfully.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Reset Database", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("Cancel", color = ZenithTheme.textSecondary)
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showAboutDialog) {
        Dialog(onDismissRequest = { showAboutDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = ZenithTheme.cardSurface,
                border = BorderStroke(1.dp, ZenithTheme.borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ZenithLogo(size = 48.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("ZENITH", fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = ZenithTheme.textPrimary)
                    Text("Your money. Your clarity.", fontSize = 12.sp, color = ZenithTheme.accentAmber, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Zenith is a high-performance offline-first personal finance manager powered by on-device OCR, Gemini voice intelligence, UPI automation, and encrypted cloud sync.",
                        fontSize = 13.sp,
                        color = ZenithTheme.textSecondary,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { showAboutDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                    ) {
                        Text("Close", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = ZenithTheme.cardSurface,
                border = BorderStroke(1.dp, ZenithTheme.borderColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Voice Entry Guide", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                    Text("Speak your expense or income naturally", fontSize = 12.sp, color = ZenithTheme.textSecondary)
                    Spacer(modifier = Modifier.height(14.dp))

                    listOf(
                        "• \"Spent 150 on lunch\"" to "Records ₹150 in Food & Dining",
                        "• \"Paid 500 for petrol via UPI\"" to "Records ₹500 in Transportation",
                        "• \"Received salary 50000 in bank\"" to "Records ₹50,000 Income",
                        "• \"Got 200 cashback on Google Pay\"" to "Records ₹200 Income"
                    ).forEach { (cmd, desc) ->
                        Text(cmd, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.accentAmber)
                        Text(desc, fontSize = 11.sp, color = ZenithTheme.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { showHelpDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                    ) {
                        Text("Got it", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    if (showAuthDialog) {
        AuthDialog(
            isSignUpInitial = initialAuthModeIsSignUp,
            onDismiss = { showAuthDialog = false },
            onSignIn = { email, pass ->
                viewModel.signIn(email, pass) { result ->
                    if (result.success) {
                        showAuthDialog = false
                        Toast.makeText(context, "Welcome back to Zenith!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, result.message ?: "Sign in failed. Please verify credentials.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSignUp = { email, pass, name ->
                viewModel.signUp(email, pass, name) { result ->
                    if (result.success) {
                        showAuthDialog = false
                        Toast.makeText(
                            context,
                            result.message ?: "Zenith account created successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(context, result.message ?: "Sign up failed. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (showExportModal) {
        ExportDataModal(
            transactions = state.allTransactions,
            currencySymbol = state.currencySymbol,
            onDismiss = { showExportModal = false }
        )
    }

    if (showImportModal) {
        ImportDataModal(
            existingTransactions = state.allTransactions,
            currencySymbol = state.currencySymbol,
            onDismiss = { showImportModal = false },
            onImportConfirmed = { transactionsToImport ->
                viewModel.importTransactions(transactionsToImport)
            },
            onNavigateToActivity = onNavigateToActivity
        )
    }

    if (showBackupModal) {
        BackupRestoreModal(
            transactions = state.allTransactions,
            categories = state.categories,
            budgets = state.budgets,
            savingsGoals = state.savingsGoals,
            onDismiss = { showBackupModal = false },
            onRestoreBackup = { restoredTx, restoredBudgets, restoredGoals ->
                viewModel.restoreFullBackup(restoredTx, restoredBudgets, restoredGoals)
            }
        )
    }
}

@Composable
fun ProfileSectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = ZenithTheme.textSecondary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun UserProfileGlassHeader(
    userName: String,
    userEmail: String?,
    isGuest: Boolean,
    onEditNameClick: () -> Unit,
    onSignInClick: () -> Unit,
    onCreateAccountClick: () -> Unit,
    onSyncClick: () -> Unit,
    onSignOutClick: () -> Unit,
    isSyncing: Boolean
) {
    val initial = userName.trim().firstOrNull()?.uppercase() ?: "Z"

    CleanCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_header_card"),
        backgroundColor = ZenithTheme.cardGlassBackground,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            if (!isGuest) listOf(ZenithTheme.accentGreen, ZenithTheme.accentCyan)
                            else listOf(ZenithTheme.cardVariantSurface, ZenithTheme.borderColor)
                        )
                    )
                    .border(
                        1.5.dp,
                        if (!isGuest) ZenithTheme.accentIndigo.copy(alpha = 0.6f) else ZenithTheme.borderColor,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!isGuest) {
                    Text(
                        text = initial,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = ZenithTheme.accentIndigo,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (!isGuest) userName else "Offline Vault (Private)",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ZenithTheme.textPrimary
                    )

                    if (!isGuest) {
                        IconButton(
                            onClick = onEditNameClick,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Name",
                                tint = ZenithTheme.accentIndigo,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Text(
                    text = if (!isGuest) (userEmail ?: "Cloud Account") else "Private Vault • 100% Stored On Device",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = if (!isGuest) ZenithTheme.accentGreen else ZenithTheme.accentCyan
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = if (!isGuest) "● Cloud Synced" else "● Local Offline",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = ZenithTheme.borderColor)
        Spacer(modifier = Modifier.height(12.dp))

        if (!isGuest) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onSyncClick,
                    enabled = !isSyncing,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Syncing...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                OutlinedButton(
                    onClick = onSignOutClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                }

                OutlinedButton(
                    onClick = onCreateAccountClick,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(40.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Create Account", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun EditNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Edit Display Name", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                Text("Personalize your greeting in Zenith", fontSize = 12.sp, color = ZenithTheme.textSecondary, modifier = Modifier.padding(top = 4.dp))

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun PreferenceSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ZenithTheme.accentGreen,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ZenithTheme.solidGreen,
                uncheckedThumbColor = ZenithTheme.textSecondary,
                uncheckedTrackColor = ZenithTheme.cardVariantSurface
            )
        )
    }
}

@Composable
fun PreferenceActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    accentColor: Color = ZenithTheme.textPrimary
) {
    val defaultTextColor = ZenithTheme.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accentColor != defaultTextColor) accentColor else ZenithTheme.accentGreen,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = actionLabel,
            tint = ZenithTheme.textMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun AuthDialog(
    isSignUpInitial: Boolean,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit
) {
    var isSignUp by remember { mutableStateOf(isSignUpInitial) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZenithLogo(size = 40.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isSignUp) "Create Zenith Account" else "Sign In to Zenith",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = if (isSignUp) "Encrypted cloud backup & device sync" else "Access your synced personal wealth vault",
                    fontSize = 12.sp,
                    color = ZenithTheme.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                if (isSignUp) {
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text("Full Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (isSignUp) {
                            if (email.isNotBlank() && password.isNotBlank()) {
                                onSignUp(email.trim(), password.trim(), fullName.trim().ifBlank { email.substringBefore("@") })
                            }
                        } else {
                            if (email.isNotBlank() && password.isNotBlank()) {
                                onSignIn(email.trim(), password.trim())
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                ) {
                    Text(if (isSignUp) "Create Account" else "Sign In", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = { isSignUp = !isSignUp }) {
                    Text(
                        text = if (isSignUp) "Already have an account? Sign In" else "Don't have an account? Create one",
                        fontSize = 12.sp,
                        color = ZenithTheme.accentAmber
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpiNotificationSettingsDialog(
    isPermissionGranted: Boolean,
    isListenerBound: Boolean = isPermissionGranted,
    isAutoDetectEnabled: Boolean,
    isInstantAutoSaveEnabled: Boolean = false,
    isPostNotificationsGranted: Boolean = true,
    onRequestPostNotifications: () -> Unit = {},
    onToggleAutoDetect: (Boolean) -> Unit,
    onToggleInstantAutoSave: (Boolean) -> Unit = {},
    onOpenSystemSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit = {},
    onReconnectListener: () -> Unit = {},
    onSimulateTestPayment: () -> Unit,
    onTestShadeNotification: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val headerColor = when {
                            !isPermissionGranted || (isAutoDetectEnabled && !isListenerBound) -> ZenithTheme.accentAmber
                            !isAutoDetectEnabled -> ZenithTheme.textSecondary
                            else -> ZenithTheme.accentGreen
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(headerColor.copy(alpha = 0.16f))
                                .border(1.dp, headerColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = headerColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Auto-Track UPI Payments",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "Instant zero-click expense logging",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val statusAccent = when {
                    !isPermissionGranted -> ZenithTheme.accentAmber
                    !isAutoDetectEnabled -> ZenithTheme.textSecondary
                    !isListenerBound -> ZenithTheme.accentAmber
                    else -> ZenithTheme.accentGreen
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = statusAccent.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, statusAccent.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isPermissionGranted && isAutoDetectEnabled && isListenerBound) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = statusAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    !isPermissionGranted -> "Notification Listener Required"
                                    !isAutoDetectEnabled -> "Detection Paused in App"
                                    !isListenerBound -> "Listener Disconnected by System"
                                    else -> "Service Active & Listening"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = when {
                                !isPermissionGranted -> "Enable Notification Access for Zenith. On Android 13+ sideloaded APKs, first open App Info -> ⋮ menu -> Allow restricted settings."
                                !isAutoDetectEnabled -> "Notification listener is granted in system, but paused by your in-app preference."
                                !isListenerBound -> "Permission is granted, but Android unbound the background listener (common after APK install or battery optimization). Tap Reconnect below."
                                else -> "Zenith is actively monitoring UPI, banking, and transaction SMS notifications 100% on-device."
                            },
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary,
                            lineHeight = 15.sp
                        )

                        if (!isPermissionGranted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onOpenSystemSettings,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.accentAmber),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Grant Listener in Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onOpenAppInfoSettings,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) {
                                    Text("Open App Info (Restricted Settings)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textPrimary)
                                }
                            }
                        } else if (isAutoDetectEnabled && !isListenerBound) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onReconnectListener,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.accentAmber),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reconnect Background Listener", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onOpenAppInfoSettings,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                            ) {
                                Text("Check Battery / Unrestricted Usage", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textPrimary)
                            }
                        }

                        if (isPermissionGranted && !isPostNotificationsGranted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onRequestPostNotifications,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidIndigo),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Allow Shade Confirmations", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                CleanCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = ZenithTheme.cardVariantSurface
                ) {
                    PreferenceSwitchRow(
                        icon = Icons.Default.PowerSettingsNew,
                        title = "Enable Auto-Detection",
                        subtitle = "Toggle payment interceptor inside Zenith",
                        checked = isAutoDetectEnabled,
                        onCheckedChange = onToggleAutoDetect
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = ZenithTheme.borderColor)
                    Spacer(modifier = Modifier.height(10.dp))
                    PreferenceSwitchRow(
                        icon = Icons.Default.Bolt,
                        title = "Hands-Free Auto-Save",
                        subtitle = "Log detected payments silently without 1-tap prompt",
                        checked = isInstantAutoSaveEnabled,
                        onCheckedChange = onToggleInstantAutoSave
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Supported Payment Apps",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))

                val supportedApps = listOf(
                    "Google Pay", "PhonePe", "Paytm", "CRED", "BHIM",
                    "Amazon Pay", "PayZapp", "SBI YONO", "iMobile Pay", "Axis Mobile",
                    "Kotak811", "Bank SMS"
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    supportedApps.forEach { appName ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, ZenithTheme.borderColor)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(ZenithTheme.accentGreen)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(appName, fontSize = 11.sp, color = ZenithTheme.textSecondary)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSimulateTestPayment,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Test Modal", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = onTestShadeNotification,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Test Shade", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(0.9f)
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
