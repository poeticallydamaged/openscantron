# OpenScantron — offline OMR grader for Android

OpenScantron is an offline-first Android Scantron/OMR grader written in Kotlin and Jetpack
Compose. It creates examinations and printable bubble sheets, captures sheets with CameraX,
registers them with four fiducials, recognizes bubble/ID marks, grades against a pinned
answer-key version, retains non-destructive verification provenance, writes archival PDFs and
atomic CSV datasets, and exports reports/backups through the Storage Access Framework.

The application has **no account, analytics, cloud sync, or runtime network dependency**.
Room is the single source of truth; student names/IDs are never written to logs.

## Repository layout

| Path | Contents |
| :--- | :--- |
| `app/src/main/java/com/omrgrader/core` | errors, result wrapper, dispatchers, clock/logger contracts, strict JSON, RFC 4180 CSV, SHA-256 atomic writes |
| `domain` | platform-independent models, scoring, confidence policy, exam/key administration, roster import, item analysis, dataset snapshots, JSON converters |
| `omr` | `GrayImage`/`ImageOps`, homography, normalized sheet layout, marker/bubble/ID recognition, quality gate, eight-stage engine, synthetic generator, threshold calibration |
| `export` | QR codec, portable PDF 1.4 writer, printable sheets, report PDFs, wide/audit/consolidated CSVs, atomic dataset synchronizer |
| `archive` | individual/batch archival PDFs, checksums, storage controls, backup/restore manifest |
| `data/local`, `data/repository`, `data/preferences` | Room v2 entities/DAOs/migrations, concrete repository, DataStore settings |
| `platform`, `camera`, `di` | Android adapters, CameraX, SAF, `PdfDocument`, Bitmap/EXIF, ZXing, Hilt modules |
| `presentation` | Material 3 theme, Navigation Compose graph, view models, and all 24 functional screens |
| `app/src/opencv/java` | real OpenCV Android SDK `ImageOps` source, compiled only when the SDK is configured |
| `jvm-tests`, `tools/verify.sh`, `tools/cross_check.py` | standalone JVM test runner and independent Python `pypdf`/OpenCV/csv/zipfile cross-checks |
| `app/src/test`, `app/src/androidTest` | Gradle JUnit unit tests and instrumented navigation/Room tests (not executed in this sandbox) |
| `app/schemas` | Room v1/v2 exported schema JSON used by migration tests |

## Requirements

* Android Studio Ladybug or newer; JDK 17.
* Android SDK platform 35 and compatible build tools. `minSdk 26`, `targetSdk 35`.
* Gradle wrapper 8.9. The **first build** needs access to `services.gradle.org`, Google Maven
  and Maven Central to fetch the Gradle distribution and AndroidX/AGP/Room/Hilt/CameraX
  artifacts. Subsequent app use is fully offline.
* Optional: OpenCV Android SDK 4.x for the accelerated `org.opencv` image operators.
* Optional physical device/emulator for `connectedDebugAndroidTest`.

## Setup

1. Clone/open the repository root in Android Studio, or use the command line.
2. Create `local.properties` (this file is ignored by Git):
   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   # Optional, see below:
   opencv.sdk.dir=/absolute/path/to/OpenCV-android-sdk
   ```
3. Build and verify:
   ```bash
   ./gradlew assembleDebug
   ./gradlew testDebugUnitTest
   ./gradlew lintDebug
   ./gradlew connectedDebugAndroidTest   # requires a connected device/emulator
   ```
   The debug APK is written to:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```
   Install with `./gradlew installDebug` when a device is attached.

## OpenCV Android SDK integration

The pure-Kotlin `PortableImageOps` is a complete implementation and is used by default. To
compile the real OpenCV-accelerated implementation:

1. Download and unpack an OpenCV Android SDK (for example `OpenCV-android-sdk`).
2. Set `opencv.sdk.dir` in `local.properties` to that unpacked directory.
3. Copy native libraries so they are packaged:
   ```bash
   mkdir -p app/src/main/jniLibs
   cp -r "$OPENCV_ANDROID_SDK/sdk/native/libs/"* app/src/main/jniLibs/
   ```
4. `settings.gradle.kts` includes the SDK's `sdk/java` directory as the `:opencv` module when
   `opencv.sdk.dir/sdk/java` exists. `app/build.gradle.kts` then adds
   `app/src/opencv/java` to the main source set and sets `BuildConfig.HAS_OPENCV_SDK=true`.
5. `ImageOpsFactory` initializes OpenCV by reflection (`OpenCVLoader.initDebug()`) and loads
   `OpenCvImageOps`; if the SDK is absent it selects `PortableImageOps`. Neither branch
   fabricates or simulates recognition results.

Do not commit the OpenCV SDK, `local.properties`, keystores, or generated APK/AAB files.

## Standalone JVM verification (works in this sandbox)

The sandbox used to author this repository has no Android SDK and no Gradle/Maven/Google
artifact access. It can still compile and execute the platform-independent core with a real
JDK and Kotlin compiler bootstrapped from PyPI/npm:

```bash
export JAVA_HOME=/path/to/jdk
export PATH="$JAVA_HOME/bin:/path/to/kotlinc/bin:$PATH"
./tools/verify.sh
```

This compiles the same `core`/`domain`/`omr`/`export`/`archive` sources used by the Android
app, runs the JVM suite, generates sample PDFs/CSV/QR/BMP/ZIP under `verification-output/`,
runs independent Python cross-checks, and executes a static 24-screen navigation /
dead-button audit (`tools/ui_audit.py`, report: `verification-output/ui-audit.json`).
`./tools/android_syntax_check.sh` separately parses all 66 Android Kotlin files for syntax
errors; it is **not** an Android type-check. Neither script substitutes for `assembleDebug`.

To reproduce the exact four Gradle command outcomes in this sandbox, run:

```bash
./tools/verify_android.sh
```

It writes logs and `verification-output/gradle/verification.json`. See `TEST_REPORT.md`
for actual results; no APK, Android build pass, or device result is fabricated.

## Release build

`./gradlew assembleRelease` produces an unsigned/minify-disabled release from the current
`release` build type. Before publishing, configure a signing config in
`app/build.gradle.kts` (or Gradle properties), enable and validate R8/ProGuard rules, run all
four verification commands on a clean checkout with a device, and archive the signed AAB/APK.
The release build was not executed in this sandbox because the Android SDK and dependency
repositories are unavailable.

## Documentation

* `PROJECT_PLAN.md` — phases, engineering decisions and current status.
* `TECHNICAL_DOCUMENTATION.md` — architecture, schema, OMR stages, file formats, real-photo
  protocol and security/privacy notes.
* `TEST_REPORT.md` — measured JVM results, synthetic OMR accuracy, dataset sizes, exact
  Gradle blockers, acceptance status and known limitations.
