package com.omrgrader.export

import com.omrgrader.core.AtomicFiles
import com.omrgrader.core.Clock
import com.omrgrader.core.Ids
import com.omrgrader.core.OmrResult
import com.omrgrader.core.Sha256
import com.omrgrader.core.SystemClock
import com.omrgrader.domain.DatasetArchive
import com.omrgrader.domain.DatasetSnapshot
import com.omrgrader.domain.DatasetType
import com.omrgrader.domain.SynchronizationStatus
import java.io.File

data class DatasetSyncResult(
    val archives: List<DatasetArchive>,
    val wideBytes: Int,
    val auditBytes: Int,
    /** SHA-256 of each freshly written artifact for the backup manifest. */
    val checksums: Map<String, String>,
    val warnings: List<String>
)

/**
 * Dataset lifecycle. Files are derived artifacts: Room is authoritative and every
 * correction/reassignment/regrade/deletion calls synchronize() again. Writes are
 * atomic (.tmp -> fsync -> rename) so a partial CSV is never observable.
 */
object DatasetSynchronizer {
    fun fileName(examId: String, type: DatasetType): String = when (type) {
        DatasetType.WIDE_ANSWERS -> "EXAM_${examId}_STUDENT_ANSWERS.csv"
        DatasetType.RECOGNITION_AUDIT -> "EXAM_${examId}_RECOGNITION_AUDIT.csv"
    }

    fun initialize(examId: String, now: Long): List<DatasetArchive> = DatasetType.entries.map { type ->
        DatasetArchive(
            id = Ids.newId("dataset"),
            examinationId = examId,
            datasetType = type,
            filePath = "",
            fileName = fileName(examId, type),
            fileSize = 0,
            generationVersion = 0,
            synchronizationStatus = SynchronizationStatus.INITIALIZED,
            recordCount = 0,
            lastGeneratedAt = null,
            lastError = null
        )
    }

    fun markStale(archives: List<DatasetArchive>): List<DatasetArchive> =
        archives.map { it.copy(synchronizationStatus = SynchronizationStatus.STALE) }

    fun synchronize(
        snapshot: DatasetSnapshot,
        directory: File,
        previous: List<DatasetArchive> = initialize(snapshot.examination.id, 0),
        now: Long = System.currentTimeMillis(),
        clock: Clock = SystemClock
    ): OmrResult<DatasetSyncResult> = OmrResult.runCatching {
        val examId = snapshot.examination.id
        val wideBytes = CsvDatasetEngine.wideCsv(snapshot)
        val auditBytes = CsvDatasetEngine.auditCsv(snapshot)
        val wideName = fileName(examId, DatasetType.WIDE_ANSWERS)
        val auditName = fileName(examId, DatasetType.RECOGNITION_AUDIT)
        val wideFile = AtomicFiles.write(directory, wideName, wideBytes, clock)
        val auditFile = AtomicFiles.write(directory, auditName, auditBytes, clock)
        val byType = previous.associateBy { it.datasetType }

        fun archive(type: DatasetType, file: File, records: Int): DatasetArchive {
            val old = byType[type]
            return DatasetArchive(
                id = old?.id ?: Ids.newId("dataset"),
                examinationId = examId,
                datasetType = type,
                filePath = file.absolutePath,
                fileName = file.name,
                fileSize = file.length(),
                generationVersion = (old?.generationVersion ?: 0) + 1,
                synchronizationStatus = SynchronizationStatus.SYNCED,
                recordCount = records,
                lastGeneratedAt = now,
                lastError = null
            )
        }
        val wideRows = CsvDatasetEngine.wideRows(snapshot).size
        val auditRows = CsvDatasetEngine.auditRows(snapshot).size
        DatasetSyncResult(
            archives = listOf(
                archive(DatasetType.WIDE_ANSWERS, wideFile, wideRows),
                archive(DatasetType.RECOGNITION_AUDIT, auditFile, auditRows)
            ),
            wideBytes = wideBytes.size,
            auditBytes = auditBytes.size,
            checksums = mapOf(
                wideName to Sha256.of(wideBytes),
                auditName to Sha256.of(auditBytes)
            ),
            warnings = if (wideRows == 0) listOf("NO_SUBMISSIONS_TO_SYNC") else emptyList()
        )
    }

    fun failed(examId: String, type: DatasetType, error: String, previous: DatasetArchive?): DatasetArchive =
        DatasetArchive(
            id = previous?.id ?: Ids.newId("dataset"),
            examinationId = examId,
            datasetType = type,
            filePath = previous?.filePath ?: "",
            fileName = fileName(examId, type),
            fileSize = previous?.fileSize ?: 0,
            generationVersion = previous?.generationVersion ?: 0,
            synchronizationStatus = SynchronizationStatus.FAILED,
            recordCount = previous?.recordCount ?: 0,
            lastGeneratedAt = previous?.lastGeneratedAt,
            lastError = error.take(200)
        )

    data class IntegrityReport(
        val valid: List<DatasetArchive>,
        val invalid: List<DatasetArchive>,
        val reasons: Map<String, String>
    )

    /** Verifies file existence, declared size and optional SHA-256 from the backup manifest. */
    fun verify(
        archives: List<DatasetArchive>,
        checksums: Map<String, String> = emptyMap()
    ): IntegrityReport {
        val valid = ArrayList<DatasetArchive>()
        val invalid = ArrayList<DatasetArchive>()
        val reasons = LinkedHashMap<String, String>()
        for (archive in archives) {
            val file = File(archive.filePath)
            val reason: String? = when {
                !file.isFile -> "MISSING_FILE"
                file.length() != archive.fileSize -> "SIZE_MISMATCH"
                checksums[archive.fileName]?.let { Sha256.of(file) != it } == true -> "CHECKSUM_MISMATCH"
                else -> null
            }
            if (reason == null) valid += archive
            else { invalid += archive; reasons[archive.id] = reason }
        }
        return IntegrityReport(valid, invalid, reasons)
    }
}
