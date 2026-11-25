#!/usr/bin/env bash
# Reproducible JVM verification for the platform-independent core. Bootstraps a real
# JDK and Kotlin compiler from the allowlisted PyPI/npm hosts when not already present.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
TC="$ROOT/tools/.toolchain"
mkdir -p "$TC" "$ROOT/verification-output"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]]; then
  if [[ ! -x "$TC/jdk/bin/java" ]]; then
    echo "== bootstrapping JDK (jdk4py wheel from PyPI) =="
    python3 - <<'PY'
import json, urllib.request, zipfile, pathlib, shutil, sys
root = pathlib.Path("tools/.toolchain")
meta = json.load(urllib.request.urlopen("https://pypi.org/pypi/jdk4py/json"))
wheel = next(f["url"] for f in meta["urls"] if "manylinux_2_17_x86_64" in f["filename"])
path = root / "jdk4py.whl"
urllib.request.urlretrieve(wheel, path)
with zipfile.ZipFile(path) as z: z.extractall(root / "jdk4py-extract")
shutil.move(str(root / "jdk4py-extract" / "jdk4py" / "java-runtime"), str(root / "jdk"))
print("JDK ready")
PY
  fi
  export JAVA_HOME="$TC/jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v kotlinc >/dev/null 2>&1; then
  if [[ ! -x "$TC/kotlinc/bin/kotlinc" ]]; then
    echo "== bootstrapping Kotlin compiler (npm kotlin-compiler tarball) =="
    curl -fsSL -o "$TC/kotlin-compiler.tgz" \
      https://registry.npmjs.org/kotlin-compiler/-/kotlin-compiler-2.4.21.tgz
    tar -xzf "$TC/kotlin-compiler.tgz" -C "$TC"
    mv "$TC/package" "$TC/kotlinc"
  fi
  export PATH="$TC/kotlinc/bin:$PATH"
fi

echo "== compiling $(find app/src/main/java/com/omrgrader/{core,domain,omr,export,archive} jvm-tests/src -name '*.kt' | wc -l) Kotlin source files =="
mapfile -t SRCS < <(find app/src/main/java/com/omrgrader/core \
  app/src/main/java/com/omrgrader/domain \
  app/src/main/java/com/omrgrader/omr \
  app/src/main/java/com/omrgrader/export \
  app/src/main/java/com/omrgrader/archive \
  jvm-tests/src -name '*.kt' | sort)
kotlinc "${SRCS[@]}" -include-runtime -d "$TC/verify.jar"
echo "== running JVM test suite =="
java -Xmx2g -jar "$TC/verify.jar"

echo "== independent cross-check (pypdf / OpenCV QR / Python csv / zipfile) =="
if ! python3 -c "import pypdf, cv2" >/dev/null 2>&1; then
  python3 -m pip install -q --target "$TC/py" pypdf qrcode opencv-python-headless
fi
PYTHONPATH="${TC}/py${PYTHONPATH:+:$PYTHONPATH}" python3 tools/cross_check.py

echo "== static 24-screen navigation / dead-button audit =="
python3 tools/ui_audit.py
echo "== verification complete =="
