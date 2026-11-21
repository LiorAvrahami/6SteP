#!/bin/bash
# The build that GitHub runs (check.yml on a working branch, build.yml on main): the shared engine's tests, the signed
# release APK and the web front, in one Gradle run.
#
# The package sites (the Gradle plugin portal, Maven Central) now and then answer "not found" for ordinary packages
# (2026-11-21: twice in one day; the same build passed when it was simply run again). When that is what failed, the
# build is run again, up to three times in all. Any other failure stops at once.
set -o pipefail
for attempt in 1 2 3; do
  if gradle :shared:jvmTest assembleRelease :web:jsBrowserDistribution --no-daemon --stacktrace 2>&1 | tee build.log; then exit 0; fi
  if [ "$attempt" = 3 ] || ! grep -q "Could not resolve all artifacts\|Could not resolve all files\|Could not resolve all dependencies\|Could not GET \|Could not HEAD " build.log; then exit 1; fi
  echo "::warning::The package sites did not deliver (attempt $attempt of 3): the build is run again in 30 s"
  sleep ${CI_BUILD_PAUSE:-30}
done
