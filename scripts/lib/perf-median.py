"""Median of several scripts/perf-run JSON lines, metric by metric; keeps every sample's janky % too."""
import json
import statistics
import sys

runs = [json.loads(a) for a in sys.argv[1:]]
out = dict(runs[0])
for k, v in runs[0].items():
    if isinstance(v, (int, float)):
        vals = [r[k] for r in runs if isinstance(r.get(k), (int, float))]
        out[k] = round(statistics.median(vals), 2)
out["samples"] = len(runs)
out["jankyPctSamples"] = [r.get("jankyPct") for r in runs]
print(json.dumps(out))
