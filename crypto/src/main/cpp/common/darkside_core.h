#ifndef DARKSIDE_CORE_H
#define DARKSIDE_CORE_H

#include <stdint.h>

typedef struct {
    uint32_t nt;
    uint32_t nr;
    uint32_t ar;
    uint64_t par_list;
    uint64_t ks_list;
} DarksideParam;

/**
 * Darkside 密钥恢复（移植自官方 darkside.c 的 main 逻辑，去掉 CLI/printf）。
 *
 * @param uid       卡 UID
 * @param dps       采集参数数组（CLI 的累积列表语义：par!=0 时由调用方清空后重传）
 * @param count     数组长度
 * @param keys_out  输出候选密钥数组（调用方负责 free）
 * @return 候选密钥数量（0 = 未恢复，调用方应继续采集后重试）
 */
uint32_t darkside_recover(uint32_t uid, const DarksideParam *dps, uint32_t count,
                          uint64_t **keys_out);

#endif
