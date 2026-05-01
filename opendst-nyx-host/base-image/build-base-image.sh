#!/usr/bin/env bash
# Builds the opendst-nyx-base Docker image.
#
# Must be run from the opendst repo root after:
#   mvn install -pl opendst-runner -am   (produces opendst-runner.jar)
#   cargo build --release                (in opendst-nyx-host/, produces shim)
#   make -C opendst-nyx-host/guest       (produces libhypercall.so + nyx-guest.jar)
#
# Usage:
#   cd /path/to/opendst
#   bash opendst-nyx-host/base-image/build-base-image.sh [TAG]
#
# TAG defaults to opendst-nyx-base:latest

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
NYX_VM_DIR="$HOME/git/nyx-lite/vm_image/dockerimage"
TAG="${1:-opendst-nyx-base:latest}"

echo "==> Assembling build context in $SCRIPT_DIR/resources/"
mkdir -p "$SCRIPT_DIR/resources"

# Guest bridge
echo "  libhypercall.so"
cp "$REPO_ROOT/opendst-nyx-host/guest/build/lib/libhypercall.so" \
   "$SCRIPT_DIR/resources/libhypercall.so"

echo "  nyx-guest.jar"
cp "$REPO_ROOT/opendst-nyx-host/guest/build/nyx-guest.jar" \
   "$SCRIPT_DIR/resources/nyx-guest.jar"

# opendst-runner.jar (fat jar from Maven install)
echo "  opendst-runner.jar"
RUNNER_JAR=$(find "$REPO_ROOT/opendst-runner/target" -name "opendst-runner-*.jar" \
    ! -name "*-sources.jar" ! -name "*-javadoc.jar" | sort | tail -1)
if [ -z "$RUNNER_JAR" ]; then
    echo "ERROR: opendst-runner.jar not found. Run: mvn install -pl opendst-runner -am" >&2
    exit 1
fi
cp "$RUNNER_JAR" "$SCRIPT_DIR/resources/opendst-runner.jar"

# OpenRC network config and init script from the existing nyx VM image setup
echo "  interfaces.txt + initscript-fuzz-setup.sh + key.pub"
cp "$NYX_VM_DIR/interfaces.txt"            "$SCRIPT_DIR/interfaces.txt"
cp "$NYX_VM_DIR/initscript-fuzz-setup.sh"  "$SCRIPT_DIR/initscript-fuzz-setup.sh"
cp "$NYX_VM_DIR/key.pub"                   "$SCRIPT_DIR/key.pub"

echo "==> Building Docker image: $TAG"
docker build -t "$TAG" "$SCRIPT_DIR"

echo "==> Done: $TAG"
