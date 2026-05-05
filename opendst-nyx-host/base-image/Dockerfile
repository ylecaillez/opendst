# opendst-nyx-base: base image for OpenDST nyx-lite execution.
#
# This image serves two purposes:
#
#   1. Docker container mode (fork exploration, plan replay, debug):
#      ENTRYPOINT runs opendst-runner.jar (Bootstrap → BuildRunner).
#      Works on any machine — no KVM required.
#
#   2. Nyx VM rootfs mode (snapshot/restore exploration):
#      OpenRC runs fuzz-setup which invokes NyxGuestEntry.
#      Requires the image to be extracted to an ext4 rootfs and booted
#      via opendst-nyx-shim + Firecracker/KVM.
#
# The SUT deployment is NOT baked in here. It is added in a derived image:
#   FROM opendst-nyx-base:latest
#   COPY deployment/ /opendst-deployment/
#
# Multi-stage build:
#   rust-builder   — builds libhypercall.so (Rust/glibc) on Debian; cdylib requires glibc target
#   java-builder   — builds nyx-guest.jar + opendst-patch.jar on Alpine JDK25
#   runtime        — Alpine + JRE + gcompat (to load the glibc-linked libhypercall.so)

# --- Stage 1a: build libhypercall.so ---
# Use Alpine's system cargo (apk add cargo) rather than rustup.
# Alpine's native Rust targets the system's dynamic musl linker, which supports cdylib.
# The rustup x86_64-unknown-linux-musl target uses static-only musl and does NOT support cdylib.
FROM alpine:latest AS rust-builder

RUN apk add --no-cache cargo musl-dev

COPY libhypercall/ /build/libhypercall/
RUN printf '[workspace]\nmembers = ["libhypercall"]\nresolver = "2"\n' > /build/Cargo.toml && \
    cargo build --manifest-path /build/Cargo.toml -p libhypercall --release

# --- Stage 1b: build nyx-guest.jar + opendst-patch.jar ---
# Must use the exact same Alpine JDK as the runtime image so class files and
# the VirtualThread patch match the JRE that will execute them in the VM.
FROM alpine:latest AS java-builder

RUN apk add --no-cache openjdk25-jdk

# Compile nyx-guest.jar from Java guest sources
COPY guest-src/ /build/guest-src/
RUN mkdir -p /build/guest-classes && \
    javac -d /build/guest-classes $(find /build/guest-src -name "*.java") && \
    jar --create --file /build/nyx-guest.jar -C /build/guest-classes .

# Generate opendst-patch.jar using this JDK (guarantees VirtualThread matches runtime JRE)
COPY GeneratePatch.java /build/GeneratePatch.java
COPY SimulatorThread.java /build/SimulatorThread.java
RUN java /build/GeneratePatch.java /build/SimulatorThread.java /build/opendst-patch.jar

# --- Stage 2: runtime image with JRE only ---
FROM alpine:latest

RUN apk update \
 && apk add openrc openssh sudo util-linux bash vim rsyslog \
 && apk add openjdk25-jre-headless libgcc

# OpenRC services needed for VM boot
COPY interfaces.txt /etc/network/interfaces
COPY initscript-fuzz-setup.sh /etc/init.d/fuzz-setup
RUN  chmod +x /etc/init.d/fuzz-setup

COPY key.pub /root/.ssh/authorized_keys
# /etc/hostname and /etc/hosts are bind-mounted read-only by Docker during RUN,
# so they must be provided via COPY rather than written in a RUN command.
COPY hostname /etc/hostname
COPY hosts /etc/hosts

# Bake all static system setup so / can be mounted ro at VM boot.
# Nothing in this block must run at runtime — it is all idempotent image-time config.
RUN chmod 0740 /root/ \
 && chmod 0700 /root/.ssh \
 && chmod 0400 /root/.ssh/authorized_keys \
 && echo "root:fuzz" | chpasswd \
 && addgroup -S fuzz \
 && adduser -S fuzz -G fuzz -h /home/fuzz -s /bin/bash \
 && mkdir -p /home/fuzz/.ssh \
 && cp /root/.ssh/authorized_keys /home/fuzz/.ssh/ \
 && chown -R fuzz:fuzz /home/fuzz \
 && chmod 0740 /home/fuzz \
 && chmod 0700 /home/fuzz/.ssh \
 && chmod 0400 /home/fuzz/.ssh/authorized_keys \
 && echo "fuzz:fuzz" | chpasswd \
 && echo '%fuzz ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/fuzz \
 && mkdir -p /workload

RUN echo "basic services" \
 && ln -s agetty /etc/init.d/agetty.ttyS0 \
 && echo ttyS0 > /etc/securetty \
 && rc-update add agetty.ttyS0 default \
 && rc-update add devfs boot \
 && rc-update add procfs boot \
 && rc-update add sysfs boot \
 && rc-update add fuzz-setup default \
 && echo "done"

# Nyx-lite shim: host-side binary extracted by NyxImageManager at run time
COPY resources/opendst-nyx-shim /usr/bin/opendst-nyx-shim
RUN chmod +x /usr/bin/opendst-nyx-shim

# Pre-init wrapper: mounts tmpfs on /run and /tmp before OpenRC starts,
# so / can stay ro throughout the VM lifetime.
COPY opendst-init /sbin/opendst-init
RUN chmod +x /sbin/opendst-init

# Guest artifacts
RUN mkdir -p /resources
COPY --from=rust-builder  /build/target/release/libhypercall.so /resources/libhypercall.so
COPY --from=java-builder  /build/nyx-guest.jar                  /resources/nyx-guest.jar
COPY --from=java-builder  /build/opendst-patch.jar              /resources/opendst-patch.jar

# opendst-runner.jar: the self-contained runner (Bootstrap + BuildRunner + agent embedded)
# Used as ENTRYPOINT in container mode.
COPY resources/opendst-runner.jar /opendst-runner.jar

# Container mode entrypoint: fork exploration / plan replay / debug
# In VM mode this is not used — OpenRC calls NyxGuestEntry via fuzz-setup.
ENTRYPOINT ["java", \
  "--enable-native-access=ALL-UNNAMED", \
  "--add-opens=java.base/java.lang=ALL-UNNAMED", \
  "--add-opens=java.base/java.net=ALL-UNNAMED", \
  "--add-opens=java.base/java.util=ALL-UNNAMED", \
  "-Dnet.bytebuddy.safe=true", \
  "-XX:+UnlockExperimentalVMOptions", \
  "-XX:+UnlockDiagnosticVMOptions", \
  "-XX:+AllowArchivingWithJavaAgent", \
  "-XX:hashCode=2", \
  "-Dkeystore.pkcs12.iterationCount=1", \
  "-Dkeystore.pkcs12.keyProtectionIterationCount=1", \
  "-jar", "/opendst-runner.jar"]
