// ChameleX JNI 桥接：hardnested（独立 .so）
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include "hardnested/cmdhfmfhard.h"
#include "xz/xz_mini.h"
#include "hardnested/hardnested/tables.h"

#define LOG_TAG "ChameleX-HN-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- hardnested 进度/取消钩子（符号在 hardnested 侧定义） ----
extern void (*chamelex_hn_progress_cb)(uint32_t nonces, const char *activity, float brute_force);
extern volatile int chamelex_hn_cancel_flag;

static char g_progress_activity[96];
static uint32_t g_progress_nonces;
static float g_progress_brute_force;

static void hn_progress_hook(uint32_t nonces, const char *activity, float brute_force) {
    g_progress_nonces = nonces;
    g_progress_brute_force = brute_force;
    strncpy(g_progress_activity, activity ? activity : "", sizeof(g_progress_activity) - 1);
    g_progress_activity[sizeof(g_progress_activity) - 1] = 0;
}

// ---------------------------------------------------------------- hardnested

JNIEXPORT jlong JNICALL
Java_com_chamelex_crypto_NativeCrypto_hardnestedRecover(
        JNIEnv *env, jobject thiz, jlong uid, jint blockTarget, jint keyTypeTarget,
        jstring noncesText, jstring cacheDir) {
    (void)thiz;
    // 写 nonce 文本文件（mfnestedhard 按 "%u|%u" 逐行读取）
    const char *dir = (*env)->GetStringUTFChars(env, cacheDir, NULL);
    const char *text = (*env)->GetStringUTFChars(env, noncesText, NULL);
    char path[512];
    snprintf(path, sizeof(path), "%s/hn_nonces.txt", dir);

    FILE *fp = fopen(path, "w");
    if (fp == NULL) {
        LOGE("cannot write nonce file %s", path);
        (*env)->ReleaseStringUTFChars(env, cacheDir, dir);
        (*env)->ReleaseStringUTFChars(env, noncesText, text);
        return -1;
    }
    fputs(text, fp);
    fclose(fp);
    (*env)->ReleaseStringUTFChars(env, noncesText, text);

    g_progress_nonces = 0;
    g_progress_brute_force = 0;
    g_progress_activity[0] = 0;
    chamelex_hn_progress_cb = hn_progress_hook;
    chamelex_hn_cancel_flag = 0;

    uint64_t foundkey = 0;
    LOGI("hardnested start uid=%08lx block=%d keytype=%d", (unsigned long)uid, blockTarget, keyTypeTarget);
    int result = mfnestedhard(
            (uint8_t)blockTarget, (uint8_t)keyTypeTarget,
            NULL, 0, 0, NULL,
            false, false, false,
            &foundkey, NULL, (uint32_t)uid, path);
    LOGI("hardnested done result=%d", result);

    chamelex_hn_progress_cb = 0;
    remove(path);
    (*env)->ReleaseStringUTFChars(env, cacheDir, dir);

    if (result == 1) {
        return (jlong)foundkey;
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_chamelex_crypto_NativeCrypto_hardnestedCancel(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    chamelex_hn_cancel_flag = 1;
}

JNIEXPORT jstring JNICALL
Java_com_chamelex_crypto_NativeCrypto_hardnestedProgress(JNIEnv *env, jobject thiz) {
    (void)thiz;
    char buf[160];
    snprintf(buf, sizeof(buf), "%u|%.0f|%s", g_progress_nonces, (double)g_progress_brute_force, g_progress_activity);
    return (*env)->NewStringUTF(env, buf);
}


// ---------------------------------------------------------------- 自检

JNIEXPORT jint JNICALL
Java_com_chamelex_crypto_NativeCrypto_hardnestedSelfTest(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // xz 解压自检：解压 0x001 偶表，count 应在合理范围（< 1<<24）
    bitflip_info p = get_bitflip(EVEN_STATE, 0x001);
    if (p.input_buffer == NULL) {
        LOGE("selftest: bitflip table missing");
        return 1;
    }
    uint32_t out_len = 0;
    uint32_t out_cap = sizeof(uint32_t) + sizeof(uint32_t) * (1 << 19);
    uint8_t *buf = malloc(out_cap);
    if (buf == NULL) return 2;
    if (xz_mini_decompress(p.input_buffer, p.len, buf, out_cap, &out_len) != 0 || out_len < 4) {
        LOGE("selftest: xz decompress failed");
        free(buf);
        return 3;
    }
    uint32_t count = 0;
    memcpy(&count, buf, sizeof(count));
    free(buf);
    if (count == 0 || count >= (1u << 24)) {
        LOGE("selftest: insane count %u", count);
        return 4;
    }
    LOGI("selftest ok, bitflip 0x001 count=%u out_len=%u", count, out_len);
    return 0;
}
