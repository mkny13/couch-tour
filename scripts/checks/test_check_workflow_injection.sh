#!/usr/bin/env bash
# Self-test for check-workflow-injection.sh: verifies that workflows do not interpolate
# untrusted expressions (${{ github.event.* }}, ${{ inputs.* }}, ${{ github.head_ref }})
# directly into run: blocks.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-workflow-injection.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

[ -x "$check" ] || { echo "FAIL: check script not found or not executable: $check" >&2; exit 1; }

# 1. Safe workflow: untrusted inputs passed through step env: must pass
cat << 'EOF' > "$tmp/safe.yml"
name: Safe
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Greet
        if: ${{ inputs.tag != '' }}
        env:
          TAG: ${{ inputs.tag }}
          RUN_ID: ${{ github.run_id }}
        run: |
          echo "Tag is $TAG, run is $RUN_ID"
EOF
WORKFLOWS_DIR="$tmp" "$check" || { echo "FAIL: safe workflow should pass" >&2; exit 1; }

# 2. Workflow interpolating github.event.* in multiline run: must fail
cat << 'EOF' > "$tmp/bad_event.yml"
name: Bad Event
on:
  issues:
    types: [opened]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Print title
        run: |
          echo "${{ github.event.issue.title }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow interpolating github.event.* in run: should fail" >&2
  exit 1
fi
rm "$tmp/bad_event.yml"

# 3. Workflow interpolating inputs.* in multiline run: must fail
cat << 'EOF' > "$tmp/bad_input.yml"
name: Bad Input
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Print tag
        run: |
          echo "${{ inputs.tag }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow interpolating inputs.* in multiline run: should fail" >&2
  exit 1
fi
rm "$tmp/bad_input.yml"

# 4. Workflow interpolating inputs.* in single-line run: must fail
cat << 'EOF' > "$tmp/bad_single_line.yml"
name: Bad Single Line
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: echo "${{ inputs.tag }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow interpolating inputs.* in single-line run: should fail" >&2
  exit 1
fi
rm "$tmp/bad_single_line.yml"

# 5. Workflow interpolating github.head_ref in run: must fail
cat << 'EOF' > "$tmp/bad_head_ref.yml"
name: Bad Head Ref
on:
  pull_request:
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: |
          git checkout "${{ github.head_ref }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow interpolating github.head_ref in run: should fail" >&2
  exit 1
fi
rm "$tmp/bad_head_ref.yml"

# 6. Workflow with run: | # explanatory comment interpolating inputs.* must fail
cat << 'EOF' > "$tmp/bad_commented_run.yml"
name: Bad Commented Run
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Print tag
        run: | # explanatory comment
          echo "${{ inputs.tag }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow with run: | # comment interpolating inputs in block should fail" >&2
  exit 1
fi
rm "$tmp/bad_commented_run.yml"

# 7. Workflow with run: >- # explanatory comment interpolating inputs.* must fail
cat << 'EOF' > "$tmp/bad_folded_commented_run.yml"
name: Bad Folded Commented Run
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Print tag
        run: >- # folded scalar with comment
          echo "${{ inputs.tag }}"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow with run: >- # comment interpolating inputs in block should fail" >&2
  exit 1
fi
rm "$tmp/bad_folded_commented_run.yml"

# 8. Workflow with run: | # tag: ${{ inputs.tag }} (interpolation in header comment) must fail
cat << 'EOF' > "$tmp/bad_header_comment.yml"
name: Bad Header Comment
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - name: Print tag
        run: | # tag: ${{ inputs.tag }}
          echo "tag"
EOF
if WORKFLOWS_DIR="$tmp" "$check" 2>/dev/null; then
  echo "FAIL: workflow with untrusted expression in header comment should fail" >&2
  exit 1
fi
rm "$tmp/bad_header_comment.yml"

# 9. Workflow with - run: | followed by step-level env: passing untrusted input must pass
cat << 'EOF' > "$tmp/safe_hyphen_run_with_env.yml"
name: Safe Hyphen Run
on:
  workflow_dispatch:
    inputs:
      tag:
        type: string
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - run: | # explanatory comment
          echo "tag is $TAG"
        env:
          TAG: ${{ inputs.tag }}
EOF
WORKFLOWS_DIR="$tmp" "$check" || { echo "FAIL: safe hyphen run with following env should pass" >&2; exit 1; }
rm "$tmp/safe_hyphen_run_with_env.yml"

# 10. Real workflows in repo must pass
"$check" || { echo "FAIL: real repo workflows should pass" >&2; exit 1; }

echo "ok"
