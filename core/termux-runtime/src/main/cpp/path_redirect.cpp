#include "redirect_arguments.h"
#include <jni.h>

static std::string string(JNIEnv* env, jstring value) {
    const char* data = env->GetStringUTFChars(value, nullptr);
    if (!data) return {};
    std::string result(data);
    env->ReleaseStringUTFChars(value, data);
    return result;
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_aster_termux_PathRedirect_arguments(JNIEnv* env, jobject, jstring proot, jstring root,
                                             jstring cwd, jobjectArray command) {
    std::vector<std::string> args;
    for (jsize i = 0; i < env->GetArrayLength(command); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(command, i));
        args.push_back(string(env, value));
        env->DeleteLocalRef(value);
    }
    auto result = mapped_arguments(string(env, proot), string(env, root), string(env, cwd), args);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(result.size()), stringClass, nullptr);
    if (!array) return nullptr;
    for (size_t i = 0; i < result.size(); ++i) {
        jstring value = env->NewStringUTF(result[i].c_str());
        if (!value) return nullptr;
        env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return array;
}