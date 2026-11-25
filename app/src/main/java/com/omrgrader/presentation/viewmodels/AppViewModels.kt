package com.omrgrader.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omrgrader.archive.BackupBundle
import com.omrgrader.archive.RestoreResult
import com.omrgrader.camera.ImageQualityAnalyzer
import com.omrgrader.core.Clock
import com.omrgrader.core.OmrResult
import com.omrgrader.data.preferences.AppSettings
import com.omrgrader.data.preferences.SettingsStore
import com.omrgrader.data.preferences.ThemeMode
import com.omrgrader.data.repository.DashboardStats
import com.omrgrader.data.repository.OmrRepository
import com.omrgrader.data.repository.ScanCommitRequest
import com.omrgrader.data.repository.ScanCommitResult
import com.omrgrader.domain.*
import com.omrgrader.omr.*
import com.omrgrader.export.*
import com.omrgrader.platform.AppDirectories
import com.omrgrader.platform.ImageOpsFactory
import com.omrgrader.platform.ZxingQrCodec
import com.omrgrader.platform.SafExporter
import com.omrgrader.archive.ArchiveService
import kotlinx.coroutines.withContext
import com.omrgrader.presentation.components.UiState
import com.omrgrader.presentation.components.asUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    repository: OmrRepository
) : ViewModel() {
    val dashboard: StateFlow<UiState<DashboardStats>> = repository.dashboard().asUiState(
        viewModelScope, { it.examCount == 0 && it.submissionCount == 0 },
        "No examinations yet. Create one from the Examination List."
    )
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val directories: AppDirectories
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsStore.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()
    private val _storage = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storage.asStateFlow()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsStore.setTheme(mode) }
    fun setCompression(value: Int) = viewModelScope.launch { settingsStore.setCompression(value) }
    fun setProcessingPage(value: Boolean) = viewModelScope.launch { settingsStore.setIncludeProcessingPage(value) }
    fun setAutoCapture(value: Boolean) = viewModelScope.launch { settingsStore.setAutoCapture(value) }
    fun setSortBatch(value: Boolean) = viewModelScope.launch { settingsStore.setSortBatch(value) }
    fun setThresholds(value: OmrThresholds) = viewModelScope.launch { settingsStore.setThresholds(value) }
    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) {
        _storage.value = directories.allFiles().sumOf { it.length() }
    }
    fun calibrate() = viewModelScope.launch(Dispatchers.Default) {
        val layout = LayoutFactory.build("calibration", PageSize.A4, 10, listOf("A", "B", "C", "D"), 8)
        val cases = (1..4).map { SyntheticSheetGenerator.generate(it * 101, SyntheticCondition.CLEAN, layout) }
        val roster = InMemoryRosterLookup(cases.mapIndexed { i, c -> Student("s$i", c.studentNumber, "S", "A") })
        val report = ThresholdCalibration.recommend(cases, InMemoryLayoutRegistry(listOf(layout)),
            roster, ImageOpsFactory.create(), PortableQrCodec, settings.value.thresholds)
        settingsStore.setThresholds(report.thresholds)
        _message.value = "Calibrated from ${report.samples} sheets; separation=" +
            "%.3f".format(report.estimatedSeparation)
    }
    fun resetThresholds() = viewModelScope.launch {
        settingsStore.setThresholds(OmrThresholds())
        _message.value = "OMR thresholds reset to measured defaults"
    }
    fun deleteRawImages() = viewModelScope.launch(Dispatchers.IO) {
        val files = directories.captures.listFiles()?.filter { it.isFile } ?: emptyList()
        val deleted = ArchiveService.deleteRawImages(files, "DELETE_RAW_IMAGES_CONFIRMED")
        refreshStorage()
        _message.value = "$deleted confirmed raw capture(s) deleted; archived PDFs retained"
    }
    fun clearMessage() { _message.value = "" }
}

data class ExamForm(
    val title: String = "",
    val subject: String = "",
    val description: String = "",
    val date: Long = System.currentTimeMillis(),
    val questionCount: Int = 20,
    val choices: List<String> = listOf("A", "B", "C", "D"),
    val pageSize: PageSize = PageSize.A4,
    val pointsPerCorrect: String = "1",
    val incorrectDeduction: String = "0",
    val unansweredPoints: String = "0",
    val partialCredit: Boolean = false,
    val passingThreshold: String = "60"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExamViewModel @Inject constructor(
    private val repository: OmrRepository,
    private val clock: Clock
) : ViewModel() {
    private val showArchived = MutableStateFlow(false)
    val exams: StateFlow<UiState<List<Examination>>> = combine(
        showArchived, repository.exams(), repository.allExams()
    ) { includeArchived, active, all -> if (includeArchived) all else active }
        .asUiState(viewModelScope, { it.isEmpty() }, "No examinations. Tap + to create one.")
    fun setShowArchived(value: Boolean) { showArchived.value = value }
    private val studentQuery = MutableStateFlow("")
    @OptIn(ExperimentalCoroutinesApi::class)
    val students: StateFlow<UiState<List<Student>>> = studentQuery.flatMapLatest { query ->
        repository.students(query)
    }.asUiState(viewModelScope, { it.isEmpty() }, "No students in the roster.")
    fun setStudentQuery(value: String) { studentQuery.value = value }
    private val examId = MutableStateFlow<String?>(null)
    val exam: StateFlow<UiState<Examination>> = examId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.exam(id)
    }.map { it?.let { UiState.Success(it) } ?: UiState.Empty("Select an examination") as UiState<Examination> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    val templates: StateFlow<UiState<List<AnswerSheetTemplate>>> = examId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.templates(id)
    }.asUiState(viewModelScope, { it.isEmpty() }, "No templates configured")
    val keys: StateFlow<UiState<List<AnswerKey>>> = examId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.keys(id)
    }.asUiState(viewModelScope, { it.isEmpty() }, "No answer key versions yet")

    private val _form = MutableStateFlow(ExamForm())
    val form: StateFlow<ExamForm> = _form.asStateFlow()
    private val _operation = MutableStateFlow<UiState<String>>(UiState.Empty(""))
    val operation: StateFlow<UiState<String>> = _operation.asStateFlow()
    private val _keyDraft = MutableStateFlow<Map<Int, String>>(emptyMap())
    val keyDraft: StateFlow<Map<Int, String>> = _keyDraft.asStateFlow()
    private val _import = MutableStateFlow<UiState<RosterImportResult>>(UiState.Empty("Select a CSV"))
    val import: StateFlow<UiState<RosterImportResult>> = _import.asStateFlow()

    fun selectExam(id: String) { examId.value = id }
    fun updateForm(form: ExamForm) { _form.value = form }
    fun loadForm(exam: Examination) {
        _form.value = ExamForm(exam.title, exam.subject, exam.description, exam.date,
            exam.totalQuestions, listOf("A", "B", "C", "D"), PageSize.A4,
            exam.scoringConfiguration.pointsPerCorrect.toString(),
            exam.scoringConfiguration.incorrectDeduction.toString(),
            exam.scoringConfiguration.unansweredPoints.toString(),
            exam.scoringConfiguration.partialCredit,
            exam.scoringConfiguration.passingThreshold.toString())
    }
    fun setKeyAnswer(question: Int, answer: String) {
        _keyDraft.value = _keyDraft.value + (question to answer)
    }
    fun clearKeyDraft() { _keyDraft.value = emptyMap() }
    fun fillKeyDraft(count: Int, answer: String) {
        _keyDraft.value = (1..count).associateWith { answer }
    }
    fun loadKeyDraft(key: AnswerKey) { _keyDraft.value = key.answers }

    private fun scoring(): ScoringConfiguration = ScoringConfiguration(
        pointsPerCorrect = _form.value.pointsPerCorrect.toDoubleOrNull()?.coerceAtLeast(0.0001) ?: 1.0,
        incorrectDeduction = _form.value.incorrectDeduction.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        unansweredPoints = _form.value.unansweredPoints.toDoubleOrNull() ?: 0.0,
        partialCredit = _form.value.partialCredit,
        passingThreshold = _form.value.passingThreshold.toDoubleOrNull() ?: 60.0
    )

    fun createExam(onDone: (String) -> Unit) = viewModelScope.launch {
        _operation.value = UiState.Loading
        val f = _form.value
        val result = repository.createExam(f.title, f.subject, f.description, f.date,
            f.questionCount, f.choices, scoring(), f.pageSize)
        _operation.value = when (result) {
            is OmrResult.Success -> { onDone(result.value.id); UiState.Success("Examination created") }
            is OmrResult.Failure -> UiState.Error(result.error.message ?: "create failed")
        }
    }

    fun updateExam(exam: Examination) = viewModelScope.launch {
        val f = _form.value
        val updated = exam.copy(title = f.title, subject = f.subject, description = f.description,
            date = f.date, totalQuestions = f.questionCount,
            totalPoints = scoring().maxScore(f.questionCount), scoringConfiguration = scoring(),
            updatedAt = clock.nowMillis())
        _operation.value = when (val r = repository.updateExam(updated)) {
            is OmrResult.Success -> UiState.Success("Saved")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "save failed")
        }
    }
    fun duplicate(id: String) = viewModelScope.launch { repository.duplicateExam(id) }
    fun archive(id: String, value: Boolean) = viewModelScope.launch { repository.archiveExam(id, value) }

    fun createTemplate(examId: String, pageSize: PageSize, count: Int, choices: List<String>, columns: Int) =
        viewModelScope.launch {
            _operation.value = UiState.Loading
            _operation.value = when (val r = repository.createTemplate(examId, pageSize, count, choices, columns)) {
                is OmrResult.Success -> UiState.Success("Template ${r.value.templateCode} created")
                is OmrResult.Failure -> UiState.Error(r.error.message ?: "template failed")
            }
        }

    fun saveKey(examId: String, choices: List<String>) = viewModelScope.launch {
        _operation.value = UiState.Loading
        _operation.value = when (val r = repository.saveKey(examId, _keyDraft.value, choices)) {
            is OmrResult.Success -> UiState.Success("Answer key v${r.value.version} activated")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "key failed")
        }
    }
    fun editActiveKey(examId: String, choices: List<String>) = viewModelScope.launch {
        _operation.value = when (val r = repository.editActiveKey(examId, _keyDraft.value, choices)) {
            is OmrResult.Success -> UiState.Success("v${r.value.newKey.version} created; regrade required=${r.value.regradeRequired}")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "edit failed")
        }
    }
    fun regrade(examId: String, keyId: String) = viewModelScope.launch {
        _operation.value = when (val r = repository.regradeExam(examId, keyId)) {
            is OmrResult.Success -> UiState.Success("${r.value} submissions regraded")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "regrade failed")
        }
    }
    fun saveStudent(student: Student) = viewModelScope.launch {
        _operation.value = when (val r = repository.saveStudent(student)) {
            is OmrResult.Success -> UiState.Success("Student saved")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "save failed")
        }
    }
    fun removeStudent(id: String) = viewModelScope.launch { repository.removeStudent(id) }
    fun importStudents(bytes: ByteArray) = viewModelScope.launch {
        _import.value = UiState.Loading
        _import.value = when (val r = repository.importStudents(bytes)) {
            is OmrResult.Success -> UiState.Success(r.value)
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "import failed")
        }
    }
    fun clearOperation() { _operation.value = UiState.Empty("") }
}

data class ScanUiState(
    val examId: String = "",
    val batchId: String = "",
    val captured: ByteArray? = null,
    val rotation: Int = 0,
    val busy: Boolean = false,
    val quality: QualityReport? = null,
    val result: ScanCommitResult? = null,
    val duplicate: DuplicateMatch? = null,
    val error: String? = null,
    val message: String = ""
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: OmrRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private val _recognition = MutableStateFlow<OmrRecognitionResult?>(null)
    val recognition: StateFlow<OmrRecognitionResult?> = _recognition.asStateFlow()
    private val _batch = MutableStateFlow<UiState<List<Submission>>>(UiState.Loading)
    val batch: StateFlow<UiState<List<Submission>>> = _batch.asStateFlow()
    val settings = settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun start(examId: String) = viewModelScope.launch {
        if (_state.value.examId == examId && _state.value.batchId.isNotBlank()) return@launch
        val recovered = repository.recoverableBatch(examId)
        val batchId = recovered?.id ?: repository.startBatch(examId)
        _state.value = _state.value.copy(examId = examId, batchId = batchId,
            message = if (recovered != null) "Recovered in-progress batch" else "Batch started")
        repository.submissions(examId).asUiState(viewModelScope, { it.isEmpty() }, "No scans yet")
            .onEach { _batch.value = it }.launchIn(viewModelScope)
    }
    fun refreshBatch(examId: String) = viewModelScope.launch {
        _batch.value = UiState.Loading
        val list = repository.submissions(examId).first()
        _batch.value = if (list.isEmpty()) UiState.Empty("No scans yet") else UiState.Success(list)
    }
    fun setCaptured(bytes: ByteArray) {
        _state.value = _state.value.copy(captured = bytes, result = null, duplicate = null, error = null)
    }
    fun rotate() { _state.value = _state.value.copy(rotation = (_state.value.rotation + 90) % 360) }
    fun retake() { _state.value = _state.value.copy(captured = null, rotation = 0, result = null, error = null) }
    fun qualityReport(report: QualityReport) { _state.value = _state.value.copy(quality = report) }
    fun fail(message: String) { _state.value = _state.value.copy(error = message) }

    fun process(resolution: DuplicateResolution? = null, existingId: String? = null) = viewModelScope.launch {
        val bytes = _state.value.captured ?: run { fail("No captured image"); return@launch }
        _state.value = _state.value.copy(busy = true, error = null)
        val request = ScanCommitRequest(
            examId = _state.value.examId, batchId = _state.value.batchId,
            imageBytes = bytes, exifRotationDegrees = _state.value.rotation,
            duplicateResolution = resolution, existingIdToReplace = existingId
        )
        when (val r = repository.processScan(request)) {
            is OmrResult.Success -> when (val commit = r.value) {
                is ScanCommitResult.DuplicateRequired -> _state.value = _state.value.copy(
                    busy = false, duplicate = commit.match)
                is ScanCommitResult.Discarded -> _state.value = _state.value.copy(
                    busy = false, message = commit.reason, captured = null)
                is ScanCommitResult.Success -> {
                    _recognition.value = commit.recognition
                    _state.value = _state.value.copy(busy = false, result = commit,
                        message = "Submission ${commit.submissionId}; pending items ${commit.grading.pendingReviewCount}")
                }
            }
            is OmrResult.Failure -> _state.value = _state.value.copy(
                busy = false, error = r.error.message ?: "processing failed")
        }
    }
    fun resolveDuplicate(resolution: DuplicateResolution) {
        val id = _state.value.duplicate?.existingSubmissionId
        _state.value = _state.value.copy(duplicate = null)
        process(resolution, id)
    }
    fun clearDuplicate() { _state.value = _state.value.copy(duplicate = null, captured = null) }
    fun batchArchive(): Flow<UiState<ByteArray>> = flow {
        emit(UiState.Loading)
        when (val r = repository.generateBatchArchive(_state.value.examId, _state.value.batchId,
            settings.value.sortBatchByStudentId)) {
            is OmrResult.Success -> emit(UiState.Success(r.value))
            is OmrResult.Failure -> emit(UiState.Error(r.error.message ?: "batch PDF failed"))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val repository: OmrRepository
) : ViewModel() {
    private val submissionId = MutableStateFlow<String?>(null)
    val recognitions: StateFlow<UiState<List<QuestionRecognition>>> = submissionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.recognitions(id)
    }.asUiState(viewModelScope, { it.isEmpty() }, "No recognition rows")
    val submission: StateFlow<UiState<Submission>> = submissionId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.submission(id)
    }.map { it?.let { UiState.Success(it) } ?: UiState.Empty("Select a submission") as UiState<Submission> }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)
    private val _message = MutableStateFlow<UiState<String>>(UiState.Empty(""))
    val message: StateFlow<UiState<String>> = _message.asStateFlow()

    fun select(id: String) { submissionId.value = id }
    fun correct(question: Int, answer: String, note: String) = viewModelScope.launch {
        val id = submissionId.value ?: return@launch
        _message.value = when (val r = repository.correctQuestion(id, question, answer, note)) {
            is OmrResult.Success -> UiState.Success("Override saved; machine prediction preserved")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "correction failed")
        }
    }
    fun assign(studentId: String) = viewModelScope.launch {
        val id = submissionId.value ?: return@launch
        _message.value = when (val r = repository.assignStudent(id, studentId)) {
            is OmrResult.Success -> UiState.Success("Student assigned; CSV synchronized")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "assignment failed")
        }
    }
    fun confirm() = viewModelScope.launch {
        val id = submissionId.value ?: return@launch
        _message.value = when (val r = repository.confirmGrading(id)) {
            is OmrResult.Success -> UiState.Success("Graded: %.2f / %.2f (%s)".format(
                r.value.score, r.value.maxScore, r.value.passingStatus))
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "confirmation failed")
        }
    }
    fun verifyDatasets(examId: String) = viewModelScope.launch(Dispatchers.IO) {
        val snapshot = repository.snapshot(examId)
        if (snapshot == null) { _message.value = UiState.Error("No Room snapshot"); return@launch }
        val report = DatasetSynchronizer.verify(snapshot.datasets)
        _message.value = if (report.invalid.isEmpty()) UiState.Success(
            "${report.valid.size} datasets passed file/size integrity checks")
        else UiState.Error("Integrity failures: ${report.reasons.values.joinToString(", ")}")
    }
    fun deleteRawImages() = viewModelScope.launch(Dispatchers.IO) {
        val files = directories.captures.listFiles()?.filter { it.isFile } ?: emptyList()
        val deleted = ArchiveService.deleteRawImages(files, "DELETE_RAW_IMAGES_CONFIRMED")
        refreshStorage()
        _message.value = UiState.Success(
            "$deleted confirmed raw capture(s) deleted; archived PDFs retained")
    }
    suspend fun studentReportPdf(examId: String, submissionId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        val sub = snapshot.submissions.firstOrNull { it.id == submissionId } ?: return ByteArray(0)
        val student = sub.studentId?.let { id -> snapshot.students.firstOrNull { it.id == id } }
            ?: Student("unassigned", "UNASSIGNED", "Unassigned")
        val recs = snapshot.recognitions.filter { it.submissionId == submissionId }.map {
            QuestionRecognition("$submissionId-${it.questionNumber}", submissionId, it.questionNumber,
                it.detectedAnswer, it.finalAnswer, it.confidence, it.classification)
        }
        val grade = GradingEngine.grade(snapshot.activeKey, recs, snapshot.examination.totalQuestions)
        return PortablePdfWriter.render(ReportPdfGenerator.studentReport(
            student, snapshot.examination.title, sub, grade.items, System.currentTimeMillis()))
    }
    fun shareIntent(uri: android.net.Uri, mimeType: String) = safExporter.shareIntent(uri, mimeType)
    fun viewIntent(uri: android.net.Uri, mimeType: String) = safExporter.viewIntent(uri, mimeType)
    fun uriForFile(path: String): android.net.Uri = safExporter.uriForFile(File(path))
    fun clear() { _message.value = UiState.Empty("") }
}

data class ResultsData(
    val exam: Examination,
    val submissions: List<Submission>,
    val results: List<GradingResult>,
    val stats: ExamStatistics,
    val items: List<ItemStatistics>,
    val students: Map<String, Student>,
    val archives: Map<String, AnswerSheetArchive> = emptyMap()
)

@HiltViewModel
class ResultsViewModel @Inject constructor(
    private val repository: OmrRepository
) : ViewModel() {
    private val examId = MutableStateFlow<String?>(null)
    private val _data = MutableStateFlow<UiState<ResultsData>>(UiState.Loading)
    val data: StateFlow<UiState<ResultsData>> = _data.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sortByScore = MutableStateFlow(true)
    val sortByScore: StateFlow<Boolean> = _sortByScore.asStateFlow()

    fun load(examId: String) = viewModelScope.launch {
        this@ResultsViewModel.examId.value = examId
        _data.value = UiState.Loading
        val snapshot = repository.snapshot(examId)
        if (snapshot == null) { _data.value = UiState.Error("Answer key or exam missing"); return@launch }
        val results = snapshot.submissions.map { sub ->
            val recs = snapshot.recognitions.filter { it.submissionId == sub.id }.map {
                QuestionRecognition("${sub.id}-${it.questionNumber}", sub.id, it.questionNumber,
                    it.detectedAnswer, it.finalAnswer, it.confidence, it.classification)
            }
            GradingEngine.grade(snapshot.activeKey, recs, snapshot.examination.totalQuestions,
                snapshot.activeKey.scoringRules)
        }
        val stats = ItemAnalyzer.statistics(results, snapshot.archives.size,
            snapshot.datasets.count { it.synchronizationStatus == SynchronizationStatus.SYNCED })
        _data.value = UiState.Success(ResultsData(snapshot.examination, snapshot.submissions.map {
            Submission(it.id, it.examinationId, it.studentId, it.templateId, it.answerKeyVersionId,
                it.batchId, emptyMap(), it.finalAnswers, it.score, it.percentage, it.gradingStatus,
                it.processingStatus, "", it.scannedAt)
        }, results, stats, ItemAnalyzer.perItem(results, snapshot.activeKey),
            snapshot.students.associateBy { it.id },
            snapshot.archives.associateBy { it.submissionId }))
    }
    fun setQuery(value: String) { _query.value = value }
    fun toggleSort() { _sortByScore.value = !_sortByScore.value }
}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val repository: OmrRepository,
    private val settingsStore: SettingsStore,
    private val directories: AppDirectories,
    private val safExporter: SafExporter
) : ViewModel() {
    val archives: StateFlow<UiState<List<AnswerSheetArchive>>> = repository.archives().asUiState(
        viewModelScope, { it.isEmpty() }, "No archived answer sheets yet"
    )
    private val examId = MutableStateFlow<String?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val datasets: StateFlow<UiState<List<DatasetArchive>>> = examId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.datasets(id)
    }.asUiState(viewModelScope, { it.isEmpty() }, "No CSV datasets yet")
    private val _message = MutableStateFlow<UiState<String>>(UiState.Empty(""))
    val message: StateFlow<UiState<String>> = _message.asStateFlow()
    private val _preview = MutableStateFlow<UiState<List<List<String>>>>(UiState.Loading)
    val preview: StateFlow<UiState<List<List<String>>>> = _preview.asStateFlow()
    private val _storage = MutableStateFlow("")
    val storage: StateFlow<String> = _storage.asStateFlow()
    val settings = settingsStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun selectExam(id: String) { examId.value = id }
    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) {
        val files = directories.allFiles()
        _storage.value = "${files.size} files, ${files.sumOf { it.length() } / 1024} KiB"
    }
    fun regenerate(examId: String) = viewModelScope.launch {
        _message.value = when (val r = repository.regenerateDatasets(examId)) {
            is OmrResult.Success -> UiState.Success("${r.value} datasets synchronized atomically")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "sync failed")
        }
    }
    fun retryArchive(submissionId: String) = viewModelScope.launch {
        _message.value = when (val r = repository.retryArchive(submissionId)) {
            is OmrResult.Success -> UiState.Success("Archive ${r.value.generationStatus}")
            is OmrResult.Failure -> UiState.Error(r.error.message ?: "retry failed")
        }
    }
    fun previewCsv(path: String) = viewModelScope.launch(Dispatchers.IO) {
        _preview.value = UiState.Loading
        _preview.value = try {
            UiState.Success(com.omrgrader.core.Csv.parseBytes(File(path).readBytes()).take(101))
        } catch (e: Exception) { UiState.Error(e.javaClass.simpleName) }
    }
    fun previewPdfPath(fileName: String): String =
        listOf(directories.archives, directories.datasets, directories.exports)
            .map { File(it, fileName) }
            .firstOrNull { it.isFile }?.absolutePath
            ?: File(directories.exports, fileName).absolutePath

    suspend fun blankSheet(templateId: String, copies: Int): ByteArray =
        (repository.blankSheetPdf(templateId, copies) as? OmrResult.Success)?.value ?: ByteArray(0)
    suspend fun wideCsv(examId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        return CsvDatasetEngine.wideCsv(snapshot)
    }
    suspend fun auditCsv(examId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        return CsvDatasetEngine.auditCsv(snapshot)
    }
    suspend fun consolidatedCsv(examIds: List<String>): ByteArray {
        val snapshots = examIds.mapNotNull { repository.snapshot(it) }
        return CsvDatasetEngine.consolidatedCsv(MultiExamSnapshot(snapshots))
    }
    suspend fun summaryCsv(examId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        val results = snapshot.submissions.map { sub ->
            GradingEngine.grade(snapshot.activeKey, snapshot.recognitions.filter { it.submissionId == sub.id }.map {
                QuestionRecognition("${sub.id}-${it.questionNumber}", sub.id, it.questionNumber,
                    it.detectedAnswer, it.finalAnswer, it.confidence, it.classification)
            }, snapshot.examination.totalQuestions)
        }
        return CsvDatasetEngine.summaryCsv(snapshot.examination.title,
            ItemAnalyzer.statistics(results), ItemAnalyzer.perItem(results, snapshot.activeKey))
    }
    suspend fun examReportPdf(examId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        val results = snapshot.submissions.map { sub ->
            GradingEngine.grade(snapshot.activeKey, snapshot.recognitions.filter { it.submissionId == sub.id }.map {
                QuestionRecognition("${sub.id}-${it.questionNumber}", sub.id, it.questionNumber,
                    it.detectedAnswer, it.finalAnswer, it.confidence, it.classification)
            }, snapshot.examination.totalQuestions)
        }
        val stats = ItemAnalyzer.statistics(results, snapshot.archives.size, snapshot.datasets.size)
        return PortablePdfWriter.render(ReportPdfGenerator.examReport(snapshot.examination.title,
            snapshot.examination.subject, stats, ItemAnalyzer.perItem(results, snapshot.activeKey),
            System.currentTimeMillis()))
    }
    suspend fun batchPdf(examId: String, batchId: String): ByteArray =
        (repository.generateBatchArchive(examId, batchId, settings.value.sortBatchByStudentId) as? OmrResult.Success)
            ?.value ?: ByteArray(0)
    fun verifyArchive(archive: AnswerSheetArchive) = viewModelScope.launch(Dispatchers.IO) {
        _message.value = if (ArchiveService.verify(archive))
            UiState.Success("Archive checksum and file size verified")
        else UiState.Error("Archive checksum, size or file path failed verification")
    }
    suspend fun backup(includeRawImages: Boolean = true): BackupBundle =
        (repository.createBackup(includeRawImages) as? OmrResult.Success)?.value
            ?: throw IllegalStateException("backup failed")
    suspend fun restore(uri: android.net.Uri): RestoreResult =
        (repository.restoreBackup(uri) as? OmrResult.Success)?.value
            ?: throw IllegalStateException("restore failed")
    suspend fun rosterCsv(): ByteArray {
        val students = repository.students("").first()
        return CsvDatasetEngine.rosterCsv(students)
    }
    suspend fun writeToTree(treeUri: android.net.Uri, fileName: String,
                            bytes: ByteArray, mime: String): android.net.Uri =
        withContext(Dispatchers.IO) { safExporter.writeToTree(treeUri, fileName, bytes, mime) }
    suspend fun export(fileName: String, bytes: ByteArray, mime: String): android.net.Uri =
        (repository.exportToSaf(fileName, bytes, mime) as? OmrResult.Success)?.value
            ?: throw IllegalStateException("export failed")
    fun verifyDatasets(examId: String) = viewModelScope.launch(Dispatchers.IO) {
        val snapshot = repository.snapshot(examId)
        if (snapshot == null) { _message.value = UiState.Error("No Room snapshot"); return@launch }
        val report = DatasetSynchronizer.verify(snapshot.datasets)
        _message.value = if (report.invalid.isEmpty()) UiState.Success(
            "${report.valid.size} datasets passed file/size integrity checks")
        else UiState.Error("Integrity failures: ${report.reasons.values.joinToString(", ")}")
    }
    fun deleteRawImages() = viewModelScope.launch(Dispatchers.IO) {
        val files = directories.captures.listFiles()?.filter { it.isFile } ?: emptyList()
        val deleted = ArchiveService.deleteRawImages(files, "DELETE_RAW_IMAGES_CONFIRMED")
        refreshStorage()
        _message.value = UiState.Success(
            "$deleted confirmed raw capture(s) deleted; archived PDFs retained")
    }
    suspend fun studentReportPdf(examId: String, submissionId: String): ByteArray {
        val snapshot = repository.snapshot(examId) ?: return ByteArray(0)
        val sub = snapshot.submissions.firstOrNull { it.id == submissionId } ?: return ByteArray(0)
        val student = sub.studentId?.let { id -> snapshot.students.firstOrNull { it.id == id } }
            ?: Student("unassigned", "UNASSIGNED", "Unassigned")
        val recs = snapshot.recognitions.filter { it.submissionId == submissionId }.map {
            QuestionRecognition("$submissionId-${it.questionNumber}", submissionId, it.questionNumber,
                it.detectedAnswer, it.finalAnswer, it.confidence, it.classification)
        }
        val grade = GradingEngine.grade(snapshot.activeKey, recs, snapshot.examination.totalQuestions)
        return PortablePdfWriter.render(ReportPdfGenerator.studentReport(
            student, snapshot.examination.title, sub, grade.items, System.currentTimeMillis()))
    }
    fun shareIntent(uri: android.net.Uri, mimeType: String) = safExporter.shareIntent(uri, mimeType)
    fun viewIntent(uri: android.net.Uri, mimeType: String) = safExporter.viewIntent(uri, mimeType)
    fun uriForFile(path: String): android.net.Uri = safExporter.uriForFile(File(path))
    fun clear() { _message.value = UiState.Empty("") }
}
