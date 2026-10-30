cmake_minimum_required(VERSION 3.22.1)
project("opendmr" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 11)

set(OPENDMR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/OpenDMR")

include_directories(
    ${OPENDMR_ROOT}
    ${OPENDMR_ROOT}/decoder
    ${OPENDMR_ROOT}/encoder
)

set(OPENDMR_SRCS
    ${OPENDMR_ROOT}/opendmr.cpp
    ${OPENDMR_ROOT}/decoder/mbelib.c
    ${OPENDMR_ROOT}/decoder/mbe_adaptive.c
    ${OPENDMR_ROOT}/decoder/mbe_unvoiced_fft.c
    ${OPENDMR_ROOT}/decoder/ambe3600x2450.c
    ${OPENDMR_ROOT}/decoder/ambe_common.c
    ${OPENDMR_ROOT}/decoder/ecc.c
    ${OPENDMR_ROOT}/decoder/ecc_const.c
    ${OPENDMR_ROOT}/decoder/pffft.c
    ${OPENDMR_ROOT}/decoder/fftpack.c
    ${OPENDMR_ROOT}/encoder/cgolay24128.cpp
    ${OPENDMR_ROOT}/encoder/mbeenc.cpp
    ${OPENDMR_ROOT}/encoder/aux_sub.cc
    ${OPENDMR_ROOT}/encoder/basicop2.cc
    ${OPENDMR_ROOT}/encoder/ch_encode.cc
    ${OPENDMR_ROOT}/encoder/dc_rmv.cc
    ${OPENDMR_ROOT}/encoder/dsp_sub.cc
    ${OPENDMR_ROOT}/encoder/encode.cc
    ${OPENDMR_ROOT}/encoder/imbe_vocoder.cc
    ${OPENDMR_ROOT}/encoder/imbe_vocoder_impl.cc
    ${OPENDMR_ROOT}/encoder/math_sub.cc
    ${OPENDMR_ROOT}/encoder/pe_lpf.cc
    ${OPENDMR_ROOT}/encoder/pitch_est.cc
    ${OPENDMR_ROOT}/encoder/pitch_ref.cc
    ${OPENDMR_ROOT}/encoder/qnt_sub.cc
    ${OPENDMR_ROOT}/encoder/rand_gen.cc
    ${OPENDMR_ROOT}/encoder/sa_encode.cc
    ${OPENDMR_ROOT}/encoder/tbls.cc
    ${OPENDMR_ROOT}/encoder/v_uv_det.cc
)

add_library(opendmr SHARED ${OPENDMR_SRCS})
find_library(log-lib log)
target_link_libraries(opendmr m ${log-lib})
