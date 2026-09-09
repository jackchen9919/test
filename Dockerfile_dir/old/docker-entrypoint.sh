#!/bin/sh
set -e
USER=www-data
PORT=8080
MinRAMPercentage=${MinRAMPercentage:-25.0}
MaxRAMPercentage=${MaxRAMPercentage:-75.0}
MetaspaceSize=${MetaspaceSize:-256m}
BASE_DIR=/app
DEBUG=${DEBUG:-false}

mkdir -p /data/log/$HOSTNAME
ln -sTf /data/log/$HOSTNAME /app/log

if [ "$1" = 'start' ];then
        eval JAVA_OPTS=$JAVA_OPTS
fi

# dubug
if $DEBUG;then
        JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5000"
fi

# jvm options
JAVA_OPTS="$JAVA_OPTS -XX:ErrorFile=${BASE_DIR}/log/hs_err_pid_%p.log -XX:MaxRAMPercentage=${MaxRAMPercentage} -XX:InitialRAMPercentage=${MinRAMPercentage} -XX:MetaspaceSize=${MetaspaceSize} -Dsun.net.inetaddr.ttl=60 -Dserver.port=$PORT -Dlogging.file.name=${BASE_DIR}/log/app.log"

# gc options
GC_OPTS="-XX:+DisableExplicitGC -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+PrintGCDetails -verbose:gc -XX:+PrintGCDateStamps -XX:+PrintHeapAtGC -XX:+PrintTenuringDistribution -Xloggc:${BASE_DIR}/log/gc.log -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=10 -XX:GCLogFileSize=512k"

# dump options
DUMP_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${BASE_DIR}/log/oom.hprof"


# allow the container to be started with `--user`
if [ "$1" = 'start' -a "$(id -u)" = '0' ]; then
        find . \! -user $USER -exec chown $USER '{}' +
        cd /data/config
        exec gosu $USER "$0" java $JAVA_OPTS $GC_OPTS $DUMP_OPTS -jar $BASE_DIR/app.jar --spring.config.location=/data/config/application.yml
fi

exec "$@"
