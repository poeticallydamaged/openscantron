# TEST_REPORT.md — OpenScantron

Executed: 2026-11-25 UTC. All results below come from command outputs and JSON artifacts in
this repository. This report intentionally distinguishes **executed JVM verification** from
**blocked Android/Gradle verification**. No Gradle pass, Lint pass, device test, OpenCV
Android SDK build, real-photo result, or APK delivery is fabricated.

## 1. Summary

| Verification | Status | Measured result |
| :--- | :--- | :--- |
| `./tools/verify.sh` | PASS | 38 Kotlin source files compiled; **27/27** JVM tests; **25/25** independent cross-checks; 128-sheet synthetic OMR suite with `failures=0`; static 24-screen/185-button audit passed; exit 0 |
| `./gradlew assembleDebug` | BLOCKED | exit 1 before project configuration: Gradle 8.9 distribution TLS handshake from `services.gradle.org` terminated |
| `./gradlew testDebugUnitTest` | BLOCKED | same Gradle distribution blocker |
| `./gradlew lintDebug` | BLOCKED | same Gradle distribution blocker; additionally no Android SDK/Lint toolchain |
| `./gradlew connectedDebugAndroidTest` | BLOCKED | same Gradle blocker; additionally no Android SDK, `adb`, emulator/device |
| `app/build/outputs/apk/debug/app-debug.apk` | NOT PRODUCED | expected output of a successful `assembleDebug`; currently absent |
| Android JVM unit tests / instrumented tests | SOURCE WRITTEN, NOT RUN | 2 `app/src/test` classes and 3 `app/src/androidTest` classes; blocked by Gradle/Android dependencies |
| Standalone Android-source syntax check | SYNTAX PASS / TYPE-CHECK BLOCKED | 66 Kotlin files; `kotlinc` exit 1 due 3801 expected missing-dependency errors (3174 unresolved references); 0 syntax-like errors |
| Room schema export | PRESENT, NOT KSP-GENERATED | `app/schemas/.../1.json`, `2.json` generated deterministically by `tools/generate_room_schemas.py`; KSP generation requires the blocked Gradle toolchain |
| Real photographed sheets | NOT EXECUTED | no claimed accuracy; protocol documented in `TECHNICAL_DOCUMENTATION.md` |

## 2. Executed pure-JVM verification

Command:

```bash
export JAVA_HOME=/tmp/toolchain/jdk4py/jdk4py/java-runtime
export PATH=$JAVA_HOME/bin:/tmp/toolchain/package/bin:$PATH
export PYTHONPATH=/tmp/toolchain/py
./tools/verify.sh
```

Result: exit 0, `verification complete`; no compiler warnings in the final run. Full
stdout is preserved at `verification-output/final-verify.log`. The script also runs
`tools/ui_audit.py`. Sources:
`app/src/main/java/com/omrgrader/{core,domain,omr,export,archive}` and `jvm-tests/src`
(38 files). The JVM toolchain was OpenJDK 25.0.5.1 with Kotlin compiler 2.4.21; the app
targets Java 17 with Kotlin 2.0.21 when built normally.

### JVM tests — 27/27 passed

Report: `verification-output/jvm-results.json`.

Coverage includes strict JSON escaping/Unicode, RFC 4180 CSV, atomic `.tmp`/fsync/rename
writes and stable SHA-256, `OmrResult` error mapping, exam presets/custom counts/duplicate/
archive, answer-key versioning and controlled regrade, equal/weighted/deduction/partial/pending
grading with a zero-max guard, item-analysis KPIs, duplicate decisions, roster import invalid
rows, batch counters, QR codec, deterministic layout JSON, printable sheet PDF draw list,
report KPI PDF, wide/audit/consolidated CSVs, atomic dataset synchronization, archive
integrity/naming/checksum/retry/storage deletion, backup create/restore/tamper/relational
validation, homography/marker/quality math, threshold calibration, and the full synthetic OMR
suite.

### Independent Python cross-checks — 25/25 passed

Report: `verification-output/cross-check.json`. Uses `pypdf`, OpenCV QR decoder, Python
`csv`, `zipfile`, `io` and `hashlib` on artifacts produced by Kotlin:

* Blank sheet: 3 pages; `OPEN SCANTRON ANSWER SHEET`; template code; `/Title` metadata.
* Individual archive: 2 pages; page 1 has `/Im0`; image XObject uses `/DCTDecode` with the
  original JPEG bytes embedded without recompression; no `/Rotate` for the 0° fixture;
  intrinsic JPEG dimensions `16×24`; page 1 content stream has no `BT`/` Tj` overlays;
  page 2 is labelled `PERSPECTIVE-CORRECTED`.
* Batch archive: 3 retained pages; page metadata includes
  `EXAM exam-1 | BATCH batch-1 | PAGE 1 | STUDENT 2026-001 | SUBMISSION sub-1`.
* Exam report PDF: contains `EXAM PERFORMANCE REPORT` and live submission KPI text.
* QR: OpenCV decodes `OMR1|TPL-A020-V1-000EXAM1|v1|exam-1|20|ABCD`.
* Wide CSV: 476 bytes; UTF-8 BOM; `Q001..Q007`; one data row; explicit `BLANK`; quoted
  `Midterm, "A"` survives Python `csv.reader`.
* Recognition audit CSV: 432 bytes; UTF-8 BOM; 6 rows; long-format `question_number`.
* Dataset sync bytes: 894.
* Sample backup ZIP: 8 ZIP entries / 7 manifest files; every SHA-256 matches; database
  included.

Artifact sizes measured in `verification-output/jvm-results.json`:
`individual_archive_pdf_bytes=7211`, `batch_archive_pdf_bytes=16564`,
`backup_zip_bytes=1749`, `wide_csv_bytes=476`, `audit_csv_bytes=432`,
`dataset_sync_bytes=894`, `calibration_separation=0.7525248748378288`.

### Synthetic OMR accuracy

Report: `verification-output/omr-accuracy.json`; 16 adverse conditions × 8 seeds, 20-question
A4 layout = **128 synthetic sheets**.

| Metric | Result |
| :--- | :--- |
| registration | 128/128 = 100% |
| single marks | 1489/1489 = 100% |
| blanks | 573/573 = 100% |
| multiple marks | 131/131 = 100% |
| ambiguous flags | 367/367 = 100% |
| student IDs | 128/128 = 100% |
| failures | 0 |

These are **synthetic harness results**, not photographed-sheet accuracy. The real-photo
protocol is in `TECHNICAL_DOCUMENTATION.md` section 8 and has not been executed.

### Standalone Android-source syntax check

Command: `./tools/android_syntax_check.sh` with the same JDK/Kotlin compiler from PyPI/npm.
Report: `verification-output/android-syntax.json`; full log:
`verification-output/android-syntax.log`. It compiled all 66 `app/src/main/java` Kotlin
files with standalone `kotlinc`. Exit code was 1 with 3801 compiler error lines, 3174 of
which are unresolved Android/Compose/Room/Hilt/CameraX/ZXing references; **0** syntax-like
errors (unbalanced delimiters, unexpected tokens, unclosed comments, redeclarations). This
is recorded as a syntax pass only, not an Android build or type-check.

### Static navigation / zero-dead-button audit

Report: `verification-output/ui-audit.json`; command `python3 tools/ui_audit.py`; exit 0.
Source-level checks: 24 `...Screen` composables, 24 route constants, 24 `NavHost`
destinations, all 24 screens appear in `AppNav`, all 24 NavHost calls match their
composable signatures, all 24 use `AppScreen`, all four screen files reference `UiState`,
166 standard Material buttons all have non-empty `onClick`, and 19 `ExportButton` calls
all have a `produce` action. Total 185/185 button/export actions
passed; failures empty. This audit is not an Android build or device UI test; it cannot
prove runtime click behavior.

## 3. Android unit and instrumented tests (written, not executed)

* `app/src/test/java/com/omrgrader/PureCoreUnitTests.kt` — CSV/BOM, atomic write, zero-score
  guard, answer-key validation, QR round-trip.
* `app/src/test/java/com/omrgrader/OmrEngineUnitTests.kt` — clean synthetic sheet registration,
  template/student ID, 20-question recognition.
* `app/src/androidTest/java/com/omrgrader/AppNavigationInstrumentedTest.kt` — dashboard, exam
  list and create-exam navigation with Compose test rules.
* `app/src/androidTest/java/com/omrgrader/RoomMigrationInstrumentedTest.kt` —
  `MigrationTestHelper` v1→v2 migration with exported schemas and preserved rows.
* `app/src/androidTest/java/com/omrgrader/RoomTransactionInstrumentedTest.kt` — in-memory Room
  exam/template/dataset transaction bundle.

These sources require JUnit, AndroidX Test, Room testing, Compose test and Android platform
classes. They could not be compiled or executed here.

## 4. Exact Android/Gradle blockers

`./tools/verify_android.sh` executed all four requested commands and recorded logs, exit
codes, durations and a machine-readable summary in
`verification-output/gradle/verification.json`. The final run was at 2026-11-25
08:20:42 UTC. Result:

| Command | Exit | Duration (s) | Exact first error |
| :--- | ---: | ---: | :--- |
| `./gradlew assembleDebug` | 1 | 0 | `Attempt 1/1 failed. Reason: Remote host terminated the handshake` |
| `./gradlew testDebugUnitTest` | 1 | 1 | `Attempt 1/1 failed. Reason: Remote host terminated the handshake` |
| `./gradlew lintDebug` | 1 | 0 | `Attempt 1/1 failed. Reason: Remote host terminated the handshake` |
| `./gradlew connectedDebugAndroidTest` | 1 | 0 | `Attempt 1/1 failed. Reason: Remote host terminated the handshake` |

Full logs: `verification-output/gradle/{assembleDebug,testDebugUnitTest,lintDebug,connectedDebugAndroidTest}.log`.
All four contain the same `javax.net.ssl.SSLHandshakeException` while the genuine Gradle
wrapper jar tries to download `gradle-8.9-bin.zip` from `services.gradle.org`; project
configuration, dependency resolution, compilation and test execution never began.

`verification-output/gradle/network.log` records:

| Host | curl result |
| :--- | :--- |
| `services.gradle.org/distributions/gradle-8.9-bin.zip` | HTTP 000, `SSL_ERROR_SYSCALL`, curl 35 |
| `dl.google.com/dl/android/maven2/` | HTTP 000, `SSL_ERROR_SYSCALL`, curl 35 |
| `maven.google.com/` | HTTP 000, `SSL_ERROR_SYSCALL`, curl 35 |
| `repo1.maven.org/maven2/` | HTTP 000, `SSL_ERROR_SYSCALL`, curl 35 |
| PyPI / npm / GitHub | HTTP 200 |

`verification-output/gradle/environment.log` records `ANDROID_HOME=unset`,
`ANDROID_SDK_ROOT=unset`, `adb=not_found`, `sdkmanager=not_found`, and no Android SDK.
JDK 25.0.5.1 from `jdk4py` can run the wrapper, but it is a runtime without `javac` and
does not supply Gradle, AGP, Android platform jars, build tools, Lint or a device.

## 5. Acceptance criteria status

| Requirement | Status |
| :--- | :--- |
| Offline Android app; no account/analytics/network/PII logging | Source-implemented; runtime verification blocked |
| Room v2, Hilt, DataStore, CameraX, SAF, QR/fiducial registration | Source-implemented; Android compile/device verification blocked |
| Versioned keys/templates, roster CSV, correction/duplicate handling | Domain JVM-tested; Android integration blocked |
| Grading/status/KPIs/item analysis | JVM-tested; Android integration blocked |
| Individual/batch archive PDFs; original JPEG page 1 with no overlays | JVM-tested with real JPEG `/DCTDecode`; independently cross-checked |
| Blank/student/exam report PDFs; wide/audit/consolidated/summary CSVs | JVM-tested and independently cross-checked |
| Checksum ZIP backup/restore | JVM-tested and independently cross-checked |
| 24 Compose screens, loading/empty/error states, no dead buttons | Source-implemented; static 24-screen/185-button audit passed; runtime UI/device verification blocked |
| `README.md`, `PROJECT_PLAN.md`, `TECHNICAL_DOCUMENTATION.md`, `TEST_REPORT.md` | Present; `PROJECT_PLAN.md` updated |
| Android unit/instrumented tests, exported Room schemas | Present; tests not executed; schemas not KSP-generated here |
| `app/build/outputs/apk/debug/app-debug.apk` | **Not produced**; exact blockers above |

## 6. Known limitations and reproducibility

* Portable QR decoder does not validate function/format patterns; a single-bit data tamper
  can still decode to the original payload. QR metadata is not an authentication mechanism.
* No real-photo OMR accuracy exists; use the documented protocol before classroom release.
* OpenCV Android SDK/native `.so` files are not vendored. Pure-Kotlin `ImageOps` is complete;
  `app/src/opencv/java/OpenCvImageOps.kt` compiles only when `opencv.sdk.dir` exists.
* Android sources were syntax-checked with standalone `kotlinc` (66 files, 0 syntax-like
  errors) but cannot be fully type-checked without Android SDK/Compose/Room/Hilt/CameraX
  dependencies. The standalone check produced 3801 missing-dependency/type errors; it is
  not reported as a Gradle build pass.
* Room schema JSON was authored/generated from the declared entities for migration tests;
  a normal KSP build should be used to regenerate it on an Android host.
* To reproduce on a full host: configure `local.properties` with `sdk.dir`, ensure network
  access to Gradle/Google/Maven, run `./tools/verify.sh` and
  `./tools/android_syntax_check.sh`, then run all four Gradle commands with a connected
  device and verify `app/build/outputs/apk/debug/app-debug.apk`.
