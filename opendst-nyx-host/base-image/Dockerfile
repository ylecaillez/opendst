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
FROM alpine:latest

RUN apk update \
 && apk add openrc openssh sudo util-linux bash vim rsyslog \
 && apk add openjdk25-jre-headless

# OpenRC services needed for VM boot
COPY interfaces.txt /etc/network/interfaces
COPY initscript-fuzz-setup.sh /etc/init.d/fuzz-setup
RUN  chmod +x /etc/init.d/fuzz-setup

COPY key.pub /root/.ssh/authorized_keys

RUN echo "basic services" \
 && ln -s agetty /etc/init.d/agetty.ttyS0 \
 && echo ttyS0 > /etc/securetty \
 && rc-update add agetty.ttyS0 default \
 && rc-update add devfs boot \
 && rc-update add procfs boot \
 && rc-update add sysfs boot \
 && rc-update add fuzz-setup default \
 && echo "done"

# Nyx-lite shim: extracted by NyxImageManager to run Firecracker VMs on the host
COPY resources/opendst-nyx-shim /usr/bin/opendst-nyx-shim
RUN chmod +x /usr/bin/opendst-nyx-shim

# Guest bridge: JNI hypercall library and NyxGuestEntry JAR (VM mode only)
COPY resources/libhypercall.so /resources/libhypercall.so
COPY resources/nyx-guest.jar   /resources/nyx-guest.jar

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
