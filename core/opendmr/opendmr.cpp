/*
 * OpenDMR - Open Source DMR (AMBE+2) Vocoder Library
 *
 * Implementation file
 */

#include "opendmr.h"
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <new>

/* mbelib-neo for decoding */
extern "C" {
#include "mbelib.h"
}

/* MBEEncoder for encoding */
#include "mbeenc.h"

/*
 * ============================================================================
 * Version
 * ============================================================================
 */

#define OPENDMR_VERSION_MAJOR   1
#define OPENDMR_VERSION_MINOR   0
#define OPENDMR_VERSION_PATCH   0

static const char *version_string = "1.0.0";

/*
 * ============================================================================
 * DMR AMBE+2 Interleave & FEC Constants (ETSI TS 102 361-1 Annex B)
 * ============================================================================
 */

/* ETSI TS 102 361-1 Annex B interleave schedule (36 dibits per AMBE frame) */
static const uint8_t R_W[36] = {
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 1,
    0, 1, 0, 1, 0, 2,
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 2, 0, 2
};

static const uint8_t R_X[36] = {
    23, 10, 22, 9, 21, 8,
    20, 7, 19, 6, 18, 5,
    17, 4, 16, 3, 15, 2,
    14, 1, 13, 0, 12, 10,
    11, 9, 10, 8, 9, 7,
    8, 6, 7, 5, 6, 4
};

static const uint8_t R_Y[36] = {
    0, 2, 0, 2, 0, 2,
    0, 2, 0, 3, 0, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3,
    1, 3, 1, 3, 1, 3
};

static const uint8_t R_Z[36] = {
    5, 3, 4, 2, 3, 1,
    2, 0, 1, 13, 0, 12,
    22, 11, 21, 10, 20, 9,
    19, 8, 18, 7, 17, 6,
    16, 5, 15, 4, 14, 3,
    13, 2, 12, 1, 11, 0
};

/* DMR AMBE Golay (23,12) generator polynomial matrix (identical to mbelib golayGenerator) */
static const int dmr_golay_gen[12] = {
    0x63a, 0x31d, 0x7b4, 0x3da, 0x1ed, 0x6cc, 0x366, 0x1b3, 0x6e3, 0x54b, 0x49f, 0x475
};

static uint32_t dmr_encode_golay2312(uint32_t d)
{
    uint32_t parity = 0;
    for (int i = 0; i < 12; i++) {
        if ((d >> (11 - i)) & 1)
            parity ^= dmr_golay_gen[i];
    }
    return (d << 11) | parity;
}

/*
 * Converts 49-bit voice parameters into standard DMR char ambe_fr[4][24].
 * Applies Golay(23,12) to C0, PRNG scrambling + Golay(23,12) to C1, and copies C2/C3.
 */
static void encode_ambe_fr(const char ambe_d[49], char ambe_fr[4][24])
{
    memset(ambe_fr, 0, 96);

    /* C0: bits 0..11 */
    uint32_t c0 = 0;
    for (int i = 0; i < 12; i++)
        c0 = (c0 << 1) | (ambe_d[i] & 1);
    uint32_t cw0 = dmr_encode_golay2312(c0);

    /* C1: bits 12..23 */
    uint32_t c1 = 0;
    for (int i = 0; i < 12; i++)
        c1 = (c1 << 1) | (ambe_d[12 + i] & 1);
    uint32_t cw1 = dmr_encode_golay2312(c1);

    /* PRNG mask generated from C0 */
    uint16_t pr[24];
    pr[0] = static_cast<uint16_t>(16U * c0);
    for (int i = 1; i < 24; i++) {
        pr[i] = static_cast<uint16_t>((173U * static_cast<uint32_t>(pr[i - 1]) + 13849U) % 65536U);
    }
    for (int i = 1; i < 24; i++) {
        pr[i] = pr[i] / 32768U;
    }

    /* ambe_fr[0]: j=0 parity, j=1..23 is cw0 (bit 0..22) */
    char parity0 = 0;
    for (int j = 0; j < 23; j++) {
        char b = (cw0 >> j) & 1;
        parity0 ^= b;
        ambe_fr[0][j + 1] = b;
    }
    ambe_fr[0][0] = parity0;

    /* ambe_fr[1]: j=0..22 is cw1 ^ pr[k] for j=22 down to 0, k=1..23 */
    int k = 1;
    for (int j = 22; j >= 0; j--) {
        char b = (cw1 >> j) & 1;
        ambe_fr[1][j] = b ^ static_cast<char>(pr[k]);
        k++;
    }

    /* ambe_fr[2]: j=10 down to 0 is ambe_d[24..34] */
    for (int j = 0; j < 11; j++)
        ambe_fr[2][10 - j] = ambe_d[24 + j] & 1;

    /* ambe_fr[3]: j=13 down to 0 is ambe_d[35..48] */
    for (int j = 0; j < 14; j++)
        ambe_fr[3][13 - j] = ambe_d[35 + j] & 1;
}

/*
 * Converts 72-bit canonical frame (9 bytes) to char ambe_fr[4][24].
 */
static void canonical_to_fr(const uint8_t *frame72, char ambe_fr[4][24])
{
    memset(ambe_fr, 0, 96);
    auto bit = [frame72](int c) -> char {
        return (frame72[c / 8] >> (7 - (c % 8))) & 1;
    };
    for (int c = 0; c < 24; c++)
        ambe_fr[0][23 - c] = bit(c);
    for (int c = 24; c < 47; c++)
        ambe_fr[1][46 - c] = bit(c);
    for (int c = 47; c < 58; c++)
        ambe_fr[2][57 - c] = bit(c);
    for (int c = 58; c < 72; c++)
        ambe_fr[3][71 - c] = bit(c);
}

/*
 * Converts char ambe_fr[4][24] to 72-bit canonical frame (9 bytes).
 */
static void fr_to_canonical(const char ambe_fr[4][24], uint8_t *frame72)
{
    memset(frame72, 0, 9);
    for (int c = 0; c < 24; c++) {
        if (ambe_fr[0][23 - c])
            frame72[c / 8] |= (1 << (7 - (c % 8)));
    }
    for (int c = 24; c < 47; c++) {
        if (ambe_fr[1][46 - c])
            frame72[c / 8] |= (1 << (7 - (c % 8)));
    }
    for (int c = 47; c < 58; c++) {
        if (ambe_fr[2][57 - c])
            frame72[c / 8] |= (1 << (7 - (c % 8)));
    }
    for (int c = 58; c < 72; c++) {
        if (ambe_fr[3][71 - c])
            frame72[c / 8] |= (1 << (7 - (c % 8)));
    }
}

/*
 * ============================================================================
 * Decoder Implementation
 * ============================================================================
 */

struct opendmr_decoder {
    mbe_parms cur_mp;
    mbe_parms prev_mp;
    mbe_parms prev_mp_enhanced;
};

opendmr_decoder_t *opendmr_decoder_create(void)
{
    opendmr_decoder_t *dec = static_cast<opendmr_decoder_t *>(calloc(1, sizeof(opendmr_decoder_t)));
    if (dec) {
        mbe_initMbeParms(&dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced);
    }
    return dec;
}

void opendmr_decoder_destroy(opendmr_decoder_t *dec)
{
    free(dec);
}

void opendmr_decoder_reset(opendmr_decoder_t *dec)
{
    if (dec) {
        mbe_initMbeParms(&dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced);
    }
}

bool opendmr_decode(opendmr_decoder_t *dec,
                    const uint8_t ambe[OPENDMR_AMBE_FRAME_BYTES],
                    int16_t pcm[OPENDMR_PCM_SAMPLES],
                    int *errs)
{
    if (!dec || !ambe || !pcm)
        return false;

    char ambe_fr[4][24];
    canonical_to_fr(ambe, ambe_fr);

    int err1 = 0, err2 = 0;
    char err_str[64] = {0};
    char ambe_d[49] = {0};

    mbe_processAmbe3600x2450Frame(pcm, &err1, &err2, err_str, ambe_fr, ambe_d,
                                  &dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced, 3);

    if (errs)
        *errs = err1 + err2;

    return true;
}

bool opendmr_decode_burst(opendmr_decoder_t *dec,
                          const uint8_t burst[OPENDMR_BURST_BYTES],
                          int16_t pcm[OPENDMR_BURST_SAMPLES],
                          int *errs)
{
    if (!dec || !burst || !pcm)
        return false;

    /* Extract 132 dibits */
    uint8_t dibits[132];
    for (int i = 0; i < 33; i++) {
        uint8_t b = burst[i];
        dibits[i * 4 + 0] = (b >> 6) & 3;
        dibits[i * 4 + 1] = (b >> 4) & 3;
        dibits[i * 4 + 2] = (b >> 2) & 3;
        dibits[i * 4 + 3] = b & 3;
    }

    char fr1[4][24] = {0};
    char fr2[4][24] = {0};
    char fr3[4][24] = {0};
    int idx = 0;

    /* Frame 1: 36 dibits */
    for (int i = 0; i < 36; i++) {
        uint8_t d = dibits[idx++];
        fr1[R_W[i]][R_X[i]] = (d >> 1) & 1;
        fr1[R_Y[i]][R_Z[i]] = d & 1;
    }

    /* Frame 2 first half: 18 dibits */
    for (int i = 0; i < 18; i++) {
        uint8_t d = dibits[idx++];
        fr2[R_W[i]][R_X[i]] = (d >> 1) & 1;
        fr2[R_Y[i]][R_Z[i]] = d & 1;
    }

    /* Skip 24 dibits (48 bits) of sync / EMB */
    idx += 24;

    /* Frame 2 second half: 18 dibits */
    for (int i = 18; i < 36; i++) {
        uint8_t d = dibits[idx++];
        fr2[R_W[i]][R_X[i]] = (d >> 1) & 1;
        fr2[R_Y[i]][R_Z[i]] = d & 1;
    }

    /* Frame 3: 36 dibits */
    for (int i = 0; i < 36; i++) {
        uint8_t d = dibits[idx++];
        fr3[R_W[i]][R_X[i]] = (d >> 1) & 1;
        fr3[R_Y[i]][R_Z[i]] = d & 1;
    }

    int total_errs = 0;
    int err1 = 0, err2 = 0, err3 = 0, err_dummy = 0;
    char err_str[64] = {0};
    char ambe_d[49] = {0};

    mbe_processAmbe3600x2450Frame(&pcm[0], &err1, &err_dummy, err_str, fr1, ambe_d,
                                  &dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced, 3);
    total_errs += err1;

    mbe_processAmbe3600x2450Frame(&pcm[160], &err2, &err_dummy, err_str, fr2, ambe_d,
                                  &dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced, 3);
    total_errs += err2;

    mbe_processAmbe3600x2450Frame(&pcm[320], &err3, &err_dummy, err_str, fr3, ambe_d,
                                  &dec->cur_mp, &dec->prev_mp, &dec->prev_mp_enhanced, 3);
    total_errs += err3;

    if (errs)
        *errs = total_errs;

    return true;
}

/*
 * ============================================================================
 * Encoder Implementation
 * ============================================================================
 */

struct opendmr_encoder {
    MBEEncoder *enc;
    int gain_db;
};

opendmr_encoder_t *opendmr_encoder_create(void)
{
    opendmr_encoder_t *enc = static_cast<opendmr_encoder_t *>(calloc(1, sizeof(opendmr_encoder_t)));
    if (enc) {
        enc->enc = new (std::nothrow) MBEEncoder();
        if (!enc->enc) {
            free(enc);
            return nullptr;
        }
        enc->enc->set_dmr_mode();
        enc->enc->set_gain_adjust(1.0f);
        enc->gain_db = 0;
    }
    return enc;
}

void opendmr_encoder_destroy(opendmr_encoder_t *enc)
{
    if (enc) {
        delete enc->enc;
        free(enc);
    }
}

void opendmr_encoder_reset(opendmr_encoder_t *enc)
{
    if (enc && enc->enc) {
        delete enc->enc;
        enc->enc = new (std::nothrow) MBEEncoder();
        if (!enc->enc) {
            return;
        }
        enc->enc->set_dmr_mode();
        enc->enc->set_gain_adjust(powf(10.0f, enc->gain_db / 20.0f));
    }
}

void opendmr_encoder_set_gain(opendmr_encoder_t *enc, int gain_db)
{
    if (enc && enc->enc) {
        if (gain_db < -20) gain_db = -20;
        if (gain_db > 20) gain_db = 20;
        enc->gain_db = gain_db;
        enc->enc->set_gain_adjust(powf(10.0f, gain_db / 20.0f));
    }
}

static void encode_single_pcm_to_fr(opendmr_encoder_t *enc, const int16_t pcm[160], char ambe_fr[4][24])
{
    int b[9] = {0};
    enc->enc->encode_dmr_params(pcm, b);

    static const int b_lengths[9] = {7, 5, 5, 9, 7, 5, 4, 4, 3};
    char ambe_d[49];
    int pos = 0;
    for (int i = 0; i < 9; i++) {
        int val = b[i];
        for (int j = b_lengths[i] - 1; j >= 0; j--) {
            ambe_d[pos++] = (val >> j) & 1;
        }
    }

    encode_ambe_fr(ambe_d, ambe_fr);
}

bool opendmr_encode(opendmr_encoder_t *enc,
                    const int16_t pcm[OPENDMR_PCM_SAMPLES],
                    uint8_t ambe[OPENDMR_AMBE_FRAME_BYTES])
{
    if (!enc || !enc->enc || !pcm || !ambe)
        return false;

    char ambe_fr[4][24];
    encode_single_pcm_to_fr(enc, pcm, ambe_fr);
    fr_to_canonical(ambe_fr, ambe);

    return true;
}

bool opendmr_encode_burst(opendmr_encoder_t *enc,
                          const int16_t pcm[OPENDMR_BURST_SAMPLES],
                          uint8_t burst[OPENDMR_BURST_BYTES],
                          const uint8_t center_dibits[OPENDMR_CENTER_DIBITS])
{
    if (!enc || !enc->enc || !pcm || !burst)
        return false;

    char fr1[4][24];
    char fr2[4][24];
    char fr3[4][24];

    encode_single_pcm_to_fr(enc, &pcm[0], fr1);
    encode_single_pcm_to_fr(enc, &pcm[160], fr2);
    encode_single_pcm_to_fr(enc, &pcm[320], fr3);

    uint8_t dibits[132] = {0};
    int idx = 0;

    /* Frame 1: 36 dibits */
    for (int i = 0; i < 36; i++) {
        dibits[idx++] = (fr1[R_W[i]][R_X[i]] << 1) | (fr1[R_Y[i]][R_Z[i]] & 1);
    }

    /* Frame 2 first half: 18 dibits */
    for (int i = 0; i < 18; i++) {
        dibits[idx++] = (fr2[R_W[i]][R_X[i]] << 1) | (fr2[R_Y[i]][R_Z[i]] & 1);
    }

    /* 24 center dibits (sync / EMB) */
    if (center_dibits) {
        for (int i = 0; i < 24; i++) {
            dibits[idx++] = center_dibits[i] & 3;
        }
    } else {
        idx += 24;
    }

    /* Frame 2 second half: 18 dibits */
    for (int i = 18; i < 36; i++) {
        dibits[idx++] = (fr2[R_W[i]][R_X[i]] << 1) | (fr2[R_Y[i]][R_Z[i]] & 1);
    }

    /* Frame 3: 36 dibits */
    for (int i = 0; i < 36; i++) {
        dibits[idx++] = (fr3[R_W[i]][R_X[i]] << 1) | (fr3[R_Y[i]][R_Z[i]] & 1);
    }

    /* Pack 132 dibits into 33 bytes */
    memset(burst, 0, 33);
    for (int i = 0; i < 33; i++) {
        burst[i] = ((dibits[i * 4 + 0] & 3) << 6) |
                   ((dibits[i * 4 + 1] & 3) << 4) |
                   ((dibits[i * 4 + 2] & 3) << 2) |
                   (dibits[i * 4 + 3] & 3);
    }

    return true;
}

/*
 * ============================================================================
 * Utility Functions
 * ============================================================================
 */

const char *opendmr_version(void)
{
    return version_string;
}

void opendmr_convert_frame(uint8_t bytes[OPENDMR_AMBE_FRAME_BYTES],
                           uint8_t bits[OPENDMR_AMBE_FRAME_BITS],
                           bool to_bits)
{
    if (to_bits) {
        for (int i = 0; i < 72; i++) {
            int byte_idx = i / 8;
            int bit_pos = 7 - (i % 8);
            bits[i] = (bytes[byte_idx] >> bit_pos) & 1;
        }
    } else {
        memset(bytes, 0, 9);
        for (int i = 0; i < 72; i++) {
            int byte_idx = i / 8;
            int bit_pos = 7 - (i % 8);
            if (bits[i])
                bytes[byte_idx] |= (1 << bit_pos);
        }
    }
}
