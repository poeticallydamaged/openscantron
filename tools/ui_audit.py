#!/usr/bin/env python3
"""Static audit of the 24-screen Compose navigation/button wiring.

This is a source-level check, NOT an Android build or UI test. It verifies that every
standard Material button has a non-empty onClick, every ExportButton has a producer,
all 24 screen composables are hosted by NavHost, and each screen uses AppScreen/UiState.
"""
from __future__ import annotations
import json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/java/com/omrgrader/presentation"
screen_files = sorted((P / "screens").glob("*Screens.kt"))
files = screen_files + [P / "screens/Common.kt", P / "components/Scaffold.kt"]
routes_text = (P / "navigation/Routes.kt").read_text()
nav_text = (P / "navigation/AppNav.kt").read_text()


def matching_paren(text: str, open_index: int) -> str:
    depth = 0
    in_string = False
    escape = False
    for i in range(open_index, len(text)):
        c = text[i]
        if in_string:
            if escape: escape = False
            elif c == "\\": escape = True
            elif c == '"': in_string = False
            continue
        if c == '"': in_string = True; continue
        if c == "(": depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0: return text[open_index:i]
    raise ValueError("unbalanced parentheses")



def call_arguments(text: str, name: str) -> list[list[str]]:
    """Return top-level positional argument lists for all calls to name(...)."""
    result = []
    for m in re.finditer(r"\b" + re.escape(name) + r"\(", text):
        i = m.end(); depth = 1; in_string = False; escape = False
        while i < len(text) and depth:
            c = text[i]
            if in_string:
                if escape: escape = False
                elif c == "\\": escape = True
                elif c == '"': in_string = False
            else:
                if c == '"': in_string = True
                elif c == "(": depth += 1
                elif c == ")": depth -= 1
            i += 1
        args = text[m.end():i-1]
        parts = []; d = 0; last = 0
        for j, c in enumerate(args):
            if c in "(<{": d += 1
            elif c in ")>}": d -= 1
            elif c == "," and d == 0:
                parts.append(re.sub(r"\s+", " ", args[last:j].strip())); last = j + 1
        parts.append(re.sub(r"\s+", " ", args[last:].strip()))
        result.append([x for x in parts if x])
    return result


def signature_parameter_count(text: str, name: str) -> int | None:
    m = re.search(r"^fun " + re.escape(name) + r"\(", text, re.M)
    if not m: return None
    i = m.end(); depth = 1
    while i < len(text) and depth:
        if text[i] == "(": depth += 1
        elif text[i] == ")": depth -= 1
        i += 1
    args = text[m.end():i-1]
    parts = []; d = 0; last = 0
    for j, c in enumerate(args):
        if c in "(<{": d += 1
        elif c in ")>}": d -= 1
        elif c == "," and d == 0:
            parts.append(args[last:j].strip()); last = j + 1
    parts.append(args[last:].strip())
    return len([x for x in parts if x])

standard = re.compile(r"(?<![A-Za-z0-9_])(Button|IconButton|OutlinedButton|TextButton|FloatingActionButton|IconToggleButton)\s*\(")
export = re.compile(r"(?<![A-Za-z0-9_])(ExportButton)\s*\(")
button_calls = []
for f in files:
    text = f.read_text()
    for kind, pattern in (("material", standard), ("export", export)):
        for m in pattern.finditer(text):
            args = matching_paren(text, m.end() - 1)
            line = text[:m.start()].count("\n") + 1
            empty = bool(re.search(r"onClick\s*=\s*\{\s*\}", args))
            if kind == "material":
                passed = "onClick" in args and not empty
                action = "onClick"
            else:
                passed = "produce" in args and not empty
                action = "produce"
            button_calls.append({
                "file": str(f.relative_to(ROOT)), "line": line,
                "kind": kind, "name": m.group(1),
                "action": action, "passed": passed,
            })

screens = []
for f in screen_files:
    text = f.read_text()
    for m in re.finditer(r"^fun (\w+Screen)\(", text, re.M):
        screens.append({
            "file": str(f.relative_to(ROOT)),
            "name": m.group(1),
            "in_nav_host": m.group(1) in nav_text,
            "app_screen": "AppScreen(" in text,
            "ui_state": "UiState." in text,
        })

nav_arity = []
for s in screens:
    expected = signature_parameter_count("\n".join(f.read_text() for f in screen_files), s["name"])
    calls = call_arguments(nav_text, s["name"])
    actual = len(calls[0]) if calls else None
    nav_arity.append({"screen": s["name"], "expected": expected,
                      "actual": actual, "passed": expected is not None and expected == actual})

route_constants = re.findall(r'const val ([A-Z_]+) = "([^"]+)"', routes_text)
destinations = re.findall(r"\n\s+composable\(", nav_text)
checks = {
    "screen_composables": len(screens),
    "screens_in_nav_host": sum(s["in_nav_host"] for s in screens),
    "screens_with_app_screen": sum(s["app_screen"] for s in screens),
    "screen_files_with_ui_state": sum("UiState." in f.read_text() for f in screen_files),
    "route_constants": len(route_constants),
    "nav_host_destinations": len(destinations),
    "standard_buttons": sum(c["kind"] == "material" for c in button_calls),
    "export_buttons": sum(c["kind"] == "export" for c in button_calls),
    "buttons_with_non_empty_action": sum(c["passed"] for c in button_calls),
    "nav_call_arity_matches": sum(a["passed"] for a in nav_arity),
}
failures = []
if checks["screen_composables"] != 24: failures.append("expected 24 screen composables")
if checks["screens_in_nav_host"] != 24: failures.append("all screens must be referenced by AppNav")
if checks["route_constants"] != 24: failures.append("expected 24 route constants")
if checks["nav_host_destinations"] != 24: failures.append("expected 24 NavHost destinations")
if checks["screen_files_with_ui_state"] != len(screen_files): failures.append("all screen files must reference UiState")
if checks["screens_with_app_screen"] != 24: failures.append("all screens must use AppScreen")
if checks["buttons_with_non_empty_action"] != len(button_calls):
    failures += [f"dead button {c['file']}:{c['line']}" for c in button_calls if not c["passed"]]
if checks["nav_call_arity_matches"] != 24:
    failures += [f"NavHost arity mismatch {a['screen']}: expected {a['expected']}, got {a['actual']}"
                 for a in nav_arity if not a["passed"]]

report = {
    "type": "static_source_audit",
    "not_android_build_or_device_test": True,
    "checks": checks,
    "failures": failures,
    "passed": not failures,
    "screens": screens,
    "buttons": button_calls,
    "nav_call_arity": nav_arity,
}
out = ROOT / "verification-output/ui-audit.json"
out.parent.mkdir(exist_ok=True)
out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"checks": checks, "passed": report["passed"], "failures": failures}, indent=2))
raise SystemExit(0 if report["passed"] else 1)
