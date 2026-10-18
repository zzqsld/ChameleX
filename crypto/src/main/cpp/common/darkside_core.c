#include <stdlib.h>
#include <string.h>

#include "parity.h"
#include "crapto1.h"
#include "mfkey.h"
#include "common.h"
#include "darkside_core.h"

uint32_t darkside_recover(uint32_t uid, const DarksideParam *dps, uint32_t count,
                          uint64_t **keys_out) {
    *keys_out = NULL;
    uint32_t outCount = 0;
    uint32_t capacity = 0;
    uint64_t *out = NULL;

    uint64_t *keylist = NULL, *last_keylist = NULL;

    for (uint32_t i = 0; i < count; i++) {
        uint32_t keycount = nonce2key(uid, dps[i].nt, dps[i].nr, dps[i].ar,
                                      dps[i].par_list, dps[i].ks_list, &keylist);
        if (keycount == 0) {
            continue;
        }

        // only parity zero attack
        if (dps[i].par_list == 0) {
            qsort(keylist, keycount, sizeof(*keylist), compare_uint64);
            keycount = intersection(last_keylist, keylist);
            if (keycount == 0) {
                free(last_keylist);
                last_keylist = keylist;
                keylist = NULL;
                continue;
            }
        }
        if (keycount > 0) {
            const uint64_t *src = (dps[i].par_list == 0) ? last_keylist : keylist;
            if (outCount + keycount > capacity) {
                capacity = (outCount + keycount) * 2;
                void *tmp = realloc(out, capacity * sizeof(uint64_t));
                if (tmp == NULL) {
                    free(out);
                    free(last_keylist);
                    free(keylist);
                    return 0;
                }
                out = tmp;
            }
            memcpy(out + outCount, src, keycount * sizeof(uint64_t));
            outCount += keycount;
            // 当轮 keylist 已拷贝完成（par==0 时交集结果在 last_keylist 中保留继续累积）
            free(keylist);
            keylist = NULL;
        }
    }

    free(last_keylist);
    free(keylist);

    *keys_out = out;
    return outCount;
}
