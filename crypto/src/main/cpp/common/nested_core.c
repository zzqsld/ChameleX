#include <stdlib.h>
#include <string.h>
#include "common.h"
#include "nested_util.h"
#include "nested_core.h"

uint64_t *nested_recover(uint32_t uid, uint32_t dist,
                         const NestedNonceIn *nonces, uint32_t count,
                         uint32_t *keyCount) {
    *keyCount = 0;
    NtpKs1 *pNK = NULL;
    uint32_t j = 0;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t nt1 = nonces[i].nt;
        uint32_t nt2 = nonces[i].nt_enc;
        uint8_t par_arr[3] = {0x00};
        if (nonces[i].par != 0) {
            for (uint32_t m = 0; m < 3; m++) {
                par_arr[m] = (nonces[i].par >> m) & 0x01;
            }
        }
        // Try to recover the keystream1
        uint32_t nttest = prng_successor(nt1, dist - 14);
        for (int32_t m = (int32_t)dist - 14; m <= (int32_t)dist + 14; m += 1) {
            uint32_t ks1 = nt2 ^ nttest;
            if (valid_nonce(nttest, nt2, ks1, par_arr)) {
                ++j;
                void *tmp = realloc(pNK, sizeof(NtpKs1) * j);
                if (tmp == NULL) {
                    free(pNK);
                    return NULL;
                }
                pNK = tmp;
                pNK[j - 1].ntp = nttest;
                pNK[j - 1].ks1 = ks1;
            }
            nttest = prng_successor(nttest, 1);
        }
    }

    if (j == 0) {
        return NULL;
    }
    uint64_t *keys = nested(pNK, j, uid, keyCount);
    free(pNK);
    return keys;
}

uint64_t *staticnested_recover(uint32_t uid, uint8_t target_type,
                               const uint32_t nts[][2], uint32_t count,
                               uint32_t *keyCount) {
    *keyCount = 0;
    NtpKs1 *pNK = NULL;
    uint32_t j = 0;
    uint32_t dist = 0;
    bool check_st_level_at_first_run = false;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t nt1 = nts[i][0];
        uint32_t nt2 = nts[i][1];

        // Which generation of static tag is detected.
        if (!check_st_level_at_first_run) {
            if (nt1 == 0x01200145) {
                dist = 160; // st gen1
            } else if (nt1 == 0x009080A2) {   // st gen2
                if (target_type == 0x61) {
                    dist = 161;
                } else if (target_type == 0x60) {
                    dist = 160;
                } else {
                    return NULL;
                }
            } else {
                return NULL;
            }
            check_st_level_at_first_run = true;
        }

        uint32_t nttest = prng_successor(nt1, dist);
        uint32_t ks1 = nt2 ^ nttest;
        ++j;
        dist += 160;

        void *tmp = realloc(pNK, sizeof(NtpKs1) * j);
        if (tmp == NULL) {
            free(pNK);
            return NULL;
        }
        pNK = tmp;
        pNK[j - 1].ntp = nttest;
        pNK[j - 1].ks1 = ks1;
    }

    if (j == 0) {
        return NULL;
    }
    uint64_t *keys = nested(pNK, j, uid, keyCount);
    free(pNK);
    return keys;
}
