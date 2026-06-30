@echo off
REM PDF 转换系统 Docker 离线部署脚本 (Windows 版本)
REM 适用于无网络环境的服务器部署

setlocal enabledelayedexpansion

REM 获取脚本所在目录
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..

REM 颜色设置（Windows 10+）
set INFO=[INFO]
set WARN=[WARN]
set ERROR=[ERROR]
set STEP=[STEP]

REM 日志函数
:log_info
echo %INFO% %~1
goto :eof

:log_warn
echo %WARN% %~1
goto :eof

:log_error
echo %ERROR% %~1
goto :eof

:log_step
echo %STEP% %~1
goto :eof

REM 检查 Docker 是否安装
:check_docker
docker --version >nul 2>&1
if errorlevel 1 (
    call :log_error "Docker 未安装，请先安装 Docker Desktop"
    exit /b 1
)
docker --version
goto :eof

REM 切换到 docker 目录
:cd_docker_dir
cd /d "%SCRIPT_DIR%"
goto :eof

REM 加载镜像
:load_images
call :cd_docker_dir
call :log_step "加载 Docker 镜像..."

if not exist "images" (
    call :log_error "images 目录不存在！"
    call :log_error "请先在有网络的环境执行导出操作"
    exit /b 1
)

dir /b images\*.tar >nul 2>&1
if errorlevel 1 (
    call :log_error "images 目录中没有镜像文件！"
    exit /b 1
)

call :log_info "开始加载镜像..."
for %%i in (images\*.tar) do (
    call :log_info "加载镜像: %%~nxi"
    docker load -i "%%i"
)

call :log_info "镜像加载完成"
goto :eof

REM 启动服务
:start_services
call :cd_docker_dir
call :log_step "启动服务..."

if not exist "upload" mkdir upload
if not exist "output" mkdir output
if not exist "logs" mkdir logs

docker-compose up -d

call :log_info "服务启动完成"
timeout /t 5 /nobreak >nul

call :log_info "服务状态:"
docker-compose ps
goto :eof

REM 停止服务
:stop_services
call :cd_docker_dir
call :log_step "停止服务..."
docker-compose stop
call :log_info "服务已停止"
goto :eof

REM 重启服务
:restart_services
call :cd_docker_dir
call :log_step "重启服务..."
docker-compose restart
call :log_info "服务已重启"
goto :eof

REM 查看日志
:view_logs
call :cd_docker_dir
call :log_info "查看日志 (Ctrl+C 退出)..."
docker-compose logs -f
goto :eof

REM 清理资源
:clean_resources
call :cd_docker_dir
call :log_warn "清理所有容器和数据卷？(Y/N)"
set /p response=
if /i "%response%"=="Y" (
    call :log_info "清理资源..."
    docker-compose down -v
    call :log_info "资源已清理"
) else (
    call :log_info "取消清理"
)
goto :eof

REM 查看状态
:show_status
call :cd_docker_dir
call :log_info "服务状态:"
docker-compose ps

echo.
call :log_info "容器资源使用:"
docker stats --no-stream
goto :eof

REM 导出镜像（在有网络的环境执行）
:export_images
call :log_step "导出 Docker 镜像..."

if not exist "images" mkdir images

call :log_info "导出 MySQL 镜像..."
docker save mysql:5.7 -o images/mysql-5.7.tar

call :log_info "导出 Redis 镜像..."
docker save redis:6.2-alpine -o images/redis-6.2-alpine.tar

call :log_info "导出 PDF 服务镜像（如果已构建）..."
docker images | findstr "pdf-service" >nul
if not errorlevel 1 (
    for /f "tokens=1,2" %%a in ('docker images ^| findstr "pdf-service"') do (
        docker save %%a:%%b -o images/pdf-service.tar
        goto :found_pdf
    )
    :found_pdf
) else (
    call :log_warn "PDF 服务镜像未找到，跳过"
    call :log_warn "请先运行: docker-compose build pdf-service"
)

call :log_info "镜像导出完成！"
call :log_info "镜像文件位置: %SCRIPT_DIR%images\"
dir images\
goto :eof

REM 帮助信息
:show_help
echo PDF 转换系统 Docker 离线部署脚本
echo.
echo 使用方法:
echo     offline-deploy.bat [命令]
echo.
echo 离线服务器使用命令:
echo     load        加载 Docker 镜像（从 images 目录）
echo     start       启动所有服务
echo     stop        停止所有服务
echo     restart     重启所有服务
echo     logs        查看日志
echo     status      查看服务状态
echo     clean       清理所有容器和数据卷
echo.
echo 有网络环境使用命令（准备阶段）:
echo     export      导出 Docker 镜像到 images 目录
echo.
echo 示例:
echo     在有网络的环境:
echo     offline-deploy.bat export     # 导出镜像
echo.
echo     在离线服务器:
echo     offline-deploy.bat load       # 加载镜像
echo     offline-deploy.bat start      # 启动服务
echo     offline-deploy.bat logs       # 查看日志
echo.
goto :eof

REM 主函数
:main
if "%1"=="load" (
    call :check_docker
    call :load_images
) else if "%1"=="start" (
    call :check_docker
    call :start_services
) else if "%1"=="stop" (
    call :stop_services
) else if "%1"=="restart" (
    call :restart_services
) else if "%1"=="logs" (
    call :view_logs
) else if "%1"=="status" (
    call :show_status
) else if "%1"=="clean" (
    call :clean_resources
) else if "%1"=="export" (
    call :check_docker
    call :export_images
) else if "%1"=="help" (
    call :show_help
) else if "%1"=="--help" (
    call :show_help
) else if "%1"=="-h" (
    call :show_help
) else (
    call :show_help
)

goto :eof

call :main %*
