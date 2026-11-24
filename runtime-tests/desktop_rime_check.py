"""Actual desktop librime check in a fresh, isolated data directory; never invokes ADB.
ABI reference: https://raw.githubusercontent.com/rime/librime/1.17.0/src/rime_api.h
This is a desktop-engine test, NOT proof about the Android JNI build or UI.
"""
from __future__ import annotations
import collections, ctypes as C, hashlib, json, os, shutil, sys, time
from pathlib import Path
from isolated_work import isolated_work

PROJECT=Path(__file__).resolve().parents[1]
INPUT=PROJECT/'input-data'
STAMP=time.strftime('%Y%m%d-%H%M%S')
WORK = isolated_work(__file__, 'niuma-desktop-rime-')
USER=WORK/'user';SHARED=WORK/'shared';LOG=WORK/'logs'
for d in (USER,SHARED,LOG): d.mkdir(parents=True,exist_ok=False)
DLL_DIR = Path(os.environ.get('NIUMA_RIME_DLL_DIR') or '')
if not DLL_DIR.is_dir():
    raise SystemExit('set NIUMA_RIME_DLL_DIR to a Rime install directory that contains librime.dll')
ANDROID_ASSETS = '--android-assets' in sys.argv
SCHEMA_ID = b'niuma_glass' if ANDROID_ASSETS else b'niuma_android_static'
STAGED_ASSETS = PROJECT/'app/src/main/assets/shared'

class Traits(C.Structure):
 _fields_=[('data_size',C.c_int),('shared_data_dir',C.c_char_p),('user_data_dir',C.c_char_p),('distribution_name',C.c_char_p),('distribution_code_name',C.c_char_p),('distribution_version',C.c_char_p),('app_name',C.c_char_p),('modules',C.POINTER(C.c_char_p)),('min_log_level',C.c_int),('log_dir',C.c_char_p),('prebuilt_data_dir',C.c_char_p),('staging_dir',C.c_char_p)]
class Candidate(C.Structure):
 _fields_=[('text',C.c_char_p),('comment',C.c_char_p),('reserved',C.c_void_p)]
class CandidateIterator(C.Structure):
 _fields_=[('ptr',C.c_void_p),('index',C.c_int),('candidate',Candidate)]
class Commit(C.Structure):
 _fields_=[('data_size',C.c_int),('text',C.c_char_p)]

def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def parse_rows(p):
 lines=p.read_text(encoding='utf-8-sig').splitlines(); end=next(i for i,s in enumerate(lines) if s.strip()=='...')
 columns=[];in_columns=False
 for s in lines[:end]:
  if s.lstrip().startswith('#'):continue
  if s.strip()=='columns:':in_columns=True;continue
  if in_columns:
   if s.strip().startswith('- '):columns.append(s.strip()[2:].split('#',1)[0].strip())
   elif s and not s[0].isspace():in_columns=False
 for line in lines[end+1:]:
  if not line or line.startswith('#'):continue
  fields=line.split('\t')
  if len(fields)<=max(columns.index('code'),columns.index('text')):raise ValueError('Malformed dictionary row')
  yield fields[columns.index('code')],fields[columns.index('text')]

source_hashes={p.name:h(p) for p in INPUT.glob('*.yaml')}
expected=collections.defaultdict(list);raw_counts=collections.Counter();duplicate_pairs=0
for name in ('niumawb_zc.dict.yaml','niumawb_fh.dict.yaml'):
 for code,text in parse_rows(INPUT/name):
  raw_counts[code]+=1
  if text in expected[code]: duplicate_pairs+=1
  else:expected[code].append(text)
for name in source_hashes:shutil.copyfile(INPUT/name,USER/name)
(USER/'default.yaml').write_text("config_version: '1'\nschema_list:\n  - schema: niuma_android_static\n",encoding='utf-8')
(USER/'niuma_android_static.schema.yaml').write_text('''schema:
  schema_id: niuma_android_static
  name: Isolated Niuma static-order test
  version: '0.1'
switches:
  - name: ascii_mode
    reset: 0
engine:
  processors: [ascii_composer, speller, selector, navigator, express_editor]
  segmentors: [ascii_segmentor, abc_segmentor, fallback_segmentor]
  translators: [table_translator]
ascii_composer:
  switch_key:
    Shift_L: noop
    Shift_R: noop
    Control_L: noop
    Control_R: noop
speller:
  alphabet: abcdefghijklmnopqrstuvwxyz
  initials: abcdefghijklmnopqrstuvwxyz
  max_code_length: 5
  auto_select: false
translator:
  dictionary: niumawb
  enable_user_dict: false
  enable_sentence: false
  enable_encoder: false
  encode_commit_history: false
  enable_completion: false
menu:
  page_size: 9
''',encoding='utf-8')

if ANDROID_ASSETS:
 for name in source_hashes:
  if h(STAGED_ASSETS/name) != source_hashes[name]:
   raise ValueError(f'Staged Android dictionary differs from the original snapshot: {name}')
 shutil.copytree(STAGED_ASSETS, SHARED, dirs_exist_ok=True)
 # Fresh test-only default config uses the actual shared presets and only the staged Android schema.
 (USER/'default.yaml').write_bytes((SHARED/'default.yaml').read_bytes())
 (USER/'default.custom.yaml').write_text('patch:\n  schema_list:\n    - schema: niuma_glass\n', encoding='utf-8')

with os.add_dll_directory(str(DLL_DIR)):
 r=C.CDLL(str(DLL_DIR/'rime.dll'))
 def fn(name,result,*args):
  f=getattr(r,name);f.restype=result;f.argtypes=list(args);return f
 setup=fn('RimeSetup',None,C.POINTER(Traits));initialize=fn('RimeInitialize',None,C.POINTER(Traits));finalize=fn('RimeFinalize',None)
 maintenance=fn('RimeStartMaintenance',C.c_int,C.c_int);join=fn('RimeJoinMaintenanceThread',None)
 create=fn('RimeCreateSession',C.c_size_t);destroy=fn('RimeDestroySession',C.c_int,C.c_size_t)
 select_schema=fn('RimeSelectSchema',C.c_int,C.c_size_t,C.c_char_p)
 clear=fn('RimeClearComposition',None,C.c_size_t);key=fn('RimeProcessKey',C.c_int,C.c_size_t,C.c_int,C.c_int)
 begin=fn('RimeCandidateListBegin',C.c_int,C.c_size_t,C.POINTER(CandidateIterator));nxt=fn('RimeCandidateListNext',C.c_int,C.POINTER(CandidateIterator));end=fn('RimeCandidateListEnd',None,C.POINTER(CandidateIterator))
 select=fn('RimeSelectCandidate',C.c_int,C.c_size_t,C.c_size_t)
 get_commit=fn('RimeGetCommit',C.c_int,C.c_size_t,C.POINTER(Commit));free_commit=fn('RimeFreeCommit',C.c_int,C.POINTER(Commit))
 deploy_config=fn('RimeDeployConfigFile',C.c_int,C.c_char_p,C.c_char_p)
 t=Traits();t.data_size=C.sizeof(Traits)-C.sizeof(C.c_int)
 t.shared_data_dir=str(SHARED).encode();t.user_data_dir=str(USER).encode();t.distribution_name=b'Isolated Niuma test';t.distribution_code_name=b'niuma-test';t.distribution_version=b'0.1';t.app_name=b'rime.niuma.offline-test';t.log_dir=str(LOG).encode();t.min_log_level=1
 sid=0;setup(C.byref(t));initialize(C.byref(t))
 try:
  maintenance(1);join()
  theme_deployed=None
  if ANDROID_ASSETS:
   theme_deployed=bool(deploy_config(b'niuma_glass.trime.yaml',b'config_version'))
   if not theme_deployed:raise RuntimeError('Actual Android theme YAML failed native deployment')
  sid=create()
  if not sid or not select_schema(sid,SCHEMA_ID):raise RuntimeError('Isolated schema deployment/session failed')
  def candidates(code):
   clear(sid)
   for c in code:key(sid,ord(c),0)
   it=CandidateIterator();items=[]
   if begin(sid,C.byref(it)):
    try:
     while nxt(C.byref(it)):items.append(it.candidate.text.decode('utf-8'))
    finally:end(C.byref(it))
   return items
  # Deterministic coverage: all single-letter codes, largest collisions, five-letter symbols,
  # and a spread through the complete sorted code set. No source data is changed/deduplicated.
  codes=sorted(expected)
  chosen=set(c for c in codes if len(c) in (1,5))
  chosen.update(sorted(codes,key=lambda c:(-len(expected[c]),c))[:80])
  step=max(1,len(codes)//120);chosen.update(codes[::step])
  if '--all' in sys.argv:chosen=set(codes)
  query_start=time.perf_counter()
  mismatches=[]
  for code in sorted(chosen):
   actual=candidates(code)
   if actual!=expected[code]:mismatches.append(dict(code=code,expected=expected[code][:25],actual=actual[:25],expected_count=len(expected[code]),actual_count=len(actual)))
  # Verify repeated non-first selections do not alter code-candidate order with user dict disabled.
  stability=[]
  for code in sorted(chosen)[:12]:
   before=candidates(code)
   if not before:continue
   for _ in range(3):
    candidates(code);select(sid,min(1,len(before)-1));commit=Commit();commit.data_size=C.sizeof(Commit)-C.sizeof(C.c_int)
    if get_commit(sid,C.byref(commit)):free_commit(C.byref(commit))
   if candidates(code)!=before:stability.append(code)
  report=dict(query_seconds=round(time.perf_counter()-query_start,3),engine='Installed desktop librime 1.17.0; Android JNI still needs its own test',isolated_work_dir=str(WORK),source_hashes=source_hashes,distinct_codes=len(expected),tested_codes=len(chosen),source_duplicate_code_text_pairs=duplicate_pairs,comparison='Stable first occurrence of identical code+text; raw duplicate count disclosed, source files unchanged',mismatch_count=len(mismatches),mismatches=mismatches,post_selection_order_changes=stability,input_hashes_unchanged=source_hashes=={p.name:h(p) for p in INPUT.glob('*.yaml')})
  report.update(schema_id=SCHEMA_ID.decode(), actual_staged_android_assets=ANDROID_ASSETS, theme_native_deployment_succeeded=theme_deployed, association_verified=False)
  if ANDROID_ASSETS:
   report['staged_config_sha256']={name:h(STAGED_ASSETS/name) for name in ('niuma_glass.schema.yaml','niuma_glass.trime.yaml')}
  # Space acceptance is separate from candidate-index selection and Android UI delivery.
  space_commit_checks=[]
  for code in ['a','ab',next(c for c in codes if len(c)==5)]:
   candidates(code)
   handled=bool(key(sid,0x20,0))
   commit=Commit();commit.data_size=C.sizeof(Commit)-C.sizeof(C.c_int)
   actual_commit=None
   if get_commit(sid,C.byref(commit)):
    try:actual_commit=commit.text.decode('utf-8') if commit.text else ''
    finally:free_commit(C.byref(commit))
   space_commit_checks.append(dict(code=code,handled=handled,expected=expected[code][0],actual=actual_commit,passed=handled and actual_commit==expected[code][0]))
  report['space_commit_checks']=space_commit_checks
  profile='android-assets-' if ANDROID_ASSETS else ''
  out=PROJECT/'runtime-tests'/f'desktop-rime-{profile}{STAMP}.json';out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
  print(json.dumps({k:v for k,v in report.items() if k not in ('mismatches','source_hashes')},ensure_ascii=False,indent=2));print('report:',out)
  if mismatches or stability or not report['input_hashes_unchanged'] or not all(item['passed'] for item in space_commit_checks):raise SystemExit(1)
 finally:
  if sid:destroy(sid)
  finalize()
