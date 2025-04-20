#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- 使用环境变量创建 schema，并指定所有者为当前连接用户
    CREATE SCHEMA IF NOT EXISTS ${DB_SCHEMA} AUTHORIZATION ${POSTGRES_USER};

    -- 可选：可以为特定用户设置默认搜索路径，但更推荐在应用连接时指定
    -- ALTER ROLE ${POSTGRES_USER} SET search_path TO public, ${DB_SCHEMA};

    -- 可选：授予特定用户在 schema 下的权限 (如果不是所有者)
    -- GRANT ALL PRIVILEGES ON SCHEMA ${DB_SCHEMA} TO ${SOME_OTHER_USER};
EOSQL

echo "Schemas created successfully."