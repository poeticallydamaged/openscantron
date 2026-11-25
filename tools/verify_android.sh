#!/usr/bin/env bash
# Executes the four requested Gradle verification commands and records exact outcomes.
# This script does not modify a failure into a pass: it only captures logs and exit codes.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/verification-output/gradle"
mkdir -p "$OUT"
chmod +x gradlew || true

if [[ -x "$ROOT/tools/.toolchain/jdk/bin/java" ]]; then
  export JAVA_HOME="$ROOT/tools/.toolchain/jdk"
elif [[ -x "/tmp/toolchain/jdk4py/jdk4py/java-runtime/bin/java" ]]; then
  export JAVA_HOME="/tmp/toolchain/jdk4py/jdk4py/java-runtime"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  export JAVA_HOME="$JAVA_HOME"
fi
export PATH="${JAVA_HOME:-$PATH}/bin:$PATH"

{
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "JAVA_HOME=${JAVA_HOME:-unset}"
  echo "ANDROID_HOME=${ANDROID_HOME:-unset}"
  echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset}"
  echo "--- java -version ---"
  java -version 2>&1 || true
  echo "--- gradle-wrapper.properties ---"
  cat gradle/wrapper/gradle-wrapper.properties || true
  echo "--- adb ---"
  if command -v adb >/dev/null 2>&1; then adb devices 2>&1 || true; else echo "adb=not_found"; fi
  echo "--- sdkmanager ---"
  if command -v sdkmanager >/dev/null 2>&1; then sdkmanager --version 2>&1 || true; else echo "sdkmanager=not_found"; fi
} > "$OUT/environment.log" 2>&1

{
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for host in https://services.gradle.org/distributions/gradle-8.9-bin.zip \
              https://dl.google.com/dl/android/maven2/ https://maven.google.com/ \
              https://repo1.maven.org/maven2/ https://pypi.org/simple/ \
              https://registry.npmjs.org/ https://github.com/; do
    printf "%s -> " "$host"
    curl --max-time 8 -sS -o /dev/null -w "http=%{http_code} result=%{exitcode}\n" "$host" 2>&1 || echo "curl_exit=$?"
  done
} > "$OUT/network.log" 2>&1

COMMANDS=("assembleDebug" "testDebugUnitTest" "lintDebug" "connectedDebugAndroidTest")
RESULTS="$OUT/command-results.tsv"
: > "$RESULTS"
for task in "${COMMANDS[@]}"; do
  log="$OUT/$task.log"
  start=$(date +%s)
  timeout 90s ./gradlew "$task" > "$log" 2>&1
  status=$?
  end=$(date +%s)
  printf "%s\t%s\t%s\t%s\n" "$task" "$status" "$((end-start))" "$log" >> "$RESULTS"
done

python3 - <<'PY'
import json, pathlib, re
root = pathlib.Path("verification-output/gradle")
rows = []
for line in (root / "command-results.tsv").read_text().splitlines():
    if not line.strip(): continue
    task, status, duration, logpath = line.split("\t")
    log = pathlib.Path(logpath)
    text = log.read_text(errors="replace")
    errors = [l.strip() for l in text.splitlines()
              if "error" in l.lower() or "exception" in l.lower() or "could not" in l.lower()
              or "failed" in l.lower() or "refused" in l.lower()]
    rows.append({
        "task": task,
        "command": f"./gradlew {task}",
        "exit_code": int(status),
        "duration_seconds": int(duration),
        "log": str(log),
        "first_error": errors[0] if errors else (text.splitlines()[0] if text.splitlines() else ""),
        "log_bytes": len(text.encode("utf-8", "replace")),
    })
summary = {
    "executed": True,
    "commands": rows,
    "all_passed": all(r["exit_code"] == 0 for r in rows),
    "environment_log": str(root / "environment.log"),
    "network_log": str(root / "network.log"),
}
(root / "verification.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
PY
