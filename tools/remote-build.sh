#!/bin/sh
# Optional development-only build host; not used by the Android app.
set -eu
cd "$(dirname "$0")/.."
: "${MAGICPHONE_BUILD_HOST:?Set MAGICPHONE_BUILD_HOST to your SSH build host}"
BUILD_HOST=$MAGICPHONE_BUILD_HOST
BUILD_PATH=magicphone-build
rsync -az --exclude .git --exclude INSTRUCTIONS.md --exclude .gradle --exclude build --exclude artifacts --exclude local.properties ./ "$BUILD_HOST:$BUILD_PATH/"
ssh "$BUILD_HOST" 'cd ~/magicphone-build && export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" && ./gradlew :core:test :app:assembleDebug :app:assembleRelease :app:lint :fixture:assembleDebug --console=plain && /usr/bin/python3 tools/release-check.py && /usr/bin/python3 tools/dependency-inventory.py'
mkdir -p artifacts
rsync -az "$BUILD_HOST:$BUILD_PATH/app/build/outputs/" artifacts/
rsync -az "$BUILD_HOST:$BUILD_PATH/core/build/reports/" artifacts/core-reports/
rsync -az "$BUILD_HOST:$BUILD_PATH/fixture/build/outputs/apk/debug/" artifacts/fixture/
rsync -az "$BUILD_HOST:$BUILD_PATH/app/build/reports/" artifacts/app-reports/
rsync -az "$BUILD_HOST:$BUILD_PATH/docs/dependency-inventory.json" docs/
