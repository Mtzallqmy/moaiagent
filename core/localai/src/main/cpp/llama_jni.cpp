#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include "llama.h"

namespace {
constexpr const char * TAG = "AgentDroidLlama";

struct NativeSession {
    llama_model * model = nullptr;
    uint32_t context_size = 4096;
    int threads = 4;
    std::atomic<bool> stop{false};
    std::mutex generation_mutex;
};

std::once_flag backend_once;

void ensure_backend() {
    std::call_once(backend_once, [] { llama_backend_init(); });
}

std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string json_escape(const std::string & value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char * hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0xf] << hex[c & 0xf];
                } else out << static_cast<char>(c);
        }
    }
    return out.str();
}

std::string model_meta(llama_model * model, const char * key) {
    const int32_t required = llama_model_meta_val_str(model, key, nullptr, 0);
    if (required <= 0) return {};
    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    if (llama_model_meta_val_str(model, key, buffer.data(), buffer.size()) < 0) return {};
    return std::string(buffer.data());
}

std::string model_description(llama_model * model) {
    std::vector<char> buffer(1024);
    int32_t written = llama_model_desc(model, buffer.data(), buffer.size());
    if (written < 0) return {};
    if (static_cast<size_t>(written) >= buffer.size()) {
        buffer.resize(static_cast<size_t>(written) + 1);
        written = llama_model_desc(model, buffer.data(), buffer.size());
    }
    return written >= 0 ? std::string(buffer.data()) : std::string();
}

size_t valid_utf8_prefix(const std::string & input) {
    size_t i = 0;
    while (i < input.size()) {
        const unsigned char c = static_cast<unsigned char>(input[i]);
        size_t needed = 0;
        if (c < 0x80) needed = 1;
        else if ((c & 0xE0) == 0xC0) needed = 2;
        else if ((c & 0xF0) == 0xE0) needed = 3;
        else if ((c & 0xF8) == 0xF0) needed = 4;
        else return i + 1;
        if (i + needed > input.size()) break;
        bool valid = true;
        for (size_t j = 1; j < needed; ++j) {
            if ((static_cast<unsigned char>(input[i + j]) & 0xC0) != 0x80) { valid = false; break; }
        }
        if (!valid) return i + 1;
        i += needed;
    }
    return i;
}

void emit_token(JNIEnv * env, jobject callback, jmethodID method, const std::string & text) {
    if (text.empty()) return;
    jstring token = env->NewStringUTF(text.c_str());
    if (!token) return;
    env->CallVoidMethod(callback, method, token);
    env->DeleteLocalRef(token);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

bool should_abort(void * data) {
    return static_cast<NativeSession *>(data)->stop.load(std::memory_order_relaxed);
}

void throw_state(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_agentdroid_core_localai_LlamaNative_version(JNIEnv * env, jobject) {
    ensure_backend();
    return env->NewStringUTF(llama_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_agentdroid_core_localai_LlamaNative_inspect(JNIEnv * env, jobject, jstring path_value) {
    ensure_backend();
    const std::string path = jstring_to_utf8(env, path_value);
    llama_model_params params = llama_model_default_params();
    params.vocab_only = true;
    params.no_alloc = true;
    params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (!model) {
        throw_state(env, "llama.cpp could not read GGUF metadata");
        return nullptr;
    }
    const std::string architecture = model_meta(model, "general.architecture");
    const std::string description = model_description(model);
    const std::string quantization = llama_ftype_name(llama_model_ftype(model));
    std::ostringstream out;
    out << "{\"architecture\":\"" << json_escape(architecture) << "\","
        << "\"description\":\"" << json_escape(description) << "\","
        << "\"contextSize\":" << llama_model_n_ctx_train(model) << ","
        << "\"parameterCount\":" << llama_model_n_params(model) << ","
        << "\"tensorBytes\":" << llama_model_size(model) << ","
        << "\"quantization\":\"" << json_escape(quantization) << "\"}";
    llama_model_free(model);
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_agentdroid_core_localai_LlamaNative_open(JNIEnv * env, jobject, jstring path_value, jint context_size, jint threads) {
    ensure_backend();
    const std::string path = jstring_to_utf8(env, path_value);
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.check_tensors = true;
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (!model) {
        throw_state(env, "llama.cpp failed to load the GGUF model");
        return 0;
    }
    auto * session = new NativeSession();
    session->model = model;
    session->context_size = static_cast<uint32_t>(std::max(256, context_size));
    session->threads = std::max(1, threads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentdroid_core_localai_LlamaNative_generate(
        JNIEnv * env, jobject, jlong handle, jstring prompt_value, jfloat temperature, jint max_tokens, jobject callback) {
    auto * session = reinterpret_cast<NativeSession *>(handle);
    if (!session || !session->model) {
        throw_state(env, "Local model session is closed");
        return;
    }
    std::lock_guard<std::mutex> guard(session->generation_mutex);
    session->stop.store(false, std::memory_order_relaxed);
    const std::string prompt = jstring_to_utf8(env, prompt_value);
    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    int n_prompt = -llama_tokenize(vocab, prompt.c_str(), prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) {
        throw_state(env, "Could not tokenize local model prompt");
        return;
    }
    if (static_cast<uint64_t>(n_prompt) + static_cast<uint64_t>(max_tokens) > session->context_size) {
        throw_state(env, "Prompt and requested output exceed the configured local model context size");
        return;
    }
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt.c_str(), prompt.size(), prompt_tokens.data(), prompt_tokens.size(), true, true) < 0) {
        throw_state(env, "Could not tokenize local model prompt");
        return;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = session->context_size;
    ctx_params.n_batch = std::min<uint32_t>(session->context_size, std::max(32, n_prompt));
    ctx_params.n_threads = session->threads;
    ctx_params.n_threads_batch = session->threads;
    ctx_params.abort_callback = should_abort;
    ctx_params.abort_callback_data = session;
    llama_context * ctx = llama_init_from_model(session->model, ctx_params);
    if (!ctx) {
        throw_state(env, "Could not allocate llama.cpp context; the device may not have enough memory");
        return;
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    if (temperature <= 0.001f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
    if (llama_decode(ctx, batch) != 0) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        throw_state(env, "llama.cpp failed while evaluating the prompt");
        return;
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = callback_class ? env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (!on_token) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        throw_state(env, "Local token callback is unavailable");
        return;
    }

    std::string pending_utf8;
    for (int generated = 0; generated < max_tokens && !session->stop.load(std::memory_order_relaxed); ++generated) {
        llama_token token = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(128);
        int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        if (n < 0) {
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, true);
        }
        if (n > 0) {
            pending_utf8.append(piece.data(), static_cast<size_t>(n));
            const size_t prefix = valid_utf8_prefix(pending_utf8);
            if (prefix > 0) {
                emit_token(env, callback, on_token, pending_utf8.substr(0, prefix));
                pending_utf8.erase(0, prefix);
            }
        }
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }
    if (!pending_utf8.empty()) emit_token(env, callback, on_token, pending_utf8);
    llama_sampler_free(sampler);
    llama_free(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentdroid_core_localai_LlamaNative_stop(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<NativeSession *>(handle);
    if (session) session->stop.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_agentdroid_core_localai_LlamaNative_close(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<NativeSession *>(handle);
    if (!session) return;
    session->stop.store(true, std::memory_order_relaxed);
    std::lock_guard<std::mutex> guard(session->generation_mutex);
    if (session->model) llama_model_free(session->model);
    session->model = nullptr;
    delete session;
}
