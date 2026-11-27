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
import com.zenith.app.data.network.ActiveFamilyBundle
import com.zenith.app.data.network.AuthResult
import com.zenith.app.data.network.FamilyRealtimeBroadcastPayload
import com.zenith.app.data.network.FamilyRealtimeEvents
import com.zenith.app.data.network.FamilyRealtimeManager
import com.zenith.app.data.network.FamilySupabaseClient
import com.zenith.app.data.network.SupabaseClientConfig
import com.zenith.app.data.repository.CashFlowRepository
import com.zenith.app.data.repository.FamilyRepository
import com.zenith.app.data.repository.FamilyTxAttribution
import com.zenith.app.data.repository.ScopedMappers
import com.zenith.app.data.notifications.ReminderAlertEngine
import com.zenith.app.data.upi.ExtractedUpiPayment
import com.zenith.app.data.upi.MerchantCategoryRules
import com.zenith.app.data.upi.PendingUpiDetectionStore
import com.zenith.app.data.upi.RecurringPatternDetector
import com.zenith.app.data.upi.RecurringSuggestion
import com.zenith.app.data.upi.UpiNotificationListenerService
import com.zenith.app.data.upi.UpiPaymentBus
import com.zenith.app.data.util.CategoryIconHelper
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyInvitationInfo
import com.zenith.app.domain.FamilyMemberInfo
import com.zenith.app.domain.FamilyMembershipStatus
import com.zenith.app.domain.FamilyRole
import com.zenith.app.domain.FinancialContext
import com.zenith.app.domain.FinancialMode
import com.zenith.app.widget.ZenithSummaryWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    val autoPayReminders: List<AutoPayReminderEntity> = emptyList(),
    val activeMode: FinancialMode = FinancialMode.PERSONAL,
    val activeFamily: FamilyInfo? = null,
    val activeFamilyMembers: List<FamilyMemberInfo> = emptyList(),
    val activeFamilyRole: FamilyRole? = null,
    val txAttributionByServerId: Map<String, FamilyTxAttribution> = emptyMap(),
    val familyMessages: List<FamilyMessageEntity> = emptyList(),
    val selectedFilterMemberId: String? = null,
    val selectedFilterLabel: String? = null,
    val isFamilyRealtimeConnected: Boolean = false
)

class CashFlowViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CashFlowRepository
    private val familyRepository: FamilyRepository
    private val familyRealtimeManager = FamilyRealtimeManager()

    private val prefs = application.getSharedPreferences("zenith_prefs", android.content.Context.MODE_PRIVATE)

    private val _searchQuery = MutableStateFlow("")
    private val _filterType = MutableStateFlow<TransactionType?>(null)
    private val _filterCategory = MutableStateFlow<String?>(null)
    private val _filterMemberId = MutableStateFlow<String?>(null)
    private val _filterLabel = MutableStateFlow<String?>(null)
    private val _currencySymbol = MutableStateFlow(prefs.getString("currency_symbol", "₹") ?: "₹")
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

    private val authServiceInitialized = MutableStateFlow(false)
    private val authSessionSyncMutex = Mutex()
    private var voiceRefineJob: Job? = null

    val currentMonthYear: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            return sdf.format(Date())
        }

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

    private fun isValidUuidString(uid: String?): Boolean {
        if (uid.isNullOrBlank()) return false
        return runCatching { UUID.fromString(uid) }.isSuccess
    }

    private fun familyEnabledPrefKey(uid: String): String =
        if (isValidUuidString(uid)) "family_mode_enabled_$uid" else "family_mode_enabled_guest"

    private val _financialContext = MutableStateFlow(
        FinancialContext(
            mode = FinancialMode.PERSONAL,
            isFamilyEnabled = prefs.getBoolean(
                familyEnabledPrefKey(_userSupabaseId.value ?: "local_user_1"),
                false
            ),
            currentUserId = _userSupabaseId.value ?: "local_user_1",
            familyMembershipStatus = if (prefs.getBoolean(
                    familyEnabledPrefKey(_userSupabaseId.value ?: "local_user_1"),
                    false
                )
            ) {
                FamilyMembershipStatus.NONE
            } else {
                FamilyMembershipStatus.DISABLED
            }
        )
    )
    val financialContext: StateFlow<FinancialContext> = _financialContext.asStateFlow()

    init {
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
        familyRepository = FamilyRepository(database.familyDao())

        viewModelScope.launch(Dispatchers.IO) {
            MerchantCategoryRules.init(application)
            PendingUpiDetectionStore.init(application)
            authService.restoreSession()
            authService.currentUser.collect { user ->
                if (user != null) {
                    bindAuthenticatedUserSession(
                        userId = user.id,
                        email = user.email ?: "",
                        fullName = user.fullName ?: user.email?.substringBefore("@").orEmpty(),
                        isGuest = false
                    )
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
                        val ctx = _financialContext.value
                        if (ctx.isFamilyEnabled && !ctx.activeFamilyId.isNullOrBlank() && ctx.familyMembershipStatus == FamilyMembershipStatus.ACTIVE) {
                            syncAndBroadcastFamilyWorkspace(ctx.activeFamilyId, ctx.activeMembers)
                        }
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
            repository.commitPendingUndoDeletes()
            repository.deduplicateTransactions()
            ReminderAlertEngine.checkAndNotify(getApplication())
            syncFamilyEnabledPreferenceForUser(currentUserId)
        }

        // Bind private Supabase Realtime broadcast channel lifecycle to verified active family
        viewModelScope.launch(Dispatchers.IO) {
            _financialContext.collect { ctx ->
                val famId = ctx.activeFamilyId
                if (ctx.isFamilyEnabled && !famId.isNullOrBlank() &&
                    ctx.familyMembershipStatus == FamilyMembershipStatus.ACTIVE &&
                    SupabaseClientConfig.isConfigured &&
                    isValidUuidString(currentUserId)
                ) {
                    familyRealtimeManager.connectToFamily(
                        familyId = famId,
                        onRemoteEvent = { event ->
                            handleIncomingFamilyBroadcast(event)
                        },
                        onReconcileNeeded = { reconnectedFamilyId ->
                            reconcileFamilyFromCloud(reconnectedFamilyId)
                        }
                    )
                } else {
                    familyRealtimeManager.disconnect()
                }
            }
        }
    }

    private suspend fun handleIncomingFamilyBroadcast(payload: FamilyRealtimeBroadcastPayload) {
        if (payload.actorUserId == currentUserId) return
        when (payload.event) {
            FamilyRealtimeEvents.MEMBER_JOINED,
            FamilyRealtimeEvents.MEMBER_REMOVED -> {
                refreshFamilyMembership()
                reconcileFamilyFromCloud(payload.familyId)
            }
            else -> {
                reconcileFamilyFromCloud(payload.familyId)
            }
        }
    }

    private suspend fun reconcileFamilyFromCloud(familyId: String) {
        val ctx = _financialContext.value
        if (!ctx.isFamilyEnabled || ctx.activeFamilyId != familyId) return
        familyRepository.syncFamilyWorkspace(familyId, ctx.activeMembers)
    }

    private suspend fun syncAndBroadcastFamilyWorkspace(
        familyId: String,
        members: List<FamilyMemberInfo>
    ): Boolean {
        val result = familyRepository.syncFamilyWorkspace(familyId, members)
        if (result.syncedOk) {
            familyRealtimeManager.emitSyncResultBroadcasts(familyId, currentUserId, result)
        }
        return result.syncedOk
    }

    override fun onCleared() {
        familyRealtimeManager.disconnect()
        super.onCleared()
    }

    fun setFamilyFeatureEnabled(enabled: Boolean) {
        val uid = currentUserId
        prefs.edit().putBoolean(familyEnabledPrefKey(uid), enabled).apply()
        if (!enabled) {
            clearScopedTransientState()
            _financialContext.value = _financialContext.value.copy(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = false,
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.DISABLED,
                membershipError = null
            )
            return
        }

        _financialContext.value = _financialContext.value.copy(
            isFamilyEnabled = true,
            currentUserId = uid,
            familyMembershipStatus = if (syncEngine.isValidUuid(uid) && SupabaseClientConfig.isConfigured) {
                FamilyMembershipStatus.LOADING
            } else {
                FamilyMembershipStatus.NONE
            },
            membershipError = null
        )
        refreshFamilyMembership()
    }

    private fun clearScopedTransientState() {
        _searchQuery.value = ""
        _filterType.value = null
        _filterCategory.value = null
        _filterMemberId.value = null
        _filterLabel.value = null
        _aiCoachAdvice.value = null
    }

    fun selectFinancialMode(targetMode: FinancialMode): Boolean {
        val currentCtx = _financialContext.value
        if (targetMode == FinancialMode.PERSONAL) {
            if (currentCtx.mode != FinancialMode.PERSONAL) {
                clearScopedTransientState()
            }
            _financialContext.value = currentCtx.copy(mode = FinancialMode.PERSONAL)
            return true
        }
        if (!currentCtx.isFamilyEnabled) return false
        if (currentCtx.familyMembershipStatus != FamilyMembershipStatus.ACTIVE || currentCtx.activeFamilyId.isNullOrBlank()) {
            return false
        }
        if (currentCtx.mode != FinancialMode.FAMILY) {
            clearScopedTransientState()
        }
        _financialContext.value = currentCtx.copy(mode = FinancialMode.FAMILY)
        triggerFamilyBackgroundSync()
        return true
    }

    /**
     * Test-only / explicit workspace override for unit testing mode isolation cleanly
     * without requiring live network auth.
     */
    internal fun applyLocalCachedFamilyContextForTest(
        family: FamilyInfo,
        members: List<FamilyMemberInfo>,
        role: FamilyRole,
        enterFamilyMode: Boolean = true
    ) {
        val uid = currentUserId
        prefs.edit().putBoolean(familyEnabledPrefKey(uid), true).apply()
        clearScopedTransientState()
        _financialContext.value = FinancialContext(
            mode = if (enterFamilyMode) FinancialMode.FAMILY else FinancialMode.PERSONAL,
            isFamilyEnabled = true,
            activeFamilyId = family.id,
            activeFamily = family,
            activeMembers = members,
            currentUserId = uid,
            membershipRole = role,
            familyMembershipStatus = FamilyMembershipStatus.ACTIVE,
            membershipError = null
        )
    }

    fun refreshFamilyMembership() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshFamilyMembershipSuspend()
        }
    }

    private suspend fun refreshFamilyMembershipSuspend() {
        val uid = currentUserId
        if (!_financialContext.value.isFamilyEnabled) {
            _financialContext.value = _financialContext.value.copy(
                mode = FinancialMode.PERSONAL,
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.DISABLED
            )
            return
        }
        if (!_isAuthenticated.value || _isGuestMode.value || !syncEngine.isValidUuid(uid)) {
            _financialContext.value = _financialContext.value.copy(
                mode = FinancialMode.PERSONAL,
                currentUserId = uid,
                activeFamilyId = null,
                activeFamily = null,
                activeMembers = emptyList(),
                membershipRole = null,
                familyMembershipStatus = FamilyMembershipStatus.NONE,
                membershipError = null
            )
            return
        }

        // Hydrate cached offline membership first if present so offline restarts retain safe cache
        val cachedFamily = familyRepository.getActiveFamilyForUserOnce(uid)
        val cachedMembers = cachedFamily?.let { familyRepository.getActiveMembersOnce(it.id) }.orEmpty()
        val cachedSelf = cachedMembers.firstOrNull { it.userId == uid }
        if (cachedFamily != null && cachedSelf != null) {
            val mappedMembers = cachedMembers.map { m ->
                FamilyMemberInfo(
                    id = m.id,
                    familyId = m.familyId,
                    userId = m.userId,
                    displayName = m.displayName,
                    avatarEmoji = m.avatarEmoji,
                    avatarColor = m.avatarColor,
                    role = FamilyRole.fromString(m.role),
                    status = m.status,
                    memberLabel = m.memberLabel
                )
            }
            _financialContext.value = _financialContext.value.copy(
                currentUserId = uid,
                activeFamilyId = cachedFamily.id,
                activeFamily = FamilyInfo(
                    id = cachedFamily.id,
                    name = cachedFamily.name,
                    avatarEmoji = cachedFamily.avatarEmoji,
                    ownerId = cachedFamily.ownerId.takeIf { it.isNotBlank() },
                    monthlyBudgetLimit = cachedFamily.monthlyBudgetLimit,
                    familyLabel = cachedFamily.familyLabel
                ),
                activeMembers = mappedMembers,
                membershipRole = FamilyRole.fromString(cachedSelf.role),
                familyMembershipStatus = FamilyMembershipStatus.ACTIVE,
                membershipError = null
            )
        } else if (!SupabaseClientConfig.isConfigured) {
            _financialContext.value = _financialContext.value.copy(
                mode = FinancialMode.PERSONAL,
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.NONE,
                membershipError = null
            )
            return
        } else {
            _financialContext.value = _financialContext.value.copy(
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.LOADING,
                membershipError = null
            )
        }

        if (!SupabaseClientConfig.isConfigured) {
            return
        }

        val result = FamilySupabaseClient.fetchMyActiveFamily()
        result.onSuccess { bundle ->
            if (bundle == null) {
                familyRepository.wipeAllFamilyData()
                clearFamilyMembershipState()
            } else {
                familyRepository.cacheActiveBundle(bundle)
                applyActiveBundle(bundle, enterFamilyMode = _financialContext.value.mode == FinancialMode.FAMILY)
                familyRepository.syncFamilyWorkspace(bundle.family.id, bundle.members)
            }
        }.onFailure { err ->
            if (cachedFamily != null && cachedSelf != null) {
                _financialContext.value = _financialContext.value.copy(
                    membershipError = err.message ?: "Offline mode — showing cached family ledger."
                )
            } else {
                _financialContext.value = _financialContext.value.copy(
                    mode = FinancialMode.PERSONAL,
                    familyMembershipStatus = FamilyMembershipStatus.ERROR,
                    membershipError = err.message ?: "Unable to verify family membership."
                )
            }
        }
    }

    private fun syncFamilyEnabledPreferenceForUser(uid: String) {
        val enabled = prefs.getBoolean(familyEnabledPrefKey(uid), false)
        if (!enabled) {
            _financialContext.value = FinancialContext(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = false,
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.DISABLED
            )
        } else {
            _financialContext.value = _financialContext.value.copy(
                isFamilyEnabled = true,
                currentUserId = uid
            )
            refreshFamilyMembership()
        }
    }

    private suspend fun syncFamilyEnabledPreferenceAndMembershipSuspend(uid: String) {
        val enabled = prefs.getBoolean(familyEnabledPrefKey(uid), false)
        if (!enabled) {
            _financialContext.value = FinancialContext(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = false,
                currentUserId = uid,
                familyMembershipStatus = FamilyMembershipStatus.DISABLED
            )
        } else {
            _financialContext.value = _financialContext.value.copy(
                isFamilyEnabled = true,
                currentUserId = uid
            )
            refreshFamilyMembershipSuspend()
        }
    }

    private val _pendingInviteInput = MutableStateFlow<String?>(null)
    val pendingInviteInput: StateFlow<String?> = _pendingInviteInput.asStateFlow()

    private val familyActionMutex = Mutex()

    fun onFamilyInviteLinkReceived(rawInvite: String) {
        val clean = rawInvite.trim()
        if (clean.isBlank()) return
        if (!_financialContext.value.isFamilyEnabled) {
            setFamilyFeatureEnabled(true)
        }
        _pendingInviteInput.value = clean
    }

    fun consumePendingInviteInput() {
        _pendingInviteInput.value = null
    }

    private fun validateAuthenticatedFamilyActor(): Result<String> {
        val uid = currentUserId
        if (!_isAuthenticated.value || _isGuestMode.value || !isValidUuidString(uid)) {
            return Result.failure(IllegalStateException("Sign in with a verified Zenith account to continue."))
        }
        return Result.success(uid)
    }

    private fun applyActiveBundle(bundle: ActiveFamilyBundle, enterFamilyMode: Boolean) {
        val uid = currentUserId
        prefs.edit().putBoolean(familyEnabledPrefKey(uid), true).apply()
        _financialContext.value = FinancialContext(
            mode = if (enterFamilyMode) FinancialMode.FAMILY else _financialContext.value.mode,
            isFamilyEnabled = true,
            activeFamilyId = bundle.family.id,
            activeFamily = bundle.family,
            activeMembers = bundle.members,
            currentUserId = uid,
            membershipRole = bundle.membership.role,
            familyMembershipStatus = FamilyMembershipStatus.ACTIVE,
            membershipError = null
        )
    }

    private fun clearFamilyMembershipState() {
        val uid = currentUserId
        _financialContext.value = _financialContext.value.copy(
            mode = FinancialMode.PERSONAL,
            activeFamilyId = null,
            activeFamily = null,
            activeMembers = emptyList(),
            currentUserId = uid,
            membershipRole = null,
            familyMembershipStatus = if (_financialContext.value.isFamilyEnabled) {
                FamilyMembershipStatus.NONE
            } else {
                FamilyMembershipStatus.DISABLED
            },
            membershipError = null
        )
    }

    fun createFamilyAtomic(
        name: String,
        avatarEmoji: String,
        monthlyBudgetLimit: Double,
        idempotencyKey: String,
        familyLabel: String = "",
        creatorName: String = "",
        creatorEmoji: String = "🙂",
        creatorLabel: String = "",
        onComplete: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val res = familyActionMutex.withLock {
                val authCheck = validateAuthenticatedFamilyActor()
                if (authCheck.isFailure) {
                    return@withLock Result.failure<String>(authCheck.exceptionOrNull()!!)
                }
                val cleanName = name.trim()
                if (cleanName.isBlank()) {
                    return@withLock Result.failure(IllegalArgumentException("Family name is required."))
                }
                val result = FamilySupabaseClient.createFamilyAtomic(
                    name = cleanName,
                    avatarEmoji = avatarEmoji,
                    creatorName = creatorName.trim().ifBlank { currentUserName },
                    idempotencyKey = idempotencyKey,
                    monthlyBudgetLimit = monthlyBudgetLimit,
                    familyLabel = familyLabel.trim(),
                    creatorLabel = creatorLabel.trim(),
                    creatorEmoji = creatorEmoji.ifBlank { "🙂" }
                )
                result.map { bundle ->
                    familyRepository.cacheActiveBundle(bundle)
                    applyActiveBundle(bundle, enterFamilyMode = true)
                    syncAndBroadcastFamilyWorkspace(bundle.family.id, bundle.members)
                    familyRealtimeManager.emitCommittedEvent(
                        FamilyRealtimeBroadcastPayload(
                            event = FamilyRealtimeEvents.MEMBER_JOINED,
                            familyId = bundle.family.id,
                            actorUserId = currentUserId,
                            entityId = currentUserId
                        )
                    )
                    bundle.family.id
                }
            }
            onComplete(res)
        }
    }

    fun acceptFamilyInvitation(
        codeOrLink: String,
        displayName: String,
        avatarEmoji: String,
        memberLabel: String = "",
        onComplete: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val res = familyActionMutex.withLock {
                val authCheck = validateAuthenticatedFamilyActor()
                if (authCheck.isFailure) {
                    return@withLock Result.failure<String>(authCheck.exceptionOrNull()!!)
                }
                val clean = codeOrLink.trim()
                if (clean.isBlank()) {
                    return@withLock Result.failure(IllegalArgumentException("Invitation code or link is required."))
                }
                val result = FamilySupabaseClient.acceptFamilyInvitation(
                    codeOrToken = clean,
                    displayName = displayName.ifBlank { currentUserName },
                    avatarEmoji = avatarEmoji,
                    memberLabel = memberLabel.trim()
                )
                result.map { bundle ->
                    familyRepository.cacheActiveBundle(bundle)
                    applyActiveBundle(bundle, enterFamilyMode = true)
                    syncAndBroadcastFamilyWorkspace(bundle.family.id, bundle.members)
                    familyRealtimeManager.emitCommittedEvent(
                        FamilyRealtimeBroadcastPayload(
                            event = FamilyRealtimeEvents.MEMBER_JOINED,
                            familyId = bundle.family.id,
                            actorUserId = currentUserId,
                            entityId = currentUserId
                        )
                    )
                    bundle.family.id
                }
            }
            onComplete(res)
        }
    }

    fun updateFamilyProfile(
        name: String,
        avatarEmoji: String,
        familyLabel: String,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val cleanName = name.trim()
            if (cleanName.isBlank()) {
                return@launch onComplete(Result.failure(IllegalArgumentException("Family name cannot be blank.")))
            }
            val res = FamilySupabaseClient.updateFamilyProfile(famId, cleanName, avatarEmoji, familyLabel)
            if (res.isSuccess) {
                refreshFamilyMembership()
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_JOINED, famId, currentUserId)
                )
            }
            onComplete(res)
        }
    }

    fun updateFamilyMemberProfile(
        targetUserId: String,
        displayName: String,
        memberLabel: String,
        avatarEmoji: String? = null,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val cleanName = displayName.trim()
            if (cleanName.isBlank()) {
                return@launch onComplete(Result.failure(IllegalArgumentException("Member name cannot be blank.")))
            }
            val res = FamilySupabaseClient.updateFamilyMemberProfile(famId, targetUserId, cleanName, memberLabel, avatarEmoji)
            if (res.isSuccess) {
                refreshFamilyMembership()
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_JOINED, famId, currentUserId, targetUserId)
                )
            }
            onComplete(res)
        }
    }

    private fun cacheFamilyInvitation(invite: FamilyInvitationInfo) {
        prefs.edit()
            .putString("cached_inv_id_${invite.familyId}", invite.id)
            .putString("cached_inv_code_${invite.familyId}", invite.inviteCode)
            .putString("cached_inv_token_${invite.familyId}", invite.inviteToken)
            .putString("cached_inv_role_${invite.familyId}", invite.role.name)
            .apply()
    }

    fun getCachedFamilyInvitation(familyId: String): FamilyInvitationInfo? {
        val code = prefs.getString("cached_inv_code_$familyId", null)?.trim().orEmpty()
        val token = prefs.getString("cached_inv_token_$familyId", null)?.trim().orEmpty()
        if (code.isBlank() || token.isBlank()) return null
        val id = prefs.getString("cached_inv_id_$familyId", null) ?: code
        val role = prefs.getString("cached_inv_role_$familyId", null)
            ?.let { FamilyRole.fromString(it) } ?: FamilyRole.MEMBER
        return FamilyInvitationInfo(
            id = id,
            familyId = familyId,
            inviteCode = code,
            inviteToken = token,
            role = role
        )
    }

    fun getOrCreateQuickFamilyInvitation(onComplete: (Result<FamilyInvitationInfo>) -> Unit) {
        val famId = _financialContext.value.activeFamilyId
            ?: return onComplete(Result.failure(IllegalStateException("No active family selected.")))
        getCachedFamilyInvitation(famId)?.let { cached ->
            onComplete(Result.success(cached))
            return
        }
        generateFamilyInvitation("", FamilyRole.MEMBER, onComplete)
    }

    fun generateFamilyInvitation(
        invitedEmail: String,
        role: FamilyRole,
        onComplete: (Result<FamilyInvitationInfo>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family selected.")))
            val res = FamilySupabaseClient.createFamilyInvitation(famId, invitedEmail, role)
            res.onSuccess { cacheFamilyInvitation(it) }
            onComplete(res)
        }
    }

    fun updateFamilyMemberRole(
        targetUserId: String,
        newRole: FamilyRole,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val res = FamilySupabaseClient.updateMemberRole(famId, targetUserId, newRole)
            if (res.isSuccess) {
                refreshFamilyMembership()
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_JOINED, famId, currentUserId, targetUserId)
                )
            }
            onComplete(res)
        }
    }

    fun removeFamilyMember(
        targetUserId: String,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val res = FamilySupabaseClient.removeMember(famId, targetUserId)
            if (res.isSuccess) {
                refreshFamilyMembership()
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_REMOVED, famId, currentUserId, targetUserId)
                )
            }
            onComplete(res)
        }
    }

    fun transferFamilyOwnership(
        newOwnerUserId: String,
        onComplete: (Result<Boolean>) -> Unit
    ) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val res = FamilySupabaseClient.transferOwnership(famId, newOwnerUserId)
            if (res.isSuccess) {
                refreshFamilyMembership()
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_JOINED, famId, currentUserId, newOwnerUserId)
                )
            }
            onComplete(res)
        }
    }

    fun leaveCurrentFamily(onComplete: (Result<Boolean>) -> Unit) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val res = FamilySupabaseClient.leaveFamily(famId)
            if (res.isSuccess) {
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_REMOVED, famId, currentUserId, currentUserId)
                )
                familyRealtimeManager.disconnect()
                familyRepository.wipeAllFamilyData()
                clearFamilyMembershipState()
            }
            onComplete(res)
        }
    }

    fun closeCurrentFamily(onComplete: (Result<Boolean>) -> Unit) {
        viewModelScope.launch {
            val famId = _financialContext.value.activeFamilyId
                ?: return@launch onComplete(Result.failure(IllegalStateException("No active family.")))
            val res = FamilySupabaseClient.closeFamily(famId)
            if (res.isSuccess) {
                familyRealtimeManager.emitCommittedEvent(
                    FamilyRealtimeBroadcastPayload(FamilyRealtimeEvents.MEMBER_REMOVED, famId, currentUserId, famId)
                )
                familyRealtimeManager.disconnect()
                familyRepository.wipeAllFamilyData()
                clearFamilyMembershipState()
            }
            onComplete(res)
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val baseDataState: Flow<BaseData> = _financialContext.flatMapLatest { ctx ->
        if (ctx.isFamilyModeActive) {
            val famId = ctx.activeFamilyId!!
            combine(
                familyRepository.observeFamilyTransactions(famId),
                familyRepository.observeFamilyCategories(famId),
                familyRepository.observeFamilyBudgets(famId),
                familyRepository.observeFamilyMessages(famId)
            ) { famTxs, famCats, famBudgets, famMsgs ->
                val memberLabels = ctx.activeMembers.associate { it.userId to it.memberLabel }
                BaseData(
                    transactions = famTxs.map(ScopedMappers::toUiTransaction),
                    categories = famCats.map(ScopedMappers::toUiCategory),
                    budgets = famBudgets.map(ScopedMappers::toUiBudget),
                    savingsGoals = emptyList(),
                    scannedItems = emptyList(),
                    autoPayReminders = emptyList(),
                    activeMode = FinancialMode.FAMILY,
                    activeFamily = ctx.activeFamily,
                    activeFamilyMembers = ctx.activeMembers,
                    activeFamilyRole = ctx.membershipRole,
                    txAttributionByServerId = famTxs.associate { it.id to ScopedMappers.toAttribution(it, memberLabels) },
                    familyMessages = famMsgs
                )
            }
        } else {
            combine(
                combine(
                    repository.getTransactionsByScope(FinanceScope.PERSONAL),
                    repository.allCategories,
                    repository.getBudgetsByScope(FinanceScope.PERSONAL),
                    ::Triple
                ),
                combine(
                    repository.getGoalsByScope(FinanceScope.PERSONAL),
                    repository.allScannedItems,
                    repository.getAutoPayRemindersByScope(FinanceScope.PERSONAL),
                    ::Triple
                )
            ) { (txList, catList, budgetList), (goalList, scannedList, autoPayList) ->
                BaseData(
                    transactions = txList,
                    categories = catList,
                    budgets = budgetList,
                    savingsGoals = goalList,
                    scannedItems = scannedList,
                    autoPayReminders = autoPayList,
                    activeMode = FinancialMode.PERSONAL,
                    activeFamily = ctx.activeFamily,
                    activeFamilyMembers = ctx.activeMembers,
                    activeFamilyRole = ctx.membershipRole,
                    txAttributionByServerId = emptyMap(),
                    familyMessages = emptyList()
                )
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val recurringSuggestions: StateFlow<List<RecurringSuggestion>> = _financialContext.flatMapLatest { ctx ->
        if (ctx.isFamilyModeActive) {
            flowOf(emptyList())
        } else {
            combine(
                repository.getTransactionsByScope(FinanceScope.PERSONAL),
                repository.getAutoPayRemindersByScope(FinanceScope.PERSONAL),
                _dismissedRecurringKeys
            ) { txs, reminders, dismissed ->
                RecurringPatternDetector.detectSuggestions(
                    transactions = txs,
                    existingReminders = reminders,
                    dismissedKeys = dismissed
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val filterState: Flow<FilterState> = combine(
        combine(_searchQuery, _filterType, _filterCategory, _filterMemberId) { search, type, cat, memberId ->
            QuadFilter(search, type, cat, memberId)
        },
        combine(_currencySymbol, _selectedTab, _isBalanceHidden, _filterLabel) { currency, tab, hidden, label ->
            QuadUiFilter(currency, tab, hidden, label)
        },
        familyRealtimeManager.isConnected
    ) { f1, f2, rtConnected ->
        FilterState(f1.search, f1.type, f1.category, f1.memberId, f2.currency, f2.tab, f2.hidden, f2.label, rtConnected)
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
                    tx.note.contains(filter.search, ignoreCase = true) ||
                    tx.customLabel.contains(filter.search, ignoreCase = true)

            val matchesType = filter.type == null || tx.type == filter.type
            val matchesCat = filter.category == null || tx.category == filter.category
            val matchesMember = filter.memberId == null || run {
                val attr = tx.serverId?.let { base.txAttributionByServerId[it] }
                attr?.paidByUserId == filter.memberId || attr?.createdByUserId == filter.memberId
            }
            val matchesLabel = filter.label.isNullOrBlank() || tx.customLabel.equals(filter.label, ignoreCase = true)

            matchesSearch && matchesType && matchesCat && matchesMember && matchesLabel
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
            autoPayReminders = base.autoPayReminders,
            activeMode = base.activeMode,
            activeFamily = base.activeFamily,
            activeFamilyMembers = base.activeFamilyMembers,
            activeFamilyRole = base.activeFamilyRole,
            txAttributionByServerId = base.txAttributionByServerId,
            familyMessages = base.familyMessages,
            selectedFilterMemberId = filter.memberId,
            selectedFilterLabel = filter.label,
            isFamilyRealtimeConnected = filter.isRealtimeConnected
        )
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
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

    fun setFilterMemberId(memberId: String?) {
        _filterMemberId.value = memberId
    }

    fun setFilterLabel(label: String?) {
        _filterLabel.value = label
    }

    fun setCurrency(symbol: String) {
        _currencySymbol.value = symbol
        prefs.edit().putString("currency_symbol", symbol).apply()
    }

    fun updateUserDisplayName(newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        _activeUserName.value = clean
        prefs.edit().putString("user_name", clean).apply()
        viewModelScope.launch(Dispatchers.IO) {
            val uid = authService.currentUser.value?.id ?: _userSupabaseId.value
            if (!uid.isNullOrBlank()) {
                runCatching {
                    val db = CashFlowDatabase.getDatabase(getApplication())
                    val existing = db.userProfileDao().getProfile(uid)
                    db.userProfileDao().insertProfile(
                        (existing ?: UserProfileEntity(
                            id = uid,
                            fullName = clean,
                            email = _activeUserEmail.value.orEmpty()
                        )).copy(
                            fullName = clean,
                            syncStatus = "PENDING_UPDATE",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    userProfileRepository.syncProfile(uid)
                }
            }
        }
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
        syncFamilyEnabledPreferenceForUser(userId ?: "local_user_1")
    }

    suspend fun clearLocalUserCache() = withContext(Dispatchers.IO) {
        try {
            val app: Application = getApplication()
            val db = CashFlowDatabase.getDatabase(app)
            repository.wipeLocalDatabase()
            familyRepository.wipeAllFamilyData()
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
            syncFamilyEnabledPreferenceAndMembershipSuspend(userId)
            val ctx = _financialContext.value
            if (ctx.isFamilyEnabled && !ctx.activeFamilyId.isNullOrBlank() && ctx.familyMembershipStatus == FamilyMembershipStatus.ACTIVE) {
                val famSyncOk = syncAndBroadcastFamilyWorkspace(ctx.activeFamilyId, ctx.activeMembers)
                if (!famSyncOk) success = false
            }
            if (success) {
                _syncUiState.value = SyncUiState.Success(System.currentTimeMillis())
            } else {
                _syncUiState.value = SyncUiState.Error("Offline or partial sync — local changes saved safely.")
            }
        } catch (e: Exception) {
            Log.e("CashFlowVM", "restoreAllCloudData error: ${e.message}", e)
            _syncUiState.value = SyncUiState.Error("Sync failed: ${e.message}")
            success = false
        }
        success
    }

    private suspend fun bindAuthenticatedUserSession(
        userId: String,
        email: String,
        fullName: String,
        isGuest: Boolean
    ) {
        authSessionSyncMutex.withLock {
            val cleanName = fullName.ifBlank { email.substringBefore("@").ifBlank { "Zenith User" } }
            val prevId = prefs.getString("last_authenticated_user_id", null)
            if (prevId != null && prevId != userId) {
                clearLocalUserCache()
            }
            prefs.edit()
                .putString("last_authenticated_user_id", userId)
                .apply()
            setAuthenticatedUser(email, cleanName, userId = userId, isGuest = isGuest)
            if (syncEngine.isValidUuid(userId)) {
                repository.reassignPersonalTransactionsToUser(userId)
                restoreAllCloudData(userId)
            }
        }
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
                if (userId != null) {
                    bindAuthenticatedUserSession(userId, email.trim(), cleanName, isGuest = false)
                } else {
                    setAuthenticatedUser(email.trim(), cleanName, userId = null, isGuest = false)
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
                if (userId != null) {
                    bindAuthenticatedUserSession(userId, email.trim(), cleanName, isGuest = false)
                } else {
                    setAuthenticatedUser(email.trim(), cleanName, userId = null, isGuest = false)
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
            // 1. Best-effort flush of pending mutations before revoking session / wiping local cache
            val uid = authService.currentUser.value?.id ?: _userSupabaseId.value
            if (!uid.isNullOrBlank() && syncEngine.isValidUuid(uid) && SupabaseClientConfig.isConfigured) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        withTimeoutOrNull(4000L) {
                            repository.commitPendingUndoDeletes()
                            syncEngine.syncAll(uid)
                            val ctx = _financialContext.value
                            if (ctx.isFamilyEnabled && !ctx.activeFamilyId.isNullOrBlank()) {
                                familyRepository.syncFamilyWorkspace(ctx.activeFamilyId, ctx.activeMembers)
                            }
                        }
                    }
                }
            }
            familyRealtimeManager.disconnect()
            authService.signOut()
            prefs.edit()
                .putBoolean("is_authenticated", false)
                .putBoolean("is_guest_mode", false)
                .remove("user_email")
                .remove("user_name")
                .remove("user_supabase_id")
                .remove("last_authenticated_user_id")
                .remove("currency_symbol")
                .apply()
            _isAuthenticated.value = false
            _isGuestMode.value = false
            _activeUserEmail.value = null
            _activeUserName.value = null
            _userSupabaseId.value = null
            _currencySymbol.value = "₹"
            _selectedTab.value = 0
            _voiceDialogShowing.value = false
            _upiDialogShowing.value = false
            _upiScanDialogShowing.value = false
            _receiptDialogShowing.value = false
            _detectedUpiPayment.value = null
            _parsedVoice.value = null
            _parsedReceipt.value = null
            clearScopedTransientState()
            _syncUiState.value = SyncUiState.Idle
            _financialContext.value = FinancialContext(
                mode = FinancialMode.PERSONAL,
                isFamilyEnabled = false,
                currentUserId = "local_user_1",
                familyMembershipStatus = FamilyMembershipStatus.DISABLED
            )
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

    private fun triggerFamilyBackgroundSync() {
        val ctx = _financialContext.value
        val famId = ctx.activeFamilyId ?: return
        if (!ctx.isFamilyEnabled || ctx.familyMembershipStatus != FamilyMembershipStatus.ACTIVE) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                syncAndBroadcastFamilyWorkspace(famId, ctx.activeMembers)
            } catch (_: Exception) {
            }
        }
    }

    fun canEditOrDeleteFamilyTransaction(tx: TransactionEntity): Boolean {
        if (tx.financeScope != FinanceScope.FAMILY) return true
        val ctx = _financialContext.value
        if (ctx.membershipRole?.canAdmin() == true) return true
        val attr = tx.serverId?.let { uiState.value.txAttributionByServerId[it] }
        return tx.createdByUserId == currentUserId || attr?.paidByUserId == currentUserId
    }

    fun sendFamilyChatMessage(
        body: String,
        replyToMessageId: String? = null,
        relatedTransactionId: String? = null,
        messageType: String = if (relatedTransactionId != null) "EXPENSE_SHARE" else "TEXT"
    ) {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return
        val ctx = _financialContext.value
        val famId = ctx.activeFamilyId ?: return
        if (!ctx.isFamilyEnabled || ctx.familyMembershipStatus != FamilyMembershipStatus.ACTIVE) return
        val uid = currentUserId
        val senderMember = ctx.activeMembers.firstOrNull { it.userId == uid }
        viewModelScope.launch {
            familyRepository.enqueueMessage(
                FamilyMessageEntity(
                    id = UUID.randomUUID().toString(),
                    familyId = famId,
                    senderUserId = uid,
                    senderName = senderMember?.displayName ?: currentUserName,
                    senderAvatar = senderMember?.avatarEmoji ?: "🙂",
                    messageType = messageType,
                    body = cleanBody,
                    relatedTransactionId = relatedTransactionId,
                    replyToMessageId = replyToMessageId,
                    clientMsgId = UUID.randomUUID().toString(),
                    syncStatus = "PENDING_CREATE"
                )
            )
            triggerFamilyBackgroundSync()
        }
    }

    fun deleteFamilyChatMessage(message: FamilyMessageEntity) {
        val ctx = _financialContext.value
        val uid = currentUserId
        val canDelete = message.senderUserId == uid || ctx.membershipRole?.canAdmin() == true
        if (!canDelete) return
        viewModelScope.launch {
            familyRepository.softDeleteMessage(message.id)
            triggerFamilyBackgroundSync()
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
        upiTransactionId: String? = null,
        paidByUserId: String? = null,
        customLabel: String = ""
    ) {
        viewModelScope.launch {
            val cleanCustomLabel = customLabel.trim()
            val ctx = _financialContext.value
            if (ctx.isFamilyModeActive && !ctx.activeFamilyId.isNullOrBlank()) {
                val famId = ctx.activeFamilyId
                val actorId = currentUserId
                val resolvedPaidBy = paidByUserId?.takeIf { id ->
                    ctx.activeMembers.any { it.userId == id }
                } ?: actorId
                val creatorMember = ctx.activeMembers.firstOrNull { it.userId == actorId }
                val paidMember = ctx.activeMembers.firstOrNull { it.userId == resolvedPaidBy }
                val generatedId = UUID.randomUUID().toString()
                val generatedClientId = UUID.randomUUID().toString()
                familyRepository.addOrUpdateTransaction(
                    FamilyTransactionEntity(
                        id = generatedId,
                        familyId = famId,
                        createdBy = actorId,
                        paidBy = resolvedPaidBy,
                        createdByName = creatorMember?.displayName ?: currentUserName,
                        paidByName = paidMember?.displayName ?: creatorMember?.displayName ?: currentUserName,
                        transactionType = type,
                        amount = amount,
                        categoryName = category.ifBlank { "Other" },
                        title = title.ifBlank { category.ifBlank { "Transaction" } },
                        description = note,
                        paymentMethod = paymentMethod.ifBlank { "UPI" },
                        upiId = upiId,
                        upiTransactionId = upiTransactionId,
                        transactionDateMillis = dateMillis,
                        clientTxId = generatedClientId,
                        syncStatus = "PENDING_CREATE",
                        customLabel = cleanCustomLabel
                    )
                )
                triggerFamilyBackgroundSync()
                return@launch
            }

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
                    syncStatus = "PENDING_CREATE",
                    customLabel = cleanCustomLabel
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
        type: TransactionType = TransactionType.EXPENSE,
        scopeToFamily: Boolean = false
    ) {
        if (amount <= 0.0 || amount.isNaN() || amount.isInfinite()) return
        UpiPaymentBus.tryClaimRecording(amount, upiTransactionId)
        viewModelScope.launch {
            upiInsertMutex.withLock {
                val targetFamilyMode = scopeToFamily && _financialContext.value.isFamilyModeActive
                val targetScope = if (targetFamilyMode) FinanceScope.FAMILY else FinanceScope.PERSONAL
                val famTxs = if (targetFamilyMode) {
                    _financialContext.value.activeFamilyId?.let { famId ->
                        familyRepository.observeFamilyTransactions(famId).first()
                    }.orEmpty()
                } else {
                    emptyList()
                }
                val existingByUtr: Any? = if (!targetFamilyMode) {
                    upiTransactionId?.takeIf { it.isNotBlank() }?.let { repository.getTransactionByUtr(it) }
                } else {
                    upiTransactionId?.takeIf { it.isNotBlank() }?.let { utr ->
                        famTxs.firstOrNull { !it.isDeleted && it.upiTransactionId.equals(utr, ignoreCase = true) }
                    }
                }
                val existingNearby: Any? = if (!targetFamilyMode) {
                    repository.findPotentialDuplicates(
                        amount = amount,
                        type = type,
                        scope = targetScope,
                        startMillis = dateMillis - 300_000L,
                        endMillis = dateMillis + 300_000L
                    ).firstOrNull()
                } else {
                    famTxs.firstOrNull { tx ->
                        !tx.isDeleted &&
                            tx.transactionType == type &&
                            kotlin.math.abs(tx.amount - amount) < 0.01 &&
                            tx.transactionDateMillis in (dateMillis - 300_000L)..(dateMillis + 300_000L)
                    }
                }

                if (existingByUtr == null && existingNearby == null) {
                    if (targetFamilyMode) {
                        addTransaction(
                            title = title,
                            amount = amount,
                            type = type,
                            category = category,
                            paymentMethod = "UPI",
                            note = note,
                            dateMillis = dateMillis,
                            upiId = upiId,
                            upiTransactionId = upiTransactionId,
                            paidByUserId = currentUserId
                        )
                    } else {
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
                        triggerBackgroundSync()
                    }
                    try {
                        Log.d(
                            "CashFlowViewModel",
                            "Recorded UPI ${type.name} amount=$amount maskedVpa=${com.zenith.app.data.upi.UpiService.maskVpa(upiId)} hasRef=${!upiTransactionId.isNullOrBlank()}"
                        )
                    } catch (_: Throwable) {
                    }
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

    fun dismissDetectedUpiPayment(keepPendingForLater: Boolean = false) {
        if (!keepPendingForLater) {
            _detectedUpiPayment.value?.let {
                PendingUpiDetectionStore.removeItem(it, getApplication())
            }
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
        upiTransactionId: String?,
        scopeToFamily: Boolean = false,
        type: TransactionType? = null
    ) {
        val detected = _detectedUpiPayment.value
        val ts = detected?.timestamp ?: System.currentTimeMillis()
        val txnType = type ?: if (detected?.isDebit != false) TransactionType.EXPENSE else TransactionType.INCOME
        val noteText = if (txnType == TransactionType.EXPENSE) "UPI Payment" else "UPI Credit"
        addUpiTransaction(
            title = title,
            amount = amount,
            category = category,
            upiId = upiId,
            upiTransactionId = upiTransactionId,
            dateMillis = ts,
            note = noteText,
            type = txnType,
            scopeToFamily = scopeToFamily
        )
    }

    fun updateTransaction(transaction: TransactionEntity, updatedPaidByUserId: String? = null) {
        viewModelScope.launch {
            if (transaction.financeScope == FinanceScope.FAMILY) {
                val serverId = transaction.serverId ?: return@launch
                if (!canEditOrDeleteFamilyTransaction(transaction)) return@launch
                val existing = familyRepository.getTransactionById(serverId) ?: return@launch
                val ctx = _financialContext.value
                val nextPaidBy = updatedPaidByUserId ?: existing.paidBy
                val nextPaidName = ctx.activeMembers.firstOrNull { it.userId == nextPaidBy }?.displayName
                    ?: existing.paidByName
                familyRepository.addOrUpdateTransaction(
                    existing.copy(
                        title = transaction.title,
                        amount = transaction.amount,
                        transactionType = transaction.type,
                        categoryName = transaction.category.ifBlank { "Other" },
                        description = transaction.note,
                        paymentMethod = transaction.paymentMethod,
                        transactionDateMillis = transaction.dateMillis,
                        paidBy = nextPaidBy,
                        paidByName = nextPaidName,
                        customLabel = transaction.customLabel.trim()
                    )
                )
                triggerFamilyBackgroundSync()
                return@launch
            }
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
            if (transaction.financeScope == FinanceScope.FAMILY) {
                val serverId = transaction.serverId ?: return@launch
                if (!canEditOrDeleteFamilyTransaction(transaction)) return@launch
                familyRepository.softDeleteTransaction(serverId)
                triggerFamilyBackgroundSync()
                return@launch
            }
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
        budgetId: Long = 0,
        budgetServerId: String? = null
    ) {
        viewModelScope.launch {
            val ctx = _financialContext.value
            if (ctx.isFamilyModeActive && !ctx.activeFamilyId.isNullOrBlank()) {
                if (ctx.membershipRole?.canAdmin() != true) return@launch
                val famId = ctx.activeFamilyId
                val existing = when {
                    !budgetServerId.isNullOrBlank() -> familyRepository.getBudgetById(budgetServerId)
                    budgetId != 0L -> uiState.value.budgets.firstOrNull { it.id == budgetId }?.serverId?.let {
                        familyRepository.getBudgetById(it)
                    }
                    else -> null
                }
                val targetMonth = existing?.monthYear?.takeIf { it.isNotBlank() } ?: currentMonthYear
                val id = existing?.id ?: UUID.nameUUIDFromBytes(
                    "fambud_${famId}_${categoryName.trim().lowercase()}_${targetMonth}_${periodType}".toByteArray()
                ).toString()
                familyRepository.saveBudget(
                    id = id,
                    familyId = famId,
                    categoryId = existing?.categoryId,
                    categoryName = categoryName,
                    monthlyLimit = limit,
                    monthYear = targetMonth,
                    periodType = periodType,
                    updatedBy = currentUserId
                )
                if (categoryName.equals("Overall", ignoreCase = true)) {
                    familyRepository.updateLocalMonthlyBudgetLimit(famId, limit)
                    ctx.activeFamily?.let { fam ->
                        _financialContext.value = _financialContext.value.copy(
                            activeFamily = fam.copy(monthlyBudgetLimit = limit.coerceAtLeast(0.0))
                        )
                    }
                    if (SupabaseClientConfig.isConfigured) {
                        FamilySupabaseClient.updateFamilyMonthlyBudgetLimit(famId, limit)
                    }
                }
                triggerFamilyBackgroundSync()
                return@launch
            }

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
            val ctx = _financialContext.value
            if (budget.financeScope == FinanceScope.FAMILY || ctx.isFamilyModeActive) {
                if (ctx.membershipRole?.canAdmin() != true) return@launch
                val famId = budget.familyId ?: ctx.activeFamilyId ?: return@launch
                val targetId = budget.serverId ?: UUID.nameUUIDFromBytes(
                    "fambud_${famId}_${budget.categoryName.trim().lowercase()}_${budget.monthYear}_${budget.periodType}".toByteArray()
                ).toString()
                familyRepository.saveBudget(
                    id = targetId,
                    familyId = famId,
                    categoryId = null,
                    categoryName = budget.categoryName,
                    monthlyLimit = budget.monthlyLimit,
                    monthYear = budget.monthYear,
                    periodType = budget.periodType,
                    updatedBy = currentUserId
                )
                triggerFamilyBackgroundSync()
                return@launch
            }
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
            if (budget.financeScope == FinanceScope.FAMILY) {
                if (_financialContext.value.membershipRole?.canAdmin() != true) return@launch
                val serverId = budget.serverId ?: return@launch
                familyRepository.softDeleteBudget(serverId)
                triggerFamilyBackgroundSync()
                return@launch
            }
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
            val ctx = _financialContext.value
            if (ctx.isFamilyModeActive && !ctx.activeFamilyId.isNullOrBlank()) {
                if (ctx.membershipRole?.canAdmin() != true) return@launch
                familyRepository.addCategory(
                    familyId = ctx.activeFamilyId,
                    name = name,
                    iconName = "Star",
                    colorHex = colorHex,
                    type = TransactionType.EXPENSE,
                    createdBy = currentUserId
                )
                triggerFamilyBackgroundSync()
                return@launch
            }
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

        voiceRefineJob?.cancel()
        val reqId = UUID.randomUUID().toString()
        _voiceUserEditing.value = false
        val instantPreParsed = GeminiAiService.fallbackParseVoiceCommand(trimmed).copy(requestId = reqId)
        _parsedVoice.value = instantPreParsed
        _voiceProcessing.value = false

        voiceRefineJob = viewModelScope.launch {
            try {
                val parsed = GeminiAiService.parseVoiceCommand(trimmed)
                // Only update if user is still reviewing this exact session and hasn't edited details manually
                if (parsed.amount > 0 && _parsedVoice.value?.requestId == reqId && !_voiceUserEditing.value) {
                    _parsedVoice.value = parsed.copy(requestId = UUID.randomUUID().toString())
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

    fun clearParsedReceipt() {
        _parsedReceipt.value = null
        _receiptProcessing.value = false
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

    private fun parseReceiptDateMillis(dateStr: String, timeStr: String?): Long {
        val combined = listOfNotNull(dateStr.trim().takeIf { it.isNotBlank() && !it.equals("Today", true) }, timeStr?.trim()?.takeIf { it.isNotBlank() })
            .joinToString(" ")
        if (combined.isBlank()) return System.currentTimeMillis()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "dd-MM-yyyy HH:mm",
            "dd-MM-yyyy",
            "dd MMM yyyy HH:mm",
            "dd MMM yyyy",
            "MMM dd, yyyy"
        )
        for (pattern in patterns) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(combined)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return System.currentTimeMillis()
    }

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
        val parsedDateMillis = parseReceiptDateMillis(dateStr, timeStr)
        val summaryNote = if (items.isNotEmpty()) "${items.size} item${if (items.size > 1) "s" else ""}: " + items.take(2).joinToString { it.name } else "Receipt Scan"
        if (_financialContext.value.isFamilyModeActive) {
            val itemDetail = if (items.isNotEmpty()) {
                " | Items: " + items.take(6).joinToString("; ") { "${it.name} (${it.totalPrice})" }
            } else ""
            addTransaction(
                title = merchant.ifBlank { "Receipt Purchase" },
                amount = amount,
                type = TransactionType.EXPENSE,
                category = category,
                paymentMethod = paymentMethod,
                note = (summaryNote + itemDetail).take(450),
                dateMillis = parsedDateMillis,
                paidByUserId = currentUserId
            )
            closeReceiptDialog()
            return
        }
        viewModelScope.launch {
            val iconName = CategoryIconHelper.iconNameForCategory(category)

            val tx = TransactionEntity(
                title = merchant.ifBlank { "Receipt Purchase" },
                amount = amount,
                type = TransactionType.EXPENSE,
                category = category,
                categoryIconName = iconName,
                paymentMethod = paymentMethod,
                note = summaryNote,
                receiptImageUri = imageUri,
                financeScope = FinanceScope.PERSONAL,
                familyId = null,
                createdByUserId = currentUserId,
                dateMillis = parsedDateMillis,
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
            if (transaction.financeScope == FinanceScope.FAMILY) {
                val serverId = transaction.serverId ?: return@launch
                if (!canEditOrDeleteFamilyTransaction(transaction)) return@launch
                familyRepository.softDeleteTransaction(serverId)
                return@launch
            }
            repository.softDeleteForUndo(transaction)
        }
    }

    fun commitDeletedTransaction() {
        viewModelScope.launch {
            repository.commitPendingUndoDeletes()
            if (_financialContext.value.isFamilyModeActive) {
                triggerFamilyBackgroundSync()
            } else {
                triggerBackgroundSync()
            }
        }
    }

    fun commitDeletedTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            if (transaction.financeScope == FinanceScope.FAMILY) {
                triggerFamilyBackgroundSync()
            } else {
                repository.commitPendingUndoDelete(transaction.id)
                triggerBackgroundSync()
            }
        }
    }

    fun undoDeleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            if (transaction.financeScope == FinanceScope.FAMILY) {
                val serverId = transaction.serverId ?: return@launch
                familyRepository.restoreTransaction(serverId)
                triggerFamilyBackgroundSync()
                return@launch
            }
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
        if (_aiCoachLoading.value) return
        viewModelScope.launch {
            _aiCoachLoading.value = true
            try {
                val advice = GeminiAiService.getFinancialCoachAdvice(
                    totalIncome = totalIncome,
                    totalExpense = totalExpense,
                    topExpenseCategory = topCat,
                    currencySymbol = _currencySymbol.value
                )
                _aiCoachAdvice.value = advice
            } finally {
                _aiCoachLoading.value = false
            }
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

    fun clearAllLocalData(alsoClearCloud: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = _financialContext.value
            if (ctx.isFamilyModeActive && !ctx.activeFamilyId.isNullOrBlank()) {
                val famId = ctx.activeFamilyId
                val isAdmin = ctx.membershipRole?.canAdmin() == true
                val clearedCloud = familyRepository.clearFamilyTransactionsEverywhere(
                    familyId = famId,
                    userId = currentUserId,
                    isAdmin = isAdmin,
                    alsoClearCloud = alsoClearCloud
                )
                if (clearedCloud && alsoClearCloud) {
                    familyRealtimeManager.emitCommittedEvent(
                        FamilyRealtimeBroadcastPayload(
                            event = FamilyRealtimeEvents.TRANSACTION_DELETED,
                            familyId = famId,
                            actorUserId = currentUserId
                        )
                    )
                }
                return@launch
            }

            val uid = authService.currentUser.value?.id ?: _userSupabaseId.value
            val isCloudSignedIn = !uid.isNullOrBlank() && syncEngine.isValidUuid(uid) && SupabaseClientConfig.isConfigured
            if (alsoClearCloud && isCloudSignedIn) {
                val cloudCleared = syncEngine.clearCloudPersonalLedger(uid)
                if (cloudCleared) {
                    repository.wipeLocalDatabase()
                    repository.reseedDefaultCategories()
                } else {
                    // Offline or cloud clear deferred: retain PENDING_DELETE tombstones so sync deletes cloud rows cleanly on reconnect without regaining
                    repository.markAllPersonalCloudDataPendingDelete()
                }
            } else {
                repository.wipeLocalDatabase()
                repository.reseedDefaultCategories()
            }
            MerchantCategoryRules.clear()
            PendingUpiDetectionStore.clearAll(getApplication())
            ZenithSummaryWidgetProvider.requestUpdate(getApplication())
        }
    }

    fun exportTransactionsCsv(): String {
        val state = uiState.value
        val txs = state.allTransactions.ifEmpty { state.transactions }
        val isFamily = state.activeMode == FinancialMode.FAMILY
        val header = if (isFamily) {
            "Date,Title,Category,Type,Amount,PaymentMethod,EnteredBy,PaidBy,Note\n"
        } else {
            "Date,Title,Category,Type,Amount,PaymentMethod,Note\n"
        }
        val sb = StringBuilder(header)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        txs.forEach { tx ->
            val dateStr = sdf.format(Date(tx.dateMillis))
            if (isFamily) {
                val attr = tx.serverId?.let { state.txAttributionByServerId[it] }
                val entered = attr?.createdByName ?: "Member"
                val paid = attr?.paidByName ?: entered
                sb.append("\"$dateStr\",\"${tx.title}\",\"${tx.category}\",\"${tx.type.name}\",${tx.amount},\"${tx.paymentMethod}\",\"$entered\",\"$paid\",\"${tx.note}\"\n")
            } else {
                sb.append("\"$dateStr\",\"${tx.title}\",\"${tx.category}\",\"${tx.type.name}\",${tx.amount},\"${tx.paymentMethod}\",\"${tx.note}\"\n")
            }
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
    val autoPayReminders: List<AutoPayReminderEntity> = emptyList(),
    val activeMode: FinancialMode = FinancialMode.PERSONAL,
    val activeFamily: FamilyInfo? = null,
    val activeFamilyMembers: List<FamilyMemberInfo> = emptyList(),
    val activeFamilyRole: FamilyRole? = null,
    val txAttributionByServerId: Map<String, FamilyTxAttribution> = emptyMap(),
    val familyMessages: List<FamilyMessageEntity> = emptyList()
)

private data class QuadFilter(
    val search: String,
    val type: TransactionType?,
    val category: String?,
    val memberId: String?
)

private data class QuadUiFilter(
    val currency: String,
    val tab: Int,
    val hidden: Boolean,
    val label: String?
)

private data class FilterState(
    val search: String,
    val type: TransactionType?,
    val category: String?,
    val memberId: String?,
    val currency: String,
    val tab: Int,
    val isBalanceHidden: Boolean,
    val label: String? = null,
    val isRealtimeConnected: Boolean = false
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
