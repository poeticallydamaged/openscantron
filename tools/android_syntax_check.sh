#!/usr/bin/env bash
# Standalone Kotlin syntax check for all app/src/main/java sources.
#
# This is NOT an Android/Gradle compile: Android SDK/Compose/Room/Hilt/CameraX/ZXing
# classes are unavailable in this sandbox, so kotlinc exits non-zero with many expected
# unresolved-symbol errors. The script's purpose is to detect source syntax errors
# (unbalanced delimiters, unexpected tokens, unclosed comments, redeclarations) and
# record a precise, reproducible log.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/verification-output"
mkdir -p "$OUT"
LOG="$OUT/android-syntax.log"
JSON="$OUT/android-syntax.json"
mkdir -p "$OUT/.tmp"
mapfile -t SRCS < <(find app/src/main/java -name '*.kt' | sort)
echo "source_count=${#SRCS[@]}" > "$LOG"
kotlinc "${SRCS[@]}" -d "$OUT/.tmp/android-syntax.jar" >> "$LOG" 2>&1
KOTLINC_EXIT=$?
rm -rf "$OUT/.tmp"
python3 - "$KOTLINC_EXIT" "$LOG" "$JSON" <<'PY'
import json, re, sys
from pathlib import Path
status, logpath, jsonpath = int(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
lines = logpath.read_text(errors="replace").splitlines()
errors = [l for l in lines if ": error:" in l or l.startswith("e: ")]
keywords = ("expecting ", "unexpected token", "syntax error", "unclosed comment",
            "conflicting declarations", "redeclaration", "expecting an element",
            "expecting a top level declaration")
syntax_like = [l for l in lines if any(k in l.lower() for k in keywords)]
unresolved = [l for l in errors if "unresolved reference" in l]
report = {
    "type": "standalone_kotlin_syntax_check",
    "is_android_build": False,
    "expected_kotlinc_exit_nonzero_without_android_dependencies": True,
    "kotlinc_exit": status,
    "source_count": sum(1 for _ in Path("app/src/main/java").rglob("*.kt")),
    "compiler_error_lines": len(errors),
    "unresolved_reference_lines": len(unresolved),
    "syntax_like_errors": syntax_like,
    "syntax_check_passed": status == 0 or not syntax_like,
    "log": str(logpath),
}
jsonpath.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
PY
exit 0
