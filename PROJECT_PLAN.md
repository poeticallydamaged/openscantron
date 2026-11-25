# PROJECT_PLAN.md — OpenScantron offline OMR grader

> Autonomous execution log. Phase 1 was written before implementation; the status sections
> below record what was actually built and what was actually verified on 2026-11-25 UTC.
> This is not a reduction of the requested Android/Gradle/APK scope: the full Android
> project is present, and every blocked verification is recorded with its exact command.

## Phase 1 — Repository and environment inspection (completed)

Observed state at `d8315962a14eba616d8419fce1d6a2bab5ff46a1`:

* Repository contained only `README.md` (`# openscantron`). No Gradle files, Android
  manifest, source tree, tests, schemas, docs or APK.
* Sandbox: Debian 12, `git`/`gh`/Python/Node available; initially no JDK, Android SDK,
  Gradle, `kotlinc`, `adb`, emulator or `android.jar`.
* Outbound network is restricted to `github.com`, `codeload.github.com`, `api.github.com`,
  `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`. TLS connections to
  `dl.google.com`, `maven.google.com`, `repo1.maven.org` and `services.gradle.org` are
  refused. AndroidX/AGP/Room/Hilt/CameraX artifacts, the Gradle 8.9 distribution and the
  Android SDK therefore cannot be resolved in this sandbox.
* A real wrapper jar/script was fetched through the GitHub API. The distribution URL in
  `gradle/wrapper/gradle-wrapper.properties` still points to the blocked Gradle service.

### Engineering decisions forced by the environment

1. **Split the code into a strictly platform-independent core and Android adapters.**
   `com.omrgrader.core`, `domain`, `omr`, `export`, `archive` contain zero Android/Compose
   imports. Room, CameraX, DataStore, SAF, `PdfDocument`, ZXing, Hilt and the 24 Compose
   screens are adapters. The core is the single implementation of grading, OMR, CSV, PDF,
   QR math, archive and backup; the Android layer never re-implements or fakes it.
2. **A real JVM verification toolchain is bootstrapped from allowed hosts** by
   `tools/verify.sh`: `jdk4py` (Temurin JDK runtime from PyPI) + `kotlin-compiler` (npm
   tarball). It compiles the exact same core sources that Gradle compiles for Android and
   executes real unit, synthetic-OMR, CSV, PDF, QR, archive and backup tests. Independent
   cross-checks use `pypdf`, OpenCV QR decode, Python `csv`/`zipfile`/`hashlib`.
3. **PDF writing is real portable Kotlin** (PDF 1.4 objects, xref, FlateDecode raster
   XObjects, DCTDecode original JPEGs, vector text/lines/circles). `AndroidPdfRenderer`
   consumes the same draw-command list for preview; the portable writer produces archive
   bytes/checksums consistently and is exercised by the JVM suite.
4. **QR metadata is real byte-mode ECC-L QR**, versions 1..10, Reed–Solomon and mask
   selection. Android uses ZXing for camera-grade decoding; both implement `QrCodec`.
5. **OpenCV integration is optional.** `app/src/opencv/java` contains a real `org.opencv`
   `ImageOps` implementation (CLAHE, adaptive threshold, perspective transform/warp).
   Gradle includes it only when `opencv.sdk.dir` is present. Without the SDK the complete
   pure-Kotlin `PortableImageOps` runs; `ImageOpsFactory` initializes OpenCV by reflection
   when available. Neither branch fabricates recognition results.
6. **Database is authoritative.** Screen state derives from Room `Flow`s. Machine
   predictions, original JPEGs and archive PDFs are append-only; corrections preserve
   `recognized_answer` separately from `final_answer`; duplicates require explicit
   Replace/Retain/Discard. CSV/PDF files are derived artifacts regenerated atomically.
7. **No fabricated artifacts.** `TEST_REPORT.md` records the exact four Gradle command
   failures. No APK, Android build pass, Lint pass, device result, OpenCV Android SDK
   build or real-photo accuracy number is invented.

## Phase execution status

| Phase | Scope | Status | Verification |
| :--- | :--- | :--- | :--- |
| 2 | Gradle/AGP/Hilt/Navigation/Material 3, Room v2 + explicit migration, repositories, DataStore, core errors/dispatchers | **Built** | pure core JVM-tested; Android compile blocked; Room schema JSON present |
| 3 | Exam CRUD/duplicate/archive, scoring rules, versioned answer keys, grading engine, unit tests | **Built** | 27/27 JVM suite; Android unit tests written but unrun |
| 4 | Normalized layout engine (4 fiducials), QR metadata, A4/Letter printable PDF + preview | **Built** | printable/report PDF JVM + 25/25 Python cross-checks |
| 5 | 8-stage OMR pipeline + synthetic image generator (16 adverse conditions) + accuracy suite | **Built** | 128 sheets × 8 seeds, 100% on synthetic metrics, failures=0 |
| 6 | CameraX preview/flash/lens/auto-capture, quality gate, gallery import, review/rotate | **Built** | source-implemented; Android/device compile blocked |
| 7 | Confidence rules, non-destructive verification, grading, batch loop + crash recovery | **Built** | domain JVM-tested; Android integration blocked |
| 8 | Per-scan original archival PDF, atomic wide + audit CSV, SAF export | **Built** | original JPEG `/DCTDecode` page 1 cross-checked; atomic CSV cross-checked |
| P2 | Roster CRUD/CSV import, ID grid, batch PDF, archive manager, results, reports, backup/restore | **Built** | JVM + cross-checks; UI/device verification blocked |
| P3 | Weighted/partial credit, item analysis, diagnostics, layout customization, consolidated exports | **Built** | JVM + cross-checks |
| UI | 24 functional Compose screens, loading/empty/error states, no dead buttons | **Built** | static audit + standalone Kotlin syntax check; no Android/device build |
| Docs | `README.md`, `PROJECT_PLAN.md`, `TECHNICAL_DOCUMENTATION.md`, `TEST_REPORT.md` | **Built** | present with measured results and exact blockers |
| Android QA | `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `connectedDebugAndroidTest`, APK | **Blocked** | all four executed with exit 1 at Gradle distribution TLS download; no APK |

## Verification rule and executed outcome

Pure-core sources are checked with `./tools/verify.sh` whenever they change. Final executed
result: compiled 38 Kotlin files, **27/27** JVM tests, **25/25** independent cross-checks,
128-sheet synthetic OMR suite with `failures=0`, a passing static UI audit (24 screens,
24 routes/destinations, 185 non-empty button/export actions), exit 0. Reports are in
`verification-output/{jvm-results.json,cross-check.json,omr-accuracy.json}`.

Android-only sources were checked with `tools/android_syntax_check.sh` (66 Kotlin files, 0
syntax-like errors); that check correctly reports 3801 unresolved
Android/Compose/Room/Hilt/CameraX symbols because their libraries cannot be downloaded. It
is **not** a Gradle build pass and is not used to claim Android type safety. `tools/verify_android.sh` executed the four requested Gradle
commands and captured exact logs in `verification-output/gradle/`.

## Assumptions

* `minSdk 26`, `targetSdk 35`, `compileSdk 35`; single `:app` module plus optional local
  `:opencv` module; Java 17 toolchain.
* Teacher operates fully offline; no account, analytics, crash reporting or network call.
* Student PII is never logged; log statements only contain IDs, counts, file names and
  error classes.
* "Unmodified original" archival page means the captured JPEG bytes are embedded with
  `DCTDecode` without recompression and EXIF orientation is expressed as PDF `/Rotate` —
  no overlays, perspective warp or cropping. The source JPEG is retained separately and
  checksummed.
* Synthetic accuracy targets are measured on the in-repo synthetic harness. A documented
  real-photo protocol is provided; no real photographed sheets are available in this
  sandbox, so no real-photo accuracy is claimed.
