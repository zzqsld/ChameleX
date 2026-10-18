#ifndef NESTED_CORE_H
#define NESTED_CORE_H

#include <stdint.h>

typedef struct {
    uint32_t nt;
    uint32_t nt_enc;
    uint8_t par;
} NestedNonceIn;

/**
 * Nested（弱随机卡）密钥恢复（移植自官方 nested.c main 逻辑）。
 *
 * @return 候选密钥数组（调用方负责 free），*keyCount 为数量（0=失败）
 */
uint64_t *nested_recover(uint32_t uid, uint32_t dist,
                         const NestedNonceIn *nonces, uint32_t count,
                         uint32_t *keyCount);

/**
 * StaticNested（固定随机数卡）密钥恢复（移植自官方 staticnested.c main 逻辑）。
 *
 * @param target_type 目标密钥类型（0x60=A / 0x61=B）
 * @param nts         nt/nt_enc 对数组 [count][2]
 * @return 候选密钥数组（调用方负责 free），*keyCount 为数量（0=失败）；
 *         返回 NULL 且 *keyCount=0 表示卡型不支持（nt 不在已知静态表）
 */
uint64_t *staticnested_recover(uint32_t uid, uint8_t target_type,
                               const uint32_t nts[][2], uint32_t count,
                               uint32_t *keyCount);

#endif
