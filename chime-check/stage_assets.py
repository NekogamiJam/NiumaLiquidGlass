from pathlib import Path
import hashlib,json
R=Path(__file__).resolve().parents[1];S=R/'chime-check/upstream';A=R/'app/src/main/assets/chime'
def sha(b):return hashlib.sha256(b).hexdigest()
model=(S/'cifg_int8.bin').read_bytes();assert len(model)==2221879 and sha(model)=='3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318'
payloads={'cifg_int8.bin':model,**{n:(S/n).read_bytes() for n in ['LICENSE.md','NOTICE.txt','Apache-2.0.txt']}}
ledger=R/'chime-check/staged-files.json';old=json.loads(ledger.read_text('utf-8')) if ledger.exists() else {'managed_by':'niuma-chime','files':{}}
assert old['managed_by']=='niuma-chime'
for n,b in payloads.items():
 p=A/n
 if p.exists() and p.read_bytes()!=b:assert sha(p.read_bytes())==old['files'].get(n,{}).get('sha256'),'Refusing unowned modified '+n
A.mkdir(parents=True,exist_ok=True)
for n,b in payloads.items():(A/n).write_bytes(b)
ledger.write_text(json.dumps({'format':1,'managed_by':'niuma-chime','files':{n:{'bytes':len(b),'sha256':sha(b)} for n,b in payloads.items()}},indent=2)+'\n','utf-8');print('Verified/staged four Chime assets; other assets untouched')
