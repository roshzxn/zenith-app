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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.app.BuildConfig
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.upi.UpiService
import com.zenith.app.data.util.NumberFormatStyle
import com.zenith.app.ui.components.BackupRestoreModal
import com.zenith.app.ui.components.CleanCard
import com.zenith.app.ui.components.ExportDataModal
import com.zenith.app.ui.components.ImportDataModal
import com.zenith.app.ui.components.PaymentMethodDropdown
import com.zenith.app.ui.components.ZenithLogo
import com.zenith.app.ui.theme.ZenithAccentPreset
import com.zenith.app.ui.theme.ZenithCardDensity
import com.zenith.app.ui.theme.ZenithMotionSpec
import com.zenith.app.ui.theme.ZenithTheme
import com.zenith.app.ui.theme.ZenithThemeMode
import com.zenith.app.ui.viewmodel.CashFlowUiState
import com.zenith.app.ui.viewmodel.CashFlowViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: CashFlowUiState,
    viewModel: CashFlowViewModel,
    onCurrencySelect: (String) -> Unit,
    onDeleteScannedItem: (com.zenith.app.data.models.ScannedItemEntity) -> Unit,
    onNavigateToActivity: () -> Unit = {},
    onOpenCreateFamily: () -> Unit = {},
    onOpenJoinFamily: () -> Unit = {},
    onOpenScanFamilyQr: () -> Unit = {},
    onOpenFamilyManage: () -> Unit = {},
    onOpenFamilyChat: () -> Unit = {},
    onOpenFamilyInviteQr: () -> Unit = {},
    bottomContentPadding: Dp = 144.dp
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
    val accentPreset by viewModel.accentPreset.collectAsStateWithLifecycle()
    val motionSpec by viewModel.motionSpec.collectAsStateWithLifecycle()
    val cardDensity by viewModel.cardDensity.collectAsStateWithLifecycle()
    val numberFormatStyle by viewModel.numberFormatStyle.collectAsStateWithLifecycle()
    val defaultStartTab by viewModel.defaultStartTab.collectAsStateWithLifecycle()
    val maskBalancesOnLaunch by viewModel.maskBalancesOnLaunch.collectAsStateWithLifecycle()
    val recentsPrivacyEnabled by viewModel.recentsPrivacyEnabled.collectAsStateWithLifecycle()
    val budgetAlertThresholdPct by viewModel.budgetAlertThresholdPct.collectAsStateWithLifecycle()
    val defaultUpiAppPackage by viewModel.defaultUpiAppPackage.collectAsStateWithLifecycle()

    val syncState by viewModel.syncUiState.collectAsStateWithLifecycle()
    val financialContext by viewModel.financialContext.collectAsStateWithLifecycle()

    var showAuthDialog by remember { mutableStateOf(false) }
    var initialAuthModeIsSignUp by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showUpiSettingsDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
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
    var installedUpiApps by remember { mutableStateOf(UpiService.installedUpiApps(context)) }

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
                installedUpiApps = UpiService.installedUpiApps(context)
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
        contentPadding = PaddingValues(top = 16.dp, bottom = bottomContentPadding)
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

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Number Grouping",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Format totals in Indian Lakhs/Crores or International thousands",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        NumberFormatStyle.INDIAN to "Indian • 1,00,000",
                        NumberFormatStyle.INTERNATIONAL to "Global • 100,000"
                    ).forEach { (style, shortLabel) ->
                        val isSelected = numberFormatStyle == style
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) ZenithTheme.solidIndigo else Color.Transparent)
                                .clickable { viewModel.setNumberFormatStyle(style) }
                                .testTag("number_format_${style.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = shortLabel,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else ZenithTheme.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Default Start Screen",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Choose which tab opens first when you launch Zenith",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        0 to "Home",
                        1 to "Activity",
                        2 to "Budgets",
                        3 to "Analytics"
                    ).forEach { (tabIdx, tabLabel) ->
                        val isSelected = defaultStartTab == tabIdx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ZenithTheme.solidIndigo else Color.Transparent)
                                .clickable { viewModel.setDefaultStartTab(tabIdx) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabLabel,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else ZenithTheme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Budget Alert Threshold",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Trigger spending warnings at $budgetAlertThresholdPct% of your budget limit",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(75, 80, 85, 90).forEach { pct ->
                        val isSelected = budgetAlertThresholdPct == pct
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setBudgetAlertThresholdPct(pct) },
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$pct%",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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

                Text(
                    text = "Preferred UPI App",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Used by quick Scan & Pay and Bill Pay actions",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = defaultUpiAppPackage == null,
                        onClick = { viewModel.setDefaultUpiAppPackage(null) },
                        label = {
                            Text(
                                text = "System Chooser",
                                fontSize = 11.5.sp,
                                fontWeight = if (defaultUpiAppPackage == null) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                    installedUpiApps.forEach { app ->
                        val isSelected = defaultUpiAppPackage == app.packageName
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.setDefaultUpiAppPackage(app.packageName) },
                            label = {
                                Text(
                                    text = app.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 2B. FAMILY TRACKER SECTION ---
        item {
            ProfileSectionTitle("Family Tracker")

            CleanCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_settings_family_tracker"),
                backgroundColor = ZenithTheme.cardGlassBackground
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Enable Family",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = if (financialContext.isFamilyEnabled) {
                                "Family mode switcher & shared ledger tools enabled"
                            } else {
                                "Hidden from dashboard • Personal finances untouched"
                            },
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary
                        )
                    }
                    Switch(
                        checked = financialContext.isFamilyEnabled,
                        onCheckedChange = { enabled -> viewModel.setFamilyFeatureEnabled(enabled) },
                        modifier = Modifier.testTag("switch_enable_family")
                    )
                }

                if (financialContext.isFamilyEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = ZenithTheme.borderColor)
                    Spacer(modifier = Modifier.height(12.dp))

                    val isCloudSignedIn = isAuthenticated && !isGuestMode
                    when {
                        !isCloudSignedIn -> {
                            Text(
                                text = "Sign in to your Zenith account above to create or join a family.",
                                fontSize = 12.sp,
                                color = ZenithTheme.accentAmber
                            )
                        }
                        financialContext.familyMembershipStatus == com.zenith.app.domain.FamilyMembershipStatus.LOADING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ZenithTheme.accentCyan
                                )
                                Text(
                                    text = "Checking family membership...",
                                    fontSize = 12.sp,
                                    color = ZenithTheme.textSecondary
                                )
                            }
                        }
                        financialContext.familyMembershipStatus == com.zenith.app.domain.FamilyMembershipStatus.ACTIVE &&
                            financialContext.activeFamily != null -> {
                            val activeFamily = financialContext.activeFamily!!
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(ZenithTheme.accentCyan.copy(alpha = 0.16f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(activeFamily.avatarEmoji, fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = activeFamily.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.textPrimary
                                        )
                                        Text(
                                            text = "${financialContext.membershipRole?.name ?: "MEMBER"} • ${financialContext.activeMembers.size} member(s)",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary
                                        )
                                    }
                                }
                                FilterChip(
                                    selected = financialContext.mode == com.zenith.app.domain.FinancialMode.FAMILY,
                                    onClick = {
                                        val nextMode = if (financialContext.mode == com.zenith.app.domain.FinancialMode.FAMILY) {
                                            com.zenith.app.domain.FinancialMode.PERSONAL
                                        } else {
                                            com.zenith.app.domain.FinancialMode.FAMILY
                                        }
                                        viewModel.selectFinancialMode(nextMode)
                                    },
                                    label = {
                                        Text(
                                            text = if (financialContext.mode == com.zenith.app.domain.FinancialMode.FAMILY) "In Family" else "Open Family",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenFamilyManage,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("btn_profile_manage_family")
                                ) {
                                    Text("Manage", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                if (financialContext.membershipRole?.canAdmin() == true) {
                                    OutlinedButton(
                                        onClick = onOpenFamilyInviteQr,
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(38.dp)
                                            .testTag("btn_profile_family_invite_qr")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QrCode2,
                                            contentDescription = "Invite QR",
                                            tint = ZenithTheme.accentCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Invite QR", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                                Button(
                                    onClick = onOpenFamilyChat,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidIndigo),
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("btn_profile_family_chat")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forum,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Chat", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }
                            }
                        }
                        else -> {
                            financialContext.membershipError?.let { err ->
                                Text(
                                    text = err,
                                    fontSize = 11.5.sp,
                                    color = ZenithTheme.accentRed,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            Text(
                                text = "You don't belong to a family yet. Create a new family, scan a QR code, or enter an invite code.",
                                fontSize = 12.sp,
                                color = ZenithTheme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onOpenCreateFamily,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("btn_profile_create_family")
                                ) {
                                    Text("Create", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onOpenJoinFamily,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("btn_profile_join_family")
                                ) {
                                    Text("Join Code", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = onOpenScanFamilyQr,
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .testTag("btn_profile_scan_family_qr")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = "Scan Family QR",
                                        tint = ZenithTheme.accentCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Join QR", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
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
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Accent Palette",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Personalize buttons, navigation pills & chat highlights",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZenithAccentPreset.entries.forEach { preset ->
                        val isSelected = accentPreset == preset
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) preset.solidFill.copy(alpha = 0.22f) else ZenithTheme.cardVariantSurface,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) preset.swatchColor else ZenithTheme.borderColor
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setAccentPreset(preset) }
                                .testTag("accent_preset_${preset.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preset.swatchColor)
                                )
                                Text(
                                    text = preset.displayName,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) ZenithTheme.textPrimary else ZenithTheme.textSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Animation & Motion",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Tune springs across tab transitions, lists & odometer counters",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ZenithMotionSpec.entries.forEach { spec ->
                        val isSelected = motionSpec == spec
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ZenithTheme.solidIndigo else Color.Transparent)
                                .clickable { viewModel.setMotionSpec(spec) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = spec.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else ZenithTheme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Card Density",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = "Switch between roomy Cozy rows or information-dense Compact rows",
                    fontSize = 11.sp,
                    color = ZenithTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ZenithCardDensity.entries.forEach { density ->
                        val isSelected = cardDensity == density
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ZenithTheme.solidIndigo else Color.Transparent)
                                .clickable { viewModel.setCardDensity(density) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = density.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else ZenithTheme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceSwitchRow(
                    icon = Icons.Default.VisibilityOff,
                    title = "Mask Balances on Launch",
                    subtitle = "Hide financial totals until you tap the eye toggle",
                    checked = maskBalancesOnLaunch,
                    onCheckedChange = { enabled -> viewModel.setMaskBalancesOnLaunch(enabled) }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceSwitchRow(
                    icon = Icons.Default.Security,
                    title = "Hide Screen in Recents",
                    subtitle = "Blocks screenshots & blurs previews in the app switcher",
                    checked = recentsPrivacyEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setRecentsPrivacyEnabled(enabled)
                        Toast.makeText(
                            context,
                            if (enabled) "Screen privacy protection enabled." else "Screen privacy protection disabled.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
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
                    icon = Icons.Default.PrivacyTip,
                    title = "Privacy Policy & Data Safety",
                    subtitle = "How on-device UPI scanning & cloud sync handle data",
                    actionLabel = "View",
                    onClick = { showPrivacyDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = ZenithTheme.borderColor)
                Spacer(modifier = Modifier.height(10.dp))

                PreferenceActionRow(
                    icon = Icons.Default.DeleteOutline,
                    title = if (financialContext.isFamilyModeActive) "Clear Family Transactions" else "Clear Transactions & Ledger",
                    subtitle = if (financialContext.isFamilyModeActive) {
                        if (financialContext.membershipRole?.canAdmin() == true) {
                            "Deletes family transactions in device & Supabase database"
                        } else {
                            "Deletes your own family transactions in device & cloud"
                        }
                    } else {
                        "Clears transactions & ledger on device and cloud (no regain)"
                    },
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
                    subtitle = "v${BuildConfig.VERSION_NAME} • Crafted by Arun Roshan (RoshZen)",
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
                        icon = Icons.AutoMirrored.Filled.Logout,
                        title = "Reset Device Account",
                        subtitle = "Sign out and clear cached records on this device",
                        actionLabel = "Reset",
                        accentColor = ZenithTheme.accentAmber,
                        onClick = {
                            viewModel.signOut()
                            Toast.makeText(context, "Signed out and local device cache cleared.", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = ZenithTheme.borderColor)
                    Spacer(modifier = Modifier.height(10.dp))

                    PreferenceActionRow(
                        icon = Icons.Default.DeleteForever,
                        title = "Delete Account & Cloud Data",
                        subtitle = "Permanently remove your Zenith account & synced records",
                        actionLabel = "Delete",
                        accentColor = ZenithTheme.accentRed,
                        onClick = { showDeleteAccountDialog = true }
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
        val inFamily = financialContext.isFamilyModeActive
        val isFamAdmin = financialContext.membershipRole?.canAdmin() == true
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = {
                Text(
                    if (inFamily) "Clear Family Transactions?" else "Clear All Ledger Data?",
                    color = ZenithTheme.textPrimary
                )
            },
            text = {
                Text(
                    if (inFamily) {
                        if (isFamAdmin) {
                            "This will delete shared family transactions from both this device and the Supabase family database so they stay cleared."
                        } else {
                            "This will delete the family transactions you entered from both this device and Supabase (other members' entries stay untouched)."
                        }
                    } else {
                        "This will delete all personal transactions, budgets, goals, and auto-pay reminders from both this device and your Supabase cloud database so they won't reappear on sync."
                    },
                    color = ZenithTheme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllLocalData(alsoClearCloud = true)
                        showClearDataDialog = false
                        Toast.makeText(
                            context,
                            if (inFamily) "Family transactions cleared." else "Ledger cleared across device & cloud.",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text(if (inFamily) "Clear Family" else "Clear Everywhere", color = Color.White)
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
        fun openExternalLink(url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(context, "Unable to open link on this device.", Toast.LENGTH_SHORT).show()
            }
        }
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
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ZenithLogo(size = 50.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "ZENITH",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = ZenithTheme.textPrimary
                    )
                    Text(
                        "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE}) • Your money. Your clarity.",
                        fontSize = 11.5.sp,
                        color = ZenithTheme.accentAmber,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Zenith is an offline-first personal & family finance manager with on-device OCR receipt scanning, Gemini voice capture, auto UPI tracking, and encrypted Supabase sync.",
                        fontSize = 12.5.sp,
                        color = ZenithTheme.textSecondary,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = ZenithTheme.cardVariantSurface,
                        border = BorderStroke(1.dp, ZenithTheme.borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CRAFTED BY ROSHZEN",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp,
                                color = ZenithTheme.accentIndigo
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Arun Roshan",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "Software Engineer & UI/UX Developer",
                                fontSize = 11.5.sp,
                                color = ZenithTheme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "roshzen.in" to "https://roshzen.in",
                                    "GitHub" to "https://github.com/roshzxn",
                                    "LinkedIn" to "https://linkedin.com/in/arun-roshan",
                                    "Instagram" to "https://instagram.com/roshzxn",
                                    "Email" to "mailto:arunroshan1003@gmail.com"
                                ).forEach { (label, url) ->
                                    AssistChip(
                                        onClick = { openExternalLink(url) },
                                        label = {
                                            Text(
                                                text = label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = ZenithTheme.textPrimary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (label == "Email") Icons.Default.Email else Icons.AutoMirrored.Filled.OpenInNew,
                                                contentDescription = label,
                                                tint = ZenithTheme.accentIndigo,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
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

    if (showPrivacyDialog) {
        Dialog(onDismissRequest = { showPrivacyDialog = false }) {
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
                        .verticalScroll(rememberScrollState())
                        .padding(22.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = ZenithTheme.accentGreen)
                        Text(
                            "Privacy & Data Safety",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    listOf(
                        "On-Device Financial Storage" to "Your ledger records live in a local Room SQLite database on your device so Zenith works 100% offline.",
                        "Notification Listener Disclosure" to "When enabled, Zenith inspects incoming UPI & bank payment notifications on-device to extract merchant, amount, and account mode. Personal chats, OTPs, and unrelated alerts are ignored and never uploaded or sold.",
                        "Receipt OCR & Voice Parsing" to "Receipt text extraction uses ML Kit on-device. Voice text or image hints sent to Gemini API (when triggered) are used strictly to structure your transaction.",
                        "Cloud Sync & Account Deletion" to "When signed in, ledgers sync via HTTPS/TLS to Supabase with Row-Level Security. You can export your data anytime or permanently erase your account from Account Actions."
                    ).forEach { (heading, body) ->
                        Text(heading, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(body, fontSize = 11.5.sp, color = ZenithTheme.textSecondary, lineHeight = 16.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Button(
                        onClick = { showPrivacyDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidIndigo)
                    ) {
                        Text("Understood", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            title = { Text("Delete Account Permanently?", color = ZenithTheme.textPrimary) },
            text = {
                Text(
                    "This permanently deletes your Zenith user account and synced personal cloud data on Supabase as well as local records on this device. This action cannot be undone.",
                    color = ZenithTheme.textSecondary,
                    fontSize = 12.5.sp
                )
            },
            confirmButton = {
                Button(
                    enabled = !isDeletingAccount,
                    onClick = {
                        isDeletingAccount = true
                        viewModel.deleteAccount { result ->
                            isDeletingAccount = false
                            if (result.success) {
                                showDeleteAccountDialog = false
                                Toast.makeText(context, "Account permanently deleted.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    result.message ?: "Unable to delete account right now.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    if (isDeletingAccount) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("Delete Forever", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeletingAccount,
                    onClick = { showDeleteAccountDialog = false }
                ) {
                    Text("Cancel", color = ZenithTheme.textSecondary)
                }
            },
            containerColor = ZenithTheme.cardSurface,
            shape = RoundedCornerShape(20.dp)
        )
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
            onDismiss = { showExportModal = false },
            isFamilyMode = state.activeMode == com.zenith.app.domain.FinancialMode.FAMILY,
            workspaceName = state.activeFamily?.name ?: "Personal",
            txAttributionByServerId = state.txAttributionByServerId
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

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ZenithTheme.cardVariantSurface,
                    border = BorderStroke(1.dp, ZenithTheme.borderColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Data Disclosure: Zenith reads UPI and banking transaction notifications strictly on-device to prefill amount, merchant, and mode. Personal chats, OTPs, and non-financial alerts are ignored and never uploaded.",
                        fontSize = 10.5.sp,
                        color = ZenithTheme.textSecondary,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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
