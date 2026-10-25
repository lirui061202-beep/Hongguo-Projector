#!/usr/bin/env bash
# 决定「本次提交」要不要发布：只有 versionName 与 versionCode **都严格大于**基准
# （上一次推送的提交）才发布。
#
# 用法：
#   tools/compare-version.sh <旧versionName> <旧versionCode> <新versionName> <新versionCode>
#
# 输出：stdout 的**最后一行**是决定（CI 用 $(...) 捕获），原因写在 stderr。
#   release  两个都严格大于基准 → 打包并发布 Release
#   skip     两个都与基准相同   → 普通推送（只改代码没升版本），不发布
#   error    其余情况（只升了一个、或其中一个降了）→ 版本配置有误，让 CI 红
#
# 为什么不用字符串比 versionName：`1.0.10` 按字符串比会小于 `1.0.9`（'1' < '9'），
# 于是「升了版本却不发布」。这里按「.」切段逐段比数字，段数不同时缺位补 0，
# 因此 1.0.10 > 1.0.9、1.0 = 1.0.0。
set -euo pipefail

old_name="${1:-}"; old_code="${2:-}"; new_name="${3:-}"; new_code="${4:-}"
if [[ -z "$old_name" || -z "$old_code" || -z "$new_name" || -z "$new_code" ]]; then
  echo "用法：tools/compare-version.sh <旧versionName> <旧versionCode> <新versionName> <新versionCode>" >&2
  exit 2
fi

# 比较两个版本串：打印 greater / lower / equal。
compare_names() {
  local -a a b
  IFS='.' read -r -a a <<<"$1"
  IFS='.' read -r -a b <<<"$2"
  local count=${#a[@]}
  (( ${#b[@]} > count )) && count=${#b[@]}
  local i x y
  for (( i = 0; i < count; i++ )); do
    x="${a[i]:-0}"; y="${b[i]:-0}"
    if [[ "$x" =~ ^[0-9]+$ && "$y" =~ ^[0-9]+$ ]]; then
      (( y > x )) && { printf 'greater\n'; return; }
      (( y < x )) && { printf 'lower\n'; return; }
    else
      # 非纯数字段（例如 1.0.0-rc1）退回字典序，避免静默判成相等。
      [[ "$y" > "$x" ]] && { printf 'greater\n'; return; }
      [[ "$y" < "$x" ]] && { printf 'lower\n'; return; }
    fi
  done
  printf 'equal\n'
}

# 比较两个 versionCode：必须是整数。
compare_codes() {
  if [[ ! "$1" =~ ^[0-9]+$ || ! "$2" =~ ^[0-9]+$ ]]; then
    echo "versionCode 必须是整数（收到 $1 / $2）" >&2
    exit 2
  fi
  if (( $2 > $1 )); then printf 'greater\n'
  elif (( $2 < $1 )); then printf 'lower\n'
  else printf 'equal\n'
  fi
}

name_cmp="$(compare_names "$old_name" "$new_name")"
code_cmp="$(compare_codes "$old_code" "$new_code")"

echo "基准：versionName=${old_name} versionCode=${old_code}" >&2
echo "本次：versionName=${new_name} versionCode=${new_code}" >&2
echo "比较：versionName ${name_cmp} / versionCode ${code_cmp}" >&2

if [[ "$name_cmp" == greater && "$code_cmp" == greater ]]; then
  echo "两个都严格大于基准，发布。" >&2
  printf 'release\n'
elif [[ "$name_cmp" == equal && "$code_cmp" == equal ]]; then
  echo "两个都与基准相同，不发布。" >&2
  printf 'skip\n'
else
  echo "版本必须**同时**大于基准才发布：versionName ${old_name} → ${new_name}（${name_cmp}）、versionCode ${old_code} → ${new_code}（${code_cmp}）。" >&2
  printf 'error\n'
fi
