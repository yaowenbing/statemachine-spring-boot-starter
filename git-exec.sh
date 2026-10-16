#!/bin/bash
#
# Git 批量提交脚本（自动递归所有子仓库）
#
# 功能:
#   自动遍历根仓库及所有一级子 Git 仓库，依次执行：
#   1. 暂存所有变更（git add --all）
#   2. 使用标准化提交信息提交（含分支名和用户名）
#   3. 拉取远程最新代码（pull --rebase）
#   4. 推送到远程仓库（push）
#
# 用法:
#   git-exec.sh "提交信息"           # 完整流程：提交 + pull + push
#   git-exec.sh -p "提交信息"        # 仅本地提交，跳过 pull/push
#
# 参数:
#   提交信息    必需，描述本次变更的内容
#
# 选项:
#   -p, --skip-push   跳过 pull 和 push，仅本地提交
#   -h, --help        显示帮助信息
#
# 作者: 姚文兵
# 创建日期: 2026-04-06
# 最后修改: 2026-06-22

# 严格模式：遇错即停，未定义变量报错，管道失败时退出
set -euo pipefail

# 脚本所在目录的绝对路径（用于定位仓库根目录）
SCRIPT_DIR="$(dirname "$(realpath "$0")")"
VERSION="2.0.0"

# ─── 终端颜色定义 ───
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RST='\033[0m'

# ─── 日志输出函数（带颜色标记） ───
log() { echo -e "${GREEN}[INFO]${RST} $1"; }      # 普通信息
warn() { echo -e "${YELLOW}[WARN]${RST} $1"; }     # 警告信息
err() { echo -e "${RED}[ERROR]${RST} $1"; }         # 错误信息
header() { echo -e "\n${CYAN}${BOLD}━━━ $1 ━━━${RST}"; }  # 分隔标题

# ─── 命令行参数解析 ───
SKIP_REMOTE=false   # 是否跳过远程操作（pull/push）
COMMIT_MSG=""       # 用户提供的提交信息

# 显示帮助信息并退出
show_usage() {
    cat << EOF
Git 批量提交脚本 v$VERSION（自动递归所有子仓库）

用法: $(basename "$0") [选项] "提交信息"

选项:
    -p, --skip-push   跳过 pull 和 push，仅本地提交
    -h, --help        显示帮助

示例:
    $(basename "$0") "修复用户认证bug"
    $(basename "$0") -p "仅本地提交"
EOF
    exit 0
}

# 解析命令行参数：支持选项和位置参数
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) show_usage ;;                  # 显示帮助
        -p|--skip-push) SKIP_REMOTE=true; shift ;; # 仅本地提交
        -*) err "未知选项: $1"; exit 2 ;;          # 未知选项报错
        *)
            # 第一个位置参数作为提交信息，不允许重复
            [[ -z "$COMMIT_MSG" ]] && COMMIT_MSG="$1" || { err "只能提供一个提交信息"; exit 2; }
            shift ;;
    esac
done
# 校验提交信息不能为空
[[ -z "$COMMIT_MSG" ]] && { err "提交信息是必需的"; exit 2; }

#######################################
# 对单个 Git 仓库执行完整工作流
# 流程: 检查变更 → 暂存 → 提交 → pull → push
# 参数: $1=仓库路径 $2=显示名称
# 返回: 0=成功或无变更 1=失败
#######################################
process_repo() {
    local repo_dir="$1"   # 仓库目录路径
    local repo_name="$2"  # 仓库显示名称（用于日志）

    # ── 第一步：检查是否有待提交的变更 ──
    # 同时检查：工作区变更 + 暂存区变更 + 未跟踪文件
    if (cd "$repo_dir" && git diff --quiet && git diff --cached --quiet && [[ -z "$(git ls-files --others --exclude-standard)" ]]); then
        warn "[$repo_name] 无变更，跳过"
        return 0
    fi

    header "$repo_name"
    cd "$repo_dir"

    # ── 第二步：获取当前分支名和用户名 ──
    local branch
    branch=$(git symbolic-ref --short HEAD 2>/dev/null) || { err "[$repo_name] 无法获取分支"; return 1; }
    local user
    user=$(git config user.name)

    # 显示当前变更文件列表
    git status --short

    # ── 第三步：暂存所有变更并生成标准化提交信息 ──
    git add --all
    # 提交信息格式：当前分支名:[分支]-当前提交用户:[用户]-自定义信息
    local msg="当前分支名:[$branch]-当前提交用户:[$user]-$COMMIT_MSG"

    if git commit -m "$msg" 2>/dev/null; then
        log "[$repo_name] 提交成功: $msg"
    else
        # 提交失败时检查暂存区：可能是所有文件都被 .gitignore 排除
        if git diff --cached --quiet; then
            warn "[$repo_name] 无有效变更可提交"
            return 0
        fi
        err "[$repo_name] 提交失败"
        return 1
    fi

    # ── 第四步：远程操作（pull + push） ──
    # 如果指定了 -p 参数则跳过远程操作
    if [[ "$SKIP_REMOTE" == "true" ]]; then
        return 0
    fi

    # 检查是否配置了远程仓库，没有则跳过
    if ! git remote | grep -q .; then
        warn "[$repo_name] 无远程仓库，跳过 push"
        return 0
    fi

    # 确保当前分支有上游跟踪分支，没有则自动设置
    if ! git rev-parse --abbrev-ref @{upstream} &>/dev/null; then
        git branch --set-upstream-to="origin/$branch" 2>/dev/null || {
            warn "[$repo_name] 无法设置上游跟踪，跳过 pull/push"
            return 0
        }
    fi

    # 使用 rebase 方式拉取远程最新代码（避免产生合并提交）
    if ! git pull --rebase 2>/dev/null; then
        # 如果存在合并冲突，自动中止 rebase 并报错
        if git ls-files -u | grep -q .; then
            err "[$repo_name] 检测到合并冲突，请手动解决"
            git rebase --abort 2>/dev/null
            return 1
        fi
        # rebase 失败时回退到常规 pull
        warn "[$repo_name] pull 失败，尝试常规 pull"
        git pull 2>/dev/null || { err "[$repo_name] pull 失败"; return 1; }
    fi

    # 推送到远程仓库，首次推送自动设置上游分支
    if ! git push 2>/dev/null; then
        git push --set-upstream origin "$branch" 2>/dev/null || { err "[$repo_name] push 失败"; return 1; }
    fi

    log "[$repo_name] pull & push 完成"
}

#######################################
# 主流程：发现仓库 → 逐一处理 → 输出汇总
#######################################
main() {
    header "Git 批量提交 v$VERSION"
    log "提交信息: $COMMIT_MSG"
    [[ "$SKIP_REMOTE" == "true" ]] && log "模式: 仅本地提交（跳过 pull/push）"

    # 统计计数器
    local total=0 success=0 failed=0 skip=0
    local failed_list=""

    # ── 收集所有 Git 仓库 ──
    # 扫描范围：脚本所在目录（根仓库） + 一级子目录中的 Git 仓库
    local repos=()
    # 检查根目录本身是否是 Git 仓库
    [[ -d "$SCRIPT_DIR/.git" ]] && repos+=("$SCRIPT_DIR")
    # 遍历一级子目录，将含有 .git 的目录加入列表
    for dir in "$SCRIPT_DIR"/*/; do
        [[ -d "$dir/.git" ]] && repos+=("$dir")
    done

    # 未找到任何仓库时报错退出
    if [[ ${#repos[@]} -eq 0 ]]; then
        err "未找到任何 Git 仓库"
        exit 1
    fi

    log "发现 ${#repos[@]} 个 Git 仓库"

    # ── 逐一处理每个仓库 ──
    for repo_dir in "${repos[@]}"; do
        local name
        # 根仓库标记为 (root)，其余使用目录名
        if [[ "$repo_dir" == "$SCRIPT_DIR" ]]; then
            name="(root)"
        else
            name=$(basename "$repo_dir")
        fi
        total=$((total + 1))

        if process_repo "$repo_dir" "$name"; then
            success=$((success + 1))
        else
            failed=$((failed + 1))
            failed_list="$failed_list $name"
        fi
    done

    # ── 输出执行汇总报告 ──
    header "执行汇总"
    log "总计: $total | 成功: $success | 失败: $failed | 跳过(无变更): $skip"
    if [[ $failed -gt 0 ]]; then
        err "失败的仓库:$failed_list"
        exit 1
    fi
    log "全部完成"
}

main
