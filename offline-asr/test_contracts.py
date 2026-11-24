"""Source/packaging invariants only; not substitutes for recording/lifecycle device tests."""
from pathlib import Path
import unittest,zipfile,hashlib,json,importlib.util
R=Path(__file__).resolve().parent.parent
BASE=R/'out/RimeLiquidGlass-about-source-kit-20261113.zip'
class Contracts(unittest.TestCase):
 def test_keycaps_engine_gestures_tables_and_clipboard_unchanged(self):
  paths=['custom-src/GlassKeySurface.kt','custom-src/LiquidGlassStyle.kt','custom-src/NativeGlassRenderer.kt','custom-data/niuma_glass.schema.yaml','custom-data/niuma_glass.trime.yaml','custom-data/lua/niuma_fourcode.lua']
  for p in (R/'input-data').rglob('*.yaml'):paths.append(p.relative_to(R).as_posix())
  stem='app/src/main/java/com/osfans/trime/'
  paths += [stem+'ime/keyboard/KeyView.kt',stem+'ime/gesture/NiumaGestureRules.kt',stem+'ime/gesture/NiumaGestureExecutor.kt',stem+'data/db/ClipboardHelper.kt']
  if not BASE.is_file():
    self.skipTest('historical source-kit archive is not published in this repository')
  with zipfile.ZipFile(BASE) as z:
   for p in paths:self.assertEqual((R/p).read_bytes(),z.read(p),p)
 def test_controller_is_local_and_requires_explicit_final_confirmation(self):
  s=(R/'custom-src/NiumaVoiceController.kt').read_text('utf-8')
  self.assertIn('OfflineVoiceInput(service)',s);self.assertNotIn('SystemVoiceInput(service)',s)
  finished=s[s.index('VoiceState.FINISHED ->'):s.index('private fun confirm')]
  self.assertNotIn('onFinalText(',finished)
  confirm=s[s.index('private fun confirm'):s.index('private fun sessionStillMatches')]
  for t in ['sessionStillMatches(session)','compositionIsEmpty()','VoiceConsent.canStart','isSensitiveField','session.submitted = true']:self.assertIn(t,confirm)
 def test_bounded_capture_and_serial_native_no_network_or_audio_files(self):
  s=(R/'custom-src/OfflineVoiceInput.kt').read_text('utf-8')
  for t in ['ArrayBlockingQueue<FloatArray>(64)','newSingleThreadExecutor','synchronized(session.gate)','session.cancelled.get()','active === session','session.queue.isEmpty()','IDLE_RELEASE_MS = 30_000L']:self.assertIn(t,s)
  for t in ['SpeechRecognizer','Socket(','URL(','FileOutputStream','MediaRecorder()']:self.assertNotIn(t,s)
 def test_models_are_private_verified_atomic_and_worker_only(self):
  s=(R/'custom-src/OfflineAsrModels.kt').read_text('utf-8')
  for t in ['SHA-256','AtomicFile','canonicalFile.parentFile','noBackupFilesDir','file.channel.lock()','getMainLooper()','seen == names']:self.assertIn(t,s)
  self.assertNotIn('https://',s)
 def test_consent_is_new_local_notice_and_no_recording_on_permission(self):
  s=(R/'custom-src/VoicePermissionActivity.kt').read_text('utf-8')
  self.assertIn('CURRENT_VERSION: Int = 2',s);self.assertIn('不把音频发送',s)
  self.assertNotIn('AudioRecord',s);self.assertNotIn('startNiumaVoiceInput(',s)
 def test_native_failure_boundaries_and_fixed_frame(self):
  s=(R/'offline-asr/native/sense_jni.cpp').read_text('utf-8')
  for t in ['boundary(e,','std::unique_ptr','kCapacity=24*16000','GetArrayLength(a)!=kWindow','std::isfinite','std::fill(c.ring.begin()','begin<std::max<int64_t>(0,c.total-kCapacity)']:self.assertIn(t,s)
  for t in ['printf(','std::cout','fwrite(','AudioRecord','http']:self.assertNotIn(t,s)
 def test_public_file_probe_is_isolated_from_live_application_initializers(self):
  s=(R/'app/src/main/java/com/osfans/trime/TrimeApplication.kt').read_text('utf-8')
  a=s.index('if (BuildConfig.DEBUG && (getProcessName().endsWith(":enginecheck")')
  self.assertLess(a,s.index('!userManager.isUserUnlocked'))
  self.assertIn('getProcessName().endsWith(":offlineasrcheck")',s[a:s.index('!userManager.isUserUnlocked')])
 def test_all_packaged_offline_assets_match_ownership(self):
  spec=importlib.util.spec_from_file_location('offline_stager',R/'offline-asr/stage_assets.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  self.assertEqual(len(m.verify_staged(R)),1)
if __name__=='__main__':unittest.main()
