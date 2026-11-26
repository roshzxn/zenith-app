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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

                // App lock state: remember across recompositions; lock only on startup or background return
                var isAppUnlocked by remember { mutableStateOf(!isAppLockEnabled) }
                var isUnlockPromptInFlight by remember { mutableStateOf(false) }
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner, isAppLockEnabled) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP &&
                            isAppLockEnabled &&
                            currentScreen == ZenithNavScreen.MAIN &&
                            !isUnlockPromptInFlight &&
                            !this@MainActivity.isChangingConfigurations
                        ) {
                            isAppUnlocked = false
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val isVaultLocked = currentScreen == ZenithNavScreen.MAIN && isAppLockEnabled && !isAppUnlocked

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

                // Safe Back Navigation Handler (never switches tabs behind App Lock)
                BackHandler(enabled = true) {
                    if (isVaultLocked) {
                        finish()
                        return@BackHandler
                    }
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
                                        isAppUnlocked = true
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
                                    quickAction = if (isVaultLocked) null else pendingQuickAction,
                                    onQuickActionConsumed = { pendingQuickAction = null },
                                    isVaultLocked = isVaultLocked
                                )
                            }
                        }
                    }

                    if (isVaultLocked) {
                        Dialog(
                            onDismissRequest = {},
                            properties = DialogProperties(
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            )
                        ) {
                            AppLockOverlay(
                                activity = this@MainActivity,
                                onPromptStateChanged = { inFlight -> isUnlockPromptInFlight = inFlight },
                                onUnlocked = {
                                    isUnlockPromptInFlight = false
                                    isAppUnlocked = true
                                }
                            )
                        }
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
        if (intent.data != null) {
            val uri = intent.data!!
            if (uri.scheme == "zenith" && uri.host == "join") {
                val tokenOrCode = uri.getQueryParameter("token")
                    ?: uri.getQueryParameter("code")
                    ?: uri.lastPathSegment
                if (!tokenOrCode.isNullOrBlank()) {
                    viewModel.onFamilyInviteLinkReceived(tokenOrCode)
                }
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
    onPromptStateChanged: (Boolean) -> Unit = {},
    onUnlocked: () -> Unit
) {
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val legacyKeyguardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onPromptStateChanged(false)
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
            onPromptStateChanged(false)
            onUnlocked()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                onPromptStateChanged(true)
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
                        else onPromptStateChanged(false)
                    }
                }

                val prompt = builder.build()
                prompt.authenticate(
                    CancellationSignal(),
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onPromptStateChanged(false)
                            onUnlocked()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            onPromptStateChanged(false)
                            errorMsg = errString?.toString() ?: "Authentication required"
                        }

                        override fun onAuthenticationFailed() {
                            errorMsg = "Biometric not recognized. Try again."
                        }
                    }
                )
                return
            } catch (_: Exception) {
                onPromptStateChanged(false)
                // Fall back to KeyguardManager below
            }
        }

        @Suppress("DEPRECATION")
        val confirmIntent = keyguardManager.createConfirmDeviceCredentialIntent(
            "Unlock Zenith",
            "Verify your device credential"
        )
        if (confirmIntent != null) {
            onPromptStateChanged(true)
            legacyKeyguardLauncher.launch(confirmIntent)
        } else {
            onPromptStateChanged(false)
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
    onQuickActionConsumed: () -> Unit = {},
    isVaultLocked: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val financialContext by viewModel.financialContext.collectAsStateWithLifecycle()
    val pendingInviteInput by viewModel.pendingInviteInput.collectAsStateWithLifecycle()
    val isAuthenticated by viewModel.isAuthenticated.collectAsStateWithLifecycle()
    val pendingUpiDetections by viewModel.pendingUpiDetections.collectAsStateWithLifecycle()
    val recurringSuggestions by viewModel.recurringSuggestions.collectAsStateWithLifecycle()
    val syncUiState by viewModel.syncUiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var activeFamilySetupTab by remember { mutableStateOf<com.zenith.app.ui.components.FamilySetupTab?>(null) }
    var showFamilyManageSheet by remember { mutableStateOf(false) }
    var showFamilyChatSheet by remember { mutableStateOf(false) }
    var prefilledInviteCode by remember { mutableStateOf("") }

    LaunchedEffect(pendingInviteInput) {
        val code = pendingInviteInput
        if (!code.isNullOrBlank()) {
            prefilledInviteCode = code
            activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.JOIN
            viewModel.consumePendingInviteInput()
        }
    }

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
    ) { innerPadding ->
        val bottomBarClearance = innerPadding.calculateBottomPadding() + 18.dp
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = ZenithTheme.backgroundBrush)
                .statusBarsPadding()
        ) {
            val tabStateHolder = rememberSaveableStateHolder()
            Crossfade(
                targetState = uiState.selectedTab,
                animationSpec = tween(90),
                label = "tab_transition"
            ) { targetTab ->
                tabStateHolder.SaveableStateProvider(targetTab) {
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
                                    viewModel.commitDeletedTransaction(tx)
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
                        onDismissAllPendingDetections = { viewModel.dismissAllPendingUpiDetections() },
                        financialContext = financialContext,
                        onSelectFinancialMode = { targetMode ->
                            val switched = viewModel.selectFinancialMode(targetMode)
                            if (!switched && targetMode == com.zenith.app.domain.FinancialMode.FAMILY) {
                                activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.CREATE
                            }
                        },
                        onOpenFamilySetup = { activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.CREATE },
                        onOpenFamilyChat = { showFamilyChatSheet = true },
                        canModifyTransaction = viewModel::canEditOrDeleteFamilyTransaction,
                        onUpdateFamilyTransaction = { tx, paidById -> viewModel.updateTransaction(tx, paidById) },
                        bottomContentPadding = bottomBarClearance
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
                                    viewModel.commitDeletedTransaction(tx)
                                }
                            }
                        },
                        onUpdateTransaction = { tx -> viewModel.updateTransaction(tx) },
                        onOpenAddTransaction = { showAddDialog = true },
                        onOpenVoiceAssistant = { viewModel.openVoiceDialog() },
                        canModifyTransaction = viewModel::canEditOrDeleteFamilyTransaction,
                        onUpdateFamilyTransaction = { tx, paidById -> viewModel.updateTransaction(tx, paidById) },
                        onSelectMemberFilter = { memberId -> viewModel.setFilterMemberId(memberId) },
                        onSelectLabelFilter = { label -> viewModel.setFilterLabel(label) },
                        bottomContentPadding = bottomBarClearance
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
                        onDismissRecurringSuggestion = { suggestion -> viewModel.dismissRecurringSuggestion(suggestion) },
                        onOpenFamilyChat = { showFamilyChatSheet = true },
                        bottomContentPadding = bottomBarClearance
                    )
                    3 -> AnalyticsScreen(
                        state = uiState,
                        totalIncome = totalIncome,
                        totalExpense = totalExpense,
                        onGenerateAiAdvice = { inc, exp, cat -> viewModel.generateAiCoachAdvice(inc, exp, cat) },
                        bottomContentPadding = bottomBarClearance
                    )
                    4 -> ProfileScreen(
                        state = uiState,
                        viewModel = viewModel,
                        onCurrencySelect = { curr -> viewModel.setCurrency(curr) },
                        onDeleteScannedItem = { item -> viewModel.deleteScannedItem(item) },
                        onNavigateToActivity = { viewModel.setTab(1) },
                        onOpenCreateFamily = {
                            prefilledInviteCode = ""
                            activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.CREATE
                        },
                        onOpenJoinFamily = {
                            prefilledInviteCode = ""
                            activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.JOIN
                        },
                        onOpenFamilyManage = { showFamilyManageSheet = true },
                        onOpenFamilyChat = { showFamilyChatSheet = true },
                        bottomContentPadding = bottomBarClearance
                    )
                }
                }
            }
        }

        activeFamilySetupTab?.let { startTab ->
            com.zenith.app.ui.components.FamilySetupModal(
                initialTab = startTab,
                initialInviteInput = prefilledInviteCode,
                currencySymbol = uiState.currencySymbol,
                currentUserName = viewModel.currentUserName,
                isAuthenticated = isAuthenticated,
                onCreateFamily = { name, emoji, budget, idempotencyKey, onComplete ->
                    viewModel.createFamilyAtomic(name, emoji, budget, idempotencyKey) { res ->
                        if (res.isSuccess) {
                            viewModel.setTab(0)
                        }
                        onComplete(res)
                    }
                },
                onJoinFamily = { codeOrLink, displayName, emoji, onComplete ->
                    viewModel.acceptFamilyInvitation(codeOrLink, displayName, emoji) { res ->
                        if (res.isSuccess) {
                            viewModel.setTab(0)
                        }
                        onComplete(res)
                    }
                },
                onDismiss = {
                    activeFamilySetupTab = null
                    prefilledInviteCode = ""
                },
                onCreateFamilyWithCustomProfile = { name, emoji, budget, idempotencyKey, familyLabel, creatorName, creatorEmoji, creatorLabel, onComplete ->
                    viewModel.createFamilyAtomic(
                        name = name,
                        avatarEmoji = emoji,
                        monthlyBudgetLimit = budget,
                        idempotencyKey = idempotencyKey,
                        familyLabel = familyLabel,
                        creatorName = creatorName,
                        creatorEmoji = creatorEmoji,
                        creatorLabel = creatorLabel
                    ) { res ->
                        if (res.isSuccess) {
                            viewModel.setTab(0)
                        }
                        onComplete(res)
                    }
                },
                onJoinFamilyWithLabel = { codeOrLink, displayName, emoji, memberLabel, onComplete ->
                    viewModel.acceptFamilyInvitation(
                        codeOrLink = codeOrLink,
                        displayName = displayName,
                        avatarEmoji = emoji,
                        memberLabel = memberLabel
                    ) { res ->
                        if (res.isSuccess) {
                            viewModel.setTab(0)
                        }
                        onComplete(res)
                    }
                }
            )
        }

        if (showFamilyManageSheet) {
            com.zenith.app.ui.components.FamilyManageSheet(
                financialContext = financialContext,
                onGenerateInvite = { email, role, onComplete ->
                    viewModel.generateFamilyInvitation(email, role, onComplete)
                },
                onUpdateMemberRole = { uid, role, onComplete ->
                    viewModel.updateFamilyMemberRole(uid, role, onComplete)
                },
                onRemoveMember = { uid, onComplete ->
                    viewModel.removeFamilyMember(uid, onComplete)
                },
                onTransferOwnership = { uid, onComplete ->
                    viewModel.transferFamilyOwnership(uid, onComplete)
                },
                onLeaveFamily = { onComplete ->
                    viewModel.leaveCurrentFamily(onComplete)
                },
                onCloseFamily = { onComplete ->
                    viewModel.closeCurrentFamily(onComplete)
                },
                onDisableFamilyFeature = { viewModel.setFamilyFeatureEnabled(false) },
                onDismiss = { showFamilyManageSheet = false },
                onUpdateFamilyProfile = { name, emoji, famLabel, onComplete ->
                    viewModel.updateFamilyProfile(name, emoji, famLabel, onComplete)
                },
                onUpdateMemberProfile = { targetUid, displayName, memLabel, memEmoji, onComplete ->
                    viewModel.updateFamilyMemberProfile(targetUid, displayName, memLabel, memEmoji, onComplete)
                }
            )
        }

        if (showFamilyChatSheet && financialContext.activeFamily != null) {
            com.zenith.app.ui.components.FamilyChatSheet(
                family = financialContext.activeFamily!!,
                members = financialContext.activeMembers,
                messages = uiState.familyMessages,
                familyTransactions = uiState.allTransactions,
                txAttributionByServerId = uiState.txAttributionByServerId,
                currentUserId = viewModel.currentUserId,
                currentUserRole = financialContext.membershipRole,
                currencySymbol = uiState.currencySymbol,
                isRealtimeConnected = uiState.isFamilyRealtimeConnected,
                onSendMessage = { body, replyId, txId ->
                    viewModel.sendFamilyChatMessage(
                        body = body,
                        replyToMessageId = replyId,
                        relatedTransactionId = txId
                    )
                },
                onDeleteMessage = { msg -> viewModel.deleteFamilyChatMessage(msg) },
                onOpenManageFamily = {
                    showFamilyChatSheet = false
                    showFamilyManageSheet = true
                },
                onDismiss = { showFamilyChatSheet = false }
            )
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
                },
                familyMembers = if (financialContext.isFamilyModeActive) financialContext.activeMembers else emptyList(),
                currentUserId = viewModel.currentUserId,
                isFamilyMode = financialContext.isFamilyModeActive,
                onAddWithPaidBy = { title, amount, type, category, dateMillis, note, paymentMethod, paidById ->
                    viewModel.addTransaction(
                        title = title,
                        amount = amount,
                        type = type,
                        category = category,
                        paymentMethod = paymentMethod,
                        note = note,
                        dateMillis = dateMillis,
                        paidByUserId = paidById
                    )
                    showAddDialog = false
                },
                onAddWithLabel = { title, amount, type, category, dateMillis, note, paymentMethod, paidById, customLabel ->
                    viewModel.addTransaction(
                        title = title,
                        amount = amount,
                        type = type,
                        category = category,
                        paymentMethod = paymentMethod,
                        note = note,
                        dateMillis = dateMillis,
                        paidByUserId = paidById,
                        customLabel = customLabel
                    )
                    showAddDialog = false
                },
                onAddCustomCategory = if (!financialContext.isFamilyModeActive || financialContext.membershipRole?.canAdmin() == true) {
                    { name, colorHex -> viewModel.addCategory(name, colorHex) }
                } else {
                    null
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
                        upiTransactionId = upiTransactionId,
                        scopeToFamily = financialContext.isFamilyModeActive
                    )
                    viewModel.closeUpiDialog()
                }
            )
        }

        if (uiState.isUpiScanDialogShowing) {
            UpiQrScanModal(
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.closeUpiScanDialog() },
                onFamilyInviteQrScanned = { invite ->
                    viewModel.closeUpiScanDialog()
                    prefilledInviteCode = invite
                    activeFamilySetupTab = com.zenith.app.ui.components.FamilySetupTab.JOIN
                },
                onSaveTransaction = { title, amount, category, upiId, upiTransactionId ->
                    viewModel.addUpiTransaction(
                        title = title,
                        amount = amount,
                        category = category,
                        upiId = upiId,
                        upiTransactionId = upiTransactionId,
                        scopeToFamily = financialContext.isFamilyModeActive
                    )
                    viewModel.closeUpiScanDialog()
                }
            )
        }

        uiState.detectedUpiPayment?.let { detectedPayment ->
            UpiPaymentDetectedModal(
                payment = detectedPayment,
                currencySymbol = uiState.currencySymbol,
                onDismiss = { viewModel.dismissDetectedUpiPayment(keepPendingForLater = true) },
                onConfirmSave = { title, amount, category, upiId, upiTransactionId ->
                    viewModel.confirmDetectedUpiPayment(
                        title = title,
                        amount = amount,
                        category = category,
                        upiId = upiId,
                        upiTransactionId = upiTransactionId,
                        scopeToFamily = financialContext.isFamilyModeActive
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
