# Input to first motion: from Glass receiving a key down (deliverInputEvent) to the RenderThread queueing the
# first frame drawn after it (its buffer then reaches the screen at the next vsync). Iterations 1-9.
import sys, glob
from perfetto.trace_processor import TraceProcessor
for t in sys.argv[1:]:
    lat=[]
    for f in sorted(glob.glob(f"*_{t}_iter00[1-9]*")):
        tp=TraceProcessor(trace=f)
        B="from slice s join thread_track tt on s.track_id=tt.id join thread th using(utid) join process p using(upid) where p.name='dev.glasslauncher'"
        downs=[r.ts for r in tp.query(f"select s.ts {B} and s.name like 'deliverInputEvent src%' order by s.ts")][::2]
        q=[(r.ts, r.ts+r.dur) for r in tp.query(f"select s.ts, s.dur {B} and th.name='RenderThread' and s.name='queueBuffer' order by s.ts")]
        doframes=[r.ts for r in tp.query(f"select s.ts {B} and s.name='Choreographer#doFrame' and th.tid=p.pid order by s.ts")]
        for d in downs:
            fr=next((x for x in doframes if x>d), None)
            if fr is None: continue
            qb=next((e for s,e in q if s>fr), None)
            if qb: lat.append((qb-d)/1e6)
        tp.close()
    lat.sort(); p=lambda x: lat[min(len(lat)-1,int(len(lat)*x))]
    print(f"{t:14} input->first frame queued: p50 {p(.5):5.1f} p90 {p(.9):5.1f} max {lat[-1]:5.1f} ms (n={len(lat)})")
