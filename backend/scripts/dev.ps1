<#
后台开发启动脚本：加载本地环境变量，可选择启动容器或显式初始化新空库，再运行 Spring Boot。
关联模块：compose.yml 提供可选 MySQL / Redis；application-dev.yml 接收连接配置，db 脚本负责初始化。
已有数据库日常启动使用 -SkipInfrastructure，不添加 -InitializeDatabase。
#>
param([switch]$SkipInfrastructure, [switch]$InitializeDatabase)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# 缺少本地配置时生成容器开发凭据；接入已有服务时要求先填写配置。
if (!(Test-Path '.env')) {
  if ($SkipInfrastructure) { throw '请先按照 .env.example 创建 .env 并填写现有 MySQL / Redis 配置。' }
  $dbSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
  $rootSecret = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
  @('DB_HOST=127.0.0.1', 'DB_PORT=13306', 'DB_NAME=star_admin', 'DB_USERNAME=star', "DB_PASSWORD=$dbSecret", "MYSQL_ROOT_PASSWORD=$rootSecret", 'REDIS_HOST=127.0.0.1', 'REDIS_PORT=16379', 'REDIS_DATABASE=0', 'DB_INIT_MODE=never') | Set-Content '.env' -Encoding utf8
}
# 将本地配置注入当前进程，供 application-dev.yml 读取。
foreach ($line in Get-Content '.env') {
  if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
if (!$env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE='dev' }
# 只有显式传入初始化参数时才执行 SQL，日常启动不触碰现有业务数据。
if ($InitializeDatabase) {
  $env:DB_INIT_MODE='always'
}
# 选择容器模式时等待 MySQL 和 Redis 健康；已有服务模式跳过此步骤。
if (!$SkipInfrastructure) {
  docker compose up -d --wait
  if ($LASTEXITCODE -ne 0) { throw 'MySQL / Redis startup failed' }
}
mvn spring-boot:run
exit $LASTEXITCODE
