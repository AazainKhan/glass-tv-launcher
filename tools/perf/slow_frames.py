import sys, glob, collections
from perfetto.trace_processor import TraceProcessor
pat=sys.argv[1]; agg=collections.Counter(); cnt=collections.Counter(); nslow=0; nall=0
for f in sorted(glob.glob(pat)):
    tp=TraceProcessor(trace=f)
    base="from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid) where p.name='dev.glasslauncher'"
    frames=list(tp.query(f"select s.id, s.ts, s.dur, t.name tn {base} and ((t.name='RenderThread' and s.name='DrawFrame') or (s.name='Choreographer#doFrame' and t.tid=p.pid))"))
    nall+=len(frames)
    for fr in frames:
        if fr.dur < 16.7e6: continue
        nslow+=1
        # descendant slices of depth 1-3 under this frame, self time approximated by dur
        for r in tp.query(f"select s2.name, s2.dur from descendant_slice({fr.id}) s2 where s2.depth <= (select depth from slice where id={fr.id})+3"):
            n=r.name.split('(')[0][:70]; agg[(fr.tn,n)]+=r.dur/1e6; cnt[(fr.tn,n)]+=1
    tp.close()
print(f"frames(main+RT) {nall}, over 16.7ms {nslow}")
for (tn,n),ms in agg.most_common(22): print(f"{ms:8.0f} ms  x{cnt[(tn,n)]:<4} {tn:12} {n}")
