#!/usr/bin/env bash
set -euo pipefail

: "${LG_BASE_URL:?Set LG_BASE_URL to the server URL}"
: "${LG_API_KEY:?Set LG_API_KEY to an API key for a test client}"
runs=${LG_RUNS:-10}
[[ $runs =~ ^[1-9][0-9]*$ && $runs -le 20 ]] || { echo "LG_RUNS must be between 1 and 20" >&2; exit 2; }

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
python3 - "$scratch/request.json" <<'PYTHON'
import json
import sys

adjectives = ["matte", "small", "striped", "blue", "satin", "soft", "plain", "wide", "bright", "woven"]
products = ["tea towel", "fabric pouch", "cotton tee", "linen bag", "coaster", "poster", "mug", "candle", "ornament", "apron"]
finishes = ["everyday", "minimal", "festive", "lined", "boxed", "round", "neutral", "spare", "decorative", "simple"]
phrases = [
    {"text": f"{a} {p} {f}", "searchVolume": i + 1}
    for i, (a, p, f) in enumerate((a, p, f) for a in adjectives for p in products for f in finishes)
]
with open(sys.argv[1], "w") as output:
    json.dump({"marketplace": "ETSY", "phrases": phrases}, output)
PYTHON

for ((run=1; run<=runs; run++)); do
    # Read the API key header from stdin so it is absent from process arguments and curl output.
    printf 'X-API-Key: %s\n' "$LG_API_KEY" |
        curl --silent --show-error --fail --max-time 45 --output /dev/null --write-out '%{time_total}\n' \
            --header @- --header 'Content-Type: application/json' --data-binary "@$scratch/request.json" \
            "${LG_BASE_URL%/}/api/v1/phrase-screenings" >> "$scratch/times"
done

python3 - "$scratch/times" <<'PYTHON'
import math
import sys

with open(sys.argv[1]) as source:
    samples = sorted(float(line) * 1000 for line in source)

def percentile(p):
    return samples[math.ceil(len(samples) * p) - 1]

print(f"runs={len(samples)} phrases=1000 p50_ms={percentile(0.50):.1f} p95_ms={percentile(0.95):.1f}")
PYTHON
