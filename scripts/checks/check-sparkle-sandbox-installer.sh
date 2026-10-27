#!/usr/bin/env bash
# Guards #774: Sandboxed macOS apps using Sparkle require installer-launcher configuration.
# App Sandbox requires SUEnableInstallerLauncherService=true in Info.plist properties
# and com.apple.security.temporary-exception.mach-lookup.global-name containing both
# $(PRODUCT_BUNDLE_IDENTIFIER)-spks and $(PRODUCT_BUNDLE_IDENTIFIER)-spki in entitlements.
#
# Fails if any target with com.apple.security.app-sandbox enabled is missing any of the three.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
spec="${PROJECT_YML:-${1:-$repo/macos/project.yml}}"

if [ ! -f "$spec" ]; then
  echo "FAIL: spec file '$spec' does not exist" >&2
  exit 1
fi

python3 - "$spec" <<'PYEOF'
import sys, os

def check_project(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    targets = {}
    in_targets = False
    targets_indent = None
    target_indent = None
    current_target = None
    target_lines = []

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        indent = len(line) - len(line.lstrip(' '))
        if not in_targets:
            if stripped == 'targets:':
                in_targets = True
                targets_indent = indent
            continue

        if indent <= targets_indent:
            break

        if target_indent is None:
            target_indent = indent

        if indent == target_indent and stripped.endswith(':'):
            if current_target:
                targets[current_target] = target_lines
            current_target = stripped[:-1].strip()
            target_lines = []
        elif current_target and indent > target_indent:
            target_lines.append(line)

    if current_target:
        targets[current_target] = target_lines

    def parse_section_properties(t_lines, section_name):
        in_section = False
        section_indent = None
        in_props = False
        props_indent = None
        props = {}
        current_key = None
        current_list = []

        for raw in t_lines:
            in_quotes = False
            quote_char = ''
            cut_idx = len(raw)
            for i, ch in enumerate(raw):
                if ch in ('"', "'"):
                    if not in_quotes:
                        in_quotes = True
                        quote_char = ch
                    elif ch == quote_char:
                        in_quotes = False
                elif ch == '#' and not in_quotes:
                    cut_idx = i
                    break
            clean = raw[:cut_idx].rstrip()
            if not clean.strip():
                continue
            indent = len(clean) - len(clean.lstrip(' '))
            stripped = clean.strip()

            if not in_section:
                if stripped == f"{section_name}:":
                    in_section = True
                    section_indent = indent
                continue

            if indent <= section_indent:
                in_section = False
                if current_key:
                    props[current_key] = current_list if current_list else ""
                    current_key = None
                    current_list = []
                continue

            if not in_props:
                if stripped == "properties:":
                    in_props = True
                    props_indent = indent
                continue

            if indent <= props_indent:
                in_props = False
                if current_key:
                    props[current_key] = current_list if current_list else ""
                    current_key = None
                    current_list = []
                continue

            if stripped.startswith('- '):
                val = stripped[2:].strip().strip("\"'")
                current_list.append(val)
            elif ':' in stripped:
                if current_key:
                    props[current_key] = current_list if current_list else ""
                    current_key = None
                    current_list = []
                k, v = stripped.split(':', 1)
                k = k.strip()
                v = v.strip()
                if v:
                    if v.startswith('[') and v.endswith(']'):
                        inner = v[1:-1].strip()
                        props[k] = [x.strip().strip("\"'") for x in inner.split(',') if x.strip()]
                    elif v.lower() in ('true', 'yes'):
                        props[k] = True
                    elif v.lower() in ('false', 'no'):
                        props[k] = False
                    else:
                        props[k] = v.strip("\"'")
                else:
                    current_key = k
                    current_list = []

        if current_key:
            props[current_key] = current_list if current_list else ""

        return props

    def is_truthy(val):
        if isinstance(val, bool):
            return val
        if isinstance(val, str):
            return val.lower() in ("true", "yes")
        return False

    errors = []
    sandboxed_targets = 0

    for target_name, t_lines in targets.items():
        info_props = parse_section_properties(t_lines, "info")
        ent_props = parse_section_properties(t_lines, "entitlements")

        app_sandbox = ent_props.get("com.apple.security.app-sandbox")
        if not is_truthy(app_sandbox):
            continue

        sandboxed_targets += 1

        launcher = info_props.get("SUEnableInstallerLauncherService")
        if not is_truthy(launcher):
            errors.append(
                f"target '{target_name}' has app-sandbox enabled but SUEnableInstallerLauncherService is missing or not true in info.properties"
            )

        mach_lookup = ent_props.get("com.apple.security.temporary-exception.mach-lookup.global-name")
        if not isinstance(mach_lookup, list):
            if isinstance(mach_lookup, str) and mach_lookup:
                mach_lookup = [mach_lookup]
            else:
                mach_lookup = []

        required_services = [
            "$(PRODUCT_BUNDLE_IDENTIFIER)-spks",
            "$(PRODUCT_BUNDLE_IDENTIFIER)-spki",
        ]
        missing_services = [s for s in required_services if s not in mach_lookup]
        if missing_services:
            errors.append(
                f"target '{target_name}' has app-sandbox enabled but com.apple.security.temporary-exception.mach-lookup.global-name is missing required values: {missing_services}"
            )

    if errors:
        for err in errors:
            print(f"FAIL: {err}", file=sys.stderr)
        return 1

    print(f"PASS: {sandboxed_targets} sandboxed target(s) verified in {path}")
    return 0

sys.exit(check_project(sys.argv[1]))
PYEOF
