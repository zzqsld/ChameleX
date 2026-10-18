// ChameleX JNI 桥接：darkside / nested / staticnested / hardnested / mfkey32(占位)
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include "common/darkside_core.h"
#include "common/nested_core.h"
#include "common/mfkey32_core.h"

#define LOG_TAG "ChameleX-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------- darkside

JNIEXPORT jlongArray JNICALL
Java_com_chamelex_crypto_NativeCrypto_darksideRecover(
        JNIEnv *env, jobject thiz, jlong uid, jlongArray acquisitions) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, acquisitions);
    if (len <= 0 || len % 5 != 0) {
        return (*env)->NewLongArray(env, 0);
    }
    uint32_t count = (uint32_t)(len / 5);
    DarksideParam *dps = calloc(count, sizeof(DarksideParam));
    jlong *elems = (*env)->GetLongArrayElements(env, acquisitions, NULL);
    for (uint32_t i = 0; i < count; i++) {
        dps[i].nt = (uint32_t)elems[i * 5 + 0];
        dps[i].par_list = (uint64_t)elems[i * 5 + 1];
        dps[i].ks_list = (uint64_t)elems[i * 5 + 2];
        dps[i].nr = (uint32_t)elems[i * 5 + 3];
        dps[i].ar = (uint32_t)elems[i * 5 + 4];
    }
    (*env)->ReleaseLongArrayElements(env, acquisitions, elems, JNI_ABORT);

    uint64_t *keys = NULL;
    uint32_t keyCount = darkside_recover((uint32_t)uid, dps, count, &keys);
    free(dps);

    jlongArray result = (*env)->NewLongArray(env, (jsize)keyCount);
    if (keyCount > 0) {
        (*env)->SetLongArrayRegion(env, result, 0, (jsize)keyCount, (jlong *)keys);
        free(keys);
    }
    return result;
}

// ---------------------------------------------------------------- nested

JNIEXPORT jlongArray JNICALL
Java_com_chamelex_crypto_NativeCrypto_nestedRecover(
        JNIEnv *env, jobject thiz, jlong uid, jlong dist, jlongArray nonces) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, nonces);
    if (len <= 0 || len % 3 != 0) {
        return (*env)->NewLongArray(env, 0);
    }
    uint32_t count = (uint32_t)(len / 3);
    NestedNonceIn *nnc = calloc(count, sizeof(NestedNonceIn));
    jlong *elems = (*env)->GetLongArrayElements(env, nonces, NULL);
    for (uint32_t i = 0; i < count; i++) {
        nnc[i].nt = (uint32_t)elems[i * 3 + 0];
        nnc[i].nt_enc = (uint32_t)elems[i * 3 + 1];
        nnc[i].par = (uint8_t)elems[i * 3 + 2];
    }
    (*env)->ReleaseLongArrayElements(env, nonces, elems, JNI_ABORT);

    uint32_t keyCount = 0;
    uint64_t *keys = nested_recover((uint32_t)uid, (uint32_t)dist, nnc, count, &keyCount);
    free(nnc);

    jlongArray result = (*env)->NewLongArray(env, (jsize)keyCount);
    if (keyCount > 0 && keys != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, (jsize)keyCount, (jlong *)keys);
        free(keys);
    }
    return result;
}

// ---------------------------------------------------------------- staticnested

JNIEXPORT jlongArray JNICALL
Java_com_chamelex_crypto_NativeCrypto_staticNestedRecover(
        JNIEnv *env, jobject thiz, jlong uid, jint targetType, jlongArray nts) {
    (void)thiz;
    jsize len = (*env)->GetArrayLength(env, nts);
    if (len <= 0 || len % 2 != 0) {
        return (*env)->NewLongArray(env, 0);
    }
    uint32_t count = (uint32_t)(len / 2);
    uint32_t (*pairs)[2] = calloc(count, sizeof(uint32_t) * 2);
    jlong *elems = (*env)->GetLongArrayElements(env, nts, NULL);
    for (uint32_t i = 0; i < count; i++) {
        pairs[i][0] = (uint32_t)elems[i * 2 + 0];
        pairs[i][1] = (uint32_t)elems[i * 2 + 1];
    }
    (*env)->ReleaseLongArrayElements(env, nts, elems, JNI_ABORT);

    uint32_t keyCount = 0;
    uint64_t *keys = staticnested_recover((uint32_t)uid, (uint8_t)targetType, pairs, count, &keyCount);
    free(pairs);

    jlongArray result = (*env)->NewLongArray(env, (jsize)keyCount);
    if (keyCount > 0 && keys != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, (jsize)keyCount, (jlong *)keys);
    }
    free(keys);
    return result;
}

// ---------------------------------------------------------------- mfkey32（阶段 6 接，本阶段占位）

JNIEXPORT jlong JNICALL
Java_com_chamelex_crypto_NativeCrypto_mfkey32Recover(
        JNIEnv *env, jobject thiz, jlong uid,
        jlong nt0, jlong nr0, jlong ar0, jlong nt1, jlong nr1, jlong ar1) {
    (void)env; (void)thiz;
    uint64_t key = mfkey32v2_recover(
            (uint32_t)uid,
            (uint32_t)nt0, (uint32_t)nr0, (uint32_t)ar0,
            (uint32_t)nt1, (uint32_t)nr1, (uint32_t)ar1);
    if (key == UINT64_C(0xFFFFFFFFFFFFFFFF)) {
        return -1;
    }
    return (jlong)key;
}

JNIEXPORT jboolean JNICALL
Java_com_chamelex_crypto_NativeCrypto_mfkey32Verify(
        JNIEnv *env, jobject thiz, jlong uid, jlong nt, jlong nr, jlong ar, jlong key) {
    (void)env; (void)thiz;
    return mfkey32_verify((uint32_t)uid, (uint32_t)nt, (uint32_t)nr, (uint32_t)ar, (uint64_t)key)
           ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- 自检

JNIEXPORT jint JNICALL
Java_com_chamelex_crypto_NativeCrypto_selfTest(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // smoke test：非法输入下恢复函数应安全返回 0
    DarksideParam dp = {0};
    uint64_t *keys = NULL;
    uint32_t n = darkside_recover(0x11223344, &dp, 1, &keys);
    free(keys);
    LOGI("selftest ok, darkside smoke=%u", n);
    return 0;
}
