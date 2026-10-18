import glob, collections
from perfetto.trace_processor import TraceProcessor
c=collections.Counter(); ms=collections.Counter(); fr=collections.Counter()
for f in sorted(glob.glob("*_controlCenter_iter00[1-9]*")):
    tp=TraceProcessor(trace=f)
    ins=[r.ts for r in tp.query("select ts from slice where name like 'deliverInputEvent src%' order by ts")][::2]
    def phase(ts): return "open" if ts<ins[0] else ("moves" if ts<ins[3] else "close")
    for r in tp.query("select ts, dur, name from slice where name in ('shader_compile','texture_upload')"):
        c[(phase(r.ts),r.name)]+=1; ms[(phase(r.ts),r.name)]+=r.dur/1e6
    # frames with a compile: how many distinct DrawFrames contain shader_compile
    for r in tp.query("select d.ts from slice d where d.name='DrawFrame' and exists (select 1 from descendant_slice(d.id) x where x.name='shader_compile')"):
        fr[phase(r.ts)]+=1
    tp.close()
for k in sorted(c): print(f"{k[0]:6} {k[1]:15} n/iter {c[k]/9:5.1f}  ms/iter {ms[k]/9:6.1f}")
print("DrawFrames containing a shader compile, per iter:", {k: round(v/9,1) for k,v in fr.items()})
