// ChameleX: ui.c 的 Android shim —— PrintAndLogEx 输出到 logcat，去掉 readline 等桌面依赖。
#include "ui.h"
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <android/log.h>

void PrintAndLogEx(logLevel_t level, const char *fmt, ...) {
    (void)level;
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    __android_log_write(ANDROID_LOG_INFO, "ChameleX-HN", buffer);
}

void memcpy_filter_ansi(void *dest, const void *src, size_t n, bool filter) {
    (void)filter;
    memcpy(dest, src, n);
}

void memcpy_filter_emoji(void *dest, const void *src, size_t n, emojiMode_t mode) {
    (void)mode;
    memcpy(dest, src, n);
}
