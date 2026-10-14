#!/usr/bin/env bash
# Blackstart(现实电力)编译脚本 —— 零网络:javac 直编,classpath 唯一依赖是游戏自带的 desktop.jar。
# 用法:bash build.sh        (可覆盖:BS_JDK=<JDK目录>  BS_GAME=<游戏安装目录>)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then ROOTW="$(cygpath -m "$ROOT")"; else ROOTW="$ROOT"; fi

# ---------- 找 JDK 17 ----------
JAVAC="" JAR="" JDK_CANDIDATES=""
try_jdk() { # $1 = 候选 JDK 目录
  [ -n "$1" ] || return 1
  local bin="${2:-$1/bin}" javac="" jar="" missing="" version
  JDK_CANDIDATES="$JDK_CANDIDATES [$1]"
  if   [ -x "$bin/javac.exe" ]; then javac="$bin/javac.exe"
  elif [ -x "$bin/javac" ];     then javac="$bin/javac"
  else missing="$missing javac"; fi
  if   [ -x "$bin/jar.exe" ]; then jar="$bin/jar.exe"
  elif [ -x "$bin/jar" ];     then jar="$bin/jar"
  else missing="$missing jar"; fi
  if [ -n "$missing" ]; then
    echo "[!] JDK 候选 $1 缺少或不可执行:$missing (检查目录:$bin;含 .exe)" >&2
    return 1
  fi
  if ! version="$("$javac" -version 2>&1)"; then
    echo "[!] JDK 候选 $1 的 javac 无法运行:$version" >&2
    return 1
  fi
  if ! version="$("$jar" --version 2>&1)"; then
    echo "[!] JDK 候选 $1 的 jar 无法运行:$version" >&2
    return 1
  fi
  JAVAC="$javac" JAR="$jar"
  return 0
}
if [ -n "${BS_JDK:-}" ] && ! try_jdk "$BS_JDK"; then
  echo "[!] BS_JDK=$BS_JDK 不含完整可用的 JDK 工具,改从 PATH 找…" >&2
fi
if [ -z "$JAVAC" ] && command -v javac >/dev/null 2>&1; then
  JD="$(dirname "$(command -v javac)")"
  try_jdk "PATH ($JD)" "$JD" || true
fi
echo "[i] 已检查 JDK 候选:${JDK_CANDIDATES:- 无(BS_JDK 未设置且 PATH 无 javac)}"
if [ -z "$JAVAC" ]; then
  echo "[×] 找不到完整可用的 JDK(javac 与同目录 jar)。请安装 JDK 17(如 Temurin)并确保在 PATH 里,或设 BS_JDK=<JDK目录> 再跑。" >&2
  exit 1
fi
JV="$("$JAVAC" -version 2>&1 || true)"
echo "[i] JDK: $JAVAC ($JV)"
MAJ="$(printf '%s' "$JV" | grep -oE '[0-9]+' | head -1 || true)"
if [ -n "$MAJ" ] && [ "$MAJ" -lt 17 ]; then
  echo "[×] 这个 JDK($JV)不满足 --release 17,请用 JDK 17+(如 Temurin),或设 BS_JDK=<JDK目录>。" >&2
  exit 1
fi

# ---------- 找 desktop.jar(编译用的游戏本体,不在本仓库) ----------
if [ ! -f "$ROOT/libs/desktop.jar" ]; then
  CAND=""
  if [ -n "${BS_GAME:-}" ]; then
    if   [ -f "$BS_GAME/jre/desktop.jar" ]; then CAND="$BS_GAME/jre/desktop.jar"
    elif [ -f "$BS_GAME/desktop.jar" ];     then CAND="$BS_GAME/desktop.jar"; fi
  fi
  if [ -z "$CAND" ]; then
    for p in "C:/Program Files (x86)/Steam/steamapps/common/Mindustry/jre/desktop.jar" \
             "C:/Program Files/Steam/steamapps/common/Mindustry/jre/desktop.jar"; do
      if [ -f "$p" ]; then CAND="$p"; break; fi
    done
  fi
  if [ -n "$CAND" ]; then
    mkdir -p "$ROOT/libs"
    cp "$CAND" "$ROOT/libs/desktop.jar"
    echo "[i] 已从 $CAND 复制到 libs/desktop.jar(下次直接复用)"
  else
    echo "[×] 找不到 desktop.jar。三种获得方式:" >&2
    echo "    ① 从你的 Mindustry 安装目录 jre/ 里把 desktop.jar 复制到本仓库 libs/;" >&2
    echo "    ② 从官方 GitHub release 下载任一 ~v160 桌面版 jar,放进 libs/;" >&2
    echo "    ③ 设 BS_GAME=<游戏安装目录> 再跑(会自动复制)。" >&2
    exit 1
  fi
fi
D="$ROOT/libs/desktop.jar"
DSZ="$(wc -c < "$D")"
if command -v md5sum >/dev/null 2>&1; then
  DMD5="$(md5sum "$D" | cut -d' ' -f1)"
  echo "[i] desktop.jar: $DSZ 字节 / md5 $DMD5"
else
  echo "[i] desktop.jar: $DSZ 字节"
fi
echo "    参考:锁定参考版 v8 Build 160.5 = 83,070,449 字节 / md5 f3a66564b1fbf45a1c0e50d7499c2283;其他 ~v160 版本一般同样可编译"

# ---------- 构建 ----------
if [ ! -d "$ROOT/src/main/java" ]; then
  echo "[×] 没找到 src/main/java,确认是从仓库根调用本脚本。" >&2
  exit 1
fi
rm -rf "$ROOT/build/classes"
mkdir -p "$ROOT/build/classes" "$ROOT/build/libs"

echo "[1/3] javac (--release 17)…"
cd "$ROOT/src/main/java"
FILES=$(find . -name '*.java' | sed 's|^\./||' | LC_ALL=C sort)
# 把文件清单写进 javac @argfile:每行一条、整行双引号包住(空格不再被拆成多个参数)。
# 条目相对路径按 javac 的 cwd(src/main/java) 解析,故用绝对路径引用 build/ 下的 argfile。
printf '%s\n' "$FILES" | sed '/^$/d; s/.*/"&"/' > "$ROOTW/build/javac.args"
"$JAVAC" --release 17 -encoding UTF-8 \
  -cp "$ROOTW/libs/desktop.jar" \
  -d "$ROOTW/build/classes" \
  "@$ROOTW/build/javac.args"

echo "[2/3] jar 打包(类 + 资源同包)…"
JARFILE="$ROOTW/build/libs/blackstart-dev.jar"
"$JAR" --create --file "$JARFILE" \
  -C "$ROOTW/build/classes" . \
  -C "$ROOTW/src/main/resources" .

echo "[3/3] 结构抽查:"
LIST="$("$JAR" tf "$JARFILE")"
MISS=""
grep -qE '^mod\.json$' <<<"$LIST"                          || MISS="$MISS mod.json"
grep -qE '^bundles/' <<<"$LIST"                           || MISS="$MISS bundles/"
grep -qE '^blackstart/.*\.class$' <<<"$LIST"              || MISS="$MISS blackstart/*.class"
if [ -n "$MISS" ]; then
  echo "[×] 产物结构不完整,缺:$MISS" >&2
  exit 1
fi
NCLS="$(printf '%s\n' "$LIST" | grep -cE '^blackstart/.*\.class$')"
NBUN="$(printf '%s\n' "$LIST" | grep -cE '^bundles/')"
echo "    mod.json ✓   blackstart/*.class × $NCLS   bundles/ × $NBUN"

SIZE="$(wc -c < "$ROOT/build/libs/blackstart-dev.jar")"
echo
echo "✅ 产出:build/libs/blackstart-dev.jar (${SIZE} 字节)"
echo "   安装:拷进 <游戏目录>/saves/mods/(Steam 桌面版实测为 <游戏安装目录>/saves/mods/),重启游戏生效。"
