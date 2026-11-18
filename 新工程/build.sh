#!/bin/bash
# 编译 APK。工具链路径通过环境变量传入，不写死在本机绝对路径上。
#
# 用法：
#   bash build.sh assembleDebug
#
# 需要先准备（或用环境变量覆盖）：
#   TOOLCHAIN   JDK 17 + Android SDK + Gradle 8.13 所在的根目录
#   JDK_HOME    JDK 17 目录（默认 $TOOLCHAIN/jdk17/jdk-17.0.2）
#   SDK_HOME    Android SDK 目录（默认 $TOOLCHAIN/android-sdk）
#   GRADLE_BIN  gradle 可执行文件或 .bat（默认 $TOOLCHAIN/gradle-8.13/bin/gradle.bat）
#   GRADLE_USER_HOME  依赖缓存目录（默认工程内 .gradle-home）
#
# 【两个必须注意的坑（原作者实测踩出来的，Windows 中文路径用户尤其相关）】
# 1) 交给 Gradle/JDK 的路径必须是纯 ASCII。
#    工程真实路径在中文目录下时 Gradle 会报：
#      Could not initialize native services.
#      > Failed to load native library 'native-platform.dll'
#    解决：给工程建一个 ASCII 路径的 junction（Windows: mklink /J），在 junction 里编译。
# 2) GRADLE_USER_HOME 若指向受限目录，Gradle 建 native/.lock 会失败：
#      FileNotFoundException: ...native-platform.dll.lock (拒绝访问)
#    解决：把 GRADLE_USER_HOME 指到一个可写目录。
# 这两条是环境问题，不是代码问题；在普通 Linux/macOS 或纯英文路径的 Windows 上不会遇到。
set -e

# 工程根目录：本脚本所在目录，可用 TB_ROOT 覆盖（例如指向 ASCII junction）
ROOT="${TB_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
if [ ! -d "$ROOT/app" ]; then
  echo "ERROR: 在 $ROOT 下找不到 app/ 目录，请检查 TB_ROOT" >&2
  exit 1
fi
cd "$ROOT"

# 工具链：优先环境变量，其次常见位置
if [ -z "${TOOLCHAIN:-}" ]; then
  for cand in "$HOME/.android-toolchain" "/opt/android-toolchain" "C:/tbapp/toolchain"; do
    if [ -d "$cand" ]; then TOOLCHAIN="$cand"; break; fi
  done
fi
if [ -z "${TOOLCHAIN:-}" ] || [ ! -d "$TOOLCHAIN" ]; then
  echo "ERROR: 没找到工具链。请设置 TOOLCHAIN 指向含 jdk17 / android-sdk / gradle-8.13 的目录" >&2
  exit 1
fi

JDK_HOME="${JDK_HOME:-$TOOLCHAIN/jdk17/jdk-17.0.2}"
SDK_HOME="${SDK_HOME:-$TOOLCHAIN/android-sdk}"
GRADLE_BIN="${GRADLE_BIN:-$TOOLCHAIN/gradle-8.13/bin/gradle.bat}"
[ -x "$GRADLE_BIN" ] || [ -f "$GRADLE_BIN" ] || GRADLE_BIN="$TOOLCHAIN/gradle-8.13/bin/gradle"

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$SDK_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ROOT/.android"
# 依赖缓存默认放工程内，避免指向只读目录
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-home}"
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

# 临时目录也放工程内：TMP 必须可写，且路径是 ASCII
export TMP="$ROOT/.tmp"
export TEMP="$TMP"
export TMPDIR="$TMP"
mkdir -p "$TMP"

echo "ROOT=$ROOT"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "GRADLE_USER_HOME=$GRADLE_USER_HOME"

exec "$GRADLE_BIN" --no-daemon --console=plain "$@"
