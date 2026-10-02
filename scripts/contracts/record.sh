#!/usr/bin/env bash
# Thin wrapper; see record.py.
exec python3 "$(dirname "$0")/record.py" "$@"
