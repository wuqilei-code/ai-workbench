#!/usr/bin/env sh
# 生成 docker/.env:复制 .env.example 并填入随机密钥/密码,免去手工生成。
#
# 用法:
#   sh gen-env.sh            # 生成(若 .env 已存在则拒绝)
#   sh gen-env.sh --force    # 覆盖已存在的 .env
#
# 生成后请按需补充 AGENT_MODEL / AGENT_MODEL_API_KEY,然后 docker compose up --build
set -eu
cd "$(dirname "$0")"

if [ -f .env ] && [ "${1:-}" != "--force" ]; then
  echo ".env 已存在;如需覆盖请加 --force" >&2
  exit 1
fi

# 32 字节随机十六进制(64 字符)
hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}
# 32 字节随机 Base64
b64() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 32 | tr -d '\n'
  else
    head -c 32 /dev/urandom | base64 | tr -d '\n'
  fi
}

JWT_SECRET="$(hex 32)"
INTERNAL_TOKEN="$(hex 32)"
APP_AES_KEY="$(b64)"
DB_PASS="$(hex 16)"
ROOT_PASS="$(hex 16)"
PG_PASS="$(hex 16)"

# 用 | 作分隔符(Base64 字母表不含 |,安全)
sed \
  -e "s|^JWT_SECRET=.*|JWT_SECRET=${JWT_SECRET}|" \
  -e "s|^APP_AES_KEY=.*|APP_AES_KEY=${APP_AES_KEY}|" \
  -e "s|^INTERNAL_TOKEN=.*|INTERNAL_TOKEN=${INTERNAL_TOKEN}|" \
  -e "s|^SPRING_DATASOURCE_PASSWORD=.*|SPRING_DATASOURCE_PASSWORD=${DB_PASS}|" \
  -e "s|^MYSQL_PASSWORD=.*|MYSQL_PASSWORD=${DB_PASS}|" \
  -e "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=${ROOT_PASS}|" \
  -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=${PG_PASS}|" \
  .env.example > .env

echo "已生成 docker/.env(随机密钥已填入)。"
echo "请按需补充 AGENT_MODEL / AGENT_MODEL_API_KEY,然后: docker compose up --build"
