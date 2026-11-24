from pathlib import Path
import urllib.request,json,hashlib
P=Path(__file__).resolve().parent.parent;R=Path(__file__).resolve().parent;R.mkdir(exist_ok=True)
meta=json.loads((P/'mnn-pocket-check/continuation-options/lukeslp--chime-sms-2m.json').read_text('utf-8'));rev=meta['sha'];wanted={'cifg_int8.bin','inference.py','localtype_tokenizer.py','config.json','LICENSE.md','NOTICE.txt','Apache-2.0.txt','README.md'}
rows=[]
for x in meta['siblings']:
 n=x['rfilename']
 if n not in wanted:continue
 url=f'https://huggingface.co/lukeslp/chime-sms-2m/resolve/{rev}/{n}'
 with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'LocalResearch/1.0'}),timeout=35) as f:b=f.read()
 assert len(b)==x['size'],n
 if 'lfs' in x:assert hashlib.sha256(b).hexdigest()==x['lfs']['sha256']
 else:assert hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()==x['blobId']
 p=R/'upstream'/n;p.parent.mkdir(exist_ok=True)
 if p.exists():assert p.read_bytes()==b,n
 else:p.write_bytes(b)
 rows.append({'name':n,'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),'url':url})
assert {x['name'] for x in rows}==wanted
(R/'downloads.json').write_text(json.dumps({'repo':meta['id'],'revision':rev,'files':rows},ensure_ascii=False,indent=2)+'\n','utf-8');print(json.dumps(rows,ensure_ascii=True))
