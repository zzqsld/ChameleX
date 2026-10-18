#include <string.h>
#include "xz.h"
#include "xz_mini.h"

int xz_mini_decompress(const uint8_t *in, uint32_t in_len,
                       uint8_t *out, uint32_t out_cap, uint32_t *out_len) {
    struct xz_buf buf;
    struct xz_dec *dec;
    enum xz_ret ret;

    dec = xz_dec_init(XZ_DYNALLOC, 1 << 26);
    if (dec == NULL) {
        return -1;
    }

    buf.in = in;
    buf.in_pos = 0;
    buf.in_size = in_len;
    buf.out = out;
    buf.out_pos = 0;
    buf.out_size = out_cap;

    ret = xz_dec_run(dec, &buf);
    xz_dec_end(dec);

    if (ret != XZ_STREAM_END) {
        return -2;
    }
    if (out_len != NULL) {
        *out_len = (uint32_t)buf.out_pos;
    }
    return 0;
}
