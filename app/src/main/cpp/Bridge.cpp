#include <android/bitmap.h>
#include <atomic>

#include <jni.h>
#include <memory>
#include <mutex>
#include <nlohmann/json.hpp>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <xdb/xdb.h>
extern "C" void scrctl_configure_system_roots(const char *path);
namespace {
JavaVM *vm;
std::atomic<jlong> ids{1};
JNIEnv *environment() {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
    throw std::runtime_error("JNI thread detached");
  return env;
}
std::string utf8(JNIEnv *e, jstring s) {
  auto t = e->GetObjectClass(s);
  auto enc = e->NewStringUTF("UTF-8");
  auto a = static_cast<jbyteArray>(e->CallObjectMethod(
      s, e->GetMethodID(t, "getBytes", "(Ljava/lang/String;)[B"), enc));
  if (!a)
    throw std::runtime_error("Invalid Java string");
  std::string out(e->GetArrayLength(a), '\0');
  e->GetByteArrayRegion(a, 0, out.size(),
                        reinterpret_cast<jbyte *>(out.data()));
  e->DeleteLocalRef(a);
  e->DeleteLocalRef(t);
  e->DeleteLocalRef(enc);
  return out;
}
jstring text(JNIEnv *e, const std::string &s) {
  auto a = e->NewByteArray(s.size());
  if (!a)
    throw std::runtime_error("JSON allocation failed");
  e->SetByteArrayRegion(a, 0, s.size(),
                        reinterpret_cast<const jbyte *>(s.data()));
  auto t = e->FindClass("java/lang/String");
  auto enc = e->NewStringUTF("UTF-8");
  auto out = static_cast<jstring>(e->NewObject(
      t, e->GetMethodID(t, "<init>", "([BLjava/lang/String;)V"), a, enc));
  e->DeleteLocalRef(a);
  e->DeleteLocalRef(t);
  e->DeleteLocalRef(enc);
  return out;
}
void failure(JNIEnv *e, const std::exception &ex) {
  if (!e->ExceptionCheck())
    e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), ex.what());
}
struct Client {
  xdb_client *handle = nullptr;
  jobject events = nullptr;
  std::mutex requests;
  ~Client() {
    xdb_client_destroy(handle);
    if (events)
      environment()->DeleteGlobalRef(events);
  }
};
void event(const char *json, void *userdata) {
  auto *c = static_cast<Client *>(userdata);
  auto *e = environment();
  auto t = e->GetObjectClass(c->events);
  auto s = text(e, json);
  e->CallVoidMethod(c->events,
                    e->GetMethodID(t, "onEvent", "(Ljava/lang/String;)V"), s);
  e->DeleteLocalRef(s);
  e->DeleteLocalRef(t);
}
struct Runtime {
  xdb_server *server = nullptr;
  std::mutex mutex;
  bool closing = false;
  std::unordered_map<jlong, std::shared_ptr<Client>> clients;
  void interrupt() {
    std::lock_guard lock(mutex);
    for (auto &[id, c] : clients)
      xdb_client_interrupt(c->handle);
  }
  ~Runtime() {
    clients.clear();
    xdb_server_stop(server);
  }
};
std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<Runtime>> runtimes;
std::shared_ptr<Runtime> runtime(jlong id) {
  std::lock_guard lock(registry_mutex);
  auto f = runtimes.find(id);
  if (f == runtimes.end())
    throw std::runtime_error("Server no longer active");
  return f->second;
}
std::shared_ptr<Client> client(const std::shared_ptr<Runtime> &r, jlong id) {
  std::lock_guard lock(r->mutex);
  auto f = r->clients.find(id);
  if (r->closing || f == r->clients.end())
    throw std::runtime_error("Client no longer active");
  return f->second;
}
jobject bitmap(JNIEnv *e, const xdb_response *raw) {
  auto envelope = nlohmann::json::parse(xdb_response_json(raw));
  if (!envelope.value("ok", false))
    return nullptr;
  auto result = envelope.value("result", nlohmann::json::object());
  if (!result.is_object() || result.value("format", std::string()) != "bgra")
    return nullptr;
  int w = result.value("width", 0), h = result.value("height", 0),
      pitch = result.value("row_pitch", 0);
  if (w <= 0 || h <= 0 || w > 8192 || h > 8192 ||
      int64_t(pitch) < int64_t(w) * 4 ||
      uint64_t(pitch) * h != xdb_response_size(raw) ||
      xdb_response_size(raw) > 64 * 1024 * 1024)
    throw std::runtime_error("Invalid BGRA dimensions");
  auto t = e->FindClass("android/graphics/Bitmap");
  auto ct = e->FindClass("android/graphics/Bitmap$Config");
  auto cfg = e->GetStaticObjectField(
      ct,
      e->GetStaticFieldID(ct, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
  auto image = e->CallStaticObjectMethod(
      t,
      e->GetStaticMethodID(
          t, "createBitmap",
          "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"),
      w, h, cfg);
  e->DeleteLocalRef(t);
  e->DeleteLocalRef(ct);
  e->DeleteLocalRef(cfg);
  if (!image)
    throw std::runtime_error("Bitmap allocation failed");
  AndroidBitmapInfo info{};
  void *pixels = nullptr;
  if (AndroidBitmap_getInfo(e, image, &info) != 0 ||
      AndroidBitmap_lockPixels(e, image, &pixels) != 0)
    throw std::runtime_error("Bitmap lock failed");
  auto *source = xdb_response_data(raw);
  for (int y = 0; y < h; ++y) {
    auto *out = static_cast<uint8_t *>(pixels) + y * info.stride;
    auto *in = source + y * pitch;
    for (int x = 0; x < w; ++x) {
      out[4 * x] = in[4 * x + 2];
      out[4 * x + 1] = in[4 * x + 1];
      out[4 * x + 2] = in[4 * x];
      out[4 * x + 3] = in[4 * x + 3];
    }
  }
  AndroidBitmap_unlockPixels(e, image);
  return image;
}
} // namespace
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *j, void *) {
  vm = j;
  return JNI_VERSION_1_6;
}
#define JNI(type, name)                                                        \
  extern "C" JNIEXPORT type JNICALL Java_org_scrctl_android_NativeBridge_##name
JNI(jlong, startNative)(JNIEnv *e, jobject, jstring directory, jstring caFile) {
  try {
    if (std::string(xdb_client_version()) != XDB_VERSION_STRING)
      throw std::runtime_error("Native client version does not match the compiled SDK");
    scrctl_configure_system_roots(utf8(e, caFile).c_str());
    auto r = std::make_shared<Runtime>();
    r->server = xdb_server_start(0, utf8(e, directory).c_str());
    if (!r->server)
      throw std::runtime_error(xdb_server_last_error());
    auto id = ids++;
    std::lock_guard lock(registry_mutex);
    runtimes.emplace(id, r);
    return id;
  } catch (const std::exception &x) {
    failure(e, x);
    return 0;
  }
}
JNI(jint, port)(JNIEnv *e, jobject, jlong id) {
  try {
    return xdb_server_port(runtime(id)->server);
  } catch (const std::exception &x) {
    failure(e, x);
    return 0;
  }
}
JNI(jlong, connect)(JNIEnv *e, jobject, jlong id, jobject events) {
  try {
    auto r = runtime(id);
    auto c = std::make_shared<Client>();
    c->events = e->NewGlobalRef(events);
    c->handle = xdb_client_connect(xdb_server_port(r->server), event, c.get());
    if (!c->handle)
      throw std::runtime_error(xdb_last_error());
    std::lock_guard lock(r->mutex);
    if (r->closing)
      throw std::runtime_error("Server closing");
    auto key = ids++;
    r->clients.emplace(key, c);
    return key;
  } catch (const std::exception &x) {
    failure(e, x);
    return 0;
  }
}
JNI(jobject, request)(JNIEnv *e, jobject, jlong id, jlong key, jstring method,
                      jstring params) {
  try {
    auto r = runtime(id);
    auto c = client(r, key);
    std::lock_guard lock(c->requests);
    xdb_response *response = nullptr;
    if (!xdb_client_request(c->handle, utf8(e, method).c_str(),
                            utf8(e, params).c_str(), &response))
      throw std::runtime_error(xdb_last_error());
    std::unique_ptr<xdb_response, decltype(&xdb_response_destroy)> raw(
        response, xdb_response_destroy);
    auto s = text(e, xdb_response_json(response));
    auto image = bitmap(e, response);
    auto t = e->FindClass("org/scrctl/android/NativeReply");
    auto result = e->NewObject(
        t,
        e->GetMethodID(t, "<init>",
                       "(Ljava/lang/String;Landroid/graphics/Bitmap;)V"),
        s, image);
    e->DeleteLocalRef(t);
    e->DeleteLocalRef(s);
    if (image)
      e->DeleteLocalRef(image);
    return result;
  } catch (const std::exception &x) {
    failure(e, x);
    return nullptr;
  }
}
JNI(void, interrupt)(JNIEnv *, jobject, jlong id, jlong key) {
  try {
    auto r = runtime(id);
    xdb_client_interrupt(client(r, key)->handle);
  } catch (...) {
  }
}
JNI(void, interruptAll)(JNIEnv *, jobject, jlong id) {
  try {
    runtime(id)->interrupt();
  } catch (...) {
  }
}
JNI(void, close)(JNIEnv *, jobject, jlong id, jlong key) {
  try {
    auto r = runtime(id);
    std::shared_ptr<Client> c;
    {
      std::lock_guard lock(r->mutex);
      auto f = r->clients.find(key);
      if (f != r->clients.end()) {
        c = f->second;
        r->clients.erase(f);
      }
    }
    if (c)
      xdb_client_interrupt(c->handle);
  } catch (...) {
  }
}
JNI(void, stopNative)(JNIEnv *, jobject, jlong id) {
  std::shared_ptr<Runtime> r;
  {
    std::lock_guard lock(registry_mutex);
    auto f = runtimes.find(id);
    if (f == runtimes.end())
      return;
    r = f->second;
    runtimes.erase(f);
  }
  {
    std::lock_guard lock(r->mutex);
    r->closing = true;
  }
  r->interrupt();
  // In-flight calls retain r. The last one joins Server off the UI thread.
}
