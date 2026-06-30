@echo off
REM PDF 转换系统 Docker 部署脚本 (Windows)
REM 使用方法: docker-deploy.bat [命令]

setlocal enabledelayedexpansion

if "%1"=="" goto help
if "%1"=="help" goto help
if "%1"=="-h" goto help
if "%1"=="--help" goto help

if "%1"=="build" goto build
if "%1"=="start" goto start
if "%1"=="stop" goto stop
if "%1"=="restart" goto restart
if "%1"=="logs" goto logs
if "%1"=="status" goto status
if "%1"=="clean" goto clean
if "%1"=="shell" goto shell

:help
echo PDF 转换系统 Docker 部署脚本 (Windows)
echo.
echo 使用方法:
echo     docker-deploy.bat [命令]
echo.
echo 可用命令:
echo     build       构建 Docker 镜像
echo     start       启动所有服务
echo     stop        停止所有服务
echo     restart     重启所有服务
echo     logs        查看日志
echo     status      查看服务状态
echo     clean       清理所有容器和数据卷
echo     shell       进入容器 Shell
echo     help        显示此帮助信息
echo.
echo 示例:
echo     docker-deploy.bat build    # 构建镜像
echo     docker-deploy.bat start    # 启动服务
echo     docker-deploy.bat logs     # 查看日志
echo.
goto end

:build
echo [INFO] 开始构建 Docker 镜像...
docker-compose build
if %errorlevel% neq 0 goto error
echo [INFO] 镜像构建完成
goto end

:start
echo [INFO] 启动服务...
docker-compose up -d
if %errorlevel% neq 0 goto error
echo [INFO] 服务启动完成
echo.
echo [INFO] 服务状态:
docker-compose ps
goto end

:stop
echo [INFO] 停止服务...
docker-compose stop
if %errorlevel% neq 0 goto error
echo [INFO] 服务已停止
goto end

:restart
echo [INFO] 重启服务...
docker-compose restart
if %errorlevel% neq 0 goto error
echo [INFO] 服务已重启
goto end

:logs
echo [INFO] 查看日志 (Ctrl+C 退出)...
docker-compose logs -f
goto end

:status
echo [INFO] 服务状态:
docker-compose ps
echo.
echo [INFO] 资源使用:
docker stats --no-stream
goto end

:clean
echo [WARN] 清理所有容器和数据卷？(Y/N)
set /p response=
if /i not "%response%"=="Y" goto end
echo [INFO] 清理资源...
docker-compose down -v
echo [INFO] 资源已清理
goto end

:shell
echo [INFO] 进入 PDF 服务容器...
docker-compose exec pdf-service bash
goto end

:error
echo [ERROR] 命令执行失败
exit /b 1

:end
endlocal
