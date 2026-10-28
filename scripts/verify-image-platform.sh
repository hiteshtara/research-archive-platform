#!/usr/bin/env bash
#
# Refuse to publish a container image that cannot run on DEV/TEST/PROD Fargate.
#
# WHY THIS EXISTS. On 2026-10-28 the loader and API images were built on an
# Apple Silicon Mac without --platform, producing arm64-only images. Both were
# pushed, a task definition was registered against one, and the failure only
# surfaced when ECS tried to start the task:
#
#   CannotPullContainerError: image Manifest does not contain descriptor
#   matching platform 'linux/amd64'
#
# Everything that was checked before publishing - migrations present, classes
# present, enforcement default, no secrets - was CONTENT verification, and
# content was never the problem. The one property that determines whether the
# image can run at all was never checked. This script checks it.
#
# Usage:
#   scripts/verify-image-platform.sh <image-ref> [<image-ref> ...]
#
# Exit 0 only if EVERY image is linux/amd64. Any other result is a failure.
set -euo pipefail

REQUIRED_OS="linux"
REQUIRED_ARCH="${REQUIRED_ARCH:-amd64}"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <image-ref> [<image-ref> ...]" >&2
  exit 2
fi

failed=0
for image in "$@"; do
  if ! platform="$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}' 2>/dev/null)"; then
    echo "FAIL  $image - not present locally; build or pull it first" >&2
    failed=1
    continue
  fi

  if [ "$platform" = "${REQUIRED_OS}/${REQUIRED_ARCH}" ]; then
    echo "OK    $image  $platform"
  else
    echo "FAIL  $image  $platform - Fargate requires ${REQUIRED_OS}/${REQUIRED_ARCH}." >&2
    echo "      Rebuild with: docker buildx build --platform ${REQUIRED_OS}/${REQUIRED_ARCH} --load ..." >&2
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo >&2
  echo "Refusing to continue. An image of the wrong architecture pushes and" >&2
  echo "registers without complaint, and fails only when ECS tries to start" >&2
  echo "it - after the tag is consumed and a task definition revision is" >&2
  echo "burned." >&2
  exit 1
fi
