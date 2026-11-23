#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mode=${1:-incident}
seconds=${2:-12}
service_ms=${3:-40}
output="runs/${mode}-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$output" .build
javac --release 21 -d .build IncidentLab.java
if [[ "${JFR:-0}" == 1 ]]; then
  java "-XX:StartFlightRecording=filename=$output/recording.jfr,settings=profile,dumponexit=true" -cp .build IncidentLab "$mode" "$output" "$seconds" "$service_ms"
else
  java -cp .build IncidentLab "$mode" "$output" "$seconds" "$service_ms"
fi
