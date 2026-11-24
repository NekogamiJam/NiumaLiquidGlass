from pathlib import Path
import os,sys,json,time,statistics,ctypes as C
os.environ['OPENBLAS_NUM_THREADS']='1';os.environ['OMP_NUM_THREADS']='1';os.environ['MKL_NUM_THREADS']='1'
R=Path(__file__).resolve().parent;sys.path.insert(0,str(R.parent/'voice-research/runtime'));sys.path.insert(0,str(R/'upstream'))
import inference as I
class Counters(C.Structure):_fields_=[('cb',C.c_ulong),('PageFaultCount',C.c_ulong),('PeakWorkingSetSize',C.c_size_t),('WorkingSetSize',C.c_size_t),('QuotaPeakPagedPoolUsage',C.c_size_t),('QuotaPagedPoolUsage',C.c_size_t),('QuotaPeakNonPagedPoolUsage',C.c_size_t),('QuotaNonPagedPoolUsage',C.c_size_t),('PagefileUsage',C.c_size_t),('PeakPagefileUsage',C.c_size_t),('PrivateUsage',C.c_size_t)]
C.windll.kernel32.GetCurrentProcess.restype=C.c_void_p
def mem():
 c=Counters();c.cb=C.sizeof(c);assert C.windll.psapi.GetProcessMemoryInfo(C.c_void_p(C.windll.kernel32.GetCurrentProcess()),C.byref(c),c.cb);return {k:getattr(c,k) for k in ['WorkingSetSize','PeakWorkingSetSize','PrivateUsage']}
before=mem();t=time.perf_counter();m=I.LocalTypeModel();load=time.perf_counter()-t;rows=[]
for c in json.loads((R/'cases.json').read_text('utf-8'))['cases']:
 times=[];outputs=[]
 for _ in range(3):
  t=time.perf_counter();v=m.predict(c['text'],6);times.append(time.perf_counter()-t);outputs.append(v)
 assert all(x==outputs[0] for x in outputs)
 ids,prefix=I.context_ids(m.tokenizer,c['text']);v=outputs[0]
 assert len(v)==len(set(v)) and all(I.offerable(x) and x.startswith(prefix) for x in v)
 rows.append({**c,'prefix':prefix,'context_ids':ids,'suggestions':v,'first_ms':times[0]*1000,'warm_median_ms':statistics.median(times[1:])*1000,'target_top1':bool(c['targets'] and v and v[0] in c['targets']),'target_top3':bool(set(v[:3])&set(c['targets']))})
score={k:{'cases':len(a:=[x for x in rows if x['kind']==k]),'top1':sum(x['target_top1'] for x in a),'top3':sum(x['target_top3'] for x in a)} for k in ['next','completion']}
passed=score['next']['top3']/score['next']['cases']>=.6 and score['completion']['top3']/score['completion']['cases']>=.8
out={'model':'Chime-SMS-2M pinned INT8, NumPy upstream float64 reference','load_seconds':load,'before_memory':before,'after_memory':mem(),'quality_screen':score,'criteria_passed':passed,'warm_median_ms':statistics.median(x['warm_median_ms'] for x in rows),'warm_max_ms':max(x['warm_median_ms'] for x in rows),'cases':rows,'phone_microphone_or_editor_used':False}
(R/'desktop-results.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n','utf-8');(R/'desktop-review.txt').write_text('\n'.join(f"{x['id']} {x['text']!r} => {x['suggestions']} targetTop3={x['target_top3']}" for x in rows)+'\n','utf-8');print(json.dumps({k:v for k,v in out.items() if k!='cases'},ensure_ascii=True))
