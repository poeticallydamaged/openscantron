package com.omrgrader.presentation.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.omrgrader.archive.ArchiveNaming
import com.omrgrader.camera.CameraXController
import com.omrgrader.camera.ImageQualityAnalyzer
import com.omrgrader.camera.rememberGalleryImagePicker
import com.omrgrader.data.repository.ScanCommitResult
import com.omrgrader.domain.*
import com.omrgrader.omr.RasterImage
import com.omrgrader.platform.AndroidImages
import com.omrgrader.platform.ImageOpsFactory
import com.omrgrader.presentation.components.*
import com.omrgrader.presentation.navigation.Routes
import com.omrgrader.presentation.viewmodels.*
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanningCameraScreen(
    examId: String,
    scanVm: ScanViewModel,
    settingsVm: SettingsViewModel,
    nav: NavHostController
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by scanVm.state.collectAsState()
    val scanSettings by scanVm.settings.collectAsState()
    val uiSettings by settingsVm.settings.collectAsState()
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { permission = it }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var rebind by remember { mutableStateOf(0) }
    var torchOn by remember { mutableStateOf(false) }
    val controller = remember { CameraXController(context) }
    val imageOps = remember { ImageOpsFactory.create() }
    val autoCaptured = remember { AtomicBoolean(false) }
    val analyzer = remember(imageOps, scanSettings.thresholds, uiSettings.autoCapture) {
        ImageQualityAnalyzer(imageOps, scanSettings.thresholds) { report ->
            scanVm.qualityReport(report)
            if (uiSettings.autoCapture && report.passed && autoCaptured.compareAndSet(false, true)) {
                controller.capture({ bytes ->
                    scanVm.setCaptured(bytes)
                    nav.navigate(Routes.captureReview(examId))
                }, { scanVm.fail(it) })
            }
        }
    }
    val importImage = rememberGalleryImagePicker(context, { bytes ->
        scanVm.setCaptured(bytes)
        nav.navigate(Routes.captureReview(examId))
    }, { scanVm.fail(it) })
    LaunchedEffect(examId) { scanVm.start(examId) }
    LaunchedEffect(Unit) { if (!permission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(state.captured) { if (state.captured == null) autoCaptured.set(false) }
    DisposableEffect(previewView, rebind, permission, analyzer) {
        if (permission && previewView != null) {
            controller.bind(previewView!!, lifecycleOwner, analyzer) { scanVm.fail(it) }
        }
        onDispose { controller.close() }
    }
    AppScreen("Scanning Camera", UiState.Success(Unit), onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize()) {
            if (!permission) {
                MessageBanner("Camera permission is required for live preview. Gallery import remains available.", true)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = this
                        }
                    })
                    Card(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Quality: ${if (state.quality?.passed == true) "PASS" else "CHECK"}")
                            state.quality?.warnings?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("Short side: ${state.quality?.metrics?.minSidePixels ?: 0} px")
                            Text("Markers: ${state.quality?.metrics?.markerCount ?: 0} · skew " +
                                "${"%.1f".format(state.quality?.metrics?.skewDegrees ?: 0.0)}°")
                        }
                    }
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        Text("Auto-capture")
                        Switch(checked = uiSettings.autoCapture,
                            onCheckedChange = { settingsVm.setAutoCapture(it) })
                    }
                }
            }
            state.error?.let { MessageBanner(it, isError = true) }
            state.message.takeIf { it.isNotBlank() }?.let { MessageBanner(it) }
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        controller.capture({ bytes ->
                            scanVm.setCaptured(bytes)
                            nav.navigate(Routes.captureReview(examId))
                        }, { scanVm.fail(it) })
                    }) { Text("Capture") }
                    Button(onClick = { torchOn = controller.toggleFlash() }) {
                        Text(if (torchOn) "Torch off" else "Torch on")
                    }
                    Button(onClick = { controller.switchLens(); rebind++ }) { Text("Switch lens") }
                    Button(onClick = { importImage() }) { Text("Gallery import") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { nav.navigate(Routes.batchSummary(examId)) }) { Text("Batch summary") }
                    Button(enabled = state.captured != null,
                        onClick = { nav.navigate(Routes.captureReview(examId)) }) { Text("Review pending") }
                    Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Settings") }
                }
            }
        }
    }
}

@Composable
fun CapturedImageReviewScreen(
    examId: String, scanVm: ScanViewModel, nav: NavHostController
) {
    val state by scanVm.state.collectAsState()
    val bytes = state.captured
    val bitmap = remember(bytes, state.rotation) {
        if (bytes == null) null else {
            val base = AndroidImages.toBitmap(AndroidImages.decode(bytes).image)
            if (state.rotation == 0) base else {
                val matrix = Matrix().apply { postRotate(state.rotation.toFloat()) }
                android.graphics.Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
                    .also { if (it != base) base.recycle() }
            }
        }
    }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    val uiState: UiState<ByteArray> = when {
        bytes == null -> UiState.Empty("No captured image. Return to the camera or import an image.")
        else -> UiState.Success(bytes)
    }
    AppScreen("Captured Image Review", uiState, onBack = { nav.popBackStack() }) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                }
            }, update = { it.setImageBitmap(bitmap) })
            Text("User rotation: ${state.rotation}°. The original bytes are retained until confirmed/deleted.")
            state.quality?.warnings?.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            state.error?.let { MessageBanner(it, isError = true) }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { scanVm.rotate() }) { Text("Rotate 90°") }
                    Button(onClick = { scanVm.retake(); nav.popBackStack() }) { Text("Retake") }
                    Button(onClick = { nav.navigate(Routes.scanning(examId)) }) { Text("Back to camera") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = bytes != null && !state.busy, onClick = {
                        scanVm.process()
                        nav.navigate(Routes.processing(examId))
                    }) { Text("Process OMR") }
                    Button(onClick = { nav.navigate(Routes.batchSummary(examId)) }) { Text("Batch summary") }
                }
            }
        }
    }
}

@Composable
fun OmrProcessingStatusScreen(
    examId: String, scanVm: ScanViewModel, nav: NavHostController
) {
    val state by scanVm.state.collectAsState()
    val duplicate = state.duplicate
    val uiState: UiState<ScanCommitResult.Success> = when {
        state.busy -> UiState.Loading
        state.error != null -> UiState.Error(state.error!!)
        state.result is ScanCommitResult.Success -> UiState.Success(state.result as ScanCommitResult.Success)
        else -> UiState.Empty("No processing result yet. Capture and process a sheet first.")
    }
    AppScreen("OMR Processing Status", uiState, onBack = { nav.popBackStack() }) { result ->
        val r = result.recognition
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MessageBanner("Submission ${result.submissionId} committed; archive ${result.archiveStatus}")
            InfoRow("Template", r.templateCode)
            InfoRow("Registration", "${r.markerScore} / alignment ${r.alignmentScore}")
            InfoRow("Student ID", r.studentId ?: "UNASSIGNED")
            InfoRow("Identification", r.identificationMode.name)
            InfoRow("Manual assignment required", r.needsManualAssignment.toString())
            InfoRow("Pending review items", result.grading.pendingReviewCount.toString())
            InfoRow("Score", "${result.grading.score} / ${result.grading.maxScore} (${result.grading.passingStatus})")
            if (r.warnings.isNotEmpty()) {
                Card { Column(Modifier.padding(8.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                    r.warnings.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.verification(result.submissionId, examId)) }) {
                    Text("Verify recognition")
                }
                Button(onClick = { nav.navigate(Routes.batchSummary(examId)) }) { Text("Batch summary") }
                Button(onClick = { nav.navigate(Routes.scanning(examId)) }) { Text("Scan next") }
            }
            Button(onClick = { nav.navigate(Routes.results(examId)) }) { Text("Examination results") }
        }
    }
    if (duplicate != null) {
        AlertDialog(onDismissRequest = { scanVm.clearDuplicate() },
            title = { Text("Duplicate submission detected") },
            text = { Text("${duplicate.reason}. Existing submission ${duplicate.existingSubmissionId}. " +
                "Choose an explicit resolution.") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { scanVm.resolveDuplicate(DuplicateResolution.REPLACE) }) { Text("Replace") }
                    TextButton(onClick = { scanVm.resolveDuplicate(DuplicateResolution.RETAIN) }) { Text("Retain") }
                    TextButton(onClick = { scanVm.resolveDuplicate(DuplicateResolution.DISCARD) }) { Text("Discard") }
                }
            },
            dismissButton = { TextButton(onClick = { scanVm.clearDuplicate() }) { Text("Cancel") } })
    }
}

@Composable
private fun CropView(crop: RasterImage?, modifier: Modifier = Modifier) {
    val bitmap = remember(crop) { crop?.let { AndroidImages.toBitmap(it) } }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    AndroidView(modifier = modifier, factory = { ctx ->
        ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
    }, update = { it.setImageBitmap(bitmap) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionVerificationScreen(
    submissionId: String, examId: String,
    vm: VerificationViewModel, examVm: ExamViewModel,
    scanVm: ScanViewModel, docs: DocumentsViewModel,
    nav: NavHostController
) {
    val context = LocalContext.current
    LaunchedEffect(submissionId) { vm.select(submissionId) }
    val rowsState by vm.recognitions.collectAsState()
    val submissionState by vm.submission.collectAsState()
    val message by vm.message.collectAsState()
    val studentsState by examVm.students.collectAsState()
    val recognition by scanVm.recognition.collectAsState()
    var note by remember { mutableStateOf("") }
    var selectedStudent by remember { mutableStateOf<String?>(null) }
    var assignExpanded by remember { mutableStateOf(false) }
    AppScreen("Recognition Verification", rowsState, onBack = { nav.popBackStack() }) { rows ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            OperationBanner(message)
            val sub = (submissionState as? UiState.Success)?.data
            InfoRow("Submission", submissionId)
            InfoRow("Current student", sub?.studentId ?: "UNASSIGNED")
            val students = (studentsState as? UiState.Success)?.data ?: emptyList()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { assignExpanded = true }) { Text("Assign student") }
                DropdownMenu(expanded = assignExpanded, onDismissRequest = { assignExpanded = false }) {
                    students.forEach { student ->
                        DropdownMenuItem(text = { Text("${student.studentNumber} · ${student.fullName}") },
                            onClick = { selectedStudent = student.id; assignExpanded = false })
                    }
                }
                Button(enabled = selectedStudent != null, onClick = {
                    selectedStudent?.let { vm.assign(it) }
                }) { Text("Save assignment") }
            }
            selectedStudent?.let { id ->
                val student = students.firstOrNull { it.id == id }
                Text("Selected: ${student?.studentNumber ?: id}")
            }
            OutlinedTextField(value = note, onValueChange = { note = it },
                label = { Text("Correction provenance note") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                items(rows) { row ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Q${row.questionNumber.toString().padStart(3, '0')} · " +
                                "${row.classification} · confidence ${"%.3f".format(row.confidence)}")
                            InfoRow("Machine prediction", row.recognizedAnswer)
                            InfoRow("Final answer", row.finalAnswer)
                            InfoRow("Corrected", "${row.manuallyCorrected} at ${row.correctedAt ?: "-"}")
                            CropView(recognition?.cropForQuestion(row.questionNumber, 3),
                                Modifier.fillMaxWidth().height(68.dp))
                            val alternatives = (row.alternatives + listOf(
                                row.recognizedAnswer, CellValue.BLANK, CellValue.MULTIPLE,
                                CellValue.AMBIGUOUS, CellValue.INVALID, CellValue.PENDING_REVIEW
                            )).filter { it.isNotBlank() }.distinct()
                            Text("Non-destructive overrides", style = MaterialTheme.typography.labelMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                alternatives.take(9).forEach { answer ->
                                    TextButton(onClick = {
                                        vm.correct(row.questionNumber, answer, note)
                                    }) { Text(answer) }
                                }
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.confirm() }) { Text("Confirm grading") }
                    Button(onClick = { vm.clear() }) { Text("Clear message") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportButton("Student report PDF", "application/pdf", context, docs,
                        produce = { "EXAM_${examId}_STUDENT_RESULT_${submissionId}.pdf" to
                            docs.studentReportPdf(examId, submissionId) })
                    Button(onClick = { nav.navigate(Routes.results(examId)) }) { Text("Results") }
                    Button(onClick = { nav.navigate(Routes.batchSummary(examId)) }) { Text("Batch summary") }
                }
                Button(onClick = { nav.popBackStack() }) { Text("Back") }
            }
        }
    }
}

@Composable
fun BatchScanningSummaryScreen(
    examId: String, scanVm: ScanViewModel, docs: DocumentsViewModel, nav: NavHostController
) {
    val context = LocalContext.current
    val state by scanVm.state.collectAsState()
    val batchState by scanVm.batch.collectAsState()
    LaunchedEffect(examId) { scanVm.start(examId) }
    AppScreen("Batch Scanning Summary", batchState, onBack = { nav.popBackStack() }) { subs ->
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            state.message.takeIf { it.isNotBlank() }?.let { MessageBanner(it) }
            state.error?.let { MessageBanner(it, isError = true) }
            Row(Modifier.fillMaxWidth()) {
                StatCard("Scanned", subs.size.toString(), Modifier.weight(1f))
                StatCard("Graded", subs.count { it.gradingStatus == GradingStatus.GRADED }.toString(), Modifier.weight(1f))
                StatCard("Pending", subs.count { it.gradingStatus == GradingStatus.PENDING_REVIEW }.toString(), Modifier.weight(1f))
            }
            val mean = if (subs.isEmpty()) 0.0 else subs.sumOf { it.score ?: 0.0 } / subs.size
            InfoRow("Mean score", "%.2f".format(mean))
            InfoRow("Batch", state.batchId)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExportButton("Generate batch archive PDF", "application/pdf", context, docs,
                    enabled = state.batchId.isNotBlank(),
                    produce = { ArchiveNaming.batch(examId, state.batchId) to
                        docs.batchPdf(examId, state.batchId) })
                Button(onClick = { scanVm.refreshBatch(examId) }) { Text("Refresh counters") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { nav.navigate(Routes.scanning(examId)) }) { Text("Scan next") }
                Button(onClick = { nav.navigate(Routes.results(examId)) }) { Text("Results") }
                Button(onClick = { nav.navigate(Routes.ARCHIVES) }) { Text("All archives") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(subs) { sub ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(Modifier.padding(10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(sub.id)
                                Text("${sub.studentId ?: "UNASSIGNED"} · ${sub.gradingStatus} · " +
                                    "${sub.score ?: 0.0} · ${sub.processingStatus}")
                            }
                            TextButton(onClick = { nav.navigate(Routes.verification(sub.id, examId)) }) {
                                Text("Verify")
                            }
                        }
                    }
                }
            }
        }
    }
}
