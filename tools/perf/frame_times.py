import sys, glob, statistics as st
from perfetto.trace_processor import TraceProcessor
for t in sys.argv[1:]:
    g=[]; m=[]; r=[]
    for f in sorted(glob.glob(f"*_{t}_iter00[1-9]*")):
        tp=TraceProcessor(trace=f)
        base="from slice s join thread_track tt on s.track_id=tt.id join thread th using(utid) join process p using(upid) where p.name='dev.glasslauncher'"
        g+=[x.d/1e6 for x in tp.query(f"select s.dur d {base} and th.name='GPU completion' and s.name='waitForever'")]
        m+=[x.d/1e6 for x in tp.query(f"select s.dur d {base} and s.name='Choreographer#doFrame' and th.tid=p.pid")]
        r+=[x.d/1e6 for x in tp.query(f"select s.dur d {base} and th.name='RenderThread' and s.name='DrawFrame'")]
        tp.close()
    q=lambda a,p: sorted(a)[int(len(a)*p)] if a else 0
    print(f"{t:14} GPU p50 {q(g,.5):5.1f} p90 {q(g,.9):5.1f} | main p50 {q(m,.5):5.1f} p90 {q(m,.9):5.1f} | RT p50 {q(r,.5):5.1f} p90 {q(r,.9):5.1f}  (n={len(g)})")
