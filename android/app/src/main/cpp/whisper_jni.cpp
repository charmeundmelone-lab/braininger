// Brücke zwischen Kotlin (de.braininger.app.erkennung.Whisper) und whisper.cpp.
// Einstellungen wie im Test vom 20.10.2026: large-v3-turbo q8_0, 4 Threads, Silero-Stillefilter,
// kein Rückgriff auf den vorigen Text (gegen Wiederholungsschleifen).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "BrainingerWhisper"

static std::atomic<int> g_fortschritt{0};
static std::atomic<bool> g_abbrechen{false};

static void json_text(std::string &aus, const char *s) {
    aus += '"';
    for (const unsigned char *p = (const unsigned char *) s; *p; ++p) {
        switch (*p) {
            case '"': aus += "\\\""; break;
            case '\\': aus += "\\\\"; break;
            case '\n': aus += "\\n"; break;
            case '\r': aus += "\\r"; break;
            case '\t': aus += "\\t"; break;
            default:
                if (*p < 0x20) {
                    char b[8];
                    snprintf(b, sizeof b, "\\u%04x", *p);
                    aus += b;
                } else {
                    aus += (char) *p;
                }
        }
    }
    aus += '"';
}

static void bei_fortschritt(whisper_context *, whisper_state *, int prozent, void *) {
    g_fortschritt.store(prozent);
}

static bool soll_abbrechen(void *) {
    return g_abbrechen.load();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_braininger_app_erkennung_Whisper_erkennenNativ(JNIEnv *env, jobject, jstring jmodell, jstring jvad,
                                                       jfloatArray jpcm, jint threads, jstring jsprache,
                                                       jstring jhinweis) {
    g_fortschritt.store(0);
    g_abbrechen.store(false);
    const char *modell = env->GetStringUTFChars(jmodell, nullptr);
    const char *vad = env->GetStringUTFChars(jvad, nullptr);
    const char *sprache = env->GetStringUTFChars(jsprache, nullptr);
    const char *hinweis = env->GetStringUTFChars(jhinweis, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // GPU (Vulkan, Adreno 810) war im Test unbrauchbar langsam
    whisper_context *ctx = whisper_init_from_file_with_params(modell, cparams);
    std::string aus;
    if (ctx == nullptr) {
        aus = "{\"fehler\":\"Modell ließ sich nicht laden\"}";
    } else {
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.n_threads = threads;
        p.language = sprache;
        p.translate = false;
        p.no_context = true;
        p.n_max_text_ctx = 0;
        p.print_progress = false;
        p.print_realtime = false;
        p.print_timestamps = false;
        p.suppress_nst = true;
        p.progress_callback = bei_fortschritt;
        p.abort_callback = soll_abbrechen;
        p.vad = true;
        p.vad_model_path = vad;
        p.vad_params = whisper_vad_default_params();

        // Wörterbuch als Vorwissen (22.10.2026). whisper.cpp v1.9.5 nimmt den Prompt nur mit n_max_text_ctx > 0
        // (src/whisper.cpp, Zeile 7285) und füllt sonst mit bereits erkanntem Text auf (Zeile 7301). Darum genau so
        // viel Platz wie das Wörterbuch braucht: dann bleibt es bei jedem Abschnitt gleich, ohne Text-Rückkopplung.
        std::vector<whisper_token> hinweis_tokens(256);
        if (hinweis[0] != '\0') {
            int n = whisper_tokenize(ctx, hinweis, hinweis_tokens.data(), (int) hinweis_tokens.size());
            if (n > 0) {
                p.initial_prompt = hinweis;
                p.carry_initial_prompt = true;
                p.n_max_text_ctx = n + 1;
            }
        }

        jsize n = env->GetArrayLength(jpcm);
        std::vector<float> pcm(n);
        env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

        int rc = whisper_full(ctx, p, pcm.data(), (int) pcm.size());
        if (g_abbrechen.load()) {
            aus = "{\"abgebrochen\":true}";
        } else if (rc != 0) {
            aus = "{\"fehler\":\"whisper_full meldet " + std::to_string(rc) + "\"}";
        } else {
            aus = "{\"sprache\":";
            json_text(aus, whisper_lang_str(whisper_full_lang_id(ctx)));
            aus += ",\"abschnitte\":[";
            const int segmente = whisper_full_n_segments(ctx);
            for (int i = 0; i < segmente; ++i) {
                if (i > 0) aus += ',';
                char zahl[64];
                snprintf(zahl, sizeof zahl, "{\"start\":%.2f,\"ende\":%.2f,\"text\":",
                         whisper_full_get_segment_t0(ctx, i) / 100.0, whisper_full_get_segment_t1(ctx, i) / 100.0);
                aus += zahl;
                json_text(aus, whisper_full_get_segment_text(ctx, i));
                aus += '}';
            }
            aus += "]}";
        }
        whisper_free(ctx);
    }
    env->ReleaseStringUTFChars(jmodell, modell);
    env->ReleaseStringUTFChars(jvad, vad);
    env->ReleaseStringUTFChars(jsprache, sprache);
    env->ReleaseStringUTFChars(jhinweis, hinweis);
    // als UTF-8-Bytes zurück: NewStringUTF versteht nur „modified UTF-8“ und scheitert an manchen Zeichen
    jbyteArray bytes = env->NewByteArray((jsize) aus.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) aus.size(), (const jbyte *) aus.data());
    return bytes;
}

extern "C" JNIEXPORT jint JNICALL
Java_de_braininger_app_erkennung_Whisper_fortschrittNativ(JNIEnv *, jobject) {
    return g_fortschritt.load();
}

extern "C" JNIEXPORT void JNICALL
Java_de_braininger_app_erkennung_Whisper_abbrechenNativ(JNIEnv *, jobject) {
    g_abbrechen.store(true);
}
