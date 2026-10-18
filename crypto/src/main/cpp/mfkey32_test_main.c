// mfkey32 on-device 自测：用官方 crypto1.py 正向生成的已知答案向量验证 C 移植。
// 构建：NDK clang --target=aarch64-linux-android -static（见阶段 6 报告）
#include <stdio.h>
#include <inttypes.h>
#include "common/mfkey32_core.h"

int main(void) {
    struct {
        uint32_t uid, nt0, nr0, ar0, nt1, nr1, ar1;
        uint64_t expect;
    } v[] = {
        // 向量由官方 crypto1.py 正向模拟生成，且经官方 mfkey32_is_reader_has_key 自证
        {0xDEADBEEFu, 0x01234567u, 0x2BCFE9CDu, 0x3B481254u,
         0x89ABCDEFu, 0x2FDBADF9u, 0x08BF913Cu, 0xA0A1A2A3A4A5ull},
        {0x11223344u, 0xA1B2C3D4u, 0x66D7AD51u, 0x67AB3173u,
         0x13579BDFu, 0xDB571A3Bu, 0xB4A57EA5u, 0xFFFFFFFFFFFFull},
    };
    int fails = 0;
    for (int i = 0; i < 2; i++) {
        uint64_t key = mfkey32v2_recover(v[i].uid, v[i].nt0, v[i].nr0, v[i].ar0,
                                         v[i].nt1, v[i].nr1, v[i].ar1);
        int ok = (key == v[i].expect);
        int vok = mfkey32_verify(v[i].uid, v[i].nt0, v[i].nr0, v[i].ar0, v[i].expect) &&
                  mfkey32_verify(v[i].uid, v[i].nt1, v[i].nr1, v[i].ar1, v[i].expect);
        printf("vector %d: recover=%012" PRIx64 " expect=%012" PRIx64 " [%s], verify [%s]\n",
               i, key, v[i].expect, ok ? "OK" : "FAIL", vok ? "OK" : "FAIL");
        if (!ok || !vok) fails++;
    }
    printf(fails ? "MFKEY32 TEST FAILED (%d)\n" : "MFKEY32 TEST PASSED\n", fails);
    return fails;
}
