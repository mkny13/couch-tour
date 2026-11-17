#!/usr/bin/env bash
# Self-test for check-token-scope.sh: passes on the real sync-deploy.yml,
# fails when CLOUDFLARE_API_TOKEN is scoped at workflow or job level,
# and fails when referenced outside step-level env:.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
check="$here/check-token-scope.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ ! -x "$check" ]; then
  echo "FAIL: $check does not exist or is not executable" >&2
  exit 1
fi

# 1. Real sync-deploy.yml must pass
"$check" || { echo "FAIL: real sync-deploy.yml should pass" >&2; exit 1; }

# 2. Workflow with valid step-level env must pass
cat > "$tmp/valid.yml" << 'EOF'
name: Valid
on: push
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - name: Deploy
        env:
          CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
        run: wrangler deploy
EOF
SYNC_DEPLOY_WORKFLOW="$tmp/valid.yml" "$check" || { echo "FAIL: valid step-scoped token should pass" >&2; exit 1; }

# 3. Negative test: workflow-level env must fail
cat > "$tmp/workflow_env.yml" << 'EOF'
name: Bad Workflow Scope
on: push
env:
  CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - run: echo deploy
EOF
if SYNC_DEPLOY_WORKFLOW="$tmp/workflow_env.yml" "$check" 2>/dev/null; then
  echo "FAIL: workflow-level CLOUDFLARE_API_TOKEN should be rejected" >&2
  exit 1
fi

# 4. Negative test: job-level env must fail
cat > "$tmp/job_env.yml" << 'EOF'
name: Bad Job Scope
on: push
jobs:
  deploy:
    runs-on: ubuntu-latest
    env:
      CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}
    steps:
      - run: echo deploy
EOF
if SYNC_DEPLOY_WORKFLOW="$tmp/job_env.yml" "$check" 2>/dev/null; then
  echo "FAIL: job-level CLOUDFLARE_API_TOKEN should be rejected" >&2
  exit 1
fi

# 5. Negative test: step without env (token in run block) must fail
cat > "$tmp/step_run.yml" << 'EOF'
name: Bad Step Scope
on: push
jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: deploy
        run: echo $CLOUDFLARE_API_TOKEN
EOF
if SYNC_DEPLOY_WORKFLOW="$tmp/step_run.yml" "$check" 2>/dev/null; then
  echo "FAIL: CLOUDFLARE_API_TOKEN outside step env should be rejected" >&2
  exit 1
fi

echo "ok"
