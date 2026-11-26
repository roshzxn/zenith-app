package com.zenith.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.zenith.app.data.network.FamilySupabaseClient
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

/**
 * Camera QR scanner dialog tailored for scanning Zenith Family invitation QR codes.
 */
@Composable
fun FamilyInviteQrScannerDialog(
    onInviteScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var scanError by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

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
                                text = "Point at the family QR code to auto-fill invite",
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
                        .height(300.dp)
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
                                    scanError = "Not a Zenith Family invite QR. Ask the family admin to generate an invite QR."
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

                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enter Code Manually", color = ZenithTheme.textPrimary)
                }
            }
        }
    }
}
