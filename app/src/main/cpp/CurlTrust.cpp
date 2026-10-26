// App integration for the SDK's static libcurl. Android's old-hash certificate
// directories are not OpenSSL CA directories. Configure new handles explicitly;
// Explicit configuration also works when AT_SECURE suppresses environment paths.
#include <curl/curl.h>
#include <mutex>
#include <string>
namespace {
std::mutex trust_mutex;
std::string trust_file;
}
extern "C" __attribute__((visibility("default"))) void scrctl_configure_system_roots(const char *path) {
    std::lock_guard lock(trust_mutex);
    trust_file = path;
}
extern "C" CURL *__real_curl_easy_init();
extern "C" CURL *__wrap_curl_easy_init() {
    CURL *handle = __real_curl_easy_init();
    if (!handle) return nullptr;
    std::lock_guard lock(trust_mutex);
    if (trust_file.empty() || curl_easy_setopt(handle, CURLOPT_CAINFO, trust_file.c_str()) != CURLE_OK ||
        curl_easy_setopt(handle, CURLOPT_CAPATH, static_cast<const char *>(nullptr)) != CURLE_OK) {
        curl_easy_cleanup(handle);
        return nullptr;
    }
    // Do not disable peer/hostname verification. SDK TSS subsequently overrides
    // this default with its explicit dedicated Apple CA blob.
    return handle;
}
