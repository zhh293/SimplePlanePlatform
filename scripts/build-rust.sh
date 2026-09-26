#!/usr/bin/env bash
#
# build-rust.sh —— 用 cargo-ndk 把 plane-core 交叉编译为 Android 各 ABI 的
# libplane_core.so，并输出到 android-app 的 jniLibs 目录，供 Gradle 打包进 APK。
#
# 用法：
#   scripts/build-rust.sh [debug|release]   # 默认 debug
#
# 依赖（首次需安装）：
#   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   cargo install cargo-ndk
#   并安装 Android NDK r26+（通过 ANDROID_NDK_HOME 或 sdkmanager）。
#
# 见 docs/design/android-client-tasks.md Task A1。

set -euo pipefail

# 脚本所在目录的上一级 = 仓库根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROFILE="${1:-debug}"
CRATE_DIR="${REPO_ROOT}/plane-core"
OUT_DIR="${REPO_ROOT}/android-app/app/src/main/jniLibs"

# 目标 ABI 列表（与任务文档 0.2 一致）：
#   arm64-v8a    -> aarch64-linux-android      （主力，真机）
#   armeabi-v7a  -> armv7-linux-androideabi    （老设备）
#   x86_64       -> x86_64-linux-android       （模拟器）
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")

# 确保 cargo-ndk 在 PATH 中。优先用 HOME 定位（MSYS2 下 HOME = /c/Users/...）。
_cargo_bin=""
for _dir in "${HOME}/.cargo/bin" "${CARGO_HOME:-}/bin"; do
    if [ -n "${_dir}" ] && [ -x "${_dir}/cargo" ] && [ -x "${_dir}/cargo-ndk" ]; then
        _cargo_bin="${_dir}"
        break
    fi
done
if [ -n "${_cargo_bin}" ]; then
    export PATH="${_cargo_bin}:${PATH}"
fi
if ! command -v cargo >/dev/null 2>&1; then
    echo "[build-rust] ERROR: 未找到 cargo。请先安装 Rust: https://rustup.rs" >&2
    exit 1
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
    echo "[build-rust] ERROR: 未找到 cargo-ndk。请先执行：cargo install cargo-ndk" >&2
    exit 1
fi

BUILD_FLAGS=()
if [[ "${PROFILE}" == "release" ]]; then
    BUILD_FLAGS+=("--release")
elif [[ "${PROFILE}" != "debug" ]]; then
    echo "[build-rust] ERROR: 未知 profile '${PROFILE}'，仅支持 debug|release" >&2
    exit 1
fi

echo "[build-rust] crate     = ${CRATE_DIR}"
echo "[build-rust] profile   = ${PROFILE}"
echo "[build-rust] output    = ${OUT_DIR}"
echo "[build-rust] abis      = ${ABIS[*]}"

mkdir -p "${OUT_DIR}"

# cargo-ndk 的 -t 接收 Android ABI 名称（arm64-v8a 等），-o 指定 jniLibs 根目录，
# 它会自动按 ABI 建子目录并放置对应的 .so。
(
    cd "${CRATE_DIR}"
    cargo ndk \
        -t "${ABIS[0]}" \
        -t "${ABIS[1]}" \
        -t "${ABIS[2]}" \
        -o "${OUT_DIR}" \
        build "${BUILD_FLAGS[@]+"${BUILD_FLAGS[@]}"}"
)

echo "[build-rust] done. 产物:"
find "${OUT_DIR}" -name 'libplane_core.so' -print
