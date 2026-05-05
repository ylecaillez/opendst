#!/sbin/openrc-run

start() {
  ifup -i /etc/network/interfaces eth0
  ifup -i /etc/network/interfaces lo

  mkdir /dev/pts
  mount devpts /dev/pts -t devpts

  DEPLOYMENT_DIR="/opendst-deployment"
  mkdir -p "$DEPLOYMENT_DIR"
  mount -o ro /dev/vdb "$DEPLOYMENT_DIR"

  echo "==> mounts at JVM launch:"
  mount
  echo "==>"

  AGENT_JAR="$DEPLOYMENT_DIR/system/opendst-agent.jar"

  java \
    --patch-module "java.base=/resources/opendst-patch.jar" \
    -javaagent:"$AGENT_JAR" \
    --enable-native-access=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.net=ALL-UNNAMED \
    --add-opens=java.base/java.util=ALL-UNNAMED \
    -Dnet.bytebuddy.safe=true \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+UnlockDiagnosticVMOptions \
    -XX:+AllowArchivingWithJavaAgent \
    -XX:hashCode=2 \
    -Dkeystore.pkcs12.iterationCount=1 \
    -Dkeystore.pkcs12.keyProtectionIterationCount=1 \
    -Djava.library.path=/resources \
    -cp /resources/nyx-guest.jar \
    opendst.nyx.guest.NyxGuestEntry \
    "$DEPLOYMENT_DIR"

  echo "NyxGuestEntry exited — rebooting"
  reboot -f
  return 0
}
