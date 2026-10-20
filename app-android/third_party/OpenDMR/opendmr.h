/*
 * OpenDMR - Open Source DMR (AMBE+2) Vocoder Library
 *
 * A software implementation of the DMR AMBE+2 vocoder for encoding
 * and decoding digital voice. No proprietary hardware required.
 *
 * This library integrates vocoder implementations from:
 *   - mbelib-neo (decoder): arancormonk, based on mbelib by Pavel Yazev
 *   - OP25 MBEEncoder (encoder): Max H. Parke KA1RBI
 *
 * See decoder/CREDITS and encoder/CREDITS for full attribution.
 *
 * License: GNU General Public License v2.0 (GPL-2.0)
 */

#ifndef OPENDMR_H
#define OPENDMR_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * ============================================================================
 * Constants
 * ============================================================================
 */

/* Frame sizes */
#define OPENDMR_AMBE_FRAME_BYTES    9       /* 72 bits = 9 bytes */
#define OPENDMR_AMBE_FRAME_BITS     72      /* AMBE+2 frame size */
#define OPENDMR_PCM_SAMPLES         160     /* 20ms @ 8kHz sample rate */
#define OPENDMR_SAMPLE_RATE         8000    /* 8kHz audio */

/* Voice parameter sizes */
#define OPENDMR_VOICE_PARAMS        49      /* 49-bit voice parameters */

/*
 * ============================================================================
 * Opaque Types
 * ============================================================================
 */

/* Decoder state - opaque handle */
typedef struct opendmr_decoder opendmr_decoder_t;

/* Encoder state - opaque handle */
typedef struct opendmr_encoder opendmr_encoder_t;

/*
 * ============================================================================
 * Decoder API
 * ============================================================================
 */

/**
 * Create a new DMR decoder instance.
 *
 * @return Pointer to decoder, or NULL on failure.
 *         Must be freed with opendmr_decoder_destroy().
 */
opendmr_decoder_t *opendmr_decoder_create(void);

/**
 * Destroy a decoder instance and free resources.
 *
 * @param dec Decoder instance (may be NULL).
 */
void opendmr_decoder_destroy(opendmr_decoder_t *dec);

/**
 * Decode a DMR AMBE+2 frame to PCM audio.
 *
 * @param dec       Decoder instance.
 * @param ambe      Input AMBE+2 frame (9 bytes / 72 bits).
 * @param pcm       Output PCM buffer (160 samples, 16-bit signed).
 * @param errs      Optional: Number of corrected bit errors (may be NULL).
 *
 * @return true on success, false on failure.
 *
 * The decoder maintains state between frames for proper audio continuity.
 * For best results, decode frames in sequence without gaps.
 */
bool opendmr_decode(opendmr_decoder_t *dec,
                    const uint8_t ambe[OPENDMR_AMBE_FRAME_BYTES],
                    int16_t pcm[OPENDMR_PCM_SAMPLES],
                    int *errs);

/**
 * Reset decoder state (e.g., at start of new transmission).
 *
 * @param dec Decoder instance.
 */
void opendmr_decoder_reset(opendmr_decoder_t *dec);

/*
 * ============================================================================
 * Encoder API
 * ============================================================================
 */

/**
 * Create a new DMR encoder instance.
 *
 * @return Pointer to encoder, or NULL on failure.
 *         Must be freed with opendmr_encoder_destroy().
 */
opendmr_encoder_t *opendmr_encoder_create(void);

/**
 * Destroy an encoder instance and free resources.
 *
 * @param enc Encoder instance (may be NULL).
 */
void opendmr_encoder_destroy(opendmr_encoder_t *enc);

/**
 * Encode PCM audio to a DMR AMBE+2 frame.
 *
 * @param enc       Encoder instance.
 * @param pcm       Input PCM buffer (160 samples, 16-bit signed, 8kHz).
 * @param ambe      Output AMBE+2 frame (9 bytes / 72 bits).
 *
 * @return true on success, false on failure.
 *
 * The encoder maintains state between frames for proper voice analysis.
 * For best results, encode frames in sequence without gaps.
 */
bool opendmr_encode(opendmr_encoder_t *enc,
                    const int16_t pcm[OPENDMR_PCM_SAMPLES],
                    uint8_t ambe[OPENDMR_AMBE_FRAME_BYTES]);

/**
 * Reset encoder state (e.g., at start of new transmission).
 *
 * @param enc Encoder instance.
 */
void opendmr_encoder_reset(opendmr_encoder_t *enc);

/**
 * Set encoder gain adjustment in dB.
 *
 * @param enc       Encoder instance.
 * @param gain_db   Gain adjustment (-20 to +20 dB, default 0).
 *
 * Positive values increase output level, negative values decrease.
 */
void opendmr_encoder_set_gain(opendmr_encoder_t *enc, int gain_db);

/*
 * ============================================================================
 * Utility Functions
 * ============================================================================
 */

/**
 * Get library version string.
 *
 * @return Version string (e.g., "1.0.0").
 */
const char *opendmr_version(void);

/* Burst sizes */
#define OPENDMR_BURST_BYTES         33      /* 33 bytes = 264 bits = 132 dibits */
#define OPENDMR_BURST_SAMPLES       480     /* 60ms @ 8kHz (3 frames * 160 samples) */
#define OPENDMR_BURST_DIBITS        132     /* 132 dibits */
#define OPENDMR_CENTER_DIBITS       24      /* 24 center dibits (48 bits sync/EMB) */

/**
 * Decode a full 33-byte DMR burst (3 AMBE frames) to 480 samples of 8kHz PCM.
 * Handles ETSI TS 102 361-1 Annex B de-interleaving and mbelib voice synthesis.
 */
bool opendmr_decode_burst(opendmr_decoder_t *dec,
                          const uint8_t burst[OPENDMR_BURST_BYTES],
                          int16_t pcm[OPENDMR_BURST_SAMPLES],
                          int *errs);

/**
 * Encode 480 samples of 8kHz PCM to a full 33-byte DMR burst (3 AMBE frames).
 * Handles OP25 MBE speech analysis, DMR AMBE Golay(23,12) & PRNG scrambling FEC,
 * and ETSI TS 102 361-1 Annex B interleaving with center sync dibits.
 */
bool opendmr_encode_burst(opendmr_encoder_t *enc,
                          const int16_t pcm[OPENDMR_BURST_SAMPLES],
                          uint8_t burst[OPENDMR_BURST_BYTES],
                          const uint8_t center_dibits[OPENDMR_CENTER_DIBITS]);

/**
 * Convert AMBE+2 frame between byte array and bit array formats.
 *
 * @param bytes     Byte array (9 bytes, MSB first per byte).
 * @param bits      Bit array (72 bits, one bit per element).
 * @param to_bits   true = bytes->bits, false = bits->bytes.
 */
void opendmr_convert_frame(uint8_t bytes[OPENDMR_AMBE_FRAME_BYTES],
                           uint8_t bits[OPENDMR_AMBE_FRAME_BITS],
                           bool to_bits);

#ifdef __cplusplus
}
#endif

#endif /* OPENDMR_H */
