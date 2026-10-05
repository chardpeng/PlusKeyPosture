#!/usr/bin/env bash
# ============================================================
#  Plus Key 姿态自定义模块 —— 一键构建脚本 (macOS / Linux / Git Bash)
#  用法：./build.sh
# ============================================================
set -e
cd "$(dirname "$0")"

echo ""
echo "[1/4] 定位 JDK..."

# 优先用 Android Studio 自带的 JBR（本机实测 JDK 25）
for jbr in \
  "/c/Program Files/Android/Android Studio/jbr" \
  "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  "/opt/android-studio/jbr"
do
  if [ -x "$jbr/bin/java" ]; then
    export JAVA_HOME="$jbr"
    echo "      使用 Android Studio JBR: $JAVA_HOME"
    break
  fi
done

if [ -z "$JAVA_HOME" ]; then
  if command -v java >/dev/null 2>&1; then
    echo "      使用 PATH 中的 java"
    java -version 2>&1 | head -1
  else
    echo "  [错误] 未找到 JDK。请安装 JDK 21+，或设置 JAVA_HOME。"
    exit 1
  fi
else
  "$JAVA_HOME/bin/java" -version 2>&1 | head -1
fi

export PATH="$JAVA_HOME/bin:$PATH"

echo ""
echo "[2/4] 检查 Gradle Wrapper..."
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew 2>/dev/null || true
    GRADLE_CMD="./gradlew"
    echo "      使用内置 wrapper（Gradle 9.1.0）"
elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
    echo "      使用系统 Gradle"
else
    echo "  [错误] 既没有 ./gradlew，也没有系统 gradle。"
    exit 1
fi

echo ""
echo "[3/4] 开始构建 Release APK（zipalign，体积远小于 debug）..."
$GRADLE_CMD :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
echo ""
echo "[4/4] 构建完成！"
if [ -f "$APK" ]; then
    echo ""
    echo "  APK 位置：$(pwd)/$APK"
    echo ""
    echo "  安装到手机（需先插上 USB 并授权调试）："
    echo "    adb install -r \"$APK\""
    echo ""
    echo "  然后记得在 LSPosed 里启用模块，作用域勾选「系统框架」，重启手机。"
else
    echo "  [警告] 未找到 APK 输出文件。"
fi
echo ""
