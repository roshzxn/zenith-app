package com.zenith.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zenith.app.data.network.FamilySupabaseClient
import com.zenith.app.domain.FamilyInfo
import com.zenith.app.domain.FamilyInvitationInfo
import com.zenith.app.ui.theme.ZenithTheme

private val FAMILY_CODE_REGEX = Regex("\\b(FAM-[A-Za-z0-9]{4,16})\\b", RegexOption.IGNORE_CASE)

/**
 * Generates an offline QR code bitmap for a family invite URL or code.
 */
fun generateFamilyInviteQrBitmap(payload: String, sizePx: Int = 512): Bitmap? {
    val clean = payload.trim()
    if (clean.isBlank()) return null
    return runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(clean, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}

/**
 * Extracts a Zenith family invite token or FAM-XXXXXX code from a scanned QR payload.
 * Returns null for unrelated QR codes such as UPI payment payloads.
 */
fun parseFamilyInviteQrPayload(rawPayload: String): String? {
    val trimmed = rawPayload.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("upi://", ignoreCase = true) || trimmed.startsWith("000201")) {
        return null
    }
    if (trimmed.startsWith("zenith://join", ignoreCase = true) ||
        trimmed.contains("token=", ignoreCase = true) ||
        trimmed.contains("code=", ignoreCase = true)
    ) {
        val extracted = FamilySupabaseClient.extractInviteTokenOrCode(trimmed)
        if (extracted.isNotBlank()) return extracted
    }
    val codeMatch = FAMILY_CODE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
    if (!codeMatch.isNullOrBlank()) {
        return codeMatch.uppercase()
    }
    return null
}

@Composable
fun FamilyInviteQrCard(
    family: FamilyInfo,
    invite: FamilyInvitationInfo,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val joinUrl = "zenith://join?token=${invite.inviteToken}"
    val qrBitmap = remember(joinUrl) { generateFamilyInviteQrBitmap(joinUrl, 440) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Code: ${invite.inviteCode}",
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ZenithTheme.accentGreen
        )
        if (invite.maxUses > 1 && invite.invitedEmail.isBlank()) {
            val remaining = (invite.maxUses - invite.usesCount).coerceAtLeast(0)
            Text(
                text = "$remaining of ${invite.maxUses} joins remaining",
                fontSize = 11.sp,
                color = ZenithTheme.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (qrBitmap != null) {
            Spacer(modifier = Modifier.height(10.dp))
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
                        .size(176.dp)
                        .padding(10.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Scan with Zenith QR to join ${family.name}",
                fontSize = 11.sp,
                color = ZenithTheme.textSecondary,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Zenith Invite", invite.inviteCode))
                    Toast.makeText(context, "Copied ${invite.inviteCode}", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Code", fontSize = 11.5.sp)
            }
            OutlinedButton(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join ${family.name} ${family.avatarEmoji} on Zenith! Code: ${invite.inviteCode} or Link: $joinUrl"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Family Invite"))
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Share Link", fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
fun FamilyInviteQrShareDialog(
    family: FamilyInfo,
    invite: FamilyInvitationInfo?,
    isLoading: Boolean,
    errorMessage: String?,
    onGenerateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .testTag("family_qr_share_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = ZenithTheme.accentCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${family.avatarEmoji} ${family.name} Invite QR",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "Let family members scan or enter code to join",
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

                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            color = ZenithTheme.accentCyan,
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                        Text("Preparing Family QR code...", fontSize = 12.sp, color = ZenithTheme.textSecondary)
                    }
                    !errorMessage.isNullOrBlank() -> {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = ZenithTheme.accentRed,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Button(onClick = onGenerateNew, shape = RoundedCornerShape(12.dp)) {
                            Text("Retry")
                        }
                    }
                    invite != null -> {
                        FamilyInviteQrCard(family = family, invite = invite)
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(onClick = onGenerateNew) {
                            Text("Generate Fresh QR Code", fontSize = 12.sp, color = ZenithTheme.accentCyan)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Camera + Gallery QR scanner dialog tailored for scanning Zenith Family invitation QR codes.
 */
@Composable
fun FamilyInviteQrScannerDialog(
    onInviteScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var scanError by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null || handled) return@rememberLauncherForActivityResult
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            BarcodeScanning.getClient(options).process(image)
                .addOnSuccessListener { barcodes ->
                    val decoded = barcodes.firstNotNullOfOrNull { it.rawValue?.let(::parseFamilyInviteQrPayload) }
                    if (!decoded.isNullOrBlank() && !handled) {
                        handled = true
                        scanError = null
                        onInviteScanned(decoded)
                    } else {
                        scanError = "No valid Zenith Family invite QR found in the selected image."
                    }
                }
                .addOnFailureListener {
                    scanError = "Unable to read QR code from that image."
                }
        }.onFailure {
            scanError = "Unable to open selected image."
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .testTag("family_qr_scanner_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = ZenithTheme.accentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Scan Family Invite QR",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = ZenithTheme.textPrimary
                            )
                            Text(
                                text = "Scan live with camera or pick a shared QR screenshot",
                                fontSize = 11.sp,
                                color = ZenithTheme.textSecondary
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black)
                ) {
                    QrCameraView(
                        onQrScanned = { raw ->
                            if (!handled) {
                                val invite = parseFamilyInviteQrPayload(raw)
                                if (!invite.isNullOrBlank()) {
                                    handled = true
                                    scanError = null
                                    onInviteScanned(invite)
                                } else {
                                    scanError = "Not a Zenith Family invite QR. Ask the family admin to share an invite QR."
                                }
                            }
                        }
                    )
                }

                scanError?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ZenithTheme.accentRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ZenithTheme.accentRed.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            fontSize = 11.sp,
                            color = ZenithTheme.accentRed,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ZenithTheme.accentCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_scan_family_qr_gallery")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = ZenithTheme.accentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Gallery", fontSize = 12.sp, color = ZenithTheme.accentCyan)
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Enter Code", fontSize = 12.sp, color = ZenithTheme.textPrimary)
                    }
                }
            }
        }
    }
}
