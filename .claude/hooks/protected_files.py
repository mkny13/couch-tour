#!/usr/bin/env python3
"""
PreToolUse hook to protect machine-local and release-managed files.

Blocks Edit/Write to:
- local.properties (machine-local Android SDK path)
- appcast.xml (release-managed Sparkle feed)
- appcast-beta.xml (release-managed Sparkle beta feed)

Blocks Bash commands that write to those files (redirection >, >>, or sed -i, tee, cp, mv with them as destination).
Read-only mentions pass through.
"""
import json
import os
import shlex
import sys

PROTECTED_FILES = {
    "local.properties": "machine-local; gitignored Android SDK configuration",
    "appcast.xml": "release-managed; regenerated on release by CI and published by scripts/publish-appcast.sh",
    "appcast-beta.xml": "release-managed; regenerated on release by CI and published by scripts/publish-appcast.sh",
}

def get_project_root():
    # .claude/hooks/protected_files.py -> .claude -> project root
    hooks_dir = os.path.dirname(os.path.abspath(__file__))
    claude_dir = os.path.dirname(hooks_dir)
    return os.path.dirname(claude_dir)

def resolves_to_protected(path_str, project_root):
    if not path_str or not isinstance(path_str, str):
        return None
    # Strip whitespace and quotes
    cleaned = path_str.strip().strip("\"'")
    if not cleaned:
        return None

    # Expand ~ and environment variables (such as $PWD)
    expanded = os.path.expanduser(os.path.expandvars(cleaned))
    abs_path = os.path.abspath(expanded)
    real_path = os.path.realpath(abs_path)

    for pf in PROTECTED_FILES:
        target_abs = os.path.abspath(os.path.join(project_root, pf))
        target_real = os.path.realpath(target_abs)
        if abs_path == target_abs or real_path == target_real:
            return pf
    return None

def check_bash_command(cmd_str, project_root):
    if not cmd_str or not isinstance(cmd_str, str):
        return None

    try:
        s = shlex.shlex(cmd_str, posix=True, punctuation_chars=True)
        s.whitespace_split = True
        tokens = list(s)
    except Exception:
        # If shell syntax cannot be tokenized, safe fallback
        return None

    # Split tokens into separate pipeline / command segments
    subcommands = []
    current = []
    for t in tokens:
        if t in (";", "&&", "||", "|", "&"):
            if current:
                subcommands.append(current)
                current = []
        else:
            current.append(t)
    if current:
        subcommands.append(current)

    for sub in subcommands:
        if not sub:
            continue

        # 1. Check redirections: >, >>, >|, &>, &>>
        for i, t in enumerate(sub):
            if t in (">", ">>", ">|", "&>", "&>>"):
                if i + 1 < len(sub):
                    pf = resolves_to_protected(sub[i + 1], project_root)
                    if pf:
                        return pf

        # Skip leading variable assignments or wrappers like sudo
        idx = 0
        while idx < len(sub) and ("=" in sub[idx] or sub[idx] in ("sudo", "nohup", "command", "builtin", "exec")):
            idx += 1
        if idx >= len(sub):
            continue

        cmd_name = os.path.basename(sub[idx])
        args = sub[idx + 1:]

        # 2. sed -i or sed --in-place
        if cmd_name == "sed":
            has_in_place = any(
                a in ("-i", "--in-place") or a.startswith("-i") or a.startswith("--in-place=")
                for a in args
            )
            if has_in_place:
                for a in args:
                    pf = resolves_to_protected(a, project_root)
                    if pf:
                        return pf

        # 3. tee
        elif cmd_name == "tee":
            for a in args:
                if not a.startswith("-"):
                    pf = resolves_to_protected(a, project_root)
                    if pf:
                        return pf

        # 4. cp and mv
        elif cmd_name in ("cp", "mv"):
            pos_args = [a for a in args if not a.startswith("-") and a not in (">", ">>", "<", "&>", "|", ";")]
            if len(pos_args) >= 2:
                dest = pos_args[-1]
                sources = pos_args[:-1]
                pf = resolves_to_protected(dest, project_root)
                if pf:
                    return pf
                # Also check if destination is directory and any source is protected
                dest_expanded = os.path.expanduser(os.path.expandvars(dest.strip("\"'")))
                dest_abs = os.path.abspath(dest_expanded)
                for src in sources:
                    src_base = os.path.basename(src.strip("\"'"))
                    pf = resolves_to_protected(os.path.join(dest_abs, src_base), project_root)
                    if pf:
                        return pf

    return None

def block(filename):
    reason = PROTECTED_FILES.get(filename, "protected file")
    msg = f"Blocked: {filename} is protected ({reason})."
    sys.stderr.write(f"{msg}\n")
    sys.exit(2)

def main():
    try:
        if sys.stdin.isatty():
            sys.exit(0)

        raw = sys.stdin.read()
        if not raw.strip():
            sys.exit(0)

        data = json.loads(raw)
        if not isinstance(data, dict):
            sys.exit(0)

        project_root = get_project_root()

        tool_name = data.get("tool_name", "")
        tool_input = data.get("tool_input", {})
        if not isinstance(tool_input, dict):
            tool_input = data

        # Check Edit / Write / MultiEdit / NotebookEdit
        if tool_name in ("Write", "Edit", "MultiEdit", "NotebookEdit") or "file_path" in tool_input or "path" in tool_input:
            path_val = tool_input.get("file_path") or tool_input.get("path") or tool_input.get("target_file")
            pf = resolves_to_protected(path_val, project_root)
            if pf:
                block(pf)

        # Check Bash
        if tool_name == "Bash" or "command" in tool_input:
            command = tool_input.get("command", "")
            pf = check_bash_command(command, project_root)
            if pf:
                block(pf)

        sys.exit(0)
    except Exception:
        # Exit-safe: unexpected errors must never wedge a session
        sys.exit(0)

if __name__ == "__main__":
    main()
