#ifndef MFKEY32_CORE_H
#define MFKEY32_CORE_H

#include <stdint.h>
#include <stdbool.h>

/**
 * MFKey32（v2，Moebius 双 nonce 方案）密钥反推。
 * 移植自官方 mfkey32v2.c main 逻辑（去掉 CLI/printf）。
 *
 * @param uid  模拟卡 UID
 * @param nt0/nr0_enc/ar0_enc  第一次认证：tag challenge / 加密的 reader challenge / 加密的 reader response
 * @param nt1/nr1_enc/ar1_enc  第二次认证（不同 nonce）
 * @return 反推出的 48 位密钥；未找到返回 UINT64_C(-1)
 */
uint64_t mfkey32v2_recover(uint32_t uid,
                           uint32_t nt0, uint32_t nr0_enc, uint32_t ar0_enc,
                           uint32_t nt1, uint32_t nr1_enc, uint32_t ar1_enc);

/**
 * 候选密钥验证（对应官方 crypto1.py 的 mfkey32_is_reader_has_key）：
 * 用密钥模拟一次认证，检查 reader response 是否等于 prng_successor(nt, 64)。
 */
bool mfkey32_verify(uint32_t uid, uint32_t nt, uint32_t nr_enc, uint32_t ar_enc, uint64_t key);

#endif
