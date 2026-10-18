#!/bin/bash
# Installs the build toolchain into registry.suse.com/suse/sle15:15.5.
# Linux libs are built on SLES 15 SP5 because it has the oldest glibc (2.31) of all targets,
# so they also load on AlmaLinux 9, Ubuntu Jammy and Debian Bookworm.
# Expects JAVA_HOME=/opt/java and /opt/go/bin, /opt/java/bin on PATH.
set -euo pipefail

JAVA_VERSION=25
GO_VERSION=1.26.5

case "$(uname -m)" in
  x86_64) JAVA_ARCH=x64; GO_ARCH=amd64 ;;
  aarch64) JAVA_ARCH=aarch64; GO_ARCH=arm64 ;;
  *) echo "unsupported arch $(uname -m)"; exit 1 ;;
esac

zypper ar -f https://packages.adoptium.net/artifactory/rpm/opensuse/$(. /etc/os-release; echo $VERSION_ID)/$(uname -m) adoptium
zypper -n --gpg-auto-import-keys install --no-recommends gcc-c++ make cmake curl tar gzip git which findutils gawk sed grep diffutils patch file m4 temurin-$JAVA_VERSION-jdk

curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" | tar -xz -C /opt

(ldd --version | head -n 1) || true
(gcc --version | head -n 1) || true
(cmake --version | head -n 1) || true
java -version
go version

# the checkout belongs to the runner user, not to root
git config --global --add safe.directory /workspace
