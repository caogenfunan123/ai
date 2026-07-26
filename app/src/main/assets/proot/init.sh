#!/system/bin/sh
# AI 魔改器 · PRoot 容器自检脚本
# 由 RootfsDeployer.pushInitScript 推送到容器内 /root/init.sh
#
# 用途：
#   1. 注入默认环境变量
#   2. 校验关键目录权限
#   3. 输出环境快照供 MCP detect_environment 读取

set -e

echo "[init] start container self-check"

# 1. 环境变量
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/toolchain"
export HOME=/root
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export TERM=xterm-256color

# 2. 关键目录权限
for dir in /root /tmp /var /opt /opt/toolchain /root/workspace /opt/models; do
    mkdir -p "$dir"
    chmod 0755 "$dir" 2>/dev/null || true
done

# 3. 工具链可执行权限
find /opt/toolchain -type f 2>/dev/null | while read -r f; do
    chmod +x "$f" 2>/dev/null || true
done

# 4. 输出环境快照
echo "[init] arch        = $(uname -m)"
echo "[init] kernel      = $(uname -r)"
echo "[init] hostname    = $(hostname 2>/dev/null || echo unknown)"
echo "[init] user        = $(whoami 2>/dev/null || echo root)"
echo "[init] toolchain   = $(ls /opt/toolchain 2>/dev/null | tr '\n' ' ')"
echo "[init] PATH        = $PATH"
echo "[init] ok"
