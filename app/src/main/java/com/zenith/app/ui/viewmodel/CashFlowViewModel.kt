package com.zenith.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zenith.app.data.ai.GeminiAiService
import com.zenith.app.data.ai.ParsedReceipt
import com.zenith.app.data.ai.ParsedVoiceExpense
import com.zenith.app.data.ai.VoiceAssistantIntent
import com.zenith.app.data.ai.VoiceAssistantResponse
import com.zenith.app.data.database.CashFlowDatabase
import com.zenith.app.data.models.*
import com.zenith.app.data.network.AuthResult
import com.zenith.app.data.network.SupabaseClientConfig
import com.zenith.app.data.repository.CashFlowRepository
import com.zenith.app.data.notifications.ReminderAlertEngine
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.data.upi.MerchantCategoryRules
import com.zenith.app.data.upi.PendingUpiDetectionStore
import com.zenith.app.data.upi.RecurringPatternDetector
import com.zenith.app.data.upi.RecurringSuggestion
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.data.util.CategoryIconHelper
import com.zenith.app.widget.ZenithSummaryWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class MessageSender {
    USER,
    ASSISTANT
}

data class VoiceChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val parsedExpense: ParsedVoiceExpense? = null,
    val isActionCompleted: Boolean = false,
    val intent: VoiceAssistantIntent = VoiceAssistantIntent.LOG_TRANSACTION
)

sealed interface SyncUiState {
    object Idle : SyncUiState
    object Syncing : SyncUiState
    data class Success(val lastSyncedTimeMillis: Long) : SyncUiState
    data class Error(val message: String) : SyncUiState
}

data class CashFlowUiState(
    val transactions: List<TransactionEntity> = emptyList(),
    val allTransactions: List<TransactionEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val savingsGoals: List<SavingsGoalEntity> = emptyList(),
    val scannedItems: List<ScannedItemEntity> = emptyList(),
    val currencySymbol: String = "₹",
    val searchQuery: String = "",
    val selectedFilterType: TransactionType? = null,
    val selectedFilterCategory: String? = null,
    val isVoiceDialogShowing: Boolean = false,
    val isVoiceProcessing: Boolean = false,
    val parsedVoiceExpense: ParsedVoiceExpense? = null,
    val voiceChatMessages: List<VoiceChatMessage> = emptyList(),
    val latestAssistantResponse: VoiceAssistantResponse? = null,
    val isUpiDialogShowing: Boolean = false,
    val isUpiScanDialogShowing: Boolean = false,
    val isReceiptDialogShowing: Boolean = false,
    val isReceiptProcessing: Boolean = false,
    val parsedReceipt: ParsedReceipt? = null,
    val aiCoachAdvice: String? = null,
    val isAiCoachLoading: Boolean = false,
    val selectedTab: Int = 0,
    val scannedBarcodeValue: String? = null,
    val isScannedBarcodeSheetShowing: Boolean = false,
    val hasCompletedOnboarding: Boolean = true,
    val defaultPaymentMethod: String = "UPI",
    val defaultTransactionType: TransactionType = TransactionType.EXPENSE,
    val isHapticEnabled: Boolean = true,
    val isNotificationEnabled: Boolean = true,
    val detectedUpiPayment: ExtractedUpiPayment? = null,
    val isBalanceHidden: Boolean = false,
    val autoPayReminders: List<AutoPayReminderEntity> = emptyList()
)

class CashFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CashFlowRepository

    private val _searchQuery = MutableStateFlow("")
    private val _filterType = MutableStateFlow<TransactionType?>(null)
    private val _filterCategory = MutableStateFlow<String?>(null)
    private val _currencySymbol = MutableStateFlow("₹")
    private val _selectedTab = MutableStateFlow(0)

    private val _voiceDialogShowing = MutableStateFlow(false)
    private val _voiceProcessing = MutableStateFlow(false)
    private val _parsedVoice = MutableStateFlow<ParsedVoiceExpense?>(null)
    private val _voiceUserEditing = MutableStateFlow(false)
    private val _voiceChatMessages = MutableStateFlow<List<VoiceChatMessage>>(emptyList())
    private val _latestAssistantResponse = MutableStateFlow<VoiceAssistantResponse?>(null)

    private val _upiDialogShowing = MutableStateFlow(false)
    private val _upiScanDialogShowing = MutableStateFlow(false)
    private val _detectedUpiPayment = MutableStateFlow<ExtractedUpiPayment?>(null)

    private val _receiptDialogShowing = MutableStateFlow(false)
    private val _receiptProcessing = MutableStateFlow(false)
    private val _parsedReceipt = MutableStateFlow<ParsedReceipt?>(null)

    private val _aiCoachAdvice = MutableStateFlow<String?>(null)
    private val _aiCoachLoading = MutableStateFlow(false)

    private val _scannedBarcodeValue = MutableStateFlow<String?>(null)
    private val _isScannedBarcodeSheetShowing = MutableStateFlow(false)

    private val syncEngine: com.zenith.app.data.network.SyncEngine
    private val userProfileRepository: com.zenith.app.data.repository.UserProfileRepository
    val authService: com.zenith.app.data.network.AuthService = com.zenith.app.data.network.SupabaseAuthService()

    val currentMonthYear: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            return sdf.format(Date())
        }

    private val prefs = application.getSharedPreferences("zenith_prefs", android.content.Context.MODE_PRIVATE)

    private val _isAuthenticated = MutableStateFlow(prefs.getBoolean("is_authenticated", false))
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _activeUserEmail = MutableStateFlow(prefs.getString("user_email", null))
    val activeUserEmail: StateFlow<String?> = _activeUserEmail.asStateFlow()

    private val _activeUserName = MutableStateFlow(prefs.getString("user_name", null))
    val activeUserName: StateFlow<String?> = _activeUserName.asStateFlow()

    private val _isGuestMode = MutableStateFlow(prefs.getBoolean("is_guest_mode", false))
    val isGuestMode: StateFlow<Boolean> = _isGuestMode.asStateFlow()

    private val _defaultPaymentMethod = MutableStateFlow(prefs.getString("default_payment_method", "UPI") ?: "UPI")
    val defaultPaymentMethod: StateFlow<String> = _defaultPaymentMethod.asStateFlow()

    private val _isDarkMode = MutableStateFlow(prefs.getBoolean("is_dark_mode", true))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isOledBlack = MutableStateFlow(prefs.getBoolean("is_oled_black", false))
    val isOledBlack: StateFlow<Boolean> = _isOledBlack.asStateFlow()

    private val _isHapticsEnabled = MutableStateFlow(prefs.getBoolean("is_haptics_enabled", true))
    val isHapticsEnabled: StateFlow<Boolean> = _isHapticsEnabled.asStateFlow()

    private val _isNotificationsEnabled = MutableStateFlow(prefs.getBoolean("is_notifications_enabled", true))
    val isNotificationsEnabled: StateFlow<Boolean> = _isNotificationsEnabled.asStateFlow()

    private val _isAppLockEnabled = MutableStateFlow(prefs.getBoolean("app_lock_enabled", false))
    val isAppLockEnabled: StateFlow<Boolean> = _isAppLockEnabled.asStateFlow()

    private val _isBalanceHidden = MutableStateFlow(prefs.getBoolean("is_balance_hidden", false))
    val isBalanceHidden: StateFlow<Boolean> = _isBalanceHidden.asStateFlow()

    fun toggleBalanceHidden() {
        val newVal = !_isBalanceHidden.value
        _isBalanceHidden.value = newVal
        prefs.edit().putBoolean("is_balance_hidden", newVal).apply()
    }

    private val _isUpiAutoDetectionEnabled = MutableStateFlow(
        UpiNotificationListenerService.isAutoDetectionEnabled(application)
    )
    val isUpiAutoDetectionEnabled: StateFlow<Boolean> = _isUpiAutoDetectionEnabled.asStateFlow()

    private val _isUpiInstantAutoSaveEnabled = MutableStateFlow(
        UpiNotificationListenerService.isInstantAutoSaveEnabled(application)
    )
    val isUpiInstantAutoSaveEnabled: StateFlow<Boolean> = _isUpiInstantAutoSaveEnabled.asStateFlow()

    val pendingUpiDetections: StateFlow<List<ExtractedUpiPayment>> = PendingUpiDetectionStore.pendingDetections

    private val _dismissedRecurringKeys = MutableStateFlow(
        prefs.getStringSet("dismissed_recurring_keys", emptySet())?.toSet() ?: emptySet()
    )

    private val _userSupabaseId = MutableStateFlow(prefs.getString("user_supabase_id", null))
    val userSupabaseId: StateFlow<String?> = _userSupabaseId.asStateFlow()

    private val _syncUiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncUiState: StateFlow<SyncUiState> = _syncUiState.asStateFlow()

    val currentUserId: String
        get() = authService.currentUser.value?.id
            ?: _userSupabaseId.value
            ?: "local_user_1"
    val currentUserName: String
        get() = _activeUserName.value ?: authService.currentUser.value?.email?.substringBefore("@") ?: "You"

    init {
        MerchantCategoryRules.init(application)
        PendingUpiDetectionStore.init(application)

        val database = CashFlowDatabase.getDatabase(application)
        repository = CashFlowRepository(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
            budgetDao = database.budgetDao(),
            savingsGoalDao = database.savingsGoalDao(),
            scannedItemDao = database.scannedItemDao(),
            receiptDao = database.receiptDao(),
            autoPayReminderDao = database.autoPayReminderDao()
        )
        syncEngine = com.zenith.app.data.network.SyncEngine(
            transactionDao = database.transactionDao(),
            authService = authService,
            budgetDao = database.budgetDao(),
            savingsGoalDao = database.savingsGoalDao(),
            autoPayReminderDao = database.autoPayReminderDao()
        )

        userProfileRepository = com.zenith.app.data.repository.UserProfileRepository(database.userProfileDao())

        viewModelScope.launch(Dispatchers.IO) {
            authService.restoreSession()
            authService.currentUser.collect { user ->
                if (user != null) {
                    _userSupabaseId.value = user.id
                    _activeUserEmail.value = user.email
                    _activeUserName.value = user.fullName
                    _isAuthenticated.value = true
                    _isGuestMode.value = false
                    userProfileRepository.syncProfile(user.id)
                    repository.reassignPersonalTransactionsToUser(user.id)
                    restoreAllCloudData(user.id)
                }
            }
        }

        // Background cloud synchronization loop (every 15s when authenticated)
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15000)
                val uid = authService.currentUser.value?.id ?: _userSupabaseId.value
                if (!uid.isNullOrBlank() && syncEngine.isValidUuid(uid)) {
                    try {
                        syncEngine.syncAll(uid)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // Listen to real-time background UPI payment detections without duplicating in-app UPI flows
        viewModelScope.launch(Dispatchers.IO) {
            UpiPaymentBus.detectedPayments.collect { payment ->
                if (_upiDialogShowing.value || _upiScanDialogShowing.value) {
                    return@collect
                }
                if (UpiPaymentBus.wasRecentlyRecorded(payment.amount, payment.upiTransactionId)) {
                    return@collect
                }
                val dupByUtr = payment.upiTransactionId?.takeIf { it.isNotBlank() }?.let {
                    repository.getTransactionByUtr(it)
                }
                val expectedType = if (payment.isDebit) TransactionType.EXPENSE else TransactionType.INCOME
                val dupNearby = repository.findPotentialDuplicates(
                    amount = payment.amount,
                    type = expectedType,
                    scope = FinanceScope.PERSONAL,
                    startMillis = payment.timestamp - 300_000L,
                    endMillis = payment.timestamp + 300_000L
                ).firstOrNull()
                if (dupByUtr == null && dupNearby == null) {
                    _detectedUpiPayment.value = payment
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.deduplicateTransactions()
            ReminderAlertEngine.checkAndNotify(getApplication())
        }
    }

    fun setDefaultPaymentMethod(method: String) {
        _defaultPaymentMethod.value = method
        prefs.edit().putString("default_payment_method", method).apply()
    }

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        val editor = prefs.edit().putBoolean("is_dark_mode", enabled)
        if (!enabled && _isOledBlack.value) {
            _isOledBlack.value = false
            editor.putBoolean("is_oled_black", false)
        }
        editor.apply()
    }

    fun setOledBlack(enabled: Boolean) {
        _isOledBlack.value = enabled
        val editor = prefs.edit().putBoolean("is_oled_black", enabled)
        if (enabled && !_isDarkMode.value) {
            _isDarkMode.value = true
            editor.putBoolean("is_dark_mode", true)
        }
        editor.apply()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _isHapticsEnabled.value = enabled
        prefs.edit().putBoolean("is_haptics_enabled", enabled).apply()
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _isNotificationsEnabled.value = enabled
        prefs.edit().putBoolean("is_notifications_enabled", enabled).apply()
        val app = getApplication<Application>()
        if (enabled) {
            ReminderAlertEngine.ensureChannel(app)
            ReminderAlertEngine.scheduleDailyCheck(app)
            viewModelScope.launch(Dispatchers.IO) {
                ReminderAlertEngine.checkAndNotify(app)
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        _isAppLockEnabled.value = enabled
        prefs.edit().putBoolean("app_lock_enabled", enabled).apply()
    }

    fun setUpiAutoDetectionEnabled(enabled: Boolean) {
        _isUpiAutoDetectionEnabled.value = enabled
        UpiNotificationListenerService.setAutoDetectionEnabled(getApplication<Application>(), enabled)
    }

    fun setUpiInstantAutoSaveEnabled(enabled: Boolean) {
        _isUpiInstantAutoSaveEnabled.value = enabled
        UpiNotificationListenerService.setInstantAutoSaveEnabled(getApplication<Application>(), enabled)
    }

    fun simulateTestUpiPayment(
        amount: Double = 150.0,
        payeeName: String = "Chai Point",
        sourceApp: String = "Google Pay"
    ) {
        UpiNotificationListenerService.simulateTestPayment(getApplication<Application>(), amount, payeeName, sourceApp)
    }

    fun simulateTestShadeNotification(
        amount: Double = 150.0,
        payeeName: String = "Chai Point",
        sourceApp: String = "Google Pay"
    ): Boolean {
        return UpiNotificationListenerService.simulateShadeNotification(getApplication<Application>(), amount, payeeName, sourceApp)
    }

    fun logPendingUpiDetection(payment: ExtractedUpiPayment) {
        MerchantCategoryRules.learn(payment.payeeName, payment.upiId, payment.detectedCategory)
        addUpiTransaction(
            title = payment.payeeName,
            amount = payment.amount,
            category = payment.detectedCategory,
            upiId = payment.upiId,
            upiTransactionId = payment.upiTransactionId,
            dateMillis = payment.timestamp,
            note = if (payment.isDebit) "Logged from missed UPI alerts" else "Credited from missed UPI alerts",
            type = if (payment.isDebit) TransactionType.EXPENSE else TransactionType.INCOME
        )
    }

    fun dismissPendingUpiDetection(payment: ExtractedUpiPayment) {
        PendingUpiDetectionStore.removeItem(payment, getApplication())
        UpiNotificationListenerService.cancelNotificationFor(getApplication(), payment.amount, payment.payeeName)
    }

    fun dismissAllPendingUpiDetections() {
        PendingUpiDetectionStore.clearAll(getApplication())
    }

    fun acceptRecurringSuggestion(suggestion: RecurringSuggestion) {
        val cal = Calendar.getInstance().apply { timeInMillis = suggestion.nextEstimatedDueMillis }
        saveAutoPayReminder(
            title = suggestion.title,
            amount = suggestion.amount,
            category = suggestion.category,
            paymentMethod = suggestion.paymentMethod,
            frequency = suggestion.frequency,
            dueDayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
            nextDueDateMillis = suggestion.nextEstimatedDueMillis,
            isAutoDebit = suggestion.paymentMethod.contains("UPI", ignoreCase = true) ||
                suggestion.paymentMethod.contains("Pay", ignoreCase = true),
            reminderDaysBefore = 2,
            notes = "Auto-detected (${suggestion.occurrenceCount} matching payments)"
        )
    }

    fun dismissRecurringSuggestion(suggestion: RecurringSuggestion) {
        val key = RecurringPatternDetector.normalizeKey(suggestion.title)
        val updated = _dismissedRecurringKeys.value + key
        _dismissedRecurringKeys.value = updated
        prefs.edit().putStringSet("dismissed_recurring_keys", updated).apply()
    }

    fun updateUserName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotBlank()) {
            _activeUserName.value = trimmed
            prefs.edit().putString("user_name", trimmed).apply()
        }
    }

    private val baseDataState: Flow<BaseData> = combine(
        combine(
            repository.allTransactions,
            repository.allCategories,
            repository.allBudgets,
            ::Triple
        ),
        combine(
            repository.allSavingsGoals,
            repository.allScannedItems,
            repository.allAutoPayReminders,
            ::Triple
        )
    ) { (txList, catList, budgetList), (goalList, scannedList, autoPayList) ->
        BaseData(txList, catList, budgetList, goalList, scannedList, autoPayList)
    }

    val recurringSuggestions: StateFlow<List<RecurringSuggestion>> = combine(
        repository.allTransactions,
        repository.allAutoPayReminders,
        _dismissedRecurringKeys
    ) { txs, reminders, dismissed ->
        RecurringPatternDetector.detectSuggestions(
            transactions = txs,
            existingReminders = reminders,
            dismissedKeys = dismissed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val filterState: Flow<FilterState> = combine(
        _searchQuery,
        _filterType,
        _filterCategory
    ) { search, type, cat ->
        Triple(search, type, cat)
    }.combine(combine(_currencySymbol, _selectedTab, _isBalanceHidden, ::Triple)) { f1, f2 ->
        FilterState(f1.first, f1.second, f1.third, f2.first, f2.second, f2.third)
    }

    private val voiceState: Flow<VoiceState> = combine(
        combine(_voiceDialogShowing, _voiceProcessing, _parsedVoice, ::Triple),
        combine(_voiceChatMessages, _latestAssistantResponse, ::Pair),
        combine(_upiDialogShowing, _upiScanDialogShowing, _detectedUpiPayment, ::Triple)
    ) { (voiceShow, voiceProc, voiceParsed), (messages, latestResp), (upiShow, upiScanShow, detectedPayment) ->
        VoiceState(voiceShow, voiceProc, voiceParsed, messages, latestResp, upiShow, upiScanShow, detectedPayment)
    }

    private val aiState: Flow<AiState> = combine(
        _receiptDialogShowing,
        _receiptProcessing,
        _parsedReceipt,
        _aiCoachAdvice,
        _aiCoachLoading
    ) { receiptShow, receiptProc, receiptParsed, coachAdvice, coachLoading ->
        AiState(receiptShow, receiptProc, receiptParsed, coachAdvice, coachLoading)
    }

    private val scannedItemState: Flow<ScannedItemState> = combine(
        _scannedBarcodeValue,
        _isScannedBarcodeSheetShowing
    ) { barcodeValue, sheetShowing ->
        ScannedItemState(barcodeValue, sheetShowing)
    }

    val uiState: StateFlow<CashFlowUiState> = combine(
        baseDataState,
        filterState,
        voiceState,
        aiState,
        scannedItemState
    ) { base, filter, voice, ai, scannedItem ->
        val filteredTx = base.transactions.filter { tx ->
            val matchesSearch = filter.search.isBlank() ||
                    tx.title.contains(filter.search, ignoreCase = true) ||
                    tx.category.contains(filter.search, ignoreCase = true) ||
                    tx.paymentMethod.contains(filter.search, ignoreCase = true) ||
                    tx.note.contains(filter.search, ignoreCase = true)

            val matchesType = filter.type == null || tx.type == filter.type
            val matchesCat = filter.category == null || tx.category == filter.category

            matchesSearch && matchesType && matchesCat
        }

        CashFlowUiState(
            transactions = filteredTx,
            allTransactions = base.transactions,
            categories = base.categories,
            budgets = base.budgets,
            savingsGoals = base.savingsGoals,
            scannedItems = base.scannedItems,
            currencySymbol = filter.currency,
            searchQuery = filter.search,
            selectedFilterType = filter.type,
            selectedFilterCategory = filter.category,
            isVoiceDialogShowing = voice.voiceShow,
            isVoiceProcessing = voice.voiceProc,
            parsedVoiceExpense = voice.voiceParsed,
            voiceChatMessages = voice.voiceMessages,
            latestAssistantResponse = voice.latestAssistantResponse,
            isUpiDialogShowing = voice.upiShow,
            isUpiScanDialogShowing = voice.upiScanShow,
            isReceiptDialogShowing = ai.receiptShow,
            isReceiptProcessing = ai.receiptProc,
            parsedReceipt = ai.receiptParsed,
            aiCoachAdvice = ai.coachAdvice,
            isAiCoachLoading = ai.coachLoading,
            selectedTab = filter.tab,
            scannedBarcodeValue = scannedItem.barcodeValue,
            isScannedBarcodeSheetShowing = scannedItem.sheetShowing,
            detectedUpiPayment = voice.detectedPayment,
            isBalanceHidden = filter.isBalanceHidden,
            autoPayReminders = base.autoPayReminders
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CashFlowUiState()
    )

    fun getTotalIncome(transactions: List<TransactionEntity>): Double {
        return transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amount }
    }

    fun getTotalExpense(transactions: List<TransactionEntity>): Double {
        return transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
    }

    fun getNetBalance(transactions: List<TransactionEntity>): Double {
        return getTotalIncome(transactions) - getTotalExpense(transactions)
    }

    fun setTab(index: Int) {
        _selectedTab.value = index
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilterType(type: TransactionType?) {
        _filterType.value = type
    }

    fun setFilterCategory(category: String?) {
        _filterCategory.value = category
    }

    fun setCurrency(symbol: String) {
        _currencySymbol.value = symbol
    }

    val currentUser = authService.currentUser

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val localUserProfile: StateFlow<UserProfileEntity?> = authService.currentUser.flatMapLatest { user ->
        if (user != null) {
            userProfileRepository.getProfileFlow(user.id)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setAuthenticatedUser(email: String, name: String, userId: String? = null, isGuest: Boolean = false) {
        val editor = prefs.edit()
            .putBoolean("is_authenticated", true)
            .putBoolean("is_guest_mode", isGuest)
            .putString("user_email", email)
            .putString("user_name", name)
        if (userId != null) {
            editor.putString("user_supabase_id", userId)
        } else if (isGuest) {
            editor.remove("user_supabase_id")
        }
        editor.apply()

        _isAuthenticated.value = true
        _isGuestMode.value = isGuest
        _activeUserEmail.value = email
        _activeUserName.value = name
        if (userId != null) {
            _userSupabaseId.value = userId
        } else if (isGuest) {
            _userSupabaseId.value = null
        }
    }

    suspend fun clearLocalUserCache() = withContext(Dispatchers.IO) {
        try {
            val app: Application = getApplication()
            val db = CashFlowDatabase.getDatabase(app)
            repository.wipeLocalDatabase()
            repository.reseedDefaultCategories()
            db.userProfileDao().deleteAllProfiles()
            MerchantCategoryRules.clear()
            PendingUpiDetectionStore.clearAll(app)
            ZenithSummaryWidgetProvider.requestUpdate(app)
            Log.d("CashFlowVM", "clearLocalUserCache: Local user data purged successfully.")
        } catch (e: Exception) {
            Log.w("CashFlowVM", "clearLocalUserCache failed: ${e.message}")
        }
    }

    suspend fun restoreAllCloudData(userId: String): Boolean = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured || !syncEngine.isValidUuid(userId)) {
            _syncUiState.value = SyncUiState.Success(System.currentTimeMillis())
            return@withContext true
        }
        _syncUiState.value = SyncUiState.Syncing
        var success = true
        try {
            userProfileRepository.syncProfile(userId)
            val personalSyncSuccess = syncEngine.syncAll(userId)
            if (!personalSyncSuccess) success = false
            _syncUiState.value = SyncUiState.Success(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e("CashFlowVM", "restoreAllCloudData error: ${e.message}", e)
            _syncUiState.value = SyncUiState.Error("Sync failed: ${e.message}")
            success = false
        }
        success
    }

    fun signIn(email: String, pass: String, onResult: (AuthResult) -> Unit) {
        viewModelScope.launch {
            val result = authService.signIn(email, pass)
            if (result.success) {
                val user = result.user ?: authService.currentUser.value
                val cleanName = user?.fullName?.ifBlank { null }
                    ?: user?.email?.substringBefore("@")
                    ?: email.substringBefore("@")
                val userId = user?.id

                val previousUserId = prefs.getString("last_authenticated_user_id", null)
                if (previousUserId != null && userId != null && previousUserId != userId) {
                    clearLocalUserCache()
                }
                if (userId != null) {
                    prefs.edit().putString("last_authenticated_user_id", userId).apply()
                }

                setAuthenticatedUser(email, cleanName, userId = userId, isGuest = false)

                if (userId != null && syncEngine.isValidUuid(userId)) {
                    repository.reassignPersonalTransactionsToUser(userId)
                    restoreAllCloudData(userId)
                }
            }
            onResult(result)
        }
    }

    fun signUp(email: String, pass: String, name: String, onResult: (AuthResult) -> Unit) {
        viewModelScope.launch {
            val result = authService.signUp(email, pass, name)
            if (result.success && !result.requiresEmailConfirmation) {
                val user = result.user ?: authService.currentUser.value
                val cleanName = user?.fullName?.ifBlank { null } ?: name.ifBlank { email.substringBefore("@") }
                val userId = user?.id

                val previousUserId = prefs.getString("last_authenticated_user_id", null)
                if (previousUserId != null && userId != null && previousUserId != userId) {
                    clearLocalUserCache()
                }
                if (userId != null) {
                    prefs.edit().putString("last_authenticated_user_id", userId).apply()
                }

                setAuthenticatedUser(email, cleanName, userId = userId, isGuest = false)

                if (userId != null && syncEngine.isValidUuid(userId)) {
                    repository.reassignPersonalTransactionsToUser(userId)
                    restoreAllCloudData(userId)
                }
            }
            onResult(result)
        }
    }

    fun resetPassword(email: String, onResult: (AuthResult) -> Unit) {
        viewModelScope.launch {
            val result = authService.resetPassword(email)
            onResult(result)
        }
    }

    fun continueAsGuest() {
        setAuthenticatedUser("guest@zenith.vault", "Guest Explorer", userId = null, isGuest = true)
    }

    fun signOut() {
        viewModelScope.launch {
            authService.signOut()
            prefs.edit()
                .putBoolean("is_authenticated", false)
                .putBoolean("is_guest_mode", false)
                .remove("user_email")
                .remove("user_name")
                .remove("user_supabase_id")
                .remove("last_authenticated_user_id")
                .apply()
            _isAuthenticated.value = false
            _isGuestMode.value = false
            _activeUserEmail.value = null
            _activeUserName.value = null
            _userSupabaseId.value = null
            _syncUiState.value = SyncUiState.Idle
            clearLocalUserCache()
        }
    }

    fun syncNow(onComplete: ((Boolean) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val userId = authService.currentUser.value?.id ?: _userSupabaseId.value
            val success = if (!userId.isNullOrBlank() && syncEngine.isValidUuid(userId)) {
                restoreAllCloudData(userId)
            } else {
                executeSync()
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke(success)
            }
        }
    }

    suspend fun executeSync(): Boolean = withContext(Dispatchers.IO) {
        if (!SupabaseClientConfig.isConfigured) {
            _syncUiState.value = SyncUiState.Success(System.currentTimeMillis())
            return@withContext true
        }
        val userId = authService.currentUser.value?.id ?: _userSupabaseId.value
        if (userId.isNullOrBlank() || !syncEngine.isValidUuid(userId)) {
            _syncUiState.value = SyncUiState.Error("Please sign in with a Zenith account to sync.")
            return@withContext false
        }
        return@withContext restoreAllCloudData(userId)
    }

    private fun triggerBackgroundSync() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncEngine.syncAll(currentUserId)
            } catch (_: Exception) {
            }
        }
    }

    fun addTransaction(
        title: String,
        amount: Double,
        type: TransactionType,
        category: String,
        paymentMethod: String,
        note: String,
        dateMillis: Long = System.currentTimeMillis(),
        upiId: String? = null,
        upiTransactionId: String? = null
    ) {
        viewModelScope.launch {
            val iconName = CategoryIconHelper.iconNameForCategory(category)
            val generatedTxServerId = UUID.randomUUID().toString()

            repository.addTransaction(
                TransactionEntity(
                    title = title,
                    amount = amount,
                    type = type,
                    category = category,
                    categoryIconName = iconName,
                    paymentMethod = paymentMethod,
                    note = note,
                    financeScope = FinanceScope.PERSONAL,
                    familyId = null,
                    createdByUserId = currentUserId,
                    dateMillis = dateMillis,
                    upiId = upiId,
                    upiTransactionId = upiTransactionId,
                    serverId = generatedTxServerId,
                    syncStatus = "PENDING_CREATE"
                )
            )
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
            viewModelScope.launch(Dispatchers.IO) {
                ReminderAlertEngine.checkAndNotify(getApplication())
            }
            triggerBackgroundSync()
        }
    }

    private val upiInsertMutex = Mutex()

    fun addUpiTransaction(
        title: String,
        amount: Double,
        category: String,
        upiId: String?,
        upiTransactionId: String?,
        dateMillis: Long = System.currentTimeMillis(),
        note: String = "UPI Payment",
        type: TransactionType = TransactionType.EXPENSE
    ) {
        if (amount <= 0.0 || amount.isNaN() || amount.isInfinite()) return
        UpiPaymentBus.tryClaimRecording(amount, upiTransactionId)
        viewModelScope.launch {
            upiInsertMutex.withLock {
                val existingByUtr = upiTransactionId?.takeIf { it.isNotBlank() }?.let {
                    repository.getTransactionByUtr(it)
                }
                val existingNearby = repository.findPotentialDuplicates(
                    amount = amount,
                    type = type,
                    scope = FinanceScope.PERSONAL,
                    startMillis = dateMillis - 300_000L,
                    endMillis = dateMillis + 300_000L
                ).firstOrNull()

                if (existingByUtr == null && existingNearby == null) {
                    val iconName = CategoryIconHelper.iconNameForCategory(category)
                    repository.addTransaction(
                        TransactionEntity(
                            title = title,
                            amount = amount,
                            type = type,
                            category = category,
                            categoryIconName = iconName,
                            paymentMethod = "UPI",
                            note = note,
                            financeScope = FinanceScope.PERSONAL,
                            familyId = null,
                            createdByUserId = currentUserId,
                            dateMillis = dateMillis,
                            upiId = upiId,
                            upiTransactionId = upiTransactionId,
                            serverId = UUID.randomUUID().toString(),
                            syncStatus = "PENDING_CREATE"
                        )
                    )
                    try {
                        Log.d(
                            "CashFlowViewModel",
                            "Recorded UPI ${type.name} amount=$amount maskedVpa=${com.zenith.app.data.upi.UpiService.maskVpa(upiId)} hasRef=${!upiTransactionId.isNullOrBlank()}"
                        )
                    } catch (_: Throwable) {
                    }
                    triggerBackgroundSync()
                }
                UpiPaymentBus.markRecorded(amount, upiTransactionId)
                PendingUpiDetectionStore.markResolved(amount, upiTransactionId, getApplication())
                MerchantCategoryRules.learn(title, upiId, category)
                UpiNotificationListenerService.cancelNotificationFor(getApplication(), amount, title)
                ZenithSummaryWidgetProvider.requestUpdate(getApplication())
                viewModelScope.launch(Dispatchers.IO) {
                    ReminderAlertEngine.checkAndNotify(getApplication())
                }
                _detectedUpiPayment.value = null
            }
        }
    }

    fun dismissDetectedUpiPayment() {
        _detectedUpiPayment.value?.let {
            PendingUpiDetectionStore.removeItem(it, getApplication())
        }
        _detectedUpiPayment.value = null
        UpiPaymentBus.clearPending()
    }

    fun showDetectedUpiPayment(payment: ExtractedUpiPayment) {
        _detectedUpiPayment.value = payment
    }

    fun confirmDetectedUpiPayment(
        title: String,
        amount: Double,
        category: String,
        upiId: String?,
        upiTransactionId: String?
    ) {
        val detected = _detectedUpiPayment.value
        val ts = detected?.timestamp ?: System.currentTimeMillis()
        val isDebit = detected?.isDebit ?: true
        val txnType = if (isDebit) TransactionType.EXPENSE else TransactionType.INCOME
        val noteText = if (isDebit) "UPI Payment" else "UPI Credit"
        addUpiTransaction(
            title = title,
            amount = amount,
            category = category,
            upiId = upiId,
            upiTransactionId = upiTransactionId,
            dateMillis = ts,
            note = noteText,
            type = txnType
        )
    }

    fun updateTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            MerchantCategoryRules.learn(transaction.title, transaction.upiId, transaction.category)
            repository.updateTransaction(
                transaction.copy(
                    categoryIconName = CategoryIconHelper.iconNameForCategory(transaction.category),
                    syncStatus = "PENDING_UPDATE"
                )
            )
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
            triggerBackgroundSync()
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
            triggerBackgroundSync()
        }
    }

    fun importTransactions(transactions: List<TransactionEntity>) {
        viewModelScope.launch {
            transactions.forEach { tx ->
                repository.addTransaction(
                    tx.copy(
                        financeScope = FinanceScope.PERSONAL,
                        familyId = null,
                        createdByUserId = currentUserId,
                        serverId = tx.serverId ?: UUID.randomUUID().toString(),
                        syncStatus = "PENDING_CREATE"
                    )
                )
            }
            triggerBackgroundSync()
        }
    }

    fun restoreFullBackup(
        transactions: List<TransactionEntity>,
        budgets: List<BudgetEntity>,
        goals: List<SavingsGoalEntity>
    ) {
        viewModelScope.launch {
            transactions.forEach {
                repository.addTransaction(
                    it.copy(
                        financeScope = FinanceScope.PERSONAL,
                        familyId = null,
                        createdByUserId = currentUserId,
                        serverId = it.serverId ?: UUID.randomUUID().toString(),
                        syncStatus = "PENDING_CREATE"
                    )
                )
            }
            budgets.forEach {
                repository.saveBudget(
                    it.copy(
                        financeScope = FinanceScope.PERSONAL,
                        familyId = null,
                        serverId = it.serverId ?: UUID.randomUUID().toString(),
                        syncStatus = "PENDING_CREATE"
                    )
                )
            }
            goals.forEach {
                repository.saveSavingsGoal(
                    it.copy(
                        financeScope = FinanceScope.PERSONAL,
                        familyId = null,
                        serverId = it.serverId ?: UUID.randomUUID().toString(),
                        syncStatus = "PENDING_CREATE"
                    )
                )
            }
            triggerBackgroundSync()
        }
    }

    fun saveBudget(
        categoryName: String,
        limit: Double,
        periodType: String = "MONTHLY",
        customPeriodName: String = "",
        budgetId: Long = 0
    ) {
        viewModelScope.launch {
            val existing = if (budgetId > 0L) repository.getBudgetById(budgetId) else null
            val preservedServerId = existing?.serverId ?: UUID.randomUUID().toString()
            val status = if (existing?.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"

            repository.saveBudget(
                BudgetEntity(
                    id = budgetId,
                    categoryName = categoryName,
                    monthlyLimit = limit,
                    monthYear = existing?.monthYear?.takeIf { it.isNotBlank() } ?: currentMonthYear,
                    periodType = periodType,
                    customPeriodName = customPeriodName,
                    financeScope = FinanceScope.PERSONAL,
                    familyId = null,
                    serverId = preservedServerId,
                    syncStatus = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
            triggerBackgroundSync()
        }
    }

    fun saveBudgetEntity(budget: BudgetEntity) {
        viewModelScope.launch {
            val serverId = budget.serverId ?: UUID.randomUUID().toString()
            val status = if (budget.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"
            repository.saveBudget(
                budget.copy(
                    financeScope = FinanceScope.PERSONAL,
                    familyId = null,
                    serverId = serverId,
                    syncStatus = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
            triggerBackgroundSync()
        }
    }

    fun deleteBudget(budget: BudgetEntity) {
        viewModelScope.launch {
            repository.deleteBudget(budget.copy(syncStatus = "PENDING_DELETE", isDeleted = true))
            triggerBackgroundSync()
        }
    }

    fun saveSavingsGoal(
        title: String,
        targetAmount: Double,
        currentAmount: Double,
        goalId: Long = 0
    ) {
        viewModelScope.launch {
            val existing = if (goalId > 0L) repository.getGoalById(goalId) else null
            val serverId = existing?.serverId ?: UUID.randomUUID().toString()
            val status = if (existing?.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"
            repository.saveSavingsGoal(
                SavingsGoalEntity(
                    id = goalId,
                    title = title,
                    targetAmount = targetAmount,
                    currentAmount = currentAmount,
                    financeScope = FinanceScope.PERSONAL,
                    familyId = null,
                    serverId = serverId,
                    syncStatus = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
            triggerBackgroundSync()
        }
    }

    fun saveSavingsGoalEntity(goal: SavingsGoalEntity) {
        viewModelScope.launch {
            val serverId = goal.serverId ?: UUID.randomUUID().toString()
            val status = if (goal.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"
            repository.saveSavingsGoal(
                goal.copy(
                    financeScope = FinanceScope.PERSONAL,
                    familyId = null,
                    serverId = serverId,
                    syncStatus = status,
                    updatedAt = System.currentTimeMillis()
                )
            )
            triggerBackgroundSync()
        }
    }

    fun deleteSavingsGoal(goal: SavingsGoalEntity) {
        viewModelScope.launch {
            repository.deleteSavingsGoal(goal.copy(syncStatus = "PENDING_DELETE", isDeleted = true))
            triggerBackgroundSync()
        }
    }

    fun updateGoalDeposit(goal: SavingsGoalEntity, addedAmount: Double) {
        viewModelScope.launch {
            val serverId = goal.serverId ?: UUID.randomUUID().toString()
            val status = if (goal.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"
            val updated = goal.copy(
                currentAmount = goal.currentAmount + addedAmount,
                serverId = serverId,
                syncStatus = status,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveSavingsGoal(updated)
            triggerBackgroundSync()
        }
    }

    fun addCategory(name: String, colorHex: String) {
        viewModelScope.launch {
            repository.addCategory(
                CategoryEntity(
                    name = name,
                    iconName = "Star",
                    colorHex = colorHex,
                    type = TransactionType.EXPENSE,
                    isDefault = false
                )
            )
        }
    }

    // --- AUTO-PAY & RECURRING BILL REMINDER METHODS ---
    fun saveAutoPayReminder(
        title: String,
        amount: Double,
        category: String,
        paymentMethod: String,
        frequency: String = "MONTHLY",
        dueDayOfMonth: Int = 1,
        nextDueDateMillis: Long = System.currentTimeMillis(),
        isAutoDebit: Boolean = true,
        reminderDaysBefore: Int = 2,
        notes: String = "",
        reminderId: Long = 0L
    ) {
        viewModelScope.launch {
            val existing = if (reminderId > 0L) repository.getAutoPayReminderById(reminderId) else null
            val preservedServerId = existing?.serverId ?: UUID.randomUUID().toString()
            val status = if (existing?.serverId != null) "PENDING_UPDATE" else "PENDING_CREATE"

            val reminder = AutoPayReminderEntity(
                id = reminderId,
                title = title.trim(),
                amount = amount,
                category = category,
                paymentMethod = paymentMethod,
                frequency = frequency,
                dueDayOfMonth = dueDayOfMonth,
                nextDueDateMillis = nextDueDateMillis,
                isAutoDebit = isAutoDebit,
                reminderDaysBefore = reminderDaysBefore,
                financeScope = FinanceScope.PERSONAL,
                familyId = null,
                isPaused = existing?.isPaused ?: false,
                lastPaidDateMillis = existing?.lastPaidDateMillis,
                notes = notes.trim(),
                serverId = preservedServerId,
                syncStatus = status,
                updatedAt = System.currentTimeMillis()
            )
            repository.saveAutoPayReminder(reminder)
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
            viewModelScope.launch(Dispatchers.IO) {
                ReminderAlertEngine.checkAndNotify(getApplication())
            }
            triggerBackgroundSync()
        }
    }

    fun deleteAutoPayReminder(reminder: AutoPayReminderEntity) {
        viewModelScope.launch {
            repository.deleteAutoPayReminder(reminder)
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
            triggerBackgroundSync()
        }
    }

    fun togglePauseAutoPayReminder(reminder: AutoPayReminderEntity) {
        viewModelScope.launch {
            repository.togglePauseAutoPayReminder(reminder.id, !reminder.isPaused)
            triggerBackgroundSync()
        }
    }

    fun markAutoPayPaidAndLogTransaction(reminder: AutoPayReminderEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val tx = TransactionEntity(
                title = reminder.title,
                amount = reminder.amount,
                type = TransactionType.EXPENSE,
                category = reminder.category,
                categoryIconName = CategoryIconHelper.iconNameForCategory(reminder.category),
                paymentMethod = reminder.paymentMethod,
                dateMillis = now,
                note = if (reminder.notes.isNotBlank()) "Auto-Pay: ${reminder.notes}" else "Auto-Pay: ${reminder.title}",
                financeScope = FinanceScope.PERSONAL,
                familyId = null,
                createdByUserId = currentUserId,
                serverId = UUID.randomUUID().toString(),
                syncStatus = "PENDING_CREATE",
                updatedAt = now
            )
            repository.insertTransaction(tx)

            val cal = Calendar.getInstance().apply { timeInMillis = reminder.nextDueDateMillis }
            when (reminder.frequency.uppercase()) {
                "WEEKLY" -> cal.add(Calendar.WEEK_OF_YEAR, 1)
                "QUARTERLY" -> cal.add(Calendar.MONTH, 3)
                "YEARLY" -> cal.add(Calendar.YEAR, 1)
                "ONE_TIME" -> repository.togglePauseAutoPayReminder(reminder.id, true)
                else -> cal.add(Calendar.MONTH, 1)
            }
            val nextDate = cal.timeInMillis
            repository.markAutoPayPaid(reminder.id, nextDate, now)
            triggerBackgroundSync()
        }
    }

    // --- VOICE ASSISTANT ---
    fun openVoiceDialog() {
        _voiceDialogShowing.value = true
        _parsedVoice.value = null
        _voiceUserEditing.value = false
        _voiceChatMessages.value = emptyList()
    }

    fun closeVoiceDialog() {
        _voiceDialogShowing.value = false
        _voiceProcessing.value = false
        _parsedVoice.value = null
        _voiceUserEditing.value = false
    }

    fun markVoiceEditingStarted() {
        _voiceUserEditing.value = true
    }

    fun processVoicePrompt(promptText: String) {
        val trimmed = promptText.trim()
        if (trimmed.isBlank()) return

        val reqId = UUID.randomUUID().toString()
        _voiceUserEditing.value = false
        val instantPreParsed = GeminiAiService.fallbackParseVoiceCommand(trimmed).copy(requestId = reqId)
        _parsedVoice.value = instantPreParsed
        _voiceProcessing.value = false

        viewModelScope.launch {
            try {
                val parsed = GeminiAiService.parseVoiceCommand(trimmed)
                // Only update if user is still reviewing the exact same voice request and hasn't edited details
                if (parsed.amount > 0 && _parsedVoice.value?.requestId == reqId && !_voiceUserEditing.value) {
                    _parsedVoice.value = parsed.copy(requestId = reqId)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun processAudioPrompt(audioBase64: String) {
        viewModelScope.launch {
            _voiceUserEditing.value = false
            _voiceProcessing.value = true
            val parsed = GeminiAiService.parseAudioCommand(audioBase64)
            _parsedVoice.value = parsed
            _voiceProcessing.value = false
        }
    }

    fun confirmVoiceExpense() {
        val parsed = _parsedVoice.value ?: return
        addTransaction(
            title = parsed.title,
            amount = parsed.amount,
            type = parsed.type,
            category = parsed.category,
            paymentMethod = parsed.paymentMethod,
            note = parsed.note
        )
        closeVoiceDialog()
    }

    fun confirmVoiceExpenseWithEdits(
        title: String,
        amount: Double,
        type: TransactionType = TransactionType.EXPENSE,
        category: String,
        paymentMethod: String
    ) {
        val parsed = _parsedVoice.value
        addTransaction(
            title = title.ifBlank { parsed?.title ?: (if (type == TransactionType.INCOME) "Income" else "Expense") },
            amount = if (amount > 0) amount else (parsed?.amount ?: 0.0),
            type = type,
            category = category.ifBlank { parsed?.category ?: (if (type == TransactionType.INCOME) "Salary & Income" else "Food & Dining") },
            paymentMethod = paymentMethod.ifBlank { parsed?.paymentMethod ?: "UPI" },
            note = parsed?.note ?: "Voice Entry"
        )
        closeVoiceDialog()
    }

    fun clearVoiceAssistantHistory() {
        _voiceChatMessages.value = emptyList()
        _parsedVoice.value = null
        _voiceUserEditing.value = false
        _latestAssistantResponse.value = null
    }

    fun openUpiDialog() {
        _upiDialogShowing.value = true
    }

    fun closeUpiDialog() {
        _upiDialogShowing.value = false
    }

    fun openUpiScanDialog() {
        _upiScanDialogShowing.value = true
    }

    fun closeUpiScanDialog() {
        _upiScanDialogShowing.value = false
    }

    fun openReceiptDialog() {
        _receiptDialogShowing.value = true
        _parsedReceipt.value = null
    }

    fun closeReceiptDialog() {
        _receiptDialogShowing.value = false
        _receiptProcessing.value = false
        _parsedReceipt.value = null
    }

    fun processReceiptText(sampleReceiptText: String) {
        viewModelScope.launch {
            _receiptProcessing.value = true
            val parsed = GeminiAiService.parseReceiptOcr(sampleReceiptText)
            _parsedReceipt.value = parsed
            _receiptProcessing.value = false
        }
    }

    fun getReceiptForTransaction(transactionId: Long): Flow<ReceiptEntity?> = repository.getReceiptForTransaction(transactionId)
    fun getItemsForTransaction(transactionId: Long): Flow<List<ReceiptItemEntity>> = repository.getItemsForTransaction(transactionId)

    fun saveReceiptExpense(
        merchant: String,
        amount: Double,
        category: String,
        paymentMethod: String,
        dateStr: String,
        timeStr: String?,
        receiptNumber: String?,
        subtotal: Double,
        discount: Double,
        tax: Double,
        items: List<ReceiptItemEntity>,
        imageUri: String?,
        rawText: String?
    ) {
        viewModelScope.launch {
            val iconName = CategoryIconHelper.iconNameForCategory(category)
            val receiptNote = if (items.isNotEmpty()) "${items.size} item${if (items.size > 1) "s" else ""}: " + items.take(2).joinToString { it.name } else "Receipt Scan"

            val tx = TransactionEntity(
                title = merchant.ifBlank { "Receipt Purchase" },
                amount = amount,
                type = TransactionType.EXPENSE,
                category = category,
                categoryIconName = iconName,
                paymentMethod = paymentMethod,
                note = receiptNote,
                receiptImageUri = imageUri,
                financeScope = FinanceScope.PERSONAL,
                familyId = null,
                createdByUserId = currentUserId,
                dateMillis = System.currentTimeMillis(),
                serverId = UUID.randomUUID().toString(),
                syncStatus = "PENDING_CREATE"
            )
            val txId: Long = repository.addTransaction(tx)
            val receipt = ReceiptEntity(
                transactionId = txId,
                merchantName = merchant.ifBlank { "Receipt Purchase" },
                receiptNumber = receiptNumber,
                receiptDate = dateStr,
                receiptTime = timeStr,
                subtotal = subtotal,
                discount = discount,
                tax = tax,
                total = amount,
                currency = _currencySymbol.value,
                paymentMethod = paymentMethod,
                imageUri = imageUri,
                rawText = rawText
            )
            val mappedItems = items.map { it.copy(transactionId = txId) }
            repository.saveReceiptWithItems(receipt, mappedItems)
            closeReceiptDialog()
            triggerBackgroundSync()
        }
    }

    fun updateTransactionAndReceipt(
        transaction: TransactionEntity,
        receipt: ReceiptEntity?,
        items: List<ReceiptItemEntity>
    ) {
        viewModelScope.launch {
            updateTransaction(transaction)
            if (receipt != null) {
                repository.updateReceiptWithItems(receipt, items)
            }
        }
    }

    /**
     * Soft-deletes immediately for responsive UI and holds off syncing until the Snackbar Undo window closes.
     */
    fun deleteTransactionWithReceipt(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.softDeleteForUndo(transaction)
        }
    }

    fun commitDeletedTransaction() {
        triggerBackgroundSync()
    }

    fun undoDeleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.restoreTransaction(transaction)
            triggerBackgroundSync()
        }
    }

    fun confirmReceiptExpense() {
        val parsed = _parsedReceipt.value ?: return
        val itemsList = parsed.items.map {
            ReceiptItemEntity(
                transactionId = 0,
                name = it.name,
                quantity = it.quantity,
                unitPrice = it.unitPrice,
                totalPrice = it.totalPrice
            )
        }
        saveReceiptExpense(
            merchant = parsed.merchantName,
            amount = parsed.totalAmount,
            category = parsed.category,
            paymentMethod = parsed.paymentMethod,
            dateStr = parsed.dateString,
            timeStr = parsed.timeString,
            receiptNumber = parsed.receiptNumber,
            subtotal = parsed.subtotal,
            discount = parsed.discount,
            tax = parsed.tax,
            items = itemsList,
            imageUri = null,
            rawText = parsed.rawText
        )
    }

    fun generateAiCoachAdvice(totalIncome: Double, totalExpense: Double, topCat: String) {
        viewModelScope.launch {
            _aiCoachLoading.value = true
            val advice = GeminiAiService.getFinancialCoachAdvice(totalIncome, totalExpense, topCat)
            _aiCoachAdvice.value = advice
            _aiCoachLoading.value = false
        }
    }

    fun addScannedItem(barcodeValue: String, productName: String) {
        viewModelScope.launch {
            repository.addScannedItem(
                ScannedItemEntity(
                    barcodeValue = barcodeValue,
                    productName = productName
                )
            )
        }
    }

    fun deleteScannedItem(item: ScannedItemEntity) {
        viewModelScope.launch {
            repository.deleteScannedItem(item)
        }
    }

    fun openScannedBarcodeSheet(barcodeValue: String) {
        _scannedBarcodeValue.value = barcodeValue
        _isScannedBarcodeSheetShowing.value = true
    }

    fun closeScannedBarcodeSheet() {
        _isScannedBarcodeSheetShowing.value = false
        _scannedBarcodeValue.value = null
    }

    fun clearAllLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.wipeLocalDatabase()
            repository.reseedDefaultCategories()
            MerchantCategoryRules.clear()
            PendingUpiDetectionStore.clearAll(getApplication())
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun exportTransactionsCsv(): String {
        val txs = uiState.value.allTransactions.ifEmpty { uiState.value.transactions }
        val sb = StringBuilder("Date,Title,Category,Type,Amount,PaymentMethod,Note\n")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        txs.forEach { tx ->
            val dateStr = sdf.format(Date(tx.dateMillis))
            sb.append("\"$dateStr\",\"${tx.title}\",\"${tx.category}\",\"${tx.type.name}\",${tx.amount},\"${tx.paymentMethod}\",\"${tx.note}\"\n")
        }
        return sb.toString()
    }
}

private data class BaseData(
    val transactions: List<TransactionEntity>,
    val categories: List<CategoryEntity>,
    val budgets: List<BudgetEntity>,
    val savingsGoals: List<SavingsGoalEntity>,
    val scannedItems: List<ScannedItemEntity>,
    val autoPayReminders: List<AutoPayReminderEntity> = emptyList()
)

private data class FilterState(
    val search: String,
    val type: TransactionType?,
    val category: String?,
    val currency: String,
    val tab: Int,
    val isBalanceHidden: Boolean
)

private data class VoiceState(
    val voiceShow: Boolean,
    val voiceProc: Boolean,
    val voiceParsed: ParsedVoiceExpense?,
    val voiceMessages: List<VoiceChatMessage>,
    val latestAssistantResponse: VoiceAssistantResponse?,
    val upiShow: Boolean,
    val upiScanShow: Boolean,
    val detectedPayment: ExtractedUpiPayment? = null
)

private data class AiState(
    val receiptShow: Boolean,
    val receiptProc: Boolean,
    val receiptParsed: ParsedReceipt?,
    val coachAdvice: String?,
    val coachLoading: Boolean
)

private data class ScannedItemState(
    val barcodeValue: String?,
    val sheetShowing: Boolean
)
