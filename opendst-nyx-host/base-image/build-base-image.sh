#!/usr/bin/env bash
# Builds the opendst-nyx-base Docker image.
#
# Must be run from the opendst repo root after:
#   mvn install -pl opendst-runner -am   (produces opendst-runner.jar)
#   cargo build --release                (in opendst-nyx-host/, produces shim binary)
#
# libhypercall.so, nyx-guest.jar, and opendst-patch.jar are all built
# inside Docker by the guest-builder stage — no local make/cargo needed for them.
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

echo "==> Assembling build context in $SCRIPT_DIR/"
mkdir -p "$SCRIPT_DIR/resources"

# Nyx-lite shim (host-side binary, extracted by NyxImageManager at run time)
echo "  opendst-nyx-shim"
cp "$REPO_ROOT/opendst-nyx-host/target/release/opendst-nyx-shim" \
   "$SCRIPT_DIR/resources/opendst-nyx-shim"

# opendst-runner.jar (fat jar from Maven install)
echo "  opendst-runner.jar"
RUNNER_JAR=$(find "$REPO_ROOT/opendst-runner/target" -name "opendst-runner-*.jar" \
    ! -name "*-sources.jar" ! -name "*-javadoc.jar" | sort | tail -1)
if [ -z "$RUNNER_JAR" ]; then
    echo "ERROR: opendst-runner.jar not found. Run: mvn install -pl opendst-runner -am" >&2
    exit 1
fi
cp "$RUNNER_JAR" "$SCRIPT_DIR/resources/opendst-runner.jar"

# SimulatorThread source: used by guest-builder stage to generate opendst-patch.jar
echo "  SimulatorThread.java (for guest-builder Docker stage)"
cp "$REPO_ROOT/opendst-maven-plugin/src/main/resources/com/pingidentity/opendst/maven/SimulatorThread.java" \
   "$SCRIPT_DIR/SimulatorThread.java"

# Rust crate sources: built inside Docker by guest-builder stage
echo "  libhypercall/ (Rust crate sources)"
rm -rf "$SCRIPT_DIR/libhypercall"
cp -r "$REPO_ROOT/opendst-nyx-host/libhypercall" "$SCRIPT_DIR/libhypercall"

# Java guest sources: compiled inside Docker by guest-builder stage
echo "  guest-src/ (Java guest sources)"
rm -rf "$SCRIPT_DIR/guest-src"
cp -r "$REPO_ROOT/opendst-nyx-host/guest/src" "$SCRIPT_DIR/guest-src"

# Network config and SSH key from the nyx VM image setup (not versioned in repo)
echo "  interfaces.txt + key.pub"
cp "$NYX_VM_DIR/interfaces.txt" "$SCRIPT_DIR/interfaces.txt"
cp "$NYX_VM_DIR/key.pub"        "$SCRIPT_DIR/key.pub"

echo "==> Building Docker image: $TAG"
docker build -t "$TAG" "$SCRIPT_DIR"

echo "==> Done: $TAG"
