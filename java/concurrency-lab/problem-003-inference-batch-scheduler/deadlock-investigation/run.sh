#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mode=${1:-incident}
output="runs/${mode}-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$output" .build
javac --release 21 -d .build DeadlockLab.java
java -cp .build DeadlockLab "$mode" "$output"
