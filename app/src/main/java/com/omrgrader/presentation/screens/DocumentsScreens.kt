package com.omrgrader.presentation.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.omrgrader.BuildConfig
import com.omrgrader.archive.RestoreResult
import com.omrgrader.data.preferences.ThemeMode
import com.omrgrader.domain.*
import com.omrgrader.export.DatasetSynchronizer
import com.omrgrader.presentation.MainActivity
import com.omrgrader.presentation.components.*
import com.omrgrader.presentation.navigation.Routes
import com.omrgrader.presentation.viewmodels.*
import com.omrgrader.platform.printPdf
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ScannedAnswerArchivesScreen(docs: DocumentsViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val state by docs.archives.collectAsState()
    val message by docs.message.collectAsState()
    val storage by docs.storage.collectAsState()
    val shareExisting = rememberShareExistingFile(context, docs)
    val openExisting = rememberOpenExistingFile(context, docs)
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { docs.refreshStorage() }
    AppScreen("Scanned Answer Archives", state, onBack = { nav.popBackStack() },
        onRetry = { docs.refreshStorage() }) { archives ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            InfoRow("Storage", storage.ifBlank { "measuring…" })
            OperationBanner(message)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { docs.refreshStorage() }) { Text("Refresh storage") }
                Button(onClick = { confirmDelete = true }) { Text("Delete raw images (explicit)") }
                Button(onClick = { nav.navigate(Routes.EXAMS) }) { Text("Choose exam for exports") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(archives) { archive ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(archive.pdfFileName, style = MaterialTheme.typography.titleSmall)
                            Text("${archive.generationStatus} · ${archive.pdfFileSize} bytes · " +
                                "${archive.generatedAt ?: "not generated"}")
                            Text("SHA-256 ${archive.pdfChecksum.take(16)}…",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    enabled = archive.generationStatus == GenerationStatus.GENERATED,
                                    onClick = { nav.navigate(Routes.pdfViewer(archive.pdfFilePath)) }
                                ) { Text("View") }
                                TextButton(
                                    enabled = archive.generationStatus == GenerationStatus.GENERATED,
                                    onClick = { shareExisting(archive.pdfFilePath, "application/pdf") }
                                ) { Text("Share") }
                                TextButton(
                                    enabled = archive.generationStatus == GenerationStatus.GENERATED,
                                    onClick = { openExisting(archive.pdfFilePath, "application/pdf") }
                                ) { Text("Open") }
                                TextButton(onClick = { docs.verifyArchive(archive) }) { Text("Verify checksum") }
                                TextButton(onClick = { docs.retryArchive(archive.submissionId) }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
            Button(onClick = { nav.popBackStack() }) { Text("Back") }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete raw captures?") },
            text = { Text("Archived PDFs and checksums stay intact. This only removes source JPEGs " +
                "after explicit confirmation.") },
            confirmButton = {
                TextButton(onClick = { docs.deleteRawImages(); confirmDelete = false }) { Text("Confirm delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
fun PdfDocumentViewerScreen(path: String, docs: DocumentsViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val shareExisting = rememberShareExistingFile(context, docs)
    val openExisting = rememberOpenExistingFile(context, docs)
    var page by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    val state: UiState<String> = if (File(path).isFile) UiState.Success(path)
    else UiState.Error("PDF file is missing: $path")
    AppScreen("PDF Document Viewer", state, onBack = { nav.popBackStack() }) { pdfPath ->
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            PdfPreview(pdfPath, Modifier.fillMaxWidth().weight(1f), page) { pageCount = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }
                Text("Page ${page + 1} / ${pageCount.coerceAtLeast(1)}")
                TextButton(enabled = page < pageCount - 1, onClick = { page++ }) { Text("Next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { shareExisting(pdfPath, "application/pdf") }) { Text("Share") }
                Button(onClick = { openExisting(pdfPath, "application/pdf") }) { Text("Open externally") }
                Button(onClick = {
                    val file = File(pdfPath)
                    printPdf(context, file.name, file.readBytes())
                }) { Text("Print") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
}

@Composable
fun CsvDatasetsManagerScreen(
    examId: String, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    LaunchedEffect(examId) { docs.selectExam(examId) }
    val state by docs.datasets.collectAsState()
    val message by docs.message.collectAsState()
    val shareExisting = rememberShareExistingFile(context, docs)
    val openExisting = rememberOpenExistingFile(context, docs)
    AppScreen("CSV Datasets Manager", state, onBack = { nav.popBackStack() },
        onRetry = { docs.selectExam(examId) }) { datasets ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OperationBanner(message)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { docs.regenerate(examId) }) { Text("Regenerate from Room") }
                Button(onClick = { docs.verifyDatasets(examId) }) { Text("Verify integrity") }
                Button(onClick = { nav.navigate(Routes.exportHub(examId)) }) { Text("Export hub") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(datasets) { dataset ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(dataset.fileName, style = MaterialTheme.typography.titleSmall)
                            Text("${dataset.datasetType} · ${dataset.synchronizationStatus} · " +
                                "v${dataset.generationVersion} · ${dataset.fileSize} bytes")
                            Text("Records ${dataset.recordCount} · last ${dataset.lastGeneratedAt ?: "never"}")
                            dataset.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = dataset.filePath.isNotBlank(),
                                    onClick = { nav.navigate(Routes.csvPreview(dataset.filePath)) }) {
                                    Text("Preview")
                                }
                                TextButton(enabled = dataset.filePath.isNotBlank(),
                                    onClick = { shareExisting(dataset.filePath, "text/csv") }) {
                                    Text("Share")
                                }
                                TextButton(enabled = dataset.filePath.isNotBlank(),
                                    onClick = { openExisting(dataset.filePath, "text/csv") }) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }
            }
            Button(onClick = { nav.popBackStack() }) { Text("Back") }
        }
    }
}

@Composable
fun CsvDatasetPreviewScreen(path: String, docs: DocumentsViewModel, nav: NavHostController) {
    val context = LocalContext.current
    LaunchedEffect(path) { docs.previewCsv(path) }
    val state by docs.preview.collectAsState()
    var query by remember { mutableStateOf("") }
    val shareExisting = rememberShareExistingFile(context, docs)
    val openExisting = rememberOpenExistingFile(context, docs)
    AppScreen("CSV Dataset Preview", state, onBack = { nav.popBackStack() },
        onRetry = { docs.previewCsv(path) }) { rows ->
        val header = rows.firstOrNull() ?: emptyList()
        val filtered = rows.drop(1).filter { row ->
            query.isBlank() || row.any { it.contains(query, ignoreCase = true) }
        }
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("Filter rows") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text("Showing up to 100 data rows from the atomic CSV file.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { docs.previewCsv(path) }) { Text("Reload") }
                Button(onClick = { shareExisting(path, "text/csv") }) { Text("Share CSV") }
                Button(onClick = { openExisting(path, "text/csv") }) { Text("Open CSV") }
            }
            if (filtered.isEmpty()) MessageBanner("No rows match this filter.")
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        header.forEach { cell -> Text(cell, Modifier.width(150.dp).padding(4.dp),
                            style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                    }
                }
                items(filtered) { row ->
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        row.forEach { cell -> Text(cell, Modifier.width(150.dp).padding(4.dp), maxLines = 1) }
                    }
                }
            }
            Button(onClick = { nav.popBackStack() }) { Text("Back to datasets") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSharingHubScreen(
    examId: String, examVm: ExamViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    val examsState by examVm.exams.collectAsState()
    var selected by remember { mutableStateOf(setOf(examId)) }
    var lastExport by remember { mutableStateOf<Triple<String, ByteArray, String>?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val saveToTree = rememberTreeSaver(context, docs)
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val result = docs.restore(uri)
                message = if (result.success)
                    "Restored ${result.extracted.size} files. Restart the app to install the Room database."
                else "Restore failed: ${result.errors.joinToString(", ")}"
            } catch (e: Exception) { message = "Restore failed: ${e.javaClass.simpleName}" }
        }
    }
    LaunchedEffect(examId) { selected = setOf(examId) }
    AppScreen("Export & Sharing Hub", examsState, onBack = { nav.popBackStack() }) { exams ->
        val first = selected.firstOrNull()
        Column(Modifier.fillMaxSize().padding(10.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Teacher-triggered exports. All bytes are generated from Room snapshots.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { selected = exams.map { it.id }.toSet() }) { Text("Select all") }
                Button(onClick = { selected = emptySet() }) { Text("Clear selection") }
                Button(onClick = { nav.navigate(Routes.EXAMS) }) { Text("Examination list") }
            }
            Card(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                    exams.forEach { exam ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(exam.title)
                            Checkbox(checked = exam.id in selected, onCheckedChange = { checked ->
                                selected = if (checked) selected + exam.id else selected - exam.id
                            })
                        }
                    }
                }
            }
            if (message.isNotBlank()) MessageBanner(message, isError = message.startsWith("Restore failed"))
            ExportButton("Wide student answers CSV", "text/csv", context, docs,
                enabled = first != null,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = {
                    val id = checkNotNull(first) { "Select an examination" }
                    DatasetSynchronizer.fileName(id, DatasetType.WIDE_ANSWERS) to docs.wideCsv(id)
                })
            ExportButton("Recognition audit CSV", "text/csv", context, docs,
                enabled = first != null,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = {
                    val id = checkNotNull(first) { "Select an examination" }
                    DatasetSynchronizer.fileName(id, DatasetType.RECOGNITION_AUDIT) to docs.auditCsv(id)
                })
            ExportButton("Consolidated multi-exam CSV", "text/csv", context, docs,
                enabled = selected.isNotEmpty(),
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = { "CONSOLIDATED_STUDENT_ANSWERS.csv" to docs.consolidatedCsv(selected.toList()) })
            ExportButton("Exam report PDF", "application/pdf", context, docs,
                enabled = first != null,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = {
                    val id = checkNotNull(first) { "Select an examination" }
                    "EXAM_${id}_PERFORMANCE_REPORT.pdf" to docs.examReportPdf(id)
                })
            ExportButton("Summary CSV", "text/csv", context, docs,
                enabled = first != null,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = {
                    val id = checkNotNull(first) { "Select an examination" }
                    "EXAM_${id}_SUMMARY.csv" to docs.summaryCsv(id)
                })
            ExportButton("Blank answer-sheet PDF", "application/pdf", context, docs,
                enabled = first != null,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = {
                    val id = checkNotNull(first) { "Select an examination" }
                    val template = checkNotNull(exams.firstOrNull { it.id == id }?.activeTemplateId) {
                        "Configure a template first"
                    }
                    "EXAM_${id}_BLANK_SHEET.pdf" to docs.blankSheet(template, 1)
                })
            ExportButton("Create + share backup ZIP", "application/zip", context, docs,
                onProduced = { name, bytes, mime ->
                    lastExport = Triple(name, bytes, mime); message = "Shared $name"
                },
                produce = { "OPEN_SCANTRON_BACKUP.zip" to docs.backup(true).bytes })
            Button(onClick = { restoreLauncher.launch("application/zip") }) { Text("Restore backup ZIP (validates checksums)") }
            Button(enabled = lastExport != null, onClick = {
                lastExport?.let { (name, bytes, mime) -> saveToTree(name, bytes, mime) }
            }) { Text("Save last export to SAF folder") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = first != null,
                    onClick = { first?.let { nav.navigate(Routes.template(it)) } }) {
                    Text("Template / print")
                }
                Button(onClick = { nav.navigate(Routes.ARCHIVES) }) { Text("Archives") }
                Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Settings") }
            }
            Button(enabled = first != null,
                onClick = { first?.let { nav.navigate(Routes.results(it)) } }) {
                Text("Back to results")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsVm: SettingsViewModel, nav: NavHostController) {
    val context = LocalContext.current
    val settings by settingsVm.settings.collectAsState(initial = com.omrgrader.data.preferences.AppSettings())
    val message by settingsVm.message.collectAsState()
    val storage by settingsVm.storageBytes.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settingsVm.refreshStorage() }
    AppScreen("Settings", UiState.Success(settings), onBack = { nav.popBackStack() }) { s ->
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.isNotBlank()) MessageBanner(message)
            InfoRow("OpenCV Android SDK compiled", BuildConfig.HAS_OPENCV_SDK.toString())
            InfoRow("Offline core", "No account, analytics or network call")
            InfoRow("Storage", "$storage bytes")
            Text("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = s.theme == mode,
                        onClick = { settingsVm.setTheme(mode) },
                        label = { Text(mode.name) })
                }
            }
            Text("JPEG quality: ${s.compressionQuality}")
            Slider(value = s.compressionQuality.toFloat(),
                onValueChange = { settingsVm.setCompression(it.toInt()) },
                valueRange = 40f..100f, steps = 12)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Include processed page 2 in archives")
                Switch(checked = s.includeProcessingPage,
                    onCheckedChange = { settingsVm.setProcessingPage(it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Auto-capture on quality gate pass")
                Switch(checked = s.autoCapture, onCheckedChange = { settingsVm.setAutoCapture(it) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sort batch PDF by student ID")
                Switch(checked = s.sortBatchByStudentId, onCheckedChange = { settingsVm.setSortBatch(it) })
            }
            InfoRow("Mark fill threshold", "%.3f".format(s.thresholds.markFillRatio))
            InfoRow("Blank fill threshold", "%.3f".format(s.thresholds.blankFillRatio))
            InfoRow("Review confidence below", "%.3f".format(s.thresholds.reviewConfidenceBelow))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { settingsVm.calibrate() }) { Text("Calibrate OMR thresholds") }
                Button(onClick = { settingsVm.resetThresholds() }) { Text("Reset thresholds") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { settingsVm.refreshStorage() }) { Text("Refresh storage") }
                Button(onClick = { settingsVm.clearMessage() }) { Text("Clear message") }
                Button(onClick = { confirmDelete = true }) { Text("Delete raw images (explicit)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.backup("none")) }) { Text("Backup & Restore") }
                Button(onClick = { nav.navigate(Routes.EXAMS) }) { Text("Choose exam for export") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Delete raw capture images?") },
            text = { Text("This requires explicit confirmation and never deletes archived PDFs, " +
                "checksums, CSV datasets or Room records.") },
            confirmButton = {
                TextButton(onClick = { settingsVm.deleteRawImages(); confirmDelete = false }) {
                    Text("Confirm delete")
                }
            }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
fun BackupRestoreScreen(
    examId: String, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareBytes = rememberShareBytes(context, docs)
    val saveToTree = rememberTreeSaver(context, docs)
    var includeRaw by remember { mutableStateOf(true) }
    var lastBackup by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var message by remember { mutableStateOf("") }
    var restoreResult by remember { mutableStateOf<RestoreResult?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val result = docs.restore(uri)
                restoreResult = result
                message = if (result.success)
                    "Restore validated ${result.extracted.size} files. Restart the app to install the database."
                else "Restore failed: ${result.errors.joinToString(", ")}"
            } catch (e: Exception) {
                message = "Restore failed: ${e.javaClass.simpleName}"
            }
        }
    }
    AppScreen("Backup & Restore", UiState.Success(Unit), onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Relational, checksum and ZIP-entry integrity are validated before restore. " +
                "Room database installation is restart-safe.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Include raw source images in backup")
                Switch(checked = includeRaw, onCheckedChange = { includeRaw = it })
            }
            ExportButton("Create + share backup ZIP", "application/zip", context, docs,
                onProduced = { name, bytes, _ ->
                    lastBackup = name to bytes
                    message = "Created $name (${bytes.size} bytes)"
                },
                produce = { "OPEN_SCANTRON_BACKUP.zip" to docs.backup(includeRaw).bytes })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = lastBackup != null, onClick = {
                    lastBackup?.let { (name, bytes) -> shareBytes(name, bytes, "application/zip") }
                }) { Text("Share last backup") }
                Button(enabled = lastBackup != null, onClick = {
                    lastBackup?.let { (name, bytes) -> saveToTree(name, bytes, "application/zip") }
                }) { Text("Save last backup to folder") }
            }
            Button(onClick = { restoreLauncher.launch("application/zip") }) {
                Text("Select backup ZIP to validate + restore")
            }
            restoreResult?.let { result ->
                InfoRow("Restore success", result.success.toString())
                InfoRow("Extracted files", result.extracted.size.toString())
                InfoRow("Errors", result.errors.joinToString(", ").ifBlank { "none" })
                InfoRow("Warnings", result.warnings.joinToString(", ").ifBlank { "none" })
            }
            if (message.isNotBlank()) MessageBanner(message, isError = message.startsWith("Restore failed"))
            Button(onClick = {
                val intent = Intent.makeRestartActivityTask(
                    ComponentName(context, MainActivity::class.java))
                context.startActivity(intent)
            }) { Text("Restart app now") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    nav.navigate(if (examId == "none") Routes.EXAMS else Routes.exportHub(examId))
                }) { Text("Export hub") }
                Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Settings") }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
}
