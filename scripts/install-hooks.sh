#!/usr/bin/env sh
# Enables the versioned git hooks in .githooks/ (pre-commit secret guard).
# Run once after cloning:  sh scripts/install-hooks.sh
set -e
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true
echo "Pre-commit secret guard enabled (core.hooksPath=.githooks)."
