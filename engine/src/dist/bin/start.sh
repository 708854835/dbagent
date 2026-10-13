#!/bin/sh
# DbAgent 引擎启动（Linux/macOS）：
#   环境变量 JAVA_OPTS 调堆参数（默认 -Xms256m -Xmx1g）；
#   环境变量 DBAGENT_MASTER_KEY 传主密钥（校验 hmac: 配置的 Token，可选）。
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
BASE_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1g}"

ARGS="serve --config $BASE_DIR/config --data $BASE_DIR/data"
if [ -n "$DBAGENT_MASTER_KEY" ]; then
    ARGS="$ARGS --master-key $DBAGENT_MASTER_KEY"
fi

# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$BASE_DIR/lib/engine.jar" $ARGS
