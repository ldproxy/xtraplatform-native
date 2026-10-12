#!/bin/bash
# Installs the build toolchain into registry.suse.com/suse/sle15:15.5.
# Linux libs are built on SLES 15 SP5 because it has the oldest glibc (2.31) of all targets,
# so they also load on AlmaLinux 9, Ubuntu Jammy and Debian Bookworm.
# Expects JAVA_HOME=/opt/java and /opt/java/bin on PATH.
set -euo pipefail

JAVA_VERSION=25

case "$(uname -m)" in
  x86_64) JAVA_ARCH=x64 ;;
  aarch64) JAVA_ARCH=aarch64 ;;
  *) echo "unsupported arch $(uname -m)"; exit 1 ;;
esac

zypper -n --gpg-auto-import-keys install --no-recommends gcc-c++ make cmake curl tar gzip git which findutils gawk sed grep diffutils patch file m4

mkdir -p /opt/java
curl -fsSL "https://api.adoptium.net/v3/binary/latest/${JAVA_VERSION}/ga/linux/${JAVA_ARCH}/jdk/hotspot/normal/eclipse" | tar -xz -C /opt/java --strip-components=1

ldd --version | head -1
gcc --version | head -1
cmake --version | head -1
java -version

# the checkout belongs to the runner user, not to root
git config --global --add safe.directory /workspace
