import sys, glob, collections
from perfetto.trace_processor import TraceProcessor
t=sys.argv[1]; agg=collections.Counter(); n=0
for f in sorted(glob.glob(f"*_{t}_iter00[1-9]*")):
    tp=TraceProcessor(trace=f); n+=1
    q=f"""select s.name, s.dur, s.depth from slice s join thread_track tt on s.track_id=tt.id join thread th using(utid) join process p using(upid)
          where p.name='dev.glasslauncher' and th.tid=p.pid and s.depth<=2
          and s.ts < (select min(s2.ts+s2.dur) from slice s2 join thread_track t2 on s2.track_id=t2.id join thread h2 using(utid) join process p2 using(upid) where p2.name='dev.glasslauncher' and h2.name='RenderThread' and s2.name='DrawFrame')"""
    for r in tp.query(q): agg[(r.depth, r.name.split('(')[0][:80])]+=r.dur/1e6
    tp.close()
for (d,k),v in sorted(agg.items(), key=lambda x:-x[1])[:22]: print(f"{v/n:7.1f} ms  d{d}  {k}")
