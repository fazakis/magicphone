#!/usr/bin/env bash
# Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
set -euo pipefail

# Keystores, passwords and the reviewed rotation lineage live outside this repo.
: "${MAGICPHONE_SIGNING_DIR:?Set the private signing directory}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
if [[ $# != 2 ]]; then
  echo "Usage: tools/sign-release.sh unsigned.apk signed.apk" >&2
  exit 2
fi
build_tools="${ANDROID_HOME}/build-tools/36.0.0"
for item in legacy-debug.keystore release.p12 release-password debug-to-release.lineage; do
  test -f "${MAGICPHONE_SIGNING_DIR}/${item}"
done
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
"${build_tools}/zipalign" -P 16 -f 4 "$1" "${work_dir}/aligned.apk"
"${build_tools}/apksigner" sign \
  --ks "${MAGICPHONE_SIGNING_DIR}/legacy-debug.keystore" --ks-pass pass:android \
  --next-signer --ks "${MAGICPHONE_SIGNING_DIR}/release.p12" \
  --ks-key-alias magicphone-release \
  --ks-pass "file:${MAGICPHONE_SIGNING_DIR}/release-password" \
  --lineage "${MAGICPHONE_SIGNING_DIR}/debug-to-release.lineage" \
  --rotation-min-sdk-version 28 --out "$2" "${work_dir}/aligned.apk"
"${build_tools}/apksigner" verify --verbose --print-certs --min-sdk-version 30 "$2"
"${build_tools}/zipalign" -c -P 16 4 "$2"
