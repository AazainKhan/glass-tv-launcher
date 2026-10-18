Trace analysis for `scripts/bench` output (Perfetto traces in build/bench or build/baseline). Run from that folder with
`tools/.venv/bin/python -I <script> <args>` (venv: `pip install perfetto`).
- `frame_times.py <test>...`: GPU, main-thread and RenderThread time per frame (p50/p90), iterations 1–9.
- `slow_frames.py '<glob>'`: what fills frames over 16.7 ms (slices up to 3 levels under DrawFrame/doFrame).
- `startup.py <test>`: main-thread slices before the first frame (startupCold, startupWarm).
- `cc_shaders.py`: shader compiles and texture uploads per Control Center phase (open, moves, close).
Iteration 0 follows an install, so HWUI's shader cache is empty there; the scripts skip it.
