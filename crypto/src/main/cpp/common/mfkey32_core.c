#include <stdlib.h>
#include "crapto1.h"
#include "mfkey32_core.h"

uint64_t mfkey32v2_recover(uint32_t uid,
                           uint32_t nt0, uint32_t nr0_enc, uint32_t ar0_enc,
                           uint32_t nt1, uint32_t nr1_enc, uint32_t ar1_enc) {
    // Generate lfsr successors of the tag challenge
    uint32_t p64 = prng_successor(nt0, 64);
    uint32_t p64b = prng_successor(nt1, 64);

    // Extract the keystream from the messages
    struct Crypto1State *s = lfsr_recovery32(ar0_enc ^ p64, 0);
    uint64_t found = UINT64_C(0xFFFFFFFFFFFFFFFF);
    if (s == NULL) {
        return found;
    }
    for (struct Crypto1State *t = s; t->odd | t->even; ++t) {
        uint64_t key;
        lfsr_rollback_word(t, 0, 0);
        lfsr_rollback_word(t, nr0_enc, 1);
        lfsr_rollback_word(t, uid ^ nt0, 0);
        crypto1_get_lfsr(t, &key);

        crypto1_word(t, uid ^ nt1, 0);
        crypto1_word(t, nr1_enc, 1);
        if (ar1_enc == (crypto1_word(t, 0, 0) ^ p64b)) {
            found = key;
            break;
        }
    }
    free(s);
    return found;
}

bool mfkey32_verify(uint32_t uid, uint32_t nt, uint32_t nr_enc, uint32_t ar_enc, uint64_t key) {
    struct Crypto1State *s = crypto1_create(key);
    if (s == NULL) {
        return false;
    }
    crypto1_word(s, uid ^ nt, 0);   // ks0
    crypto1_word(s, nr_enc, 1);     // ks1
    uint32_t ks2 = crypto1_word(s, 0, 0);
    uint32_t ar = ar_enc ^ ks2;
    bool ok = (ar == prng_successor(nt, 64));
    crypto1_destroy(s);
    return ok;
}
