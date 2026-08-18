#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
make -s thinair-sim
json="$(./thinair-sim json)"
echo "$json"
python3 - <<'PY' <<<"$json"
import json,sys
# read from argv string via stdin of the here - actually passed as stdin? 
PY
# simple numeric checks
python3 - "$json" <<'PY'
import json,sys
s=sys.argv[1]
# if the shell passed the json as one arg
import re
# fallback: run the binary again
import subprocess
raw=subprocess.check_output(["./thinair-sim","json"], text=True)
j=json.loads(raw)
assert 1010 < j["pressure_sea"] < 1016, j
assert 300 < j["pressure_summit"] < 360, j
assert 70 < j["spo2_bc"] < 90, j
assert 45 < j["spo2_summit"] < 65, j
print("physiology constants OK", j)
PY
