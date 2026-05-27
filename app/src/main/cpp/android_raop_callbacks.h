#ifndef ANDROID_RAOP_CALLBACKS_H
#define ANDROID_RAOP_CALLBACKS_H

#include <jni.h>
#include "raop.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    JavaVM *jvm;
    jobject callback_obj;
    jmethodID on_video_data;
    jmethodID on_audio_data;
    jmethodID on_audio_format;
    jmethodID on_video_size;
    jmethodID on_volume_change;
    jmethodID on_conn_init;
    jmethodID on_conn_destroy;
    jmethodID on_conn_reset;
    jmethodID on_display_pin;
    jmethodID on_metadata;
    jmethodID on_coverart;
    jmethodID on_progress;
    jmethodID on_dacp_id;
    jmethodID on_audio_only;
    /* AirPlay video (HLS) playback callbacks */
    jmethodID on_video_play;
    jmethodID on_video_scrub;
    jmethodID on_video_rate;
    jmethodID on_video_stop;
    jmethodID on_video_playback_info;
    jmethodID on_video_playlist_remove;
    jmethodID on_photo;
    int h265_enabled;
    int require_pin;
    char *registered_keys[16];
    int registered_count;
} android_callback_ctx_t;

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj);
void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env);
void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx);

#ifdef __cplusplus
}
#endif

#endif
