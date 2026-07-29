#!/usr/bin/env bash
set -euo pipefail

cd "$HOME/stnd-generator"
umask 077
db_password="$(openssl rand -hex 24)"

{
  echo "CSR_SERVER_PORT=18180"
  echo "CSR_DB_USERNAME=postgres"
  echo "CSR_DB_PASSWORD=$db_password"
  echo "CSR_RUNNER_DB_HOST=127.0.0.1"
  echo "CSR_RUNNER_DB_PORT=43396"
  echo "CSR_RUNNER_DB_NAME=dqlite"
  echo "CSR_RUNNER_DB_USER=root"
  echo "CSR_RUNNER_DB_PASSWORD="
  echo 'CSR_RUNNER_VERSION_FILE=C:\WDQ\ide\versionInfo.txt'
} > .env

chmod 600 .env
chmod -R u+rwX,go-rwx app backup data installer-builds
