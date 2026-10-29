#!/usr/bin/env bash
set -euo pipefail

readonly LLAMA_REVISION=e613ef2c81bae98d59850d061ac29e6e3e88cb00
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT
readonly WORK="${ROOT}/.build/llama.cpp"
readonly OUTPUT="${ROOT}/probe/host-overlay/local-llm-maven"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

rm -rf "${WORK}" "${OUTPUT}"
mkdir -p "$(dirname "${WORK}")" "${OUTPUT}"
git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "${WORK}"
git -C "${WORK}" checkout --detach "${LLAMA_REVISION}"
git -C "${WORK}" apply "${ROOT}/probe/android/llama-b11056-probe.patch"

(
    cd "${WORK}/examples/llama.android"
    ./gradlew --no-daemon :lib:publishReleasePublicationToProbeRepository
)

cp -R "${WORK}/examples/llama.android/lib/build/probe-maven/." "${OUTPUT}/"
printf 'Published pinned llama.cpp probe AAR to %s\n' "${OUTPUT}"
