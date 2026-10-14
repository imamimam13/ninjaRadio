#!/usr/bin/env bash
#
# Cross-compile the OpenDMR vocoder (libopendmr.so) for Android ABIs using the
# NDK's clang toolchain. Mirrors the sources/flags in third_party/OpenDMR/Makefile.
#
# Usage:
#   build_opendmr_android.sh [abi ...]
#
# With no arguments it builds all supported ABIs. Example:
#   build_opendmr_android.sh arm64-v8a
#
# Requires ANDROID_NDK_HOME (or ANDROID_NDK_ROOT, or a single NDK under
# $ANDROID_HOME/ndk) and an NDK containing a prebuilt llvm toolchain.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SRC_DIR="${APP_DIR}/third_party/OpenDMR"
JNI_DIR="${APP_DIR}/app/src/main/jniLibs"

API_LEVEL="${OPENDMR_API_LEVEL:-26}"

resolve_ndk() {
  if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
    echo "${ANDROID_NDK_HOME}"; return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "${ANDROID_NDK_ROOT}" ]]; then
    echo "${ANDROID_NDK_ROOT}"; return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    local latest
    latest="$(ls -1 "${ANDROID_HOME}/ndk" | sort -V | tail -n1)"
    if [[ -n "${latest}" ]]; then
      echo "${ANDROID_HOME}/ndk/${latest}"; return
    fi
  fi
  echo "ERROR: could not locate the Android NDK (set ANDROID_NDK_HOME)" >&2
  exit 1
}

NDK="$(resolve_ndk)"

case "$(uname -s)" in
  Linux)  HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
  *)      echo "ERROR: unsupported host: $(uname -s)" >&2; exit 1 ;;
esac

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "ERROR: toolchain not found at ${TOOLCHAIN}" >&2
  exit 1
fi
BIN="${TOOLCHAIN}/bin"

abi_to_triple() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi" ;;
    x86)         echo "i686-linux-android" ;;
    x86_64)      echo "x86_64-linux-android" ;;
    *) echo "ERROR: unsupported ABI: $1" >&2; exit 1 ;;
  esac
}

C_SRCS=(
  decoder/mbelib.c
  decoder/mbe_adaptive.c
  decoder/mbe_unvoiced_fft.c
  decoder/ambe3600x2450.c
  decoder/ambe_common.c
  decoder/ecc.c
  decoder/ecc_const.c
  decoder/pffft.c
  decoder/fftpack.c
)

CXX_SRCS=(
  opendmr.cpp
  encoder/cgolay24128.cpp
  encoder/mbeenc.cpp
  encoder/aux_sub.cc
  encoder/basicop2.cc
  encoder/ch_encode.cc
  encoder/dc_rmv.cc
  encoder/dsp_sub.cc
  encoder/encode.cc
  encoder/imbe_vocoder.cc
  encoder/imbe_vocoder_impl.cc
  encoder/math_sub.cc
  encoder/pe_lpf.cc
  encoder/pitch_est.cc
  encoder/pitch_ref.cc
  encoder/qnt_sub.cc
  encoder/rand_gen.cc
  encoder/sa_encode.cc
  encoder/tbls.cc
  encoder/v_uv_det.cc
)

build_abi() {
  local abi="$1"
  local triple
  triple="$(abi_to_triple "${abi}")"

  local cc="${BIN}/${triple}${API_LEVEL}-clang"
  local cxx="${BIN}/${triple}${API_LEVEL}-clang++"
  if [[ ! -x "${cc}" ]]; then
    # Some NDK versions name the 32-bit ARM compiler differently.
    cc="${BIN}/armv7a-linux-androideabi${API_LEVEL}-clang"
  fi

  local out_dir="${JNI_DIR}/${abi}"
  local obj_dir
  obj_dir="$(mktemp -d)"
  mkdir -p "${out_dir}"

  echo "==> Building libopendmr.so for ${abi} (API ${API_LEVEL})"
  local objs=()
  local src obj

  for src in "${C_SRCS[@]}"; do
    obj="${obj_dir}/$(echo "${src}" | tr '/' '_').o"
    "${cc}" -O3 -Wall -fPIC -Wno-unused-but-set-variable \
      -I"${SRC_DIR}" -I"${SRC_DIR}/decoder" -I"${SRC_DIR}/encoder" \
      -c "${SRC_DIR}/${src}" -o "${obj}"
    objs+=("${obj}")
  done

  for src in "${CXX_SRCS[@]}"; do
    obj="${obj_dir}/$(echo "${src}" | tr '/' '_').o"
    "${cxx}" -O3 -std=c++11 -Wall -fPIC \
      -I"${SRC_DIR}" -I"${SRC_DIR}/decoder" -I"${SRC_DIR}/encoder" \
      -c "${SRC_DIR}/${src}" -o "${obj}"
    objs+=("${obj}")
  done

  "${cxx}" -shared -Wl,-soname,libopendmr.so -o "${out_dir}/libopendmr.so" \
    "${objs[@]}" -lm

  rm -rf "${obj_dir}"
  echo "    -> ${out_dir}/libopendmr.so"
}

if [[ "$#" -eq 0 ]]; then
  ABIS=(arm64-v8a armeabi-v7a x86_64)
else
  ABIS=("$@")
fi

for abi in "${ABIS[@]}"; do
  build_abi "${abi}"
done

echo "Done."
