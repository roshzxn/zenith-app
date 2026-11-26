package com.zenith.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenith.app.domain.*
import com.zenith.app.ui.theme.ZenithTheme
import java.util.UUID

enum class FamilySetupTab {
    CREATE,
    JOIN
}

/**
 * Atomic Create Family & Invitation-Based Join setup modal with emoji avatar selection,
 * duplicate-submit locking, stable retry idempotency, and honest error display.
 */
@Composable
fun FamilySetupModal(
    initialTab: FamilySetupTab = FamilySetupTab.CREATE,
    initialInviteInput: String = "",
    currencySymbol: String,
    currentUserName: String,
    isAuthenticated: Boolean,
    onCreateFamily: (
        name: String,
        avatarEmoji: String,
        monthlyBudgetLimit: Double,
        idempotencyKey: String,
        onComplete: (Result<String>) -> Unit
    ) -> Unit,
    onJoinFamily: (
        codeOrLink: String,
        displayName: String,
        avatarEmoji: String,
        onComplete: (Result<String>) -> Unit
    ) -> Unit,
    onDismiss: () -> Unit,
    onCreateFamilyWithCustomProfile: ((
        name: String,
        avatarEmoji: String,
        monthlyBudgetLimit: Double,
        idempotencyKey: String,
        familyLabel: String,
        creatorName: String,
        creatorEmoji: String,
        creatorLabel: String,
        onComplete: (Result<String>) -> Unit
    ) -> Unit)? = null,
    onJoinFamilyWithLabel: ((
        codeOrLink: String,
        displayName: String,
        avatarEmoji: String,
        memberLabel: String,
        onComplete: (Result<String>) -> Unit
    ) -> Unit)? = null
) {
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }

    var familyName by remember { mutableStateOf("") }
    var familyLabel by remember { mutableStateOf("") }
    var selectedFamilyEmoji by remember { mutableStateOf(FamilyAvatarCatalog.FAMILY_EMOJIS.first()) }
    var customEmojiInput by remember { mutableStateOf("") }
    var monthlyBudgetText by remember { mutableStateOf("") }
    var creatorDisplayName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var creatorMemberLabel by remember { mutableStateOf("") }
    var selectedCreatorEmoji by remember { mutableStateOf(FamilyAvatarCatalog.MEMBER_EMOJIS.first()) }
    var createIdempotencyKey by remember { mutableStateOf(UUID.randomUUID().toString()) }

    var inviteInput by remember(initialInviteInput) { mutableStateOf(initialInviteInput) }
    var joinDisplayName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var joinMemberLabel by remember { mutableStateOf("") }
    var selectedMemberEmoji by remember { mutableStateOf(FamilyAvatarCatalog.MEMBER_EMOJIS.first()) }

    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val doneActions = KeyboardActions(
        onDone = {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    )

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .imePadding()
                .testTag("family_setup_modal")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (activeTab == FamilySetupTab.CREATE) "Create Your Family" else "Join Existing Family",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = "Shared synced ledger, budgets, analytics & chat",
                            fontSize = 11.sp,
                            color = ZenithTheme.textSecondary
                        )
                    }
                    IconButton(onClick = { if (!isSubmitting) onDismiss() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ZenithTheme.cardVariantSurface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        FamilySetupTab.CREATE to "Create Family",
                        FamilySetupTab.JOIN to "Join via Invite"
                    ).forEach { (tab, label) ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) ZenithTheme.solidGreen else Color.Transparent)
                                .clickable(enabled = !isSubmitting) {
                                    activeTab = tab
                                    errorMessage = null
                                }
                                .testTag("tab_${tab.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else ZenithTheme.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!isAuthenticated) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.accentAmber.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ZenithTheme.accentAmber.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = "Sign in with a verified Zenith account before creating or joining a family.",
                            fontSize = 12.sp,
                            color = ZenithTheme.accentAmber,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (activeTab == FamilySetupTab.CREATE) {
                    Text(
                        text = "Family Avatar Emoji",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ZenithTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val effectiveEmoji = customEmojiInput.trim().ifBlank { selectedFamilyEmoji }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FamilyAvatarCatalog.FAMILY_EMOJIS.forEach { emoji ->
                            val isSel = customEmojiInput.isBlank() && selectedFamilyEmoji == emoji
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) ZenithTheme.accentCyan.copy(alpha = 0.2f) else ZenithTheme.cardVariantSurface)
                                    .border(
                                        1.5.dp,
                                        if (isSel) ZenithTheme.accentCyan else ZenithTheme.borderColor,
                                        CircleShape
                                    )
                                    .clickable {
                                        selectedFamilyEmoji = emoji
                                        customEmojiInput = ""
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(emoji, fontSize = 20.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = familyName,
                            onValueChange = {
                                familyName = it
                                createIdempotencyKey = UUID.randomUUID().toString()
                                errorMessage = null
                            },
                            label = { Text("Family Name") },
                            placeholder = { Text("e.g. Kapoor Home") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = doneActions,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.7f)
                                .testTag("input_family_name")
                        )
                        OutlinedTextField(
                            value = customEmojiInput,
                            onValueChange = { v ->
                                if (v.length <= 16) customEmojiInput = v
                            },
                            label = { Text("Custom Emoji") },
                            placeholder = { Text(effectiveEmoji) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = doneActions,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.85f)
                                .testTag("input_family_custom_emoji")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = familyLabel,
                        onValueChange = { familyLabel = it },
                        label = { Text("Family Label (Optional)") },
                        placeholder = { Text("e.g. Household, Roommates, Shared Villa") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = doneActions,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_family_label")
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FamilyAvatarCatalog.FAMILY_LABEL_SUGGESTIONS.forEach { preset ->
                            FilterChip(
                                selected = familyLabel.equals(preset, ignoreCase = true),
                                onClick = { familyLabel = if (familyLabel.equals(preset, ignoreCase = true)) "" else preset },
                                label = { Text(preset, fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = monthlyBudgetText,
                        onValueChange = { v -> if (v.all { it.isDigit() || it == '.' }) monthlyBudgetText = v },
                        label = { Text("Shared Monthly Budget (Optional, $currencySymbol)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = doneActions,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    CustomMemberProfileFields(
                        displayName = creatorDisplayName,
                        onDisplayNameChange = { creatorDisplayName = it },
                        memberLabel = creatorMemberLabel,
                        onMemberLabelChange = { creatorMemberLabel = it },
                        selectedEmoji = selectedCreatorEmoji,
                        onEmojiSelected = { selectedCreatorEmoji = it },
                        nameFieldTag = "input_creator_display_name",
                        labelFieldTag = "input_creator_member_label",
                        emojiFieldTag = "input_creator_custom_emoji"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ZenithTheme.accentRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ZenithTheme.accentRed.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("family_setup_error")
                        ) {
                            Text(
                                text = err,
                                fontSize = 12.sp,
                                color = ZenithTheme.accentRed,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isSubmitting) return@Button
                            val cleanName = familyName.trim()
                            if (cleanName.isBlank()) {
                                errorMessage = "Please enter a family name."
                                return@Button
                            }
                            if (!isAuthenticated) {
                                errorMessage = "Please sign in before creating a family."
                                return@Button
                            }
                            val budget = monthlyBudgetText.toDoubleOrNull() ?: 0.0
                            isSubmitting = true
                            errorMessage = null
                            if (onCreateFamilyWithCustomProfile != null) {
                                onCreateFamilyWithCustomProfile.invoke(
                                    cleanName,
                                    effectiveEmoji,
                                    budget,
                                    createIdempotencyKey,
                                    familyLabel.trim(),
                                    creatorDisplayName.trim(),
                                    selectedCreatorEmoji.trim().ifBlank { "🙂" },
                                    creatorMemberLabel.trim()
                                ) { res ->
                                    isSubmitting = false
                                    res.onSuccess { onDismiss() }
                                        .onFailure { err -> errorMessage = err.message ?: "Unable to create family." }
                                }
                            } else {
                                onCreateFamily(cleanName, effectiveEmoji, budget, createIdempotencyKey) { res ->
                                    isSubmitting = false
                                    res.onSuccess { onDismiss() }
                                        .onFailure { err -> errorMessage = err.message ?: "Unable to create family." }
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_confirm_create_family")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Creating Family...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create Family", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = inviteInput,
                        onValueChange = {
                            inviteInput = it
                            errorMessage = null
                        },
                        label = { Text("Invitation Code or Link") },
                        placeholder = { Text("FAM-XXXXXX or zenith://join?token=...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = doneActions,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_invite_code")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            showQrScanner = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("btn_scan_family_qr")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = ZenithTheme.accentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Invite QR Code",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ZenithTheme.accentCyan
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    CustomMemberProfileFields(
                        displayName = joinDisplayName,
                        onDisplayNameChange = { joinDisplayName = it },
                        memberLabel = joinMemberLabel,
                        onMemberLabelChange = { joinMemberLabel = it },
                        selectedEmoji = selectedMemberEmoji,
                        onEmojiSelected = { selectedMemberEmoji = it },
                        nameFieldTag = "input_join_display_name",
                        labelFieldTag = "input_join_member_label",
                        emojiFieldTag = "input_join_custom_emoji"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    errorMessage?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ZenithTheme.accentRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, ZenithTheme.accentRed.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("family_setup_error")
                        ) {
                            Text(
                                text = err,
                                fontSize = 12.sp,
                                color = ZenithTheme.accentRed,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (isSubmitting) return@Button
                            val cleanInvite = inviteInput.trim()
                            if (cleanInvite.isBlank()) {
                                errorMessage = "Enter a valid invitation code or invite link."
                                return@Button
                            }
                            if (!isAuthenticated) {
                                errorMessage = "Please sign in before joining a family."
                                return@Button
                            }
                            isSubmitting = true
                            errorMessage = null
                            if (onJoinFamilyWithLabel != null) {
                                onJoinFamilyWithLabel.invoke(
                                    cleanInvite,
                                    joinDisplayName.trim(),
                                    selectedMemberEmoji.trim().ifBlank { "🙂" },
                                    joinMemberLabel.trim()
                                ) { res ->
                                    isSubmitting = false
                                    res.onSuccess { onDismiss() }
                                        .onFailure { err -> errorMessage = err.message ?: "Unable to accept invitation." }
                                }
                            } else {
                                onJoinFamily(cleanInvite, joinDisplayName.trim(), selectedMemberEmoji.trim().ifBlank { "🙂" }) { res ->
                                    isSubmitting = false
                                    res.onSuccess { onDismiss() }
                                        .onFailure { err -> errorMessage = err.message ?: "Unable to accept invitation." }
                                }
                            }
                        },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidIndigo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_confirm_join_family")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Joining...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Join Family", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showQrScanner) {
        FamilyInviteQrScannerDialog(
            onInviteScanned = { scanned ->
                inviteInput = scanned
                errorMessage = null
                showQrScanner = false
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

@Composable
private fun CustomMemberProfileFields(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    memberLabel: String,
    onMemberLabelChange: (String) -> Unit,
    selectedEmoji: String,
    onEmojiSelected: (String) -> Unit,
    nameFieldTag: String,
    labelFieldTag: String,
    emojiFieldTag: String = "input_member_custom_emoji"
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val doneActions = KeyboardActions(
        onDone = {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Custom Member Name") },
            placeholder = { Text("e.g. Mom, Dad, Alex") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = doneActions,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1.7f)
                .testTag(nameFieldTag)
        )
        OutlinedTextField(
            value = selectedEmoji,
            onValueChange = { v ->
                if (v.length <= 16) onEmojiSelected(v)
            },
            label = { Text("Emoji") },
            placeholder = { Text("🙂") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = doneActions,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(0.7f)
                .testTag(emojiFieldTag)
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = memberLabel,
        onValueChange = onMemberLabelChange,
        label = { Text("Custom Member Label / Emoji (Optional)") },
        placeholder = { Text("e.g. 👩 Mom, 👨 Dad, 🏠 Flatmate") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = doneActions,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(labelFieldTag)
    )

    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FamilyAvatarCatalog.MEMBER_LABEL_SUGGESTIONS.forEach { preset ->
            FilterChip(
                selected = memberLabel.equals(preset, ignoreCase = true),
                onClick = {
                    onMemberLabelChange(if (memberLabel.equals(preset, ignoreCase = true)) "" else preset)
                },
                label = { Text(preset, fontSize = 11.sp) }
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Avatar Emoji (Tap preset or type custom above)",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = ZenithTheme.textSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FamilyAvatarCatalog.MEMBER_EMOJIS.forEach { emoji ->
            val isSel = selectedEmoji == emoji
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSel) ZenithTheme.accentCyan.copy(alpha = 0.2f) else ZenithTheme.cardVariantSurface)
                    .border(1.dp, if (isSel) ZenithTheme.accentCyan else ZenithTheme.borderColor, CircleShape)
                    .clickable { onEmojiSelected(emoji) },
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 18.sp)
            }
        }
    }
}

/**
 * Family administration sheet: member list, custom family & member names/labels,
 * invitation generation, and owner-safe leave / transfer ownership / close policies.
 */
@Composable
fun FamilyManageSheet(
    financialContext: FinancialContext,
    onGenerateInvite: (invitedEmail: String, role: FamilyRole, onComplete: (Result<FamilyInvitationInfo>) -> Unit) -> Unit,
    onUpdateMemberRole: (targetUserId: String, newRole: FamilyRole, onComplete: (Result<Boolean>) -> Unit) -> Unit,
    onRemoveMember: (targetUserId: String, onComplete: (Result<Boolean>) -> Unit) -> Unit,
    onTransferOwnership: (newOwnerUserId: String, onComplete: (Result<Boolean>) -> Unit) -> Unit,
    onLeaveFamily: (onComplete: (Result<Boolean>) -> Unit) -> Unit,
    onCloseFamily: (onComplete: (Result<Boolean>) -> Unit) -> Unit,
    onDisableFamilyFeature: () -> Unit,
    onDismiss: () -> Unit,
    onUpdateFamilyProfile: ((name: String, avatarEmoji: String, familyLabel: String, onComplete: (Result<Boolean>) -> Unit) -> Unit)? = null,
    onUpdateMemberProfile: ((targetUserId: String, displayName: String, memberLabel: String, avatarEmoji: String?, onComplete: (Result<Boolean>) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    val family = financialContext.activeFamily ?: return
    val role = financialContext.membershipRole ?: FamilyRole.MEMBER
    val canAdmin = role.canAdmin()
    val isOwner = role.isOwner()
    val members = financialContext.activeMembers

    var invitedEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(FamilyRole.MEMBER) }
    var latestInvite by remember { mutableStateOf<FamilyInvitationInfo?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var showOwnerLeaveGuard by remember { mutableStateOf(false) }
    var confirmCloseDialog by remember { mutableStateOf(false) }
    var showEditFamilyDialog by remember { mutableStateOf(false) }
    var memberBeingEdited by remember { mutableStateOf<FamilyMemberInfo?>(null) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 20.dp)
                .imePadding()
                .testTag("family_manage_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(ZenithTheme.accentCyan.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(family.avatarEmoji, fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = family.name,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ZenithTheme.textPrimary
                                )
                                if (family.familyLabel.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = ZenithTheme.accentCyan.copy(alpha = 0.14f)
                                    ) {
                                        Text(
                                            text = family.familyLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.accentCyan,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Your role: ${role.name} • ${members.size} active member(s)",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canAdmin && onUpdateFamilyProfile != null) {
                            IconButton(
                                onClick = { showEditFamilyDialog = true },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("btn_edit_family_profile")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Family Name & Label", tint = ZenithTheme.accentCyan, modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = { if (!isBusy) onDismiss() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                        }
                    }
                }

                actionError?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.accentRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ZenithTheme.accentRed.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(err, fontSize = 12.sp, color = ZenithTheme.accentRed, modifier = Modifier.padding(10.dp))
                    }
                }

                if (canAdmin) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ZenithTheme.cardVariantSurface,
                        border = BorderStroke(1.dp, ZenithTheme.borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Invite Family Member", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = invitedEmail,
                                onValueChange = { invitedEmail = it },
                                label = { Text("Email (Optional lock)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(FamilyRole.MEMBER, FamilyRole.ADMIN).forEach { r ->
                                    FilterChip(
                                        selected = inviteRole == r,
                                        onClick = { inviteRole = r },
                                        label = { Text(r.name, fontSize = 11.sp) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    isBusy = true
                                    actionError = null
                                    onGenerateInvite(invitedEmail.trim(), inviteRole) { res ->
                                        isBusy = false
                                        res.onSuccess { latestInvite = it }
                                            .onFailure { actionError = it.message }
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Generate Invitation Code", fontWeight = FontWeight.Bold)
                            }

                            latestInvite?.let { inv ->
                                val joinUrl = "zenith://join?token=${inv.inviteToken}"
                                val qrBitmap = remember(joinUrl) { generateFamilyInviteQrBitmap(joinUrl, 440) }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Code: ${inv.inviteCode}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = ZenithTheme.accentGreen)
                                if (qrBitmap != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = Color.White,
                                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                                            modifier = Modifier.testTag("family_invite_qr_image")
                                        ) {
                                            Image(
                                                bitmap = qrBitmap.asImageBitmap(),
                                                contentDescription = "Family Invite QR Code",
                                                modifier = Modifier
                                                    .size(164.dp)
                                                    .padding(10.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Scan with Zenith QR to join ${family.name}",
                                            fontSize = 11.sp,
                                            color = ZenithTheme.textSecondary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("Zenith Invite", inv.inviteCode))
                                            Toast.makeText(context, "Copied ${inv.inviteCode}", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Copy Code", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "Join ${family.name} ${family.avatarEmoji} on Zenith! Code: ${inv.inviteCode} or Link: $joinUrl"
                                                )
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Family Invite"))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Share Link", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text("Members", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { member ->
                        val isSelf = member.userId == financialContext.currentUserId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Text(member.avatarEmoji, fontSize = 20.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = if (isSelf) "${member.displayName} (You)" else member.displayName,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ZenithTheme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = member.role.name,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = ZenithTheme.accentIndigo
                                            )
                                            if (member.memberLabel.isNotBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = ZenithTheme.accentCyan.copy(alpha = 0.14f)
                                                ) {
                                                    Text(
                                                        text = member.memberLabel,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = ZenithTheme.accentCyan,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if ((isSelf || canAdmin) && onUpdateMemberProfile != null) {
                                        IconButton(
                                            onClick = { memberBeingEdited = member },
                                            enabled = !isBusy,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("btn_edit_member_${member.userId}")
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit Name & Label", tint = ZenithTheme.accentCyan, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (canAdmin && !isSelf && member.role != FamilyRole.OWNER) {
                                        IconButton(
                                            onClick = {
                                                val next = if (member.role == FamilyRole.MEMBER) FamilyRole.ADMIN else FamilyRole.MEMBER
                                                isBusy = true
                                                onUpdateMemberRole(member.userId, next) { res ->
                                                    isBusy = false
                                                    res.onFailure { actionError = it.message }
                                                }
                                            },
                                            enabled = !isBusy,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.AdminPanelSettings, contentDescription = "Toggle Admin", modifier = Modifier.size(16.dp))
                                        }
                                        if (isOwner) {
                                            IconButton(
                                                onClick = {
                                                    isBusy = true
                                                    onTransferOwnership(member.userId) { res ->
                                                        isBusy = false
                                                        res.onSuccess {
                                                            showOwnerLeaveGuard = false
                                                            Toast.makeText(context, "Ownership transferred to ${member.displayName}", Toast.LENGTH_SHORT).show()
                                                        }.onFailure { actionError = it.message }
                                                    }
                                                },
                                                enabled = !isBusy,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.SwapHoriz, contentDescription = "Make Owner", tint = ZenithTheme.accentAmber, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                isBusy = true
                                                onRemoveMember(member.userId) { res ->
                                                    isBusy = false
                                                    res.onFailure { actionError = it.message }
                                                }
                                            },
                                            enabled = !isBusy,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.PersonRemove, contentDescription = "Remove Member", tint = ZenithTheme.accentRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showOwnerLeaveGuard) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ZenithTheme.accentAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, ZenithTheme.accentAmber.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("owner_leave_guard_banner")
                    ) {
                        Text(
                            text = if (members.size > 1) {
                                "As Owner, tap the transfer icon (⇄) beside a member to transfer ownership before leaving, or close the family below."
                            } else {
                                "You are the only member & Owner. Close the family below to end membership without losing audit records."
                            },
                            fontSize = 11.5.sp,
                            color = ZenithTheme.accentAmber,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onDisableFamilyFeature()
                            onDismiss()
                        },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Hide Family UI", fontSize = 11.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            if (isOwner) {
                                showOwnerLeaveGuard = true
                                return@Button
                            }
                            isBusy = true
                            actionError = null
                            onLeaveFamily { res ->
                                isBusy = false
                                res.onSuccess { onDismiss() }
                                    .onFailure { actionError = it.message }
                            }
                        },
                        enabled = !isBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_leave_family")
                    ) {
                        Text("Leave Family", fontSize = 11.sp, color = Color.White, maxLines = 1)
                    }
                }

                if (isOwner) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { confirmCloseDialog = true },
                        enabled = !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_close_family")
                    ) {
                        Text("Close / Delete Family (Owner Only)", color = ZenithTheme.accentRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (confirmCloseDialog) {
        AlertDialog(
            onDismissRequest = { confirmCloseDialog = false },
            title = { Text("Close Family Group?", fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary) },
            text = {
                Text(
                    "Closing '${family.name}' will revoke pending invitations and archive the family workspace while keeping Personal ledgers untouched.",
                    fontSize = 13.sp,
                    color = ZenithTheme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmCloseDialog = false
                        isBusy = true
                        onCloseFamily { res ->
                            isBusy = false
                            res.onSuccess { onDismiss() }
                                .onFailure { actionError = it.message }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidRed)
                ) {
                    Text("Confirm Close", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseDialog = false }) { Text("Cancel") }
            },
            containerColor = ZenithTheme.cardSurface
        )
    }

    if (showEditFamilyDialog && onUpdateFamilyProfile != null) {
        var editedName by remember(family) { mutableStateOf(family.name) }
        var editedLabel by remember(family) { mutableStateOf(family.familyLabel) }
        var editedEmoji by remember(family) { mutableStateOf(family.avatarEmoji) }
        AlertDialog(
            onDismissRequest = { showEditFamilyDialog = false },
            title = { Text("Customize Family Name & Label", fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FamilyAvatarCatalog.FAMILY_EMOJIS.forEach { emoji ->
                            val isSel = editedEmoji == emoji
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isSel) ZenithTheme.accentCyan.copy(alpha = 0.2f) else ZenithTheme.cardVariantSurface)
                                    .border(1.dp, if (isSel) ZenithTheme.accentCyan else ZenithTheme.borderColor, CircleShape)
                                    .clickable { editedEmoji = emoji },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(emoji, fontSize = 18.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text("Custom Family Name") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1.7f)
                                .testTag("input_edit_family_name")
                        )
                        OutlinedTextField(
                            value = editedEmoji,
                            onValueChange = { v ->
                                if (v.length <= 16) editedEmoji = v
                            },
                            label = { Text("Emoji") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(0.7f)
                                .testTag("input_edit_family_emoji")
                        )
                    }
                    OutlinedTextField(
                        value = editedLabel,
                        onValueChange = { editedLabel = it },
                        label = { Text("Custom Family Label / Emoji") },
                        placeholder = { Text("e.g. 🏠 Household, 🛋️ Roommates") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_edit_family_label")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = editedName.trim()
                        if (clean.isNotBlank()) {
                            isBusy = true
                            showEditFamilyDialog = false
                            onUpdateFamilyProfile(clean, editedEmoji.trim().ifBlank { "🏠" }, editedLabel.trim()) { res ->
                                isBusy = false
                                res.onFailure { actionError = it.message }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                    modifier = Modifier.testTag("btn_save_family_profile")
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditFamilyDialog = false }) { Text("Cancel") }
            },
            containerColor = ZenithTheme.cardSurface
        )
    }

    memberBeingEdited?.let { editingMember ->
        if (onUpdateMemberProfile != null) {
            var editedMemberName by remember(editingMember) { mutableStateOf(editingMember.displayName) }
            var editedMemberLabel by remember(editingMember) { mutableStateOf(editingMember.memberLabel) }
            var editedMemberEmoji by remember(editingMember) { mutableStateOf(editingMember.avatarEmoji) }
            AlertDialog(
                onDismissRequest = { memberBeingEdited = null },
                title = { Text("Customize Member Name & Label", fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CustomMemberProfileFields(
                            displayName = editedMemberName,
                            onDisplayNameChange = { editedMemberName = it },
                            memberLabel = editedMemberLabel,
                            onMemberLabelChange = { editedMemberLabel = it },
                            selectedEmoji = editedMemberEmoji,
                            onEmojiSelected = { editedMemberEmoji = it },
                            nameFieldTag = "input_edit_member_name",
                            labelFieldTag = "input_edit_member_label",
                            emojiFieldTag = "input_edit_member_custom_emoji"
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val clean = editedMemberName.trim()
                            if (clean.isNotBlank()) {
                                isBusy = true
                                val uid = editingMember.userId
                                memberBeingEdited = null
                                onUpdateMemberProfile(uid, clean, editedMemberLabel.trim(), editedMemberEmoji.trim().ifBlank { "🙂" }) { res ->
                                    isBusy = false
                                    res.onFailure { actionError = it.message }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        modifier = Modifier.testTag("btn_save_member_profile")
                    ) {
                        Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { memberBeingEdited = null }) { Text("Cancel") }
                },
                containerColor = ZenithTheme.cardSurface
            )
        }
    }
}
