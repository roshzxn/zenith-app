package com.zenith.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenith.app.data.models.TransactionType
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.ui.components.AddTransactionDialog
import com.zenith.app.ui.components.ReceiptScanModal
import com.zenith.app.ui.components.UPIPaySheet
import com.zenith.app.ui.components.UpiPaymentDetectedModal
import com.zenith.app.ui.components.UpiQrScanModal
import com.zenith.app.ui.components.VoiceAiModal
import com.zenith.app.ui.components.ZenithFloatingNavigationBar
import com.zenith.app.ui.components.ZenithLogo
import com.zenith.app.ui.screens.*
import com.zenith.app.ui.theme.*
import com.zenith.app.ui.viewmodel.CashFlowViewModel
import kotlinx.coroutines.launch

enum class ZenithNavScreen {
    SPLASH,
    AUTH,
    MAIN
}

class MainActivity : ComponentActivity() {

    private val viewModel: CashFlowViewModel by viewModels()
    private var pendingQuickAction by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpiNotificationListenerService.ensureListenerBound(this)
        checkNotificationLaunch(intent)

        setContent {
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val isOledBlack by viewModel.isOledBlack.collectAsStateWithLifecycle()
            val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsStateWithLifecycle()

            // Dynamically adapt system status/navigation bars across Light, Obsidian, and OLED modes
            DisposableEffect(isDarkMode, isOledBlack) {
                val darkSystemBars = isDarkMode || isOledBlack
                enableEdgeToEdge(
                    statusBarStyle = if (darkSystemBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (darkSystemBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
                onDispose {}
            }

            CashFlowTheme(isDarkMode = isDarkMode, isOledBlack = isOledBlack) {
                val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var currentScreen by remember(isAuthenticated) {
                    mutableStateOf(if (isAuthenticated) ZenithNavScreen.MAIN else ZenithNavScreen.SPLASH)
                }
                var initialAuthIsSignUp by remember { mutableStateOf(false) }

                // App lock state (enforced when user enables App Lock in Profile and has device lock configured)
                var isAppUnlocked by remember(isAppLockEnabled) { mutableStateOf(!isAppLockEnabled) }

                val context = LocalContext.current

                // Request Android 13+ notification permission gracefully on entering main experience
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* User choice respected */ }

                LaunchedEffect(currentScreen) {
                    if (currentScreen == ZenithNavScreen.MAIN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val prefs = context.getSharedPreferences("zenith_app_prefs", Context.MODE_PRIVATE)
                        val alreadyPrompted = prefs.getBoolean("notification_prompted", false)
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted && !alreadyPrompted) {
                            prefs.edit().putBoolean("notification_prompted", true).apply()
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Safe Back Navigation Handler
                BackHandler(enabled = true) {
                    when (currentScreen) {
                        ZenithNavScreen.AUTH -> currentScreen = ZenithNavScreen.SPLASH
                        ZenithNavScreen.MAIN -> {
                            if (uiState.selectedTab != 0) {
                                viewModel.setTab(0)
                            } else {
                                finish()
                            }
                        }
                        ZenithNavScreen.SPLASH -> finish()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(300)) + slideInVertically(animationSpec = tween(300)) { it / 6 })
                                .togetherWith(fadeOut(animationSpec = tween(250)))
                        },
                        label = "zenith_root_screen_transition"
                    ) { screen ->
                        when (screen) {
                            ZenithNavScreen.SPLASH -> {
                                SplashScreen(
                                    onNavigateToLogin = {
                                        initialAuthIsSignUp = false
                                        currentScreen = ZenithNavScreen.AUTH
                                    },
                                    onNavigateToRegister = {
                                        initialAuthIsSignUp = true
                                        currentScreen = ZenithNavScreen.AUTH
                                    },
                                    onContinueAsGuest = {
                                        viewModel.continueAsGuest()
                                        currentScreen = ZenithNavScreen.MAIN
                                    }
                                )
                            }
                            ZenithNavScreen.AUTH -> {
                                AuthScreen(
                                    viewModel = viewModel,
                                    initialIsSignUp = initialAuthIsSignUp,
                                    onAuthSuccess = {
                                        isAppUnlocked = true
                                        currentScreen = ZenithNavScreen.MAIN
                                    },
                                    onBackToSplash = {
                                        currentScreen = ZenithNavScreen.SPLASH
                                    },
                                    onContinueAsGuest = {
                                        viewModel.continueAsGuest()
                                        isAppUnlocked = true
                                        currentScreen = ZenithNavScreen.MAIN
                                    }
                                )
                            }
                            ZenithNavScreen.MAIN -> {
                                CashFlowMainApp(
                                    viewModel = viewModel,
                                    quickAction = pendingQuickAction,
                                    onQuickActionConsumed = { pendingQuickAction = null }
                                )
                            }
                        }
                    }

                    if (currentScreen == ZenithNavScreen.MAIN && isAppLockEnabled && !isAppUnlocked) {
                        AppLockOverlay(
                            activity = this@MainActivity,
                            onUnlocked = { isAppUnlocked = true }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UpiNotificationListenerService.ensureListenerBound(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkNotificationLaunch(intent)
    }

    private fun checkNotificationLaunch(intent: Intent?) {
        if (intent == null) return
        if (intent.hasExtra("extra_open_tab")) {
            val targetTab = intent.getIntExtra("extra_open_tab", 0).coerceIn(0, 4)
            viewModel.setTab(targetTab)
            intent.removeExtra("extra_open_tab")
        }
        if (intent.hasExtra("extra_quick_action")) {
            val action = intent.getStringExtra("extra_quick_action")
            intent.removeExtra("extra_quick_action")
            if (!action.isNullOrBlank()) {
                pendingQuickAction = action
            }
        }
        if (intent.hasExtra("extra_upi_amount")) {
            val amount = intent.getDoubleExtra("extra_upi_amount", 0.0)
            val payee = intent.getStringExtra("extra_upi_payee") ?: "Merchant"
            val category = intent.getStringExtra("extra_upi_category") ?: "Other"
            val utr = intent.getStringExtra("extra_upi_utr")
            val vpa = intent.getStringExtra("extra_upi_vpa")
            val app = intent.getStringExtra("extra_upi_app") ?: "UPI"
            val isDebit = intent.getBooleanExtra("extra_upi_is_debit", true)

            val payment = com.zenith.app.data.upi.ExtractedUpiPayment(
                amount = amount,
                payeeName = payee,
                detectedCategory = category,
                upiTransactionId = utr,
                upiId = vpa,
                sourceApp = app,
                isDebit = isDebit
            )
            viewModel.showDetectedUpiPayment(payment)
        }
    }
}

@Composable
private fun AppLockOverlay(
    activity: ComponentActivity,
    onUnlocked: () -> Unit
) {
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val legacyKeyguardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onUnlocked()
        } else {
            errorMsg = "Authentication cancelled. Tap Unlock to retry."
        }
    }

    fun triggerAuth() {
        errorMsg = null
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isDeviceSecure != true) {
            // Device has no PIN/pattern/biometric enrolled; avoid permanently locking user out
            onUnlocked()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val builder = BiometricPrompt.Builder(activity)
                    .setTitle("Unlock Zenith")
                    .setSubtitle("Verify your identity to open your personal ledger")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                } else {
                    builder.setNegativeButton("Use PIN", activity.mainExecutor) { _, _ ->
                        @Suppress("DEPRECATION")
                        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
                            "Unlock Zenith",
                            "Verify device credential"
                        )
                        if (intent != null) legacyKeyguardLauncher.launch(intent)
                    }
                }

                val prompt = builder.build()
                prompt.authenticate(
                    CancellationSignal(),
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onUnlocked()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            errorMsg = errString?.toString() ?: "Authentication required"
                        }

                        override fun onAuthenticationFailed() {
                            errorMsg = "Biometric not recognized. Try again."
                        }
                    }
                )
                return
            } catch (_: Exception) {
                // Fall back to KeyguardManager below
            }
        }

        @Suppress("DEPRECATION")
        val confirmIntent = keyguardManager.createConfirmDeviceCredentialIntent(
            "Unlock Zenith",
            "Verify your device credential"
        )
        if (confirmIntent != null) {
            legacyKeyguardLauncher.launch(confirmIntent)
        } else {
            onUnlocked()
        }
    }

    LaunchedEffect(Unit) {
        triggerAuth()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ZenithTheme.backgroundBrush)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZenithLogo(size = 56.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(ZenithTheme.accentIndigo.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Locked",
                        tint = ZenithTheme.accentIndigo,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Zenith Vault Locked",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZenithTheme.textPrimary
                )
                Text(
                    text = errorMsg ?: "Use your fingerprint, face, or device PIN to access your financial records.",
                    fontSize = 13.sp,
                    color = if (errorMsg != null) ZenithTheme.accentRed else ZenithTheme.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.height(22.dp))
                Button(
                    onClick = { triggerAuth() },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.accentIndigo),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Zenith", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun CashFlowMainApp(
    viewModel: CashFlowViewModel,
    quickAction: String? = null,
    onQuickActionConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingUpiDetections by viewModel.pendingUpiDetections.collectAsStateWithLifecycle()
    val recurringSuggestions by viewModel.recurringSuggestions.collectAsStateWithLifecycle()
    val syncUiState by viewModel.syncUiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(quickAction) {
        when (quickAction) {
            "add" -> showAddDialog = true
            "upi_pay" -> viewModel.openUpiDialog()
            "scan_qr" -> viewModel.openUpiScanDialog()
            "voice" -> viewModel.openVoiceDialog()
        }
        if (quickAction != null) {
            onQuickActionConsumed()
        }
    }
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Always compute global ledger totals from unfiltered Personal transactions (allTransactions)
    val totalIncome = remember(uiState.allTransactions) { viewModel.getTotalIncome(uiState.allTransactions) }
    val totalExpense = remember(uiState.allTransactions) { viewModel.getTotalExpense(uiState.allTransactions) }
    val netBalance = remember(uiState.allTransactions) { viewModel.getNetBalance(uiState.allTransactions) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = ZenithTheme.backgroundBrush),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            ZenithFloatingNavigationBar(
                selectedTab = uiState.selectedTab,
                onTabSelected = { index -> viewModel.setTab(index) }
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = ZenithTheme.cardVariantSurface,
                    contentColor = ZenithTheme.textPrimary,
                    actionColor = ZenithTheme.accentCyan,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = ZenithTheme.backgroundBrush)
                .statusBarsPadding()
        ) {
            AnimatedContent(
                targetState = uiState.selectedTab,
                transitionSpec = {
                    val slideOffset = 0.15f
                    if (targetState > initialState) {
                        (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { width -> (width * slideOffset).toInt() } + fadeIn(animationSpec = tween(180)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(140)) { width -> (-width * slideOffset).toInt() } + fadeOut(animationSpec = tween(140)))
                    } else {
                        (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)) { width -> (-width * slideOffset).toInt() } + fadeIn(animationSpec = tween(180)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(140)) { width -> (width * slideOffset).toInt() } + fadeOut(animationSpec = tween(140)))
                    }
                },
                label = "tab_transition"
            ) { targetTab ->
                when (targetTab) {
                    0 -> HomeScreen(
                        state = uiState,
                        totalIncome = totalIncome,
                        totalExpense = totalExpense,
                        netBalance = netBalance,
                        onOpenAddTransaction = { showAddDialog = true },
                        onOpenVoiceAssistant = { viewModel.openVoiceDialog() },
                        onOpenReceiptScanner = { viewModel.openReceiptDialog() },
                        onOpenUpiPay = { viewModel.openUpiDialog() },
                        onOpenUpiScanner = { viewModel.openUpiScanDialog() },
                        onDeleteTransaction = { tx ->
                            viewModel.deleteTransactionWithReceipt(tx)
                            coroutineScope.launch {
                                val res = snackbarHostState.showSnackbar(
                                    message = "Transaction deleted",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (res == SnackbarResult.ActionPerformed) {
                                    viewModel.undoDeleteTransaction(tx)
                                } else {
                                    viewModel.commitDeletedTransaction()
                                }
                            }
                        },
                        onUpdateTransaction = { tx -> viewModel.updateTransaction(tx) },
                        onNavigateToActivity = { viewModel.setTab(1) },
                        onNavigateToProfile = { viewModel.setTab(4) },
                        onNavigateToBudgets = { viewModel.setTab(2) },
                        currentUserName = viewModel.currentUserName,
                        syncUiState = syncUiState,
                        onSyncNow = {
                            viewModel.syncNow { success ->
                                android.widget.Toast.makeText(
                                    context,
                                    if (success) "Cloud sync completed!" else "Offline / sync complete.",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onToggleHideBalance = { viewModel.toggleBalanceHidden() },
                        pendingDetections = pendingUpiDetections,
                        onReviewPendingDetection = { payment -> viewModel.showDetectedUpiPayment(payment) },
                        onQuickLogPendingDetection = { payment -> viewModel.logPendingUpiDetection(payment) },
                        onDismissPendingDetection = { payment -> viewModel.dismissPendingUpiDetection(payment) },
                        onDismissAllPendingDetections = { viewModel.dismissAllPendingUpiDetections() }
                    )
                    1 -> TransactionsScreen(
                        state = uiState,
                        onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                        onFilterTypeChange = { type -> viewModel.setFilterType(type) },
                        onFilterCategoryChange = { cat -> viewModel.setFilterCategory(cat) },
                        onDeleteTransaction = { tx ->
                            viewModel.deleteTransactionWithReceipt(tx)
                            coroutineScope.launch {
                                val res = snackbarHostState.showSnackbar(
                                    message = "Transaction deleted",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short
                                )
                                if (res == SnackbarResult.ActionPerformed) {
                                    viewModel.undoDeleteTransaction(tx)
                                } else {
                                    viewModel.commitDeletedTransaction()
                                }
                            }
                        },
                        onUpdateTransaction = { tx -> viewModel.updateTransaction(tx) },
                        onOpenAddTransaction = { showAddDialog = true },
                        onOpenVoiceAssistant = { viewModel.openVoiceDialog() }
                    )
                    2 -> BudgetsAndGoalsScreen(
                        state = uiState,
                        onSaveBudget = { cat, limit, periodType, customName, budgetId ->
                            viewModel.saveBudget(
                                categoryName = cat,
                                limit = limit,
                                periodType = periodType,
                                customPeriodName = customName,
                                budgetId = budgetId
                            )
                        },
                        onDeleteBudget = { budget -> viewModel.deleteBudget(budget) },
                        onSaveSavingsGoal = { goal ->
                            viewModel.saveSavingsGoalEntity(goal)
                        },
                        onDeleteSavingsGoal = { goal -> viewModel.deleteSavingsGoal(goal) },
                        onDepositToGoal = { goal, amount -> viewModel.updateGoalDeposit(goal, amount) },
                        onSaveAutoPayReminder = { title, amount, category, paymentMethod, frequency, dueDayOfMonth, nextDueDateMillis, isAutoDebit, reminderDaysBefore, notes, reminderId ->
                            viewModel.saveAutoPayReminder(
                                title, amount, category, paymentMethod, frequency, dueDayOfMonth, nextDueDateMillis, isAutoDebit, reminderDaysBefore, notes, reminderId
                            )
                        },
                        onDeleteAutoPayReminder = { reminder -> viewModel.deleteAutoPayReminder(reminder) },
                        onTogglePauseAutoPayReminder = { reminder -> viewModel.togglePauseAutoPayReminder(reminder) },
                        onMarkAutoPayPaid = { reminder -> viewModel.markAutoPayPaidAndLogTransaction(reminder) },
                        recurringSuggestions = recurringSuggestions,
                        onAddRecurringSuggestion = { suggestion ->
                            viewModel.acceptRecurringSuggestion(suggestion)
                            android.widget.Toast.makeText(context, "Added ${suggestion.title} to Auto-Pay reminders", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onDismissRecurringSuggestion = { suggestion -> viewModel.dismissRecurringSuggestion(suggestion) }
                    )
                    3 -> AnalyticsScreen(
                        state = uiState,
                        totalIncome = totalIncome,
                        totalExpense = totalExpense,
                        onGenerateAiAdvice = { inc, exp, cat -> viewModel.generateAiCoachAdvice(inc, exp, cat) }
                    )
                    4 -> ProfileScreen(
                        state = uiState,
                        viewModel = viewModel,
                        onCurrencySelect = { curr -> viewModel.setCurrency(curr) },
                        onDeleteScannedItem = { item -> viewModel.deleteScannedItem(item) },
                        onNavigateToActivity = { viewModel.setTab(1) }
                    )
                }
            }
        }

        if (showAddDialog) {
            AddTransactionDialog(
                categories = uiState.categories,
                currencySymbol = uiState.currencySymbol,
                onDismiss = { showAddDialog = false },
                onAdd = { title, amount, type, category, dateMillis, note, paymentMethod ->
                    viewModel.addTransaction(
                        title = title,
                        amount = amount,
                        type = type,
                        category = category,
                        paymentMethod = paymentMethod,
                        note = note,
                        dateMillis = dateMillis
                    )
                    showAddDialog = false
                }
            )
        }

        if (uiState.isUpiDialogShowing) {
            UPIPaySheet(
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.closeUpiDialog() },
                onOpenScanQr = {
                    viewModel.closeUpiDialog()
                    viewModel.openUpiScanDialog()
                },
                onSaveTransaction = { title, amount, category, upiId, upiTransactionId ->
                    viewModel.addUpiTransaction(
                        title = title,
                        amount = amount,
                        category = category,
                        upiId = upiId,
                        upiTransactionId = upiTransactionId
                    )
                    viewModel.closeUpiDialog()
                }
            )
        }

        if (uiState.isUpiScanDialogShowing) {
            UpiQrScanModal(
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.closeUpiScanDialog() },
                onSaveTransaction = { title, amount, category, upiId, upiTransactionId ->
                    viewModel.addUpiTransaction(
                        title = title,
                        amount = amount,
                        category = category,
                        upiId = upiId,
                        upiTransactionId = upiTransactionId
                    )
                    viewModel.closeUpiScanDialog()
                }
            )
        }

        uiState.detectedUpiPayment?.let { detectedPayment ->
            UpiPaymentDetectedModal(
                payment = detectedPayment,
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.dismissDetectedUpiPayment() },
                onConfirmSave = { title, amount, category, upiId, upiTransactionId ->
                    viewModel.confirmDetectedUpiPayment(
                        title = title,
                        amount = amount,
                        category = category,
                        upiId = upiId,
                        upiTransactionId = upiTransactionId
                    )
                }
            )
        }

        if (uiState.isVoiceDialogShowing) {
            VoiceAiModal(
                isProcessing = uiState.isVoiceProcessing,
                parsedExpense = uiState.parsedVoiceExpense,
                voiceChatMessages = uiState.voiceChatMessages,
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.closeVoiceDialog() },
                onProcessPrompt = { prompt -> viewModel.processVoicePrompt(prompt) },
                onProcessAudio = { audioBase64 -> viewModel.processAudioPrompt(audioBase64) },
                onUserStartedEditing = { viewModel.markVoiceEditingStarted() },
                onConfirmSave = { title, amount, category, paymentMethod ->
                    viewModel.confirmVoiceExpenseWithEdits(title, amount, TransactionType.EXPENSE, category, paymentMethod)
                },
                onConfirmSaveWithType = { title, amount, type, category, paymentMethod ->
                    viewModel.confirmVoiceExpenseWithEdits(title, amount, type, category, paymentMethod)
                },
                onClearHistory = {
                    viewModel.clearVoiceAssistantHistory()
                },
                onOpenManualAdd = {
                    viewModel.closeVoiceDialog()
                    showAddDialog = true
                }
            )
        }

        if (uiState.isReceiptDialogShowing) {
            ReceiptScanModal(
                isProcessing = uiState.isReceiptProcessing,
                parsedReceipt = uiState.parsedReceipt,
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.closeReceiptDialog() },
                onProcessReceipt = { text -> viewModel.processReceiptText(text) },
                onConfirmSave = { viewModel.confirmReceiptExpense() },
                onSaveReceiptDetails = { merchant, amount, category, paymentMethod, dateStr, timeStr, receiptNumber, subtotal, discount, tax, items, imageUri, rawText ->
                    viewModel.saveReceiptExpense(
                        merchant, amount, category, paymentMethod, dateStr, timeStr, receiptNumber, subtotal, discount, tax, items, imageUri, rawText
                    )
                },
                onBarcodeScanned = { barcode -> viewModel.openScannedBarcodeSheet(barcode) },
                onOpenManualAdd = {
                    viewModel.closeReceiptDialog()
                    showAddDialog = true
                }
            )
        }
    }
}
