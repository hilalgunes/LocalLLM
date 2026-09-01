#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>

typedef jobject (*LlamaEmbeddingFn)(JNIEnv *, jobject, jlong, jstring);

static const char *RNLlamaLibraries[] = {
    "librnllama_v8_2_dotprod_i8mm.so",
    "librnllama_v8_2_dotprod.so",
    "librnllama_v8_2_i8mm.so",
    "librnllama_v8_2.so",
    "librnllama_v8.so",
    "librnllama_x86_64.so",
    "librnllama.so"
};

static void throw_runtime(JNIEnv *env, const char *message) {
    jclass clazz = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (clazz != NULL) {
        (*env)->ThrowNew(env, clazz, message);
    }
}

static LlamaEmbeddingFn resolve_embedding_fn(char *errorBuffer, size_t errorBufferSize) {
    const char *symbol = "Java_org_nehuatl_llamacpp_LlamaContext_embedding";

    dlerror();
    LlamaEmbeddingFn embeddingFn = (LlamaEmbeddingFn)dlsym(RTLD_DEFAULT, symbol);
    const char *defaultError = dlerror();
    if (embeddingFn != NULL && defaultError == NULL) {
        return embeddingFn;
    }

    for (size_t i = 0; i < sizeof(RNLlamaLibraries) / sizeof(RNLlamaLibraries[0]); ++i) {
        dlerror();
        void *handle = dlopen(RNLlamaLibraries[i], RTLD_NOW | RTLD_NOLOAD);
        const char *openError = dlerror();
        if (handle == NULL || openError != NULL) {
            continue;
        }

        dlerror();
        embeddingFn = (LlamaEmbeddingFn)dlsym(handle, symbol);
        const char *symbolError = dlerror();
        if (embeddingFn != NULL && symbolError == NULL) {
            return embeddingFn;
        }
    }

    snprintf(
        errorBuffer,
        errorBufferSize,
        "%s",
        defaultError != NULL ? defaultError : "symbol not found in loaded RNLlama libraries"
    );
    return NULL;
}

JNIEXPORT jobject JNICALL
Java_com_hilal_llm_rag_LlamaCppEmbeddingCompat_embedding(
    JNIEnv *env,
    jobject thiz,
    jobject llamaContext,
    jlong contextPtr,
    jstring text
) {
    (void)thiz;
    char errorBuffer[256] = {0};
    LlamaEmbeddingFn embeddingFn = resolve_embedding_fn(errorBuffer, sizeof(errorBuffer));
    if (embeddingFn == NULL) {
        char buffer[256];
        snprintf(
            buffer,
            sizeof(buffer),
            "llama.cpp embedding JNI function could not be resolved: %s",
            errorBuffer[0] != '\0' ? errorBuffer : "unknown error"
        );
        throw_runtime(env, buffer);
        return NULL;
    }

    return embeddingFn(env, llamaContext, contextPtr, text);
}
