#!/usr/bin/env bash
set -euo pipefail

readonly STARTER_REVISION=45bad117eac610ce2efcd5f74112d13ff202b28f
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT
readonly HOST="${1:-${ROOT}/probe/host}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

command -v php >/dev/null
command -v composer >/dev/null
test -d "${ROOT}/probe/host-overlay/local-llm-maven" || {
    printf 'Build the Android AAR first: probe/android/build-llama-aar.sh\n' >&2
    exit 1
}

rm -rf "${HOST}"
git clone https://github.com/NativePHP/mobile-starter.git "${HOST}"
git -C "${HOST}" checkout --detach "${STARTER_REVISION}"
rm -rf "${HOST}/.git"
cp -R "${ROOT}/probe/host-overlay/app" "${HOST}/"
cp -R "${ROOT}/probe/host-overlay/resources" "${HOST}/"

composer --working-dir="${HOST}" config repositories.local-llm path "${ROOT}/probe/plugin"
composer --working-dir="${HOST}" require \
    nativephp/mobile:4.5.1 \
    firstlightui/local-llm-feasibility:@dev \
    --with-all-dependencies

cp "${HOST}/.env.example" "${HOST}/.env"
sed 's/^NATIVEPHP_APP_ID=$/NATIVEPHP_APP_ID=dev.firstlightui.localllm.probe/' \
    "${HOST}/.env" > "${HOST}/.env.local-llm"
mv "${HOST}/.env.local-llm" "${HOST}/.env"
printf '\nNATIVEPHP_ANDROID_MIN_SDK=29\n' >> "${HOST}/.env"
php "${HOST}/artisan" key:generate
php "${HOST}/artisan" native:install --force
cp -R "${ROOT}/probe/host-overlay/local-llm-maven" "${HOST}/nativephp/android/"

ios_project="${HOST}/nativephp/ios/NativePHP.xcodeproj/project.pbxproj"
if [[ -f "${ios_project}" ]]; then
    python3 "${ROOT}/probe/pin-ios-packages.py" "${ios_project}"
else
    printf 'iOS project was not generated on this host; run this script on macOS to prepare iOS.\n'
fi

printf 'Prepared host at %s\n' "${HOST}"
