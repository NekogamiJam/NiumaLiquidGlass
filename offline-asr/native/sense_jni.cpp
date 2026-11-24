// Private single-worker JNI bridge. No audio files, transcript logging or network.
#include <jni.h>
#include "c-api.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

namespace {
constexpr int kRate=16000, kWindow=512, kPrepad=3200, kTail=12800;
constexpr int64_t kCapacity=24*16000;
void fail(JNIEnv* e, bool oom=false) noexcept {
  if(e->ExceptionCheck()) return;
  jclass c=e->FindClass(oom?"java/lang/OutOfMemoryError":"java/lang/IllegalStateException");
  if(c && !e->ExceptionCheck()) e->ThrowNew(c,oom?"offline ASR out of memory":"offline ASR error");
}
template<class F> auto boundary(JNIEnv* e,F f) noexcept -> decltype(f()) {
  try { return f(); } catch(const std::bad_alloc&) { fail(e,true); }
  catch(...) { fail(e); }
  return {};
}
struct Context {
  const SherpaOnnxOfflineRecognizer* recognizer=nullptr;
  const SherpaOnnxVoiceActivityDetector* vad=nullptr;
  std::vector<float> ring=std::vector<float>(kCapacity,0.f);
  int64_t total=0;
  ~Context() noexcept {
    std::fill(ring.begin(),ring.end(),0.f);
    try { if(vad) SherpaOnnxDestroyVoiceActivityDetector(vad); } catch(...) {}
    try { if(recognizer) SherpaOnnxDestroyOfflineRecognizer(recognizer); } catch(...) {}
  }
};
// Access exclusively from the Kotlin process-wide inference worker.
std::unordered_set<Context*> live;
Context& checked(jlong h) {
  auto* c=reinterpret_cast<Context*>(h);
  if(!c || !live.count(c)) throw std::runtime_error("handle");
  return *c;
}
struct UtfChars {
  JNIEnv* e; jstring s; const char* p;
  UtfChars(JNIEnv* env,jstring str):e(env),s(str),p(nullptr) {
    if(!s) throw std::runtime_error("string");
    p=e->GetStringUTFChars(s,nullptr);
    if(!p || e->ExceptionCheck()) throw std::runtime_error("string");
  }
  ~UtfChars() noexcept { if(p) e->ReleaseStringUTFChars(s,p); }
};
struct FloatChars {
  JNIEnv* e; jfloatArray a; jfloat* p=nullptr;
  FloatChars(JNIEnv* env,jfloatArray arr):e(env),a(arr) {
    if(!a || e->GetArrayLength(a)!=kWindow) throw std::runtime_error("frame");
    p=e->GetFloatArrayElements(a,nullptr);
    if(!p || e->ExceptionCheck()) throw std::runtime_error("frame");
  }
  ~FloatChars() noexcept { if(p) e->ReleaseFloatArrayElements(a,p,JNI_ABORT); }
};
struct StreamFree { void operator()(const SherpaOnnxOfflineStream* p) const noexcept { try { if(p) SherpaOnnxDestroyOfflineStream(p); } catch(...) {} } };
struct ResultFree { void operator()(const SherpaOnnxOfflineRecognizerResult* p) const noexcept { try { if(p) SherpaOnnxDestroyOfflineRecognizerResult(p); } catch(...) {} } };
struct SegmentFree { void operator()(const SherpaOnnxSpeechSegment* p) const noexcept { try { if(p) SherpaOnnxDestroySpeechSegment(p); } catch(...) {} } };
std::string decode(Context& c,const std::vector<float>& samples) {
  std::unique_ptr<const SherpaOnnxOfflineStream,StreamFree> stream(SherpaOnnxCreateOfflineStream(c.recognizer));
  if(!stream) throw std::runtime_error("stream");
  SherpaOnnxAcceptWaveformOffline(stream.get(),kRate,samples.data(),static_cast<int>(samples.size()));
  SherpaOnnxDecodeOfflineStream(c.recognizer,stream.get());
  std::unique_ptr<const SherpaOnnxOfflineRecognizerResult,ResultFree> result(SherpaOnnxGetOfflineStreamResult(stream.get()));
  if(!result || !result->text) throw std::runtime_error("result");
  std::string s=result->text;
  auto first=s.find_first_not_of(" \t\r\n");
  return first==std::string::npos?"":s.substr(first,s.find_last_not_of(" \t\r\n")-first+1);
}
std::vector<std::string> drain(Context& c) {
  std::vector<std::string> texts;
  while(!SherpaOnnxVoiceActivityDetectorEmpty(c.vad)) {
    std::unique_ptr<const SherpaOnnxSpeechSegment,SegmentFree> segment(SherpaOnnxVoiceActivityDetectorFront(c.vad));
    if(!segment || segment->start<0 || segment->n<0) throw std::runtime_error("segment");
    int64_t begin=std::max<int64_t>(0,segment->start-kPrepad);
    int64_t end=std::min<int64_t>(c.total,static_cast<int64_t>(segment->start)+segment->n);
    if(begin<end) {
      if(begin<std::max<int64_t>(0,c.total-kCapacity) || end>c.total || end-begin>kCapacity) throw std::runtime_error("ring");
      std::vector<float> slice(static_cast<size_t>(end-begin));
      for(int64_t i=begin;i<end;++i) slice[i-begin]=c.ring[i%kCapacity];
      auto text=decode(c,slice);
      if(!text.empty()) texts.push_back(std::move(text));
      std::fill(slice.begin(),slice.end(),0.f);
    }
    segment.reset();
    SherpaOnnxVoiceActivityDetectorPop(c.vad);
  }
  return texts;
}
// ASR produces UTF-8; use true UTF-16 JNI strings, not modified UTF-8.
jstring javaString(JNIEnv* e,const std::string& s) {
  std::vector<jchar> out;
  for(size_t i=0;i<s.size();) {
    unsigned char b=static_cast<unsigned char>(s[i++]); uint32_t cp=b; int left=0;
    if(b>=0xf0) { cp=b&7; left=3; } else if(b>=0xe0) { cp=b&15; left=2; } else if(b>=0xc0) { cp=b&31; left=1; }
    else if(b>=0x80) { cp=0xfffd; }
    bool ok=i+left<=s.size();
    for(int n=0;ok && n<left;++n) { unsigned char t=s[i++]; if((t&0xc0)!=0x80) ok=false; else cp=(cp<<6)|(t&63); }
    if(!ok || cp>0x10ffff || (cp>=0xd800 && cp<=0xdfff)) cp=0xfffd;
    if(cp>=0x10000) { cp-=0x10000; out.push_back(0xd800+(cp>>10)); out.push_back(0xdc00+(cp&1023)); }
    else out.push_back(static_cast<jchar>(cp));
  }
  return e->NewString(out.empty()?nullptr:out.data(),static_cast<jsize>(out.size()));
}
jobjectArray javaArray(JNIEnv* e,const std::vector<std::string>& texts) {
  jclass cls=e->FindClass("java/lang/String");
  if(!cls || e->ExceptionCheck()) return nullptr;
  auto a=e->NewObjectArray(static_cast<jsize>(texts.size()),cls,nullptr); e->DeleteLocalRef(cls);
  if(!a || e->ExceptionCheck()) return nullptr;
  for(jsize i=0;i<static_cast<jsize>(texts.size());++i) {
    auto s=javaString(e,texts[i]); if(!s || e->ExceptionCheck()) return nullptr;
    e->SetObjectArrayElement(a,i,s); e->DeleteLocalRef(s);
    if(e->ExceptionCheck()) return nullptr;
  }
  return a;
}
}
extern "C" {
JNIEXPORT jint JNI_OnLoad(JavaVM*,void*) { return JNI_VERSION_1_6; }
JNIEXPORT jlong JNICALL Java_com_osfans_trime_ime_voice_LocalAsrNative_create(JNIEnv* e,jclass,jstring model,jstring tokens,jstring vad) {
  return boundary(e,[&]()->jlong {
    UtfChars m(e,model),t(e,tokens),v(e,vad); auto c=std::make_unique<Context>();
    SherpaOnnxOfflineRecognizerConfig rc{};
    rc.feat_config.sample_rate=kRate; rc.feat_config.feature_dim=80;
    rc.model_config.sense_voice.model=m.p; rc.model_config.sense_voice.language="auto"; rc.model_config.sense_voice.use_itn=1;
    rc.model_config.tokens=t.p; rc.model_config.num_threads=2; rc.model_config.provider="cpu"; rc.model_config.model_type="sense_voice"; rc.decoding_method="greedy_search";
    c->recognizer=SherpaOnnxCreateOfflineRecognizer(&rc); if(!c->recognizer) throw std::runtime_error("recognizer");
    SherpaOnnxVadModelConfig vc{};
    vc.silero_vad.model=v.p; vc.silero_vad.threshold=.5f; vc.silero_vad.min_silence_duration=.5f;
    vc.silero_vad.min_speech_duration=.25f; vc.silero_vad.window_size=kWindow; vc.silero_vad.max_speech_duration=20.f;
    vc.sample_rate=kRate; vc.num_threads=1; vc.provider="cpu";
    c->vad=SherpaOnnxCreateVoiceActivityDetector(&vc,60.f); if(!c->vad) throw std::runtime_error("vad");
    live.insert(c.get()); return reinterpret_cast<jlong>(c.release());
  });
}
JNIEXPORT void JNICALL Java_com_osfans_trime_ime_voice_LocalAsrNative_reset(JNIEnv* e,jclass,jlong h) {
  boundary(e,[&] { auto& c=checked(h); SherpaOnnxVoiceActivityDetectorReset(c.vad); c.total=0; std::fill(c.ring.begin(),c.ring.end(),0.f); return 0; });
}
JNIEXPORT jobjectArray JNICALL Java_com_osfans_trime_ime_voice_LocalAsrNative_accept(JNIEnv* e,jclass,jlong h,jfloatArray samples) {
  return boundary(e,[&]()->jobjectArray {
    auto& c=checked(h); FloatChars f(e,samples);
    for(int i=0;i<kWindow;++i) if(!std::isfinite(f.p[i])) throw std::runtime_error("sample");
    for(int i=0;i<kWindow;++i) c.ring[(c.total+i)%kCapacity]=f.p[i]; c.total+=kWindow;
    SherpaOnnxVoiceActivityDetectorAcceptWaveform(c.vad,f.p,kWindow);
    return javaArray(e,drain(c));
  });
}
JNIEXPORT jobjectArray JNICALL Java_com_osfans_trime_ime_voice_LocalAsrNative_finish(JNIEnv* e,jclass,jlong h) {
  return boundary(e,[&]()->jobjectArray {
    auto& c=checked(h); std::vector<float> zeros(kWindow,0.f);
    // Drain while padding so native VAD buffers cannot accumulate large batches.
    std::vector<std::string> texts;
    for(int i=0;i<kTail;i+=kWindow) {
      SherpaOnnxVoiceActivityDetectorAcceptWaveform(c.vad,zeros.data(),kWindow);
      auto part=drain(c); texts.insert(texts.end(),part.begin(),part.end());
    }
    SherpaOnnxVoiceActivityDetectorFlush(c.vad);
    auto part=drain(c); texts.insert(texts.end(),part.begin(),part.end());
    return javaArray(e,texts);
  });
}
JNIEXPORT void JNICALL Java_com_osfans_trime_ime_voice_LocalAsrNative_destroy(JNIEnv* e,jclass,jlong h) {
  boundary(e,[&] { if(h) { auto& c=checked(h); live.erase(&c); delete &c; } return 0; });
}
}
