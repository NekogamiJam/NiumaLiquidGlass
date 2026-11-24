"""Stage only deterministic dictionary-derived associations. No device access."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT/'association-tools'))
from build_associations import build_associations,serialize
source=ROOT/'input-data/niumawb_zc.dict.yaml'
source_bytes=source.read_bytes()
assert hashlib.sha256(source_bytes).hexdigest()=='09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9'
generated=ROOT/'association-tools/generated/niuma_associations.json'
expected=serialize(build_associations(source_bytes))
assert generated.read_bytes()==expected,'Generated associations do not match deterministic source build'
target=ROOT/'app/src/main/assets/niuma/niuma_associations.json'
manifest=ROOT/'vendor/association-assets.json'
sha=lambda data:hashlib.sha256(data).hexdigest()
if target.exists() and target.read_bytes()!=expected:
    assert manifest.exists(),'Unowned association asset must not be overwritten'
    previous=json.loads(manifest.read_text(encoding='utf-8-sig'))
    assert sha(target.read_bytes())==previous['sha256'],'Association asset manually modified; inspect before replacing'
target.parent.mkdir(parents=True,exist_ok=True)
target.write_bytes(expected)
assert target.read_bytes()==expected
info={'source_sha256':sha(source_bytes),'sha256':sha(expected),'bytes':len(expected),'keys':len(json.loads(expected)['entries']),'phone_access':False,'statistical_language_model':False,'source':str(generated.relative_to(ROOT)),'target':str(target.relative_to(ROOT))}
manifest.write_text(json.dumps(info,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(info,ensure_ascii=False,indent=2))
