# TECHNICAL_DOCUMENTATION.md — OpenScantron

Date: 2026-11-25 UTC. This document describes the source in this checkout and the exact
verification boundary. Where a capability has source but has not been Android-compiled in
this sandbox, it is labelled **source-implemented / Android execution blocked** rather than
claiming a passing build.

## 1. Architecture

```text
presentation/  Compose screens + view models + Navigation Compose (24 routes)
      | StateFlow / repository calls
data/          Room v2 + DataStore + concrete OmrRepositoryImpl
      | domain models
domain/        Examination, Student, Template, Key, Submission, Recognition,
               grading, confidence, item analysis, dataset snapshots, exam admin
omr/           GrayImage + ImageOps + 8-stage recognition engine
export/        QR, portable PDF 1.4, sheet/report PDFs, RFC 4180 CSV datasets
archive/       mandatory per-scan PDFs, batch PDF, checksum/storage, backup ZIP
core/          AppResult, dispatchers, clock/logger, JSON, CSV, atomic file writes
platform/      Android Bitmap/EXIF, PdfDocument, SAF/FileProvider, ZXing, OpenCV factory
camera/        CameraX binding, live quality analysis, gallery import
di/            Hilt singleton bindings
```

`core`, `domain`, `omr`, `export`, and `archive` contain zero Android/Compose imports.
They are compiled and tested by `tools/verify.sh` on a plain JVM. The Android layer adapts
these interfaces; it never re-implements grading, recognition, CSV quoting, PDF drawing, or
backup validation with a demo shortcut.

### Stack and concurrency

* Kotlin 2.0.21 / AGP 8.7.3 / KSP, Java 17, Compose BOM 2024.12.01, Material 3.
* Coroutines `Flow`/`StateFlow`; `viewModelScope` + repository `Flow`s.
* `AppDispatchers` sends CPU-heavy OMR/PDF to `Dispatchers.Default` and file/database/PDF/CSV
  work to `Dispatchers.IO`. DataStore and Room DAO calls are suspend functions.
* Hilt `SingletonComponent` provides the Room database, DAOs, dispatchers, logger, clock,
  `ImageOps`, `QrCodec`, `PdfRenderer`, directories, SAF exporter, settings and repository.
* DataStore persists theme, OMR thresholds, JPEG quality, processing-page preference,
  auto-capture, and batch sort preference.
* Material 3 light/dark/system themes; Navigation Compose hosts all 24 screens.
* CameraX provides preview, analysis, capture, flash, lens switching, and lifecycle binding.
* ZXing is the Android QR codec; `PortableQrCodec` is used by JVM tests and printable sheets.
* OpenCV Android SDK is optional; `OpenCvImageOps` implements the same `ImageOps` contract.

## 2. Room schema, migration and transactions

`@Database(version = 2, exportSchema = true)` declares nine entities:

1. `ExaminationEntity`
2. `StudentEntity`
3. `AnswerSheetTemplateEntity`
4. `AnswerKeyEntity`
5. `ScanBatchEntity`
6. `SubmissionEntity`
7. `QuestionRecognitionEntity`
8. `AnswerSheetArchiveEntity`
9. `DatasetArchiveEntity`

Exact fields are in `app/src/main/java/com/omrgrader/data/local/Entities.kt`. Important
invariants:

* `SubmissionEntity.studentId` is nullable (`UNASSIGNED` until manual/QR/ID assignment).
* `QuestionRecognitionEntity` preserves `recognized_answer` separately from `final_answer`,
  plus `manually_corrected`, `correction_note`, `corrected_at`, `alternatives`, confidence and
  five-state classification.
* `AnswerSheetArchiveEntity` stores `pdf_checksum` (SHA-256), size, path, name,
  `generation_status`, `generated_at`, retry/error state and the original image path.
* `DatasetArchiveEntity` stores `synchronization_status`, generation version, record count,
  file size/path, `last_generated_at` and `last_error`.
* Explicit foreign keys and indices are declared for exam/template/key/batch/submission/
  recognition/archive/dataset relations; submissions use `SET NULL` for student deletion and
  `RESTRICT` for key/template/batch deletion.
* `MIGRATION_1_2` adds correction provenance, alternatives, archive generation status,
  dataset last error, `index_students_section` and the composite
  `index_submissions_exam_id_student_id`. There is **no `fallbackToDestructiveMigration`**.
* `ExamCreationDao.createExamBundle` and `SubmissionWriteDao.insertBundle` are `@Transaction`
  DAOs; `db.withTransaction` wraps controlled regrade and submission commit.
* Room is authoritative for scores, archive paths, dataset states and statuses. CSV/PDF files
  are derived artifacts.

Exported schema JSON is in `app/schemas/com.omrgrader.data.local.OmrGraderDatabase/1.json`
and `2.json`; `tools/generate_room_schemas.py` reproduces it deterministically. A Gradle
build with KSP will regenerate/validate these schemas normally.

## 3. Examination, answer key, scoring and datasets

* Presets: 10, 20, 30, 40, 50, 60, 75, 100 plus custom 1..200; A–D, A–E, A–F and custom
  single-character labels.
* `ExamAdmin` creates, duplicates, archives/restores exams; active template and active key
  are explicit IDs.
* `AnswerKeyVersionService.validate` rejects missing/invalid items. Editing an active key
  creates a new immutable version and marks affected submissions `REGRADE_REQUIRED`;
  historical scores are not silently overwritten.
* `GradingEngine` handles correct/incorrect/unanswered/multiple policy, per-question
  weights, partial credit, deductions, passing threshold, pending review, and a zero-max-score
  division guard. `ItemAnalyzer` computes KPIs, distractor frequencies and difficulty index
  `p = correct / attempted`.
* Dataset snapshots are immutable Room-derived values. `CsvDatasetEngine` writes:
  * `EXAM_<ExamID>_STUDENT_ANSWERS.csv` with prefix
    `submission_id,exam_id,exam_title,subject,student_id,student_name,section,exam_date,scanned_at,answer_key_version,batch_id,grading_status`,
    dynamic `Q001..QNNN`, and suffix
    `correct_count,incorrect_count,blank_count,invalid_count,pending_review_count,score,max_score,percentage,passing_status`.
  * `EXAM_<ExamID>_RECOGNITION_AUDIT.csv` with
    `submission_id,exam_id,student_id,question_number,detected_answer,final_answer,confidence,classification,manually_corrected,corrected_at`.
* Standardized cells: `A`–`F`/custom labels, `BLANK`, `MULTIPLE`, `AMBIGUOUS`, `INVALID`,
  `PENDING_REVIEW`; uncertain states are never collapsed to an empty cell.
* `DatasetSynchronizer.synchronize` writes through `AtomicFiles.write`: `.tmp`, flush/fsync,
  rename. It is called after scan commit, correction, assignment, confirmation, regrade and
  discard. `DatasetSynchronizer.verify` checks existence, size and optional SHA-256.
* Consolidated multi-exam CSV is teacher-triggered and pads heterogeneous question counts
  with explicit `N/A`.

## 4. Sheet geometry and PDF generation

`LayoutFactory.build` emits deterministic normalized `[0..1]` coordinates relative to the
four marker centroids. A4 canonical size is 1240×1754; Letter is 1275×1650. Layout JSON
contains four corner fiducials, an index tab, QR rectangle, 0–9 student ID grid, and bubble
specs for all questions/choices. `SheetLayout.metadataPayload()` is QR-encoded as
`OMR1|<templateCode>|v<version>|<examId>|<questionCount>|<labels>`.

`BlankSheetPdfGenerator` draws student fields, instructions, ID grid, bubble circles, QR
metadata and corner markers into a `PdfDocumentModel`. `PortablePdfWriter` emits real PDF 1.4
objects, xref tables and FlateDecode images; `AndroidPdfRenderer` consumes the same draw
list through `android.graphics.pdf.PdfDocument` for native preview. `PdfPrintAdapter` sends
generated PDF bytes to Android's print service.

## 5. OMR engine

`OmrEngine.process` implements eight real stages:

1. EXIF orientation, grayscale conversion, denoise, CLAHE-style contrast normalization,
   adaptive local-mean threshold.
2. Four-marker connected-component detection, convex quadrilateral geometry, orientation
   (0/90/180/270) and skew estimation.
3. DLT homography from detected fiducials to a probe canvas, then perspective warp.
4. QR template decode, persisted layout lookup, geometric alignment verification, final
   homography to the registered canonical canvas.
5. Local-baseline dark-pixel fill ratios for each bubble ROI.
6. Five-state classification `BLANK`, `SINGLE_MARK`, `MULTIPLE_MARKS`, `AMBIGUOUS`,
   `INVALID`, with confidence from fill/second-fill margins and contrast.
7. Student ID bubble grid / QR / manual assignment with roster validation.
8. Immutable `OmrRecognitionResult`, quality gate, warnings, image fingerprint and canonical
   images used for zoomed ROI crops.

`ImageQualityGate` reports blur (`laplacianVariance`), minimum short side, lighting, shadow/
uneven illumination, marker visibility, skew and excessive dark area. CameraX analysis runs
this gate live; auto-capture is optional and teacher-controlled. `ThresholdCalibration`
derives threshold bounds from measured blank/mark ROI fills instead of hardcoded demo data.

`PortableQrCodec` is a byte-mode ECC-L QR encoder/decoder (versions 1..10, Reed–Solomon,
mask selection); Android uses ZXing for camera-grade decoding. The portable decoder does not
validate QR function/format patterns, so a single-bit data tamper may still round-trip; do
not treat it as an authentication or tamper-detection mechanism.

## 6. Archives, backup, SAF and privacy

* Mandatory individual archive page 1 embeds the original captured JPEG bytes with
  `DCTDecode` (no recompression); EXIF orientation is expressed as PDF `/Rotate`. It is the
  full sheet with no overlays, warp or crop. Optional labelled page 2 is the
  perspective-corrected processing image. `pypdf` independently checked the image XObject,
  `/DCTDecode` filter, intrinsic dimensions, zero rotation for the 0° fixture and no text
  ops on page 1.
* Individual name:
  `EXAM_<ExamID>_STUDENT_<StudentID>_SUBMISSION_<SubmissionID>.pdf`
  (`UNASSIGNED` until assignment). Batch name:
  `EXAM_<ExamID>_BATCH_<BatchID>_ANSWER_ARCHIVE.pdf`.
* Every generated archive stores SHA-256, size, status, retry/error state and original source
  path. Raw captures are retained until an explicit, confirmed delete.
* `BackupService` writes `manifest.json`, database bytes, layout/key/submission JSON, archive
  PDFs, dataset CSVs and optional raw images into a ZIP. Restore rejects unsafe paths,
  checksum/size mismatch, unsupported versions, missing required files and invalid relational
  counts. A validated database is staged as `restore_pending` and installed before Room opens
  on restart.
* `SafExporter` stages app-private files through `FileProvider` for `ACTION_SEND`/`ACTION_VIEW`
  and supports teacher-chosen `ACTION_OPEN_DOCUMENT_TREE` folders. No external transmission.
* `AndroidAppLogger` only emits event names, IDs/counts/file names and error classes; no
  student names, IDs or image bytes are logged. `android:allowBackup=false`.

## 7. Screens and workflow

Navigation Compose defines 24 routes/screens: Dashboard, Examination List, Create
Examination, Edit Examination, Answer Key Editor, Student Roster, Student Import, Template
Configuration, Answer Sheet Preview, Scanning Camera, Captured Image Review, OMR Processing
Status, Recognition Verification, Batch Scanning Summary, Examination Results Overview,
Student Result Details, Item Analysis, Scanned Answer Archives, PDF Document Viewer, CSV
Datasets Manager, CSV Dataset Preview, Export & Sharing Hub, Settings, Backup & Restore.

`UiState` gives every screen loading/empty/error/success rendering. Every visible button
invokes a real repository, DataStore, CameraX, SAF, print, export, navigation or validation
action; no screen uses a simulated scan result or hardcoded dataset. Duplicate scans require
explicit `Replace`, `Retain` or `Discard`; `Replace` marks the chosen existing submission
`DISCARDED`, leaving machine predictions and archived PDFs intact.

## 8. Real-photo OMR protocol (not executed here)

No photographed answer sheets were available in this sandbox and no real-photo accuracy is
claimed. To validate a deployment:

1. Print the generated sheet PDF at 300 and 600 dpi on A4 and Letter, without "fit to page"
   scaling. Verify the four fiducials and QR decode before distribution.
2. Build a ground-truth set: at least 30 sheets per condition × 3 lighting levels × 2 devices,
   with manually transcribed answer keys and student IDs. Include clean, pencil/pen, light
   marks, erasures, multiple marks, stray marks, rotation, perspective, shadow, blur, and low
   resolution.
3. Capture with CameraX at the app's normal resolution, retain original JPEG/EXIF, and assign
   a stable sheet ID. Do not overwrite or discard failed captures.
4. Run the same `OmrEngine`/`ImageOps` used by the app over every image, recording
   registration success, per-item confusion matrix, confidence, classification, student ID
   correctness and warnings in a CSV audit file.
5. Report accuracy with confidence intervals by condition and device, plus false-positive/
   false-negative counts, ambiguous flag rate, manual-review burden, and checksum-verified
   archive/CSV outputs. Calibrate thresholds only on a separate training split and report
   held-out results.
6. Keep the protocol, raw images, per-sheet ground truth and result JSON with the release
   record. Synthetic numbers in `TEST_REPORT.md` must never be substituted for these results.

## 9. Known execution boundary

* The pure JVM core is compiled and executed here; see `TEST_REPORT.md`. A static
  source-level navigation/dead-button audit (`tools/ui_audit.py`) checks 24 composables,
  24 routes/destinations, 24 NavHost call-arity matches, `AppScreen`/`UiState` usage and 185 button/export actions; it is
  not an Android build or device UI test.
* Android/Room/Hilt/Compose/CameraX/ZXing/OpenCV SDK sources are **not Android-compiled**
  in this sandbox: no Android SDK, Gradle distribution, Google/Maven artifacts, emulator, or
  `adb` are available. `tools/android_syntax_check.sh` parsed all 66 Kotlin files with
  standalone `kotlinc` and found 0 syntax-like errors, but 3801 missing-dependency/type
  errors remain unresolved without the Android libraries.
* `AndroidPdfRenderer`, `PdfPrintAdapter`, CameraX binding, ZXing decode and OpenCV native
  acceleration therefore require verification on a normal Android build host.
* The expected APK path `app/build/outputs/apk/debug/app-debug.apk` is absent here. It is
  produced only by a successful `./gradlew assembleDebug`.
