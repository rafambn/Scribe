#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
docker compose -f compose.yaml down
rm -rf data/*
touch data/.gitkeep
