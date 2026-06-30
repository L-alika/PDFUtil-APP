@echo off
REM PDF 转换系统 - Docker 镜像构建和导出脚本 (Windows)
REM 用于在有网络的服务器上构建镜像、运行验证、然后导出为tar包

setlocal enabledelayedexpansion

REM 获取脚本所在目录
set SCRIPT_DIR=%~dp0
set PROJECT_ROOT=%SCRIPT_DIR%..

REM 配置
set EXPORT_DIR=%SCRIPT_DIR%exports
set TIMESTAMP=%date:~0,4%%date:~5,2%%date:~8,2%_%time:~0,2%%time:~3,2%%time:~6,2%
set TIMESTAMP=%TIMESTAMP: =0%
set PACKAGE_NAME=pdf-util-system-latest-%TIMESTAMP%.tar.gz

echo ========================================
echo PDF 转换系统 - 镜像构建和导出工具
echo ========================================
echo.
echo 本脚本将执行以下步骤：
echo.
echo 1. 检查 Docker 环境
echo 2. 清理旧资源
echo 3. 构建 Docker 镜像
echo 4. 启动服务并验证
echo 5. 导出镜像为 tar 包
echo 6. 创建部署包
echo.
pause

REM 切换到 docker 目录
cd /d "%SCRIPT_DIR%"

REM 检查 Docker
echo.
echo [STEP] 检查 Docker 环境
echo ----------------------------------------
docker --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker 未安装！
    pause
    exit /b 1
)

docker-compose --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Compose 未安装！
    pause
    exit /b 1
)

echo [INFO] Docker 版本:
docker --version
echo [INFO] Docker Compose 版本:
docker-compose --version

REM 清理旧资源
echo.
echo [STEP] 清理旧资源
echo ----------------------------------------
echo [INFO] 停止并删除旧容器...
docker-compose down 2>nul

REM 构建镜像
echo.
echo [STEP] 构建 Docker 镜像
echo ----------------------------------------
echo [INFO] 开始构建，这可能需要 10-30 分钟...
echo [INFO] 需要下载: Maven依赖、系统包、Python包等
echo.
docker-compose build pdf-service

if errorlevel 1 (
    echo [ERROR] 镜像构建失败！
    pause
    exit /b 1
)

echo [INFO] 镜像构建完成！

REM 启动服务验证
echo.
echo [STEP] 验证镜像
echo ----------------------------------------
echo [INFO] 启动服务进行验证...

if not exist "upload" mkdir upload
if not exist "output" mkdir output
if not exist "logs" mkdir logs

docker-compose up -d

echo [INFO] 等待服务启动...
timeout /t 15 /nobreak >nul

echo [INFO] 检查容器状态...
docker-compose ps

echo [INFO] 验证 PDF 服务组件...
docker-compose exec -T pdf-service bash -c "/verify.sh" 2>nul

echo.
echo [INFO] ========================================
echo [INFO] 验证完成！请检查上述输出
echo [INFO] 访问 http://localhost:8080 查看服务
echo [INFO] ========================================
echo.
pause

REM 导出镜像
echo.
echo [STEP] 导出 Docker 镜像
echo ----------------------------------------

if not exist "%EXPORT_DIR%" mkdir "%EXPORT_DIR%"

echo [INFO] 导出 MySQL:5.7...
docker save mysql:5.7 -o "%EXPORT_DIR%\mysql-5.7.tar"

echo [INFO] 导出 Redis:6.2-alpine...
docker save redis:6.2-alpine -o "%EXPORT_DIR%\redis-6.2-alpine.tar"

echo [INFO] 导出 PDF 服务镜像...
for /f "tokens=1,2" %%a in ('docker images ^| findstr "pdf-service"') do (
    docker save %%a:%%b -o "%EXPORT_DIR%\pdf-service.tar"
    goto :found
)
:found

echo [INFO] 导出完成！文件列表:
dir /b "%EXPORT_DIR%\*.tar"

REM 停止服务
echo.
echo [STEP] 停止服务
echo ----------------------------------------
docker-compose down
echo [INFO] 服务已停止

REM 打包部署文件
echo.
echo [STEP] 打包部署文件
echo ----------------------------------------

set DEPLOY_DIR=%EXPORT_DIR%\deployment
if exist "%DEPLOY_DIR%" rmdir /s /q "%DEPLOY_DIR%"
mkdir "%DEPLOY_DIR%"

echo [INFO] 准备部署文件...

REM 复制必要文件
echo [INFO] 复制 Docker 配置文件...
xcopy /E /I /Y docker-compose.yml "%DEPLOY_DIR%\" >nul
xcopy /E /I /Y mysql "%DEPLOY_DIR%\mysql\" >nul
xcopy /E /I /Y redis "%DEPLOY_DIR%\redis\" >nul
copy /Y offline-deploy.bat "%DEPLOY_DIR%\" >nul
copy /Y 离线Docker部署指南.md "%DEPLOY_DIR%\" >nul
copy /Y OCR组件说明.md "%DEPLOY_DIR%\" >nul

REM 创建导入脚本
echo [INFO] 创建导入脚本...

(
echo @echo off
echo setlocal enabledelayedexpansion
echo.
echo set SCRIPT_DIR=%%~dp0
echo set EXPORTS_DIR=%%SCRIPT_DIR%%..\
echo.
echo echo ========================================
echo echo PDF 转换系统 - 离线部署
echo echo ========================================
echo echo.
echo.
echo echo 导入 Docker 镜像...
echo for %%%%i in ^^("%%EXPORTS_DIR%%*.tar"^) do ^(
echo     echo 导入: %%%%~nxi
echo     docker load -i "%%%%i"
echo ^)
echo.
echo echo.
echo echo 启动服务...
echo cd /d "%%SCRIPT_DIR%%"
echo if not exist "upload" mkdir upload
echo if not exist "output" mkdir output
echo if not exist "logs" mkdir logs
echo.
echo call offline-deploy.bat start
echo.
echo timeout /t 10 /nobreak ^>nul
echo.
echo echo.
echo echo ========================================
echo echo 部署完成^!
echo echo 访问地址: http://localhost:8080
echo echo ========================================
echo.
) > "%DEPLOY_DIR%\import-and-run.bat"

REM 打包
echo [INFO] 创建部署包: %PACKAGE_NAME%
cd /d "%EXPORT_DIR%"

REM 使用 tar (如果有的话) 或 7zip
where tar >nul 2>&1
if %errorlevel% equ 0 (
    tar czf "%PACKAGE_NAME%" deployment\ *.tar
) else (
    echo [WARN] 未找到 tar 命令，请手动打包 deployment 文件夹和 *.tar 文件
    echo [INFO] 或者安装 Git for Windows 或 7-Zip
)

echo [INFO] 部署包创建完成！
dir /b "%EXPORT_DIR%\*.tar.gz"

echo.
echo ========================================
echo 导出完成！
echo.
echo 部署文件位置: %EXPORT_DIR%\%PACKAGE_NAME%
echo.
echo 下一步：
echo 1. 将 %PACKAGE_NAME% 传输到离线服务器
echo 2. 在离线服务器解压
echo 3. 运行 import-and-run.bat
echo ========================================
echo.

pause
