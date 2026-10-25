#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <vector>

// KMP over UTF-16 code units: the result uses the same offsets as Sora and LSP.
// Case folding is ASCII-only here; Kotlin handles non-ASCII case-insensitive search.
static char16_t fold(char16_t c, bool insensitive) {
    return insensitive && c >= u'A' && c <= u'Z' ? static_cast<char16_t>(c + 32) : c;
}
std::vector<int32_t> find_matches(const char16_t* text, int32_t n,
                                 const char16_t* pattern, int32_t m,
                                 bool insensitive, int32_t limit) {
    std::vector<int32_t> result;
    if (m <= 0 || n < m || limit <= 0) return result;
    std::vector<int32_t> prefix(static_cast<size_t>(m), 0);
    for (int32_t i = 1, j = 0; i < m; ++i) {
        while (j && fold(pattern[i], insensitive) != fold(pattern[j], insensitive)) j = prefix[j - 1];
        if (fold(pattern[i], insensitive) == fold(pattern[j], insensitive)) ++j;
        prefix[i] = j;
    }
    result.reserve(static_cast<size_t>(std::min(limit, 256)));
    for (int32_t i = 0, j = 0; i < n; ++i) {
        while (j && fold(text[i], insensitive) != fold(pattern[j], insensitive)) j = prefix[j - 1];
        if (fold(text[i], insensitive) == fold(pattern[j], insensitive)) ++j;
        if (j == m) {
            result.push_back(i - m + 1);
            if (static_cast<int32_t>(result.size()) == limit) break;
            j = prefix[j - 1];
        }
    }
    return result;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_aster_ide_editor_NativeSearch_findNative(JNIEnv* env, jobject, jstring text,
                                                jstring pattern, jboolean insensitive, jint limit) {
    if (!text || !pattern) return env->NewIntArray(0);
    const jchar* t = env->GetStringChars(text, nullptr);
    if (!t) return nullptr;
    const jchar* p = env->GetStringChars(pattern, nullptr);
    if (!p) { env->ReleaseStringChars(text, t); return nullptr; }
    auto result = find_matches(reinterpret_cast<const char16_t*>(t), env->GetStringLength(text),
                               reinterpret_cast<const char16_t*>(p), env->GetStringLength(pattern),
                               insensitive == JNI_TRUE, std::clamp(limit, 0, 10000));
    env->ReleaseStringChars(text, t);
    env->ReleaseStringChars(pattern, p);
    jintArray array = env->NewIntArray(static_cast<jsize>(result.size()));
    if (array && !result.empty()) env->SetIntArrayRegion(array, 0, static_cast<jsize>(result.size()), result.data());
    return array;
}
