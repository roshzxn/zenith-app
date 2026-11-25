package com.zenith.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.zenith.app.data.export.*
import com.zenith.app.data.models.TransactionEntity
import com.zenith.app.data.models.TransactionType
import com.zenith.app.ui.theme.ZenithTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ExportDataModal(
    transactions: List<TransactionEntity>,
    currencySymbol: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedFormat by remember { mutableStateOf(ExportFormat.PDF) }
    var selectedDateFilter by remember { mutableStateOf(DateFilterType.THIS_MONTH) }
    var selectedTypeFilter by remember { mutableStateOf<TransactionType?>(null) }

    var incTransactions by remember { mutableStateOf(true) }
    var incCategories by remember { mutableStateOf(true) }
    var incNotes by remember { mutableStateOf(true) }
    var incPaymentMethods by remember { mutableStateOf(true) }
    val incBudgetSummary = true

    var isGenerating by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<ExportResult?>(null) }

    val filteredCount = remember(transactions, selectedDateFilter, selectedTypeFilter) {
        val options = ExportOptions(
            format = selectedFormat,
            dateFilter = selectedDateFilter,
            typeFilter = selectedTypeFilter,
            scopeFilter = null
        )
        ExportImportService.filterTransactions(transactions, options).size
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ZenithTheme.cardSurface,
            border = BorderStroke(1.dp, ZenithTheme.borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .testTag("export_data_modal")
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Export Your Data",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )
                        Text(
                            text = "Download your Zenith financial activity.",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("close_export_modal")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = ZenithTheme.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (exportResult == null) {
                    Text(
                        text = "EXPORT FORMAT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatCard(
                            title = "PDF",
                            subtitle = "Visual Report",
                            icon = Icons.Default.PictureAsPdf,
                            isSelected = selectedFormat == ExportFormat.PDF,
                            accentColor = ZenithTheme.accentRed,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedFormat = ExportFormat.PDF }
                        )

                        FormatCard(
                            title = "CSV",
                            subtitle = "Sheets / Excel",
                            icon = Icons.Default.TableChart,
                            isSelected = selectedFormat == ExportFormat.CSV,
                            accentColor = ZenithTheme.accentGreen,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedFormat = ExportFormat.CSV }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FormatCard(
                            title = "Excel",
                            subtitle = "Spreadsheet",
                            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                            isSelected = selectedFormat == ExportFormat.EXCEL,
                            accentColor = ZenithTheme.accentCyan,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedFormat = ExportFormat.EXCEL }
                        )

                        FormatCard(
                            title = "JSON",
                            subtitle = "Full Backup",
                            icon = Icons.Default.Code,
                            isSelected = selectedFormat == ExportFormat.JSON,
                            accentColor = ZenithTheme.accentAmber,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedFormat = ExportFormat.JSON }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "DATE RANGE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DateFilterType.entries.forEach { df ->
                            FilterChip(
                                selected = selectedDateFilter == df,
                                onClick = { selectedDateFilter = df },
                                label = { Text(df.label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text("Type Filter", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.textSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = selectedTypeFilter == null,
                            onClick = { selectedTypeFilter = null },
                            label = { Text("All", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedTypeFilter == TransactionType.EXPENSE,
                            onClick = { selectedTypeFilter = TransactionType.EXPENSE },
                            label = { Text("Expense", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = selectedTypeFilter == TransactionType.INCOME,
                            onClick = { selectedTypeFilter = TransactionType.INCOME },
                            label = { Text("Income", fontSize = 11.sp) }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "INCLUDE DATA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ZenithTheme.textSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IncludeCheckbox("Transactions", incTransactions) { incTransactions = it }
                        IncludeCheckbox("Categories", incCategories) { incCategories = it }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IncludeCheckbox("Notes & Details", incNotes) { incNotes = it }
                        IncludeCheckbox("Payment Methods", incPaymentMethods) { incPaymentMethods = it }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ZenithTheme.accentAmber.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ZenithTheme.accentAmber.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = ZenithTheme.accentAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Exported files contain sensitive financial information. Store them securely.",
                                fontSize = 11.sp,
                                color = ZenithTheme.accentAmber
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isGenerating = true
                                delay(300)

                                val options = ExportOptions(
                                    format = selectedFormat,
                                    dateFilter = selectedDateFilter,
                                    typeFilter = selectedTypeFilter,
                                    scopeFilter = null,
                                    includeCategories = incCategories,
                                    includeNotes = incNotes,
                                    includePaymentMethods = incPaymentMethods,
                                    includeBudgetSummary = incBudgetSummary
                                )

                                val result = when (selectedFormat) {
                                    ExportFormat.PDF -> ExportImportService.exportPdf(context, transactions, currencySymbol, options)
                                    ExportFormat.CSV -> ExportImportService.exportCsv(context, transactions, if (currencySymbol == "₹") "INR" else "USD", options)
                                    ExportFormat.EXCEL -> ExportImportService.exportExcel(context, transactions, if (currencySymbol == "₹") "INR" else "USD", options)
                                    ExportFormat.JSON -> ExportImportService.exportJsonBackup(context, transactions, emptyList(), emptyList(), emptyList())
                                }

                                exportResult = result
                                isGenerating = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("btn_trigger_export"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen),
                        enabled = !isGenerating
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating File...", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export $filteredCount Records", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = ZenithTheme.accentGreen,
                            modifier = Modifier.size(54.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Export Complete",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = ZenithTheme.textPrimary
                        )

                        Text(
                            text = "Your Zenith transaction data has been exported successfully.",
                            fontSize = 12.sp,
                            color = ZenithTheme.textSecondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ZenithTheme.cardVariantSurface,
                            border = BorderStroke(1.dp, ZenithTheme.borderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("File Name", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                    Text(exportResult?.fileName ?: "", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZenithTheme.textPrimary)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Format", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                    Text(exportResult?.fileType ?: "", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ZenithTheme.accentAmber)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Records Included", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                    Text("${exportResult?.transactionCount ?: 0} transactions", fontSize = 11.sp, color = ZenithTheme.textPrimary)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("File Size", fontSize = 11.sp, color = ZenithTheme.textSecondary)
                                    Text(exportResult?.fileSizeFormatted ?: "", fontSize = 11.sp, color = ZenithTheme.textPrimary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { exportResult?.let { ExportImportService.shareFile(context, it) } },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("btn_share_export"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ZenithTheme.solidGreen)
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = { exportResult?.let { ExportImportService.openFile(context, it) } },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("btn_open_export"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        TextButton(
                            onClick = { exportResult = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Export Another File", color = ZenithTheme.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) accentColor.copy(alpha = 0.15f) else ZenithTheme.cardVariantSurface,
        border = BorderStroke(1.5.dp, if (isSelected) accentColor else ZenithTheme.borderColor),
        modifier = modifier
            .height(60.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) accentColor else ZenithTheme.borderColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) Color.White else ZenithTheme.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) ZenithTheme.textPrimary else ZenithTheme.textSecondary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = ZenithTheme.textMuted
                )
            }
        }
    }
}

@Composable
private fun IncludeCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = ZenithTheme.accentGreen,
                uncheckedColor = ZenithTheme.textSecondary
            )
        )
        Text(text = label, fontSize = 12.sp, color = ZenithTheme.textPrimary)
    }
}
