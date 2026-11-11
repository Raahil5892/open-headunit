// ============================================================================
// File: app/src/main/cpp/hu_aap.cpp
// Project: andreknieriem/open-headunit (package com.andrerinas.openheadunit)
// Objective: Dual-Channel Android Auto Negotiation & Parallel Decoding
//   - Channel 2 (AA_CH_VID): Primary Infotainment Stream (DISPLAY_TYPE_MAIN = 0)
//   - Channel 8 / 14 (AA_CH_CLU): Native OEM Instrument Cluster Stream (DISPLAY_TYPE_CLUSTER = 1)
// ============================================================================

#define LOGTAG "hu_aap"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>

#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#define logd(...) __android_log_print(ANDROID_LOG_DEBUG, LOGTAG, __VA_ARGS__)
#define logi(...) __android_log_print(ANDROID_LOG_INFO,  LOGTAG, __VA_ARGS__)
#define logw(...) __android_log_print(ANDROID_LOG_WARN,  LOGTAG, __VA_ARGS__)
#define loge(...) __android_log_print(ANDROID_LOG_ERROR, LOGTAG, __VA_ARGS__)
#else
#define logd(...) do { fprintf(stdout, "[D/" LOGTAG "] " __VA_ARGS__); fprintf(stdout, "\n"); } while(0)
#define logi(...) do { fprintf(stdout, "[I/" LOGTAG "] " __VA_ARGS__); fprintf(stdout, "\n"); } while(0)
#define logw(...) do { fprintf(stdout, "[W/" LOGTAG "] " __VA_ARGS__); fprintf(stdout, "\n"); } while(0)
#define loge(...) do { fprintf(stderr, "[E/" LOGTAG "] " __VA_ARGS__); fprintf(stderr, "\n"); } while(0)
#endif

typedef uint8_t byte;

#define AA_CH_CTR 0
#define AA_CH_SEN 1
#define AA_CH_VID 2
#define AA_CH_TOU 3
#define AA_CH_AUD 4
#define AA_CH_AU1 5
#define AA_CH_AU2 6
#define AA_CH_MIC 7
#define AA_CH_CLU 8
#define AA_CH_MAX 8

#define DISPLAY_TYPE_MAIN      0
#define DISPLAY_TYPE_CLUSTER   1
#define DISPLAY_TYPE_AUXILIARY 2

#define VIDEO_RES_800x480   1
#define VIDEO_RES_1280x720  2
#define VIDEO_RES_1920x1080 3
#define VIDEO_FPS_30        1

#define DRIVING_STATUS_UNRESTRICTED 0

static int g_enable_cluster_display = 1;
static int g_cluster_resolution = VIDEO_RES_800x480;
static int g_night_mode_enabled = 0;

static byte g_vid_session_id = 0;
static byte g_clu_session_id = 0;

#define MAX_VIDEO_ASSY_SIZE (65536 * 16)
static byte assy_main[MAX_VIDEO_ASSY_SIZE] = {0};
static int  assy_main_size = 0;

static byte assy_clu[MAX_VIDEO_ASSY_SIZE] = {0};
static int  assy_clu_size = 0;

extern "C" {
    int hu_aap_enc_send(int chan, const byte *buf, int len);
    int hu_aap_tra_send(const byte *buf, int len, int tmo);
    void iaap_video_decode(const byte *buf, int len);
    void iaap_cluster_video_decode(const byte *buf, int len, int is_codec_config);
}

// Weak default symbols so libhur_aap_cluster.so links cleanly as a standalone shared library in NDK 29
extern "C" __attribute__((weak)) int hu_aap_enc_send(int chan, const byte *buf, int len) {
    (void)chan; (void)buf; (void)len;
    return 0;
}
extern "C" __attribute__((weak)) int hu_aap_tra_send(const byte *buf, int len, int tmo) {
    (void)buf; (void)tmo;
    return len;
}
extern "C" __attribute__((weak)) void iaap_video_decode(const byte *buf, int len) {
    (void)buf; (void)len;
}

const char *chan_get(int chan) {
    switch (chan) {
        case AA_CH_CTR: return "CTR";
        case AA_CH_SEN: return "SEN";
        case AA_CH_VID: return "VID";
        case AA_CH_TOU: return "TOU";
        case AA_CH_AUD: return "AUD";
        case AA_CH_AU1: return "AU1";
        case AA_CH_AU2: return "AU2";
        case AA_CH_MIC: return "MIC";
        case AA_CH_CLU: return "CLU";
        default:        return "UNK";
    }
}

int hu_aap_send_version_request() {
    byte vr_buf[] = {
        0x00, 0x03,
        0x00, 0x06,
        0x00, 0x01,
        0x00, 0x04,
        0x00, 0x03
    };
    return hu_aap_tra_send(vr_buf, sizeof(vr_buf), 1000);
}

int hu_aap_build_service_discovery_response(byte *out_buf, int max_len) {
    static const byte base_sd_buf[] = {
        0x00, 0x06,
        0x0A, 14, 0x08, AA_CH_SEN, 0x12, 10,
        0x0A, 2, 0x08, 12,
        0x0A, 2, 0x08, 9,
        0x0A, 2, 0x08, 1,
        0x0A, 25, 0x08, AA_CH_VID, 0x1A, 21,
        0x08, 3,
        0x22, 11, 0x08, VIDEO_RES_1280x720, 0x10, VIDEO_FPS_30, 0x18, 0, 0x20, 0, 0x28, 0xA0, 0x01,
        0x28, 1,
        0x30, 0,
        0x38, DISPLAY_TYPE_MAIN
    };
    if ((int)sizeof(base_sd_buf) > max_len) return -1;
    memcpy(out_buf, base_sd_buf, sizeof(base_sd_buf));
    int offset = (int)sizeof(base_sd_buf);

    if (g_enable_cluster_display) {
        byte res_enum = (g_cluster_resolution == VIDEO_RES_1280x720) ? VIDEO_RES_1280x720 : VIDEO_RES_800x480;
        const byte cluster_channel_descriptor[] = {
            0x0A, 25,
            0x08, AA_CH_CLU,
            0x1A, 21,
            0x08, 3,
            0x22, 11,
            0x08, res_enum,
            0x10, VIDEO_FPS_30,
            0x18, 0,
            0x20, 0,
            0x28, 0xA0, 0x01,
            0x28, 1,
            0x30, 1,
            0x38, DISPLAY_TYPE_CLUSTER
        };
        if (offset + (int)sizeof(cluster_channel_descriptor) > max_len) return -1;
        memcpy(out_buf + offset, cluster_channel_descriptor, sizeof(cluster_channel_descriptor));
        offset += (int)sizeof(cluster_channel_descriptor);
        logi("ServiceDiscoveryResponse: Injected Cluster AVChannel (chan=%d, display_type=%d, res=%d)",
             AA_CH_CLU, DISPLAY_TYPE_CLUSTER, res_enum);
    }
    return offset;
}

int hu_aap_send_sensor_batch() {
    byte sensor_notification[] = {
        0x80, 0x03,
        0x52, 0x02, 0x08, (byte)(g_night_mode_enabled ? 1 : 0),
        0x6A, 0x02, 0x08, DRIVING_STATUS_UNRESTRICTED
    };
    return hu_aap_enc_send(AA_CH_SEN, sensor_notification, sizeof(sensor_notification));
}

int hu_aap_send_video_focus(int chan, int projected) {
    byte focus_pkt[] = {
        0x80, 0x08,
        0x08, (byte)(projected ? 1 : 2),
        0x10, 0x01
    };
    return hu_aap_enc_send(chan, focus_pkt, sizeof(focus_pkt));
}

int hu_aap_send_video_ack(int chan) {
    byte session_id = (chan == AA_CH_CLU) ? g_clu_session_id : g_vid_session_id;
    byte ack_pkt[] = {
        0x80, 0x04,
        0x08, session_id,
        0x10, 0x01
    };
    return hu_aap_enc_send(chan, ack_pkt, sizeof(ack_pkt));
}

static void dispatch_decoded_video(int chan, const byte *h264_nal, int nal_len, int is_codec_config) {
    if (chan == AA_CH_CLU) {
        iaap_cluster_video_decode(h264_nal, nal_len, is_codec_config);
    } else {
        iaap_video_decode(h264_nal, nal_len);
    }
}

int iaap_video_process_channel(int chan, int msg_type, int flags, byte *buf, int len) {
    int ret = hu_aap_send_video_ack(chan);
    if (ret < 0) {
        loge("Failed to send AVMediaAckIndication on chan=%d (%s)", chan, chan_get(chan));
    }
    byte *assy_buf = (chan == AA_CH_CLU) ? assy_clu : assy_main;
    int  *assy_len = (chan == AA_CH_CLU) ? &assy_clu_size : &assy_main_size;

    if (flags == 11 && msg_type == 1 && len > 2) {
        dispatch_decoded_video(chan, &buf[2], len - 2, 1);
    } else if (flags == 11 && (msg_type == 0 || msg_type == 1) && len > 10) {
        dispatch_decoded_video(chan, &buf[10], len - 10, (msg_type == 1) ? 1 : 0);
    } else if (flags == 9 && (msg_type == 0 || msg_type == 1) && len > 10) {
        int chunk_len = len - 10;
        if (chunk_len <= MAX_VIDEO_ASSY_SIZE) {
            memcpy(assy_buf, &buf[10], chunk_len);
            *assy_len = chunk_len;
        }
    } else if (flags == 8) {
        if (*assy_len + len <= MAX_VIDEO_ASSY_SIZE) {
            memcpy(&assy_buf[*assy_len], buf, len);
            *assy_len += len;
        }
    } else if (flags == 10) {
        if (*assy_len + len <= MAX_VIDEO_ASSY_SIZE) {
            memcpy(&assy_buf[*assy_len], buf, len);
            *assy_len += len;
            dispatch_decoded_video(chan, assy_buf, *assy_len, 0);
        }
        *assy_len = 0;
    }
    return 0;
}

#ifdef __ANDROID__
static JavaVM *g_jvm = NULL;
static jclass g_cluster_server_cls = NULL;
static jmethodID g_on_cluster_nal_mid = NULL;

extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_video_ClusterVideoServer_nativeConfigureCluster(
        JNIEnv *env, jclass clazz, jboolean enableCluster, jint resolutionCode, jboolean nightMode) {
    (void)clazz;
    env->GetJavaVM(&g_jvm);
    g_enable_cluster_display = enableCluster ? 1 : 0;
    g_cluster_resolution = (resolutionCode == 2) ? VIDEO_RES_1280x720 : VIDEO_RES_800x480;
    g_night_mode_enabled = nightMode ? 1 : 0;

    jclass localCls = env->FindClass("com/andrerinas/openheadunit/decoder/video/ClusterVideoServer");
    if (localCls != NULL) {
        g_cluster_server_cls = (jclass)env->NewGlobalRef(localCls);
        g_on_cluster_nal_mid = env->GetStaticMethodID(
            g_cluster_server_cls, "onClusterH264FrameFromNative", "([BZ)V");
    }
}

extern "C" void iaap_cluster_video_decode(const byte *buf, int len, int is_codec_config) {
    if (g_jvm == NULL || g_cluster_server_cls == NULL || g_on_cluster_nal_mid == NULL || len <= 0) {
        return;
    }
    JNIEnv *env = NULL;
    bool attached = false;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, NULL) != JNI_OK) return;
        attached = true;
    }
    jbyteArray jnal = env->NewByteArray(len);
    if (jnal != NULL) {
        env->SetByteArrayRegion(jnal, 0, len, (const jbyte *)buf);
        env->CallStaticVoidMethod(
            g_cluster_server_cls, g_on_cluster_nal_mid, jnal, (jboolean)(is_codec_config ? JNI_TRUE : JNI_FALSE));
        env->DeleteLocalRef(jnal);
    }
    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}
#endif
