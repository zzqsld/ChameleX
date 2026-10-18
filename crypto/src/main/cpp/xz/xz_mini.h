#ifndef XZ_MINI_H
#define XZ_MINI_H

#include <stdint.h>

/**
 * 单发 xz 解压（基于 xz-embedded，仅解码）。
 *
 * @param in      压缩数据
 * @param in_len  压缩数据长度
 * @param out     输出缓冲
 * @param out_cap 输出缓冲容量
 * @param out_len 实际解压长度（可为 NULL）
 * @return 0 成功，非 0 失败
 */
int xz_mini_decompress(const uint8_t *in, uint32_t in_len,
                       uint8_t *out, uint32_t out_cap, uint32_t *out_len);

#endif
