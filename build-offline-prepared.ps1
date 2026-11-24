# Computer-only build; deliberately contains no ADB commands.
param(
 [switch]$StageOnly,
 [string]$PythonExe = 'python',
 [string]$JavaHome = $env:JAVA_HOME,
 [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
 [string]$GradleExecutable = 'gradle',
 [switch]$RunUnitTests
)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$vendor=Join-Path $PSScriptRoot 'vendor'
$apk=Join-Path $vendor 'trime-v3.3.13-arm64-official.apk'
if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne 'E7535EA421A25962EB2EA37C46FF867838E69D33BA6D935876AB84D108DE5020'){throw 'Official native asset digest mismatch'}
$env:NIUMA_BUILD_ROOT=$root
$env:NIUMA_OFFICIAL_APK=$apk
$env:PYTHONIOENCODING='utf-8'
@'
from pathlib import Path
import os, zipfile, hashlib, json, stat
root=Path(os.environ['NIUMA_BUILD_ROOT'])
source_archive=root/'vendor'/'trime-v3.3.13-source.zip'
if hashlib.sha256(source_archive.read_bytes()).hexdigest() != 'b8690b5bcc78cf572a451c81d1daafd568284a7bce2ef3e599edd367618569bf':
 raise ValueError('Pinned upstream source archive digest mismatch')
# Windows extraction can turn a Git symlink into its literal target path. Only materialize
# entries proven by the pinned original archive, and only if bytes still match that exact stub.
source_links={}
with zipfile.ZipFile(source_archive) as source_zip:
 for info in source_zip.infolist():
  rel=info.filename.partition('/')[2]
  if rel.startswith('app/src/main/assets/') and stat.S_ISLNK(info.external_attr >> 16):
   source_links[rel]=source_zip.read(info)
manifest=[]
with zipfile.ZipFile(os.environ['NIUMA_OFFICIAL_APK']) as z:
 for name in z.namelist():
  rel=None
  if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
   rel=Path('app/prebuilt')/Path(name).relative_to('lib')
  elif name.startswith('assets/shared/') and not name.endswith('/'):
   rel=Path('app/src/main/assets')/Path(name).relative_to('assets')
  if rel is None: continue
  if '..' in rel.parts: raise ValueError('Unexpected archive path')
  dst=root/rel
  data=z.read(name)
  source_hash=hashlib.sha256(data).hexdigest()
  if dst.exists():
   current=dst.read_bytes()
   local_hash=hashlib.sha256(current).hexdigest()
   if name.startswith('lib/') and local_hash!=source_hash: raise ValueError('Prebuilt JNI no longer matches official release')
   original_link=source_links.get(rel.as_posix())
   status='preserved-existing'
   if original_link is not None and current == original_link:
    dst.write_bytes(data)
    local_hash=hashlib.sha256(dst.read_bytes()).hexdigest()
    if local_hash != source_hash: raise ValueError('Official link materialization digest mismatch')
    status='materialized-verified-upstream-symlink'
   elif original_link is not None and local_hash == source_hash:
    status='verified-materialized-upstream-symlink'
   manifest.append(dict(source=name,path=rel.as_posix(),sha256=local_hash,official_sha256=source_hash,status=status))
   continue
  dst.parent.mkdir(parents=True,exist_ok=True)
  dst.write_bytes(data)
  manifest.append(dict(source=name,path=rel.as_posix(),sha256=source_hash,official_sha256=source_hash,status='copied-official'))
(root/'vendor'/'staged-official-assets.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False),encoding='utf-8')
print('Verified/staged official shared assets and matching JNI library:',len(manifest))
'@ | & $PythonExe -
if($LASTEXITCODE -ne 0){throw 'Official asset staging failed'}
if($StageOnly){return}
if(-not $JavaHome){throw 'Pass -JavaHome or set JAVA_HOME for the selected JDK.'}
$python=(Get-Command $PythonExe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$sdk=(Resolve-Path -LiteralPath $SdkRoot).Path
$gradle=(Get-Command $GradleExecutable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
& (Join-Path $PSScriptRoot 'source-packaging/build-frontend-portable.ps1') `
 -PythonExe $python -JavaHome $JavaHome `
 -SdkRoot $sdk -GradleExecutable $gradle `
 -GradleUserHome (Join-Path $PSScriptRoot '.gradle-cache') -RunUnitTests:$RunUnitTests
