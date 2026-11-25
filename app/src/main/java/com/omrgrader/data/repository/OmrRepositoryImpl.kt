package com.omrgrader.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.omrgrader.archive.*
import com.omrgrader.core.*
import com.omrgrader.data.local.*
import com.omrgrader.data.preferences.SettingsStore
import com.omrgrader.domain.*
import com.omrgrader.export.*
import com.omrgrader.omr.*
import com.omrgrader.platform.AndroidImages
import com.omrgrader.platform.AppDirectories
import com.omrgrader.platform.SafExporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OmrRepositoryImpl @Inject constructor(
    private val db: OmrGraderDatabase,
    private val exams: ExaminationDao,
    private val students: StudentDao,
    private val templates: TemplateDao,
    private val keys: AnswerKeyDao,
    private val batches: ScanBatchDao,
    private val submissions: SubmissionDao,
    private val recognitions: QuestionRecognitionDao,
    private val archives: ArchiveDao,
    private val datasets: DatasetDao,
    private val examCreation: ExamCreationDao,
    private val submissionWrite: SubmissionWriteDao,
    private val dispatchers: AppDispatchers,
    private val logger: AppLogger,
    private val imageOps: ImageOps,
    private val qrCodec: QrCodec,
    private val directories: AppDirectories,
    private val safExporter: SafExporter,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : OmrRepository {

    override fun dashboard(): Flow<DashboardStats> = combine(
        combine(exams.count(), submissions.totalCount(), archives.generatedCount()) { a, b, c -> Triple(a, b, c) },
        combine(datasets.syncedCount(), exams.activeExams(), submissions.recent(8)) { d, e, r -> Triple(d, e, r) }
    ) { counts, others ->
        val (examCount, submissionCount, archivedPdfCount) = counts
        val (syncedCsvCount, recentExamEntities, recentSubmissionEntities) = others
        DashboardStats(
            examCount = examCount, submissionCount = submissionCount,
            archivedPdfCount = archivedPdfCount, syncedCsvCount = syncedCsvCount,
            recentExams = recentExamEntities.take(5).map { it.toDomain() },
            recentSubmissions = recentSubmissionEntities.map { it.toDomain() }
        )
    }

    override fun exams(): Flow<List<Examination>> = exams.activeExams().map { list -> list.map { it.toDomain() } }
    override fun allExams(): Flow<List<Examination>> = exams.allExams().map { list -> list.map { it.toDomain() } }
    override fun exam(id: String): Flow<Examination?> = exams.observeById(id).map { it?.toDomain() }
    override fun students(query: String): Flow<List<Student>> =
        (if (query.isBlank()) students.all() else students.search(query)).map { list -> list.map { it.toDomain() } }
    override fun templates(examId: String): Flow<List<AnswerSheetTemplate>> =
        templates.forExam(examId).map { list -> list.map { it.toDomain() } }
    override fun keys(examId: String): Flow<List<AnswerKey>> =
        keys.forExam(examId).map { list -> list.map { it.toDomain() } }
    override fun submissions(examId: String): Flow<List<Submission>> =
        submissions.forExam(examId).map { list -> list.map { it.toDomain() } }
    override fun submission(id: String): Flow<Submission?> = submissions.observeById(id).map { it?.toDomain() }
    override fun recognitions(submissionId: String): Flow<List<QuestionRecognition>> =
        recognitions.forSubmission(submissionId).map { list -> list.map { it.toDomain() } }
    override fun archives(): Flow<List<AnswerSheetArchive>> =
        archives.all().map { list -> list.map { it.toDomain() } }
    override fun datasets(examId: String): Flow<List<DatasetArchive>> =
        datasets.forExam(examId).map { list -> list.map { it.toDomain() } }
    override fun batches(examId: String): Flow<List<ScanBatch>> =
        batches.forExam(examId).map { list -> list.map { it.toDomain() } }

    override suspend fun createExam(
        title: String, subject: String, description: String, date: Long,
        totalQuestions: Int, choices: List<String>, scoring: ScoringConfiguration, pageSize: PageSize
    ): OmrResult<Examination> = dispatchers.onIo {
        OmrResult.runCatching {
            val now = clock.nowMillis()
            val exam = ExamAdmin.create(title, subject, description, date, totalQuestions, choices, scoring, now).examination
            val layout = LayoutFactory.build(exam.id, pageSize, totalQuestions, choices,
                settingsStore.settings.first().thresholds.studentIdColumns)
            val template = AnswerSheetTemplate(
                id = Ids.newId("tpl"), templateCode = layout.templateCode, examinationId = exam.id,
                pageSize = pageSize, questionCount = totalQuestions, answerChoices = choices,
                layoutDefinition = layout.toJson(), version = 1, createdAt = now
            )
            val withTemplate = exam.copy(activeTemplateId = template.id)
            val datasetEntities = DatasetSynchronizer.initialize(exam.id, now).map { it.toEntity() }
            examCreation.createExamBundle(withTemplate.toEntity(), template.toEntity(), null, datasetEntities)
            logger.info("exam_created", "exam_id" to exam.id, "items" to totalQuestions)
            withTemplate
        }
    }

    override suspend fun updateExam(exam: Examination): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching { exams.update(exam.toEntity()) }
    }

    override suspend fun duplicateExam(id: String): OmrResult<Examination> = dispatchers.onIo {
        OmrResult.runCatching {
            val source = exams.byId(id)?.toDomain() ?: throw AppError.NotFound("examination")
            val now = clock.nowMillis()
            val copy = ExamAdmin.duplicate(source, now)
            val templateSource = source.activeTemplateId?.let { templates.byId(it) }
            val template = templateSource?.let {
                val layout = it.toDomain().layout()
                val newLayout = LayoutFactory.build(copy.id, it.toDomain().pageSize,
                    it.questionCount, DomainJson.labels(it.answerChoices),
                    settingsStore.settings.first().thresholds.studentIdColumns,
                    version = it.version, templateCode = layout.templateCode + "-COPY")
                AnswerSheetTemplate(Ids.newId("tpl"), newLayout.templateCode, copy.id,
                    it.toDomain().pageSize, it.questionCount, DomainJson.labels(it.answerChoices),
                    newLayout.toJson(), it.version, now).toEntity()
            }
            val copyWithTemplate = copy.copy(activeTemplateId = template?.id)
            examCreation.createExamBundle(copyWithTemplate.toEntity(),
                template ?: AnswerSheetTemplateEntity(Ids.newId("tpl"),
                    LayoutFactory.templateCode(copy.id, PageSize.A4, source.totalQuestions, 1),
                    copy.id, PageSize.A4.name, source.totalQuestions,
                    DomainJson.labels(listOf("A", "B", "C", "D")),
                    LayoutFactory.build(copy.id, PageSize.A4, source.totalQuestions,
                        listOf("A", "B", "C", "D")).toJson(), 1, now),
                null, DatasetSynchronizer.initialize(copy.id, now).map { it.toEntity() })
            copyWithTemplate
        }
    }

    override suspend fun archiveExam(id: String, archive: Boolean): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val exam = exams.byId(id) ?: throw AppError.NotFound("examination")
            exams.update((if (archive) ExamAdmin.archive(exam.toDomain(), clock.nowMillis())
            else ExamAdmin.restore(exam.toDomain(), clock.nowMillis())).toEntity())
        }
    }

    override suspend fun createTemplate(
        examId: String, pageSize: PageSize, questionCount: Int, choices: List<String>, idColumns: Int
    ): OmrResult<AnswerSheetTemplate> = dispatchers.onIo {
        OmrResult.runCatching {
            val exam = exams.byId(examId) ?: throw AppError.NotFound("examination")
            val version = templates.all().count { it.examId == examId } + 1
            val layout = LayoutFactory.build(examId, pageSize, questionCount, choices, idColumns, version)
            val entity = AnswerSheetTemplate(Ids.newId("tpl"), layout.templateCode, examId, pageSize,
                questionCount, choices, layout.toJson(), version, clock.nowMillis()).toEntity()
            templates.insert(entity)
            exams.update(exam.copy(activeTemplateId = entity.id))
            entity.toDomain()
        }
    }

    override suspend fun saveKey(
        examId: String, answers: Map<Int, String>, choices: List<String>
    ): OmrResult<AnswerKey> = dispatchers.onIo {
        OmrResult.runCatching {
            val exam = exams.byId(examId)?.toDomain() ?: throw AppError.NotFound("examination")
            val created = AnswerKeyVersionService.create(exam, answers, choices,
                keys.latest(examId)?.let { listOf(it.toDomain()) } ?: emptyList(), clock.nowMillis()).key
            keys.insert(created.toEntity())
            exams.update(AnswerKeyVersionService.activate(exam, created, clock.nowMillis()).toEntity())
            created
        }
    }

    override suspend fun activateKey(examId: String, keyId: String): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val exam = exams.byId(examId)?.toDomain() ?: throw AppError.NotFound("examination")
            val key = keys.byId(keyId)?.toDomain() ?: throw AppError.NotFound("answer key")
            exams.update(AnswerKeyVersionService.activate(exam, key, clock.nowMillis()).toEntity())
        }
    }

    override suspend fun editActiveKey(
        examId: String, newAnswers: Map<Int, String>, choices: List<String>
    ): OmrResult<AnswerKeyVersionService.VersionEditResult> = dispatchers.onIo {
        OmrResult.runCatching {
            val exam = exams.byId(examId)?.toDomain() ?: throw AppError.NotFound("examination")
            val active = exam.activeAnswerKeyVersionId?.let { keys.byId(it)?.toDomain() }
                ?: throw AppError.NotFound("active answer key")
            // All immutable key versions are read from Room before computing the next version.
            val allKeys = keys.allForExam(examId).map { it.toDomain() }
            val gradedIds = submissions.allForExam(examId).filter {
                it.gradingStatus == GradingStatus.GRADED.name || it.gradingStatus == GradingStatus.PROVISIONAL.name
            }.map { it.id }
            val result = AnswerKeyVersionService.editActiveKey(exam, active, newAnswers, choices,
                allKeys, gradedIds, clock.nowMillis())
            keys.insert(result.newKey.toEntity())
            exams.update(result.examination.toEntity())
            if (result.regradeRequired) {
                db.withTransaction {
                    result.affectedSubmissionIds.forEach { id ->
                        submissions.byId(id)?.let { sub ->
                            submissions.update(sub.copy(gradingStatus = GradingStatus.REGRADE_REQUIRED.name))
                        }
                    }
                }
            }
            result
        }
    }

    override suspend fun saveStudent(student: Student): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val duplicate = students.byNumber(student.studentNumber)
            if (duplicate != null && duplicate.id != student.id) throw AppError.Conflict("student number exists")
            if (students.byId(student.id) == null) students.insert(student.toEntity())
            else students.update(student.toEntity())
        }
    }

    override suspend fun importStudents(csvBytes: ByteArray): OmrResult<RosterImportResult> = dispatchers.onIo {
        OmrResult.runCatching {
            val existing = students.allOnce().map { it.toDomain() }
            val result = RosterImport.parse(csvBytes, existing)
            students.insertAll(result.valid.map { it.toEntity() })
            logger.info("roster_imported", "rows" to result.valid.size, "duplicates" to result.duplicates.size)
            result
        }
    }

    override suspend fun removeStudent(studentId: String): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching { students.delete(studentId) }
    }

    override suspend fun startBatch(examId: String): String = dispatchers.onIo {
        val now = clock.nowMillis()
        val id = Ids.newId("batch")
        batches.insert(ScanBatchEntity(id, examId, BatchStatus.ACTIVE.name, 0, 0, 0, now, now))
        id
    }

    override suspend fun recoverableBatch(examId: String): ScanBatch? = dispatchers.onIo {
        batches.recoverable().firstOrNull { it.examId == examId }?.toDomain()
    }

    override suspend fun processScan(request: ScanCommitRequest): OmrResult<ScanCommitResult> =
        dispatchers.onDefault {
            OmrResult.runCatching {
                val examEntity = exams.byId(request.examId) ?: throw AppError.NotFound("examination")
                val exam = examEntity.toDomain()
                val templateEntity = exam.activeTemplateId?.let { templates.byId(it) }
                    ?: templates.all().firstOrNull { it.examId == exam.id }
                    ?: throw AppError.NotFound("answer sheet template")
                val template = templateEntity.toDomain()
                val keyEntity = exam.activeAnswerKeyVersionId?.let { keys.byId(it) }
                    ?: keys.latest(exam.id)
                    ?: throw AppError.NotFound("active answer key")
                val key = keyEntity.toDomain()
                val settings = settingsStore.settings.first()
                // OMR uses a bounded working copy; archival page 1 embeds original JPEG bytes.
                val decoded = AndroidImages.decode(request.imageBytes)
                val layouts = templates.all().map { it.toDomain().layout() }
                val registry = InMemoryLayoutRegistry(layouts)
                val rosterStudents = students.allOnce().map { it.toDomain() }
                val roster = InMemoryRosterLookup(rosterStudents)
                val engine = OmrEngine(OmrEngineConfig(imageOps, qrCodec, settings.thresholds, clock))
                val recognition = engine.process(OmrRequest(
                    image = decoded.image, layoutRegistry = registry, roster = roster,
                    exifRotationDegrees = 0, qrStudentNumber = request.qrStudentNumber,
                    submissionId = request.submissionId, nowMillis = clock.nowMillis()
                )).getOrThrow()

                val fingerprint = Sha256.of(request.imageBytes)
                val existing = submissions.allForExam(exam.id).map { it.toDomain() }
                val duplicate = DuplicateDetector.find(existing, exam.id, recognition.studentId, fingerprint)
                if (duplicate != null && request.duplicateResolution == null)
                    return@runCatching ScanCommitResult.DuplicateRequired(duplicate)
                if (duplicate != null && request.duplicateResolution == DuplicateResolution.DISCARD)
                    return@runCatching ScanCommitResult.Discarded("teacher discarded duplicate")
                if (duplicate != null && request.duplicateResolution == DuplicateResolution.REPLACE) {
                    submissions.discard(request.existingIdToReplace ?: duplicate.existingSubmissionId)
                }

                val grade = GradingEngine.grade(key, recognition.questions, exam.totalQuestions, key.scoringRules)
                val submission = Submission(
                    id = request.submissionId, examinationId = exam.id, studentId = recognition.studentId,
                    templateId = template.id, answerKeyVersionId = key.id, batchId = request.batchId,
                    recognizedAnswers = recognition.recognizedAnswers,
                    finalAnswers = recognition.questions.associate { it.questionNumber to it.finalAnswer },
                    score = grade.score, percentage = grade.percentage,
                    gradingStatus = GradingEngine.statusFor(grade),
                    processingStatus = ProcessingStatus.RECOGNIZED,
                    imageFingerprint = fingerprint, scannedAt = clock.nowMillis()
                )
                db.withTransaction {
                    submissionWrite.insertBundle(submission.toEntity(), recognition.questions.map { it.toEntity() })
                }

                // Mandatory per-scan archive: unmodified original on page 1.
                val captureFile = AtomicFiles.write(directories.captures,
                    "${request.submissionId}.jpg", request.imageBytes)
                val studentNumber = recognition.studentId?.let { id ->
                    rosterStudents.firstOrNull { it.id == id }?.studentNumber
                }
                val archive = ArchiveService.generateIndividual(
                    exam.id, studentNumber, request.submissionId, decoded.image,
                    recognition.canonicalImage, captureFile.absolutePath, directories.archives,
                    includeProcessingPage = settings.includeProcessingPage,
                    generatedAt = clock.nowMillis(),
                    originalJpegBytes = request.imageBytes,
                    exifRotationDegrees = decoded.rotationDegrees
                )
                archives.insert(archive.archive.toEntity())

                updateBatchCounters(request.batchId)
                val syncCount = regenerateDatasetsInternal(exam.id)
                logger.info("scan_committed", "submission_id" to request.submissionId,
                    "pending" to grade.pendingReviewCount, "datasets" to syncCount,
                    "archive" to archive.archive.generationStatus.name)
                ScanCommitResult.Success(request.submissionId, recognition, grade, archive.archive.generationStatus)
            }
        }

    private suspend fun updateBatchCounters(batchId: String) {
        val batch = batches.byId(batchId) ?: return
        val all = submissions.forBatch(batchId).first()
        val retained = all.filter { it.gradingStatus != GradingStatus.DISCARDED.name }
        batches.update(batch.copy(
            totalScanned = retained.size,
            totalProcessed = retained.count { it.processingStatus != ProcessingStatus.CAPTURED.name },
            totalPending = retained.count { it.gradingStatus == GradingStatus.PENDING_REVIEW.name },
            updatedAt = clock.nowMillis()
        ))
    }

    override suspend fun correctQuestion(
        submissionId: String, questionNumber: Int, answer: String, note: String
    ): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val rows = recognitions.forSubmissionOnce(submissionId)
            val target = rows.firstOrNull { it.questionNumber == questionNumber }
                ?: throw AppError.NotFound("question recognition")
            val corrected = target.toDomain().correctedTo(answer, note, clock.nowMillis())
            recognitions.update(corrected.toEntity())
            recomputeSubmission(submissionId)
            logger.info("question_corrected", "submission_id" to submissionId, "item" to questionNumber)
        }
    }

    override suspend fun assignStudent(submissionId: String, studentId: String): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val sub = submissions.byId(submissionId) ?: throw AppError.NotFound("submission")
            if (students.byId(studentId) == null) throw AppError.Validation("student not in roster")
            submissions.update(sub.copy(studentId = studentId))
            val number = students.byId(studentId)?.studentNumber
            val archive = archives.forSubmission(submissionId)
            if (archive != null) {
                val original = File(archive.originalImagePath)
                if (original.isFile) {
                    val bytes = original.readBytes()
                    val decoded = AndroidImages.decode(bytes, Int.MAX_VALUE)
                    val regenerated = ArchiveService.generateIndividual(sub.examId, number,
                        submissionId, decoded.image, null, original.absolutePath, directories.archives,
                        includeProcessingPage = false, originalJpegBytes = bytes,
                        exifRotationDegrees = decoded.rotationDegrees)
                    archives.insert(regenerated.archive.copy(id = archive.id).toEntity())
                }
            }
            regenerateDatasetsInternal(sub.examId)
            Unit
        }
    }

    override suspend fun confirmGrading(submissionId: String): OmrResult<GradingResult> = dispatchers.onIo {
        OmrResult.runCatching {
            val sub = submissions.byId(submissionId) ?: throw AppError.NotFound("submission")
            val rows = recognitions.forSubmissionOnce(submissionId).map { it.toDomain() }
            val flags = ConfidencePolicy.flagsFor(rows, settingsStore.settings.first().thresholds)
            if (flags.isNotEmpty()) throw AppError.Validation("pending manual verifications: ${flags.size}")
            val key = keys.byId(sub.answerKeyVersionId)?.toDomain() ?: throw AppError.NotFound("answer key")
            val grade = GradingEngine.grade(key, rows,
                exams.byId(sub.examId)?.totalQuestions ?: rows.size, key.scoringRules)
            submissions.update(sub.copy(gradingStatus = GradingStatus.GRADED.name,
                score = grade.score, percentage = grade.percentage))
            regenerateDatasetsInternal(sub.examId)
            grade
        }
    }

    override suspend fun regradeExam(examId: String, keyVersionId: String): OmrResult<Int> = dispatchers.onIo {
        OmrResult.runCatching {
            val key = keys.byId(keyVersionId)?.toDomain() ?: throw AppError.NotFound("answer key")
            val exam = exams.byId(examId) ?: throw AppError.NotFound("examination")
            var count = 0
            db.withTransaction {
                submissions.allForExam(examId).forEach { entity ->
                    val rows = recognitions.forSubmissionOnce(entity.id).map { it.toDomain() }
                    val grade = GradingEngine.grade(key, rows, exam.totalQuestions, key.scoringRules)
                    submissions.update(entity.copy(answerKeyVersionId = key.id, score = grade.score,
                        percentage = grade.percentage, gradingStatus = GradingEngine.statusFor(grade).name))
                    count++
                }
            }
            regenerateDatasetsInternal(examId)
            count
        }
    }

    override suspend fun discardSubmission(submissionId: String): OmrResult<Unit> = dispatchers.onIo {
        OmrResult.runCatching {
            val sub = submissions.byId(submissionId) ?: throw AppError.NotFound("submission")
            submissions.discard(submissionId)
            regenerateDatasetsInternal(sub.examId)
            Unit
        }
    }

    override suspend fun retryArchive(submissionId: String): OmrResult<AnswerSheetArchive> = dispatchers.onIo {
        OmrResult.runCatching {
            val existing = archives.forSubmission(submissionId) ?: throw AppError.NotFound("archive record")
            val original = File(existing.originalImagePath)
            if (!original.isFile) throw AppError.Storage("original capture missing")
            val bytes = original.readBytes()
            val decoded = AndroidImages.decode(bytes, Int.MAX_VALUE)
            val sub = submissions.byId(submissionId) ?: throw AppError.NotFound("submission")
            val number = sub.studentId?.let { students.byId(it)?.studentNumber }
            val result = ArchiveService.generateIndividual(sub.examId, number, submissionId,
                decoded.image, null, original.absolutePath, directories.archives,
                includeProcessingPage = false, originalJpegBytes = bytes,
                exifRotationDegrees = decoded.rotationDegrees)
            archives.insert(result.archive.copy(id = existing.id).toEntity())
            result.archive
        }
    }

    override suspend fun generateBatchArchive(
        examId: String, batchId: String, sortByStudentId: Boolean
    ): OmrResult<ByteArray> = dispatchers.onIo {
        OmrResult.runCatching {
            val subs = submissions.forBatch(batchId).first().filter {
                it.gradingStatus != GradingStatus.DISCARDED.name
            }
            val entries = subs.mapNotNull { sub ->
                val archive = archives.forSubmission(sub.id) ?: return@mapNotNull null
                val file = File(archive.originalImagePath)
                if (!file.isFile) return@mapNotNull null
                val bytes = file.readBytes()
                val decoded = AndroidImages.decode(bytes, Int.MAX_VALUE)
                BatchEntry(sub.id, sub.studentId?.let { students.byId(it)?.studentNumber },
                    decoded.image, sub.scannedAt, originalJpegBytes = bytes,
                    exifRotationDegrees = decoded.rotationDegrees)
            }
            ArchiveService.generateBatch(examId, batchId, entries, sortByStudentId, directories.archives).bytes
        }
    }

    override suspend fun snapshot(examId: String): DatasetSnapshot? = dispatchers.onIo { buildSnapshot(examId) }

    private suspend fun buildSnapshot(examId: String): DatasetSnapshot? {
        val exam = exams.byId(examId)?.toDomain() ?: return null
        val key = (exam.activeAnswerKeyVersionId?.let { keys.byId(it) } ?: keys.latest(examId))
            ?.toDomain() ?: return null
        val allStudents = students.allOnce().map { it.toDomain() }
        val subs = submissions.allForExam(examId).filter { it.gradingStatus != GradingStatus.DISCARDED.name }
        val subIds = subs.map { it.id }
        val recs = if (subIds.isEmpty()) emptyList() else recognitions.forSubmissions(subIds)
        return DatasetSnapshot(
            examination = exam, activeKey = key, students = allStudents,
            submissions = subs.map { sub ->
                val grade = runCatching {
                    GradingEngine.grade(key, recs.filter { it.submissionId == sub.id }.map { it.toDomain() },
                        exam.totalQuestions, key.scoringRules)
                }.getOrNull()
                SubmissionSnapshot(sub.id, sub.examId, sub.studentId, sub.templateId,
                    sub.answerKeyVersionId, sub.batchId, sub.scannedAt,
                    runCatching { GradingStatus.valueOf(sub.gradingStatus) }.getOrDefault(GradingStatus.PENDING_REVIEW),
                    runCatching { ProcessingStatus.valueOf(sub.processingStatus) }.getOrDefault(ProcessingStatus.CAPTURED),
                    DomainJson.answers(sub.finalAnswers), sub.score, grade?.maxScore ?: exam.totalPoints,
                    sub.percentage, grade?.passingStatus ?: GradingEngine.PENDING)
            },
            recognitions = recs.map {
                RecognitionSnapshot(it.submissionId, it.questionNumber, it.recognizedAnswer,
                    it.finalAnswer, it.confidence,
                    runCatching { Classification.valueOf(it.classification) }.getOrDefault(Classification.INVALID),
                    it.manuallyCorrected, it.correctedAt, DomainJson.labels(it.alternatives))
            },
            archives = archives.all().first().map { it.toDomain() },
            datasets = datasets.forExamOnce(examId).map { it.toDomain() }
        )
    }

    private suspend fun recomputeSubmission(submissionId: String) {
        val sub = submissions.byId(submissionId) ?: return
        val key = keys.byId(sub.answerKeyVersionId)?.toDomain() ?: return
        val rows = recognitions.forSubmissionOnce(submissionId).map { it.toDomain() }
        val grade = GradingEngine.grade(key, rows,
            exams.byId(sub.examId)?.totalQuestions ?: rows.size, key.scoringRules)
        submissions.update(sub.copy(
            finalAnswers = DomainJson.answers(rows.associate { it.questionNumber to it.finalAnswer }),
            score = grade.score, percentage = grade.percentage,
            gradingStatus = GradingEngine.statusFor(grade).name
        ))
        regenerateDatasetsInternal(sub.examId)
    }

    private suspend fun regenerateDatasetsInternal(examId: String): Int {
        val snapshot = buildSnapshot(examId) ?: return 0
        val previous = datasets.forExamOnce(examId).map { it.toDomain() }
        return when (val result = DatasetSynchronizer.synchronize(snapshot, directories.datasets,
            previous, clock.nowMillis())) {
            is OmrResult.Success -> {
                datasets.insertAll(result.value.archives.map { it.toEntity() })
                result.value.archives.size
            }
            is OmrResult.Failure -> {
                val error = result.error.message ?: "sync failed"
                datasets.insertAll(DatasetType.entries.map { type ->
                    DatasetSynchronizer.failed(examId, type, error, previous.firstOrNull { it.datasetType == type }).toEntity()
                })
                0
            }
        }
    }

    override suspend fun regenerateDatasets(examId: String): OmrResult<Int> = dispatchers.onIo {
        OmrResult.runCatching { regenerateDatasetsInternal(examId) }
    }

    override suspend fun layoutFor(templateCode: String): SheetLayout? = dispatchers.onIo {
        templates.byCode(templateCode)?.toDomain()?.layout()
    }

    override suspend fun blankSheetPdf(templateId: String, copies: Int): OmrResult<ByteArray> = dispatchers.onDefault {
        OmrResult.runCatching {
            val template = templates.byId(templateId) ?: throw AppError.NotFound("template")
            val layout = template.toDomain().layout()
            PortablePdfWriter.render(BlankSheetPdfGenerator.generate(layout, copies, qrCodec))
        }
    }

    override suspend fun createBackup(includeRawImages: Boolean): OmrResult<BackupBundle> = dispatchers.onIo {
        OmrResult.runCatching {
            val allExams = exams.allExams().first().map { it.toDomain() }
            val allTemplates = templates.all().map { it.toDomain() }
            val allKeys = keys.all().map { it.toDomain() }
            val allSubs = mutableListOf<Submission>()
            allExams.forEach { exam -> allSubs += submissions.allForExam(exam.id).map { it.toDomain() } }
            val allRecs = if (allSubs.isEmpty()) emptyList() else
                recognitions.forSubmissions(allSubs.map { it.id }).map { it.toDomain() }
            val artifacts = directories.allFiles().mapNotNull { file ->
                val kind = when (file.parentFile?.name) {
                    "archives" -> BackupFileKind.ARCHIVE_PDF
                    "datasets" -> BackupFileKind.DATASET_CSV
                    "captures" -> BackupFileKind.RAW_IMAGE
                    else -> null
                }
                if (kind == null) null else BackupFile("${file.parentFile.name}/${file.name}", file.readBytes(), kind)
            }
            val payload = BackupPayload(
                database = if (directories.databaseFile.isFile) directories.databaseFile.readBytes()
                else "SQLite format 3".toByteArray(),
                layoutsJson = com.omrgrader.core.Json.stringify(com.omrgrader.core.Json.arr(
                    allTemplates.map { com.omrgrader.core.Json.str(it.layoutDefinition) })),
                keysJson = com.omrgrader.core.Json.stringify(com.omrgrader.core.Json.arr(
                    allKeys.map { com.omrgrader.core.Json.str(DomainJson.answers(it.answers)) })),
                submissionsJson = com.omrgrader.core.Json.stringify(com.omrgrader.core.Json.arr(
                    allSubs.map { com.omrgrader.core.Json.str(it.id) })),
                artifacts = artifacts,
                relationalCounts = mapOf(
                    "exams" to allExams.size, "students" to students.allOnce().size,
                    "submissions" to allSubs.size, "recognitions" to allRecs.size,
                    "archives" to artifacts.count { it.kind == BackupFileKind.ARCHIVE_PDF },
                    "datasets" to artifacts.count { it.kind == BackupFileKind.DATASET_CSV }
                ),
                submissionIds = allSubs.map { it.id },
                recognitionSubmissionIds = allRecs.map { it.submissionId },
                includeRawImages = includeRawImages
            )
            BackupService.create(payload, clock.nowMillis())
        }
    }

    override suspend fun restoreBackup(uri: Uri): OmrResult<RestoreResult> = dispatchers.onIo {
        OmrResult.runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw AppError.Storage("cannot read backup URI")
            val staging = File(directories.backups, "restore_staging").apply { mkdirs() }
            val result = BackupService.restore(bytes, staging)
            if (result.success) {
                val restoredDb = File(staging, "database/omrgrader.db")
                if (restoredDb.isFile) {
                    File(directories.backups, "restore_pending").apply { mkdirs() }
                        .let { pending -> restoredDb.copyTo(File(pending, "omrgrader.db"), overwrite = true) }
                    File(directories.backups, "restore_pending/READY").writeText("1")
                }
            }
            result
        }
    }

    override suspend fun exportToSaf(fileName: String, bytes: ByteArray, mimeType: String): OmrResult<Uri> =
        dispatchers.onIo {
            OmrResult.runCatching { safExporter.stageAndShare(fileName, bytes) }
        }
}
