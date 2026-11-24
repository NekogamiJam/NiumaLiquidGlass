from pathlib import Path
import hashlib,zipfile,unittest
R=Path(__file__).resolve().parents[1];B=R/'out/RimeLiquidGlass-SenseVoice-source-kit-20261114.zip'
class Contracts(unittest.TestCase):
 def test_chinese_engine_voice_keycaps_unchanged(self):
  if not B.is_file():
   self.skipTest('historical source-kit archive is not published in this repository')
  with zipfile.ZipFile(B) as z:
   names=[n for n in z.namelist() if n.startswith(('custom-data/','custom-src/','input-data/')) or '/ime/association/' in n or n.endswith('/assets/niuma/niuma_associations.json') or '/assets/offline-asr/' in n or '/jniLibs/' in n]
   self.assertGreater(len(names),30)
   for n in names:
    p=R/n
    if p.is_file():self.assertEqual(z.read(n),p.read_bytes(),n)
 def test_suffix_only_guarded_submission(self):
  s=(R/'app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt').read_text('utf-8');f=s.split('private fun commitNiumaEnglish(')[1].split('private var niumaAssociation')[0]
  self.assertEqual(f.count('readNiumaCursorWindow('),2);self.assertNotIn('deleteSurroundingText',f);self.assertIn('getRawInput().isNotEmpty()',f);self.assertIn('epoch == niumaInputEpoch.get()',f);self.assertIn('word.substring(prefix.length)',f)
 def test_model_digest_and_worker_privacy(self):
  s=(R/'app/src/main/java/com/osfans/trime/ime/english/EnglishSuggestionController.kt').read_text('utf-8')
  for n in ['takeLast(512)','Executors.newSingleThreadExecutor()','next.generation == generation','scheduled.compareAndSet(false, true)']:self.assertIn(n,s)
  for n in ['getSurroundingText','clipboardManager','writeText(','openConnection','SharedPreferences']:self.assertNotIn(n,s)
  self.assertEqual(hashlib.sha256((R/'app/src/main/assets/chime/cifg_int8.bin').read_bytes()).hexdigest(),'3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318')
 def test_qualified_screen(self):
  import json
  r=json.loads((R/'chime-check/desktop-results.json').read_text('utf-8'));self.assertTrue(r['criteria_passed']);self.assertEqual(len(r['cases']),76)
 def test_english_virtual_printable_commit_funnel(self):
  s=(R/'app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt').read_text('utf-8')
  f=s.split('if (it.isVirtual && it.value.value in 32..126')[1].split('if (it.isVirtual) {')[0]
  for v in ['!it.modifiers.ctrl','!it.modifiers.alt','!it.modifiers.meta','!it.modifiers.release','niumaEnglishIsSafe()','commitText(Character.toString(it.value.value))','return@msg']:self.assertIn(v,f)
  self.assertNotIn('sendDownUpKeyEvent',f)
 def test_version(self):
  s=(R/'app/build.gradle.kts').read_text('utf-8');self.assertIn('versionName = "0.1.0"',s);self.assertIn('versionCode = 2',s)
if __name__=='__main__':unittest.main()
