#!/bin/bash
# 跑单元测试。
#
# 为什么不用 `gradle testDebugUnitTest`：
# Gradle 的测试任务是另起一个 worker JVM，classpath 通过启动参数传给 worker。
# 工程真实路径含中文（…/地图打卡照相APP-需求与方案/…），worker 解码失败，
# 结果所有测试方法都报 `ClassNotFoundException: com.daka.app.CoreLogicTest`——
# 编译是成功的（class 文件就在 build/tmp/kotlin-classes/debugUnitTest 里），
# 只是 worker 加载不到。原「图斑举证」工程踩过同一个坑，解法一样：
#
#   Gradle 只负责编译 → 把 class 和依赖复制到纯 ASCII 路径 → 用 java 直接跑 JUnitCore
#
set -e

ROOT="${TB_ROOT:-E:/ai/deepseek/tjdaka}"
TOOLCHAIN="C:/tbapp/toolchain"
WORKW="E:/ai/deepseek/tjdaka-test"
WORK="/e/ai/deepseek/tjdaka-test"

export JAVA_HOME="$TOOLCHAIN/jdk17/jdk-17.0.2"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.android"
export GRADLE_USER_HOME="E:/ai/deepseek/tjdaka-cache"
export TMP="$ROOT/.tmp"; export TEMP="$TMP"; export TMPDIR="$TMP"
mkdir -p "$ANDROID_USER_HOME" "$TMP"

cd "$ROOT"

# 1) 只编译，不执行
LOG="$TMP/test-compile.log"
"$TOOLCHAIN/gradle-8.13/bin/gradle.bat" --no-daemon --console=plain \
    compileDebugKotlin compileDebugUnitTestKotlin >"$LOG" 2>&1 || {
  echo "编译失败，错误如下：" >&2
  grep -E "^e: " "$LOG" >&2 || tail -20 "$LOG" >&2
  exit 1
}

# 2) 编译产物 + 依赖全部复制到 ASCII 路径
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/lib"
cp -r app/build/tmp/kotlin-classes/debug/. "$WORK/classes/" 2>/dev/null || true
cp -r app/build/tmp/kotlin-classes/debugUnitTest/. "$WORK/classes/" 2>/dev/null || true

CACHE="E:/ai/deepseek/tjdaka-cache/caches/modules-2/files-2.1"
deps=(
  "$CACHE/junit/junit/4.13.2/8ac9e16d933b6fb43bc7f576336b8f4d7eb5ba12/junit-4.13.2.jar"
  "$CACHE/org.hamcrest/hamcrest-core/1.3/42a25dc3219429f0e5d060061f71acb49bf010a0/hamcrest-core-1.3.jar"
  "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar"
  "$CACHE/org.json/json/20240303/ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a/json-20240303.jar"
  # TrackGeo 直接用了 org.maplibre.geojson.*，单测里必须能加载到它
  # geojson 内部靠 gson 序列化，gson 也得在
  "$CACHE/org.maplibre.gl/android-sdk-geojson/6.0.1/256e046d9f5756f2ce2e70110741b025bf3080f0/android-sdk-geojson-6.0.1.jar"
  "$CACHE/org.maplibre.gl/android-sdk-turf/6.0.1/394851f55221cae098ea80dbcba615a5577e6429/android-sdk-turf-6.0.1.jar"
  "$CACHE/com.google.code.gson/gson/2.10.1/b3add478d4382b78ea20b1671390a858002feb6c/gson-2.10.1.jar"
)
CP="$WORKW/classes"
for j in "${deps[@]}"; do
  if [ -f "$j" ]; then
    cp "$j" "$WORK/lib/"
    CP="$CP;$WORKW/lib/$(basename "$j")"
  else
    echo "缺少依赖: $j" >&2; exit 1
  fi
done

# android.jar 只给编译器/运行期解析 android.* 的符号用（isReturnDefaultValues=true
# 让没实现的方法返回默认值，真正逻辑不依赖它）
CP="$CP;$TOOLCHAIN/android-sdk/platforms/android-34/android.jar"

# 3) 跑
CLASSES="${@:-com.daka.app.CoreLogicTest}"
JAVA="$TOOLCHAIN/jdk17/jdk-17.0.2/bin/java.exe"
status=0
for cls in $CLASSES; do
  echo "===== $cls ====="
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" org.junit.runner.JUnitCore "$cls" || status=1
done
exit $status
