#!/bin/bash

# PDF 转换系统 - Docker 镜像构建和导出脚本
# 用于在有网络的服务器上构建镜像、运行验证、然后导出为tar包
#
# 使用流程：
# 1. 在有网络的服务器运行: ./build-and-export.sh
# 2. 将生成的 tar 包传输到离线服务器
# 3. 在离线服务器运行: ./import-and-run.sh

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 配置
IMAGE_NAME="pdf-util-system"
IMAGE_TAG="latest"
EXPORT_DIR="$SCRIPT_DIR/exports"
PACKAGE_NAME="$IMAGE_NAME-$IMAGE_TAG-$(date +%Y%m%d_%H%M%S).tar.gz"

# 日志函数
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${CYAN}[STEP]${NC} $1"
}

log_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

# 检查 Docker 环境
check_docker() {
    log_header "检查 Docker 环境"

    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装！"
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose 未安装！"
        exit 1
    fi

    log_info "Docker 版本: $(docker --version)"
    log_info "Docker Compose 版本: $(docker-compose --version)"
    log_info "项目目录: $PROJECT_ROOT"
}

# 清理旧的容器和镜像
cleanup_old() {
    log_header "清理旧资源"

    log_warn "停止并删除旧容器..."
    cd "$SCRIPT_DIR"
    docker-compose down 2>/dev/null || true

    log_info "清理完成"
}

# 构建镜像
build_image() {
    log_header "构建 Docker 镜像"

    cd "$SCRIPT_DIR"

    log_info "开始构建，这可能需要 10-30 分钟..."
    log_info "(需要下载: Maven依赖、系统包、Python包等)"

    docker-compose build pdf-service

    log_info "镜像构建完成！"

    # 显示镜像信息
    docker images | grep pdf-service || true
}

# 启动服务进行验证
verify_image() {
    log_header "验证镜像"

    cd "$SCRIPT_DIR"

    log_info "启动服务进行验证..."
    mkdir -p upload output logs

    docker-compose up -d

    log_info "等待服务启动..."
    sleep 15

    log_info "检查容器状态..."
    docker-compose ps

    # 检查各个服务
    log_info "验证 MySQL..."
    if docker-compose exec -T pdfutil-mysql mysqladmin ping -h localhost -u root -ppdfutil123456 2>/dev/null; then
        log_info "✓ MySQL 运行正常"
    else
        log_warn "✗ MySQL 可能未完全启动，请稍后检查"
    fi

    log_info "验证 Redis..."
    if docker-compose exec -T pdfutil-redis redis-cli ping 2>/dev/null | grep -q PONG; then
        log_info "✓ Redis 运行正常"
    else
        log_warn "✗ Redis 可能未完全启动，请稍后检查"
    fi

    log_info "验证 PDF 服务组件..."
    log_info "运行组件验证脚本..."
    docker-compose exec -T pdf-service /verify.sh 2>/dev/null || log_warn "组件验证脚本执行失败"

    log_info "检查应用日志..."
    docker-compose logs --tail=20 pdf-service

    log_warn "验证完成！如果一切正常，将继续导出镜像"
    log_warn "如需手动验证，可以访问 http://localhost:8080"

    read -p "按 Enter 继续导出镜像，或 Ctrl+C 取消..."
}

# 预加载 PaddleOCR 模型（可选）
preload_models() {
    log_header "预加载 PaddleOCR 模型（可选）"

    log_warn "是否预加载 PaddleOCR 模型？"
    log_warn "预加载会增加导出文件大小，但首次运行更快"
    read -p "预加载模型? (y/N): " -n 1 -r
    echo

    if [[ $REPLY =~ ^[Yy]$ ]]; then
        log_info "预加载模型中..."
        cd "$SCRIPT_DIR"

        log_info "触发 PaddleOCR 模型下载..."
        docker-compose exec -T pdf-service python3 -c "
import sys
sys.stdout.flush()
from paddleocr import PaddleOCR
print('正在下载 PaddleOCR 模型，请稍候...')
ocr = PaddleOCR(use_angle_cls=True, lang='ch', show_log=False)
print('PaddleOCR 模型下载完成！')
" 2>/dev/null || log_warn "模型预加载失败"

        log_info "模型已预加载到镜像中"
    else
        log_info "跳过模型预加载（首次使用时会自动下载）"
    fi
}

# 导出镜像
export_image() {
    log_header "导出 Docker 镜像"

    mkdir -p "$EXPORT_DIR"

    log_info "准备导出镜像..."

    # 获取镜像 ID
    IMAGE_ID=$(docker images | grep "pdfutil.*pdf-service" | head -1 | awk '{print $3}')

    if [ -z "$IMAGE_ID" ]; then
        log_error "未找到 PDF 服务镜像！"
        exit 1
    fi

    log_info "导出镜像到: $EXPORT_DIR"

    # 导出 MySQL
    log_info "导出 MySQL:5.7 ..."
    docker save mysql:5.7 -o "$EXPORT_DIR/mysql-5.7.tar"

    # 导出 Redis
    log_info "导出 Redis:6.2-alpine ..."
    docker save redis:6.2-alpine -o "$EXPORT_DIR/redis-6.2-alpine.tar"

    # 导出 PDF 服务
    log_info "导出 PDF 服务镜像..."
    if [ -n "$IMAGE_ID" ]; then
        docker images | grep "pdfutil.*pdf-service" | head -1 | awk '{print $1 ":" $2}' | while read img; do
            docker save "$img" -o "$EXPORT_DIR/pdf-service.tar"
            break
        done
    fi

    # 显示导出文件大小
    log_info "导出完成！文件列表："
    ls -lh "$EXPORT_DIR"/

    local total_size=$(du -sh "$EXPORT_DIR" | cut -f1)
    log_info "导出目录总大小: $total_size"
}

# 停止服务
stop_services() {
    log_header "停止服务"

    cd "$SCRIPT_DIR"
    docker-compose down

    log_info "服务已停止"
}

# 打包部署文件
package_deployment() {
    log_header "打包部署文件"

    local DEPLOY_DIR="$EXPORT_DIR/deployment"
    mkdir -p "$DEPLOY_DIR"

    log_info "准备部署文件..."

    # 复制必要文件
    log_info "复制 Docker 配置文件..."
    cp -r "$SCRIPT_DIR"/{docker-compose.yml,mysql,redis,offline-deploy.sh,offline-deploy.bat,离线Docker部署指南.md,OCR组件说明.md} "$DEPLOY_DIR/"

    # 创建导入脚本
    log_info "创建导入脚本..."
    cat > "$DEPLOY_DIR/import-and-run.sh" << 'EOF'
#!/bin/bash

# PDF 转换系统 - 镜像导入和运行脚本（离线服务器）
# 支持: CentOS, RHEL, 麒麟V10, Ubuntu等Linux系统

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPORTS_DIR="$SCRIPT_DIR/../"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${CYAN}[STEP]${NC} $1"
}

log_header() {
    echo ""
    echo -e "${CYAN}========================================${NC}"
    echo -e "${CYAN}$1${NC}"
    echo -e "${CYAN}========================================${NC}"
    echo ""
}

# 检测操作系统
detect_os() {
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        OS_NAME=$NAME
        OS_VERSION=$VERSION
        log_info "检测到操作系统: $OS_NAME $OS_VERSION"

        # 麒麟V10特殊处理
        if echo "$OS_NAME" | grep -qi "kylin"; then
            log_warn "检测到麒麟Linux系统"
            log_info "将应用麒麟系统优化配置"
            return 0
        fi
    else
        log_warn "无法检测操作系统类型"
    fi
    return 1
}

# 检查系统配置
check_system() {
    log_header "检查系统配置"

    # 检查内存
    local total_mem=$(free -g | awk '/^Mem:/{print $2}')
    log_info "系统内存: ${total_mem}GB"
    if [ "$total_mem" -lt 12 ]; then
        log_warn "内存不足12GB，可能影响性能"
    fi

    # 检查磁盘空间
    local disk_free=$(df -h / | awk 'NR==2{print $4}')
    log_info "可用磁盘空间: $disk_free"

    # 检查Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装！"
        log_error "请先安装 Docker: yum install -y docker docker-compose"
        exit 1
    fi

    # 检查Docker服务
    if ! systemctl is-active --quiet docker; then
        log_warn "Docker 服务未运行，正在启动..."
        sudo systemctl start docker || {
            log_error "无法启动Docker服务"
            exit 1
        }
    fi

    log_info "Docker 版本: $(docker --version)"
    log_info "Docker Compose 版本: $(docker-compose --version)"
}

# 麒麟V10系统优化
optimize_kylin() {
    if [ -f /etc/os-release ] && grep -qi "kylin" /etc/os-release; then
        log_info "应用麒麟V10优化配置..."

        # 检查SELinux
        if [ -f /etc/selinux/config ]; then
            local selinux_status=$(getenforce 2>/dev/null || echo "Disabled")
            if [ "$selinux_status" = "Enforcing" ]; then
                log_warn "SELinux 处于 Enforcing 模式，可能导致容器问题"
                log_warn "建议: sudo setenforce 0 (临时) 或 编辑 /etc/selinux/config 设置 SELINUX=permissive"
            fi
        fi

        # 创建docker配置目录
        if [ ! -f /etc/docker/daemon.json ]; then
            log_info "创建Docker配置文件..."
            sudo mkdir -p /etc/docker
            cat | sudo tee /etc/docker/daemon.json > /dev/null << 'DOCKERCFG'
{
  "storage-driver": "overlay2",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "3"
  }
}
DOCKERCFG
            log_info "已创建 /etc/docker/daemon.json"
        fi
    fi
}

# 导入镜像
import_images() {
    log_header "导入 Docker 镜像"

    if [ ! -d "$EXPORTS_DIR" ]; then
        log_error "找不到导出目录 $EXPORTS_DIR"
        exit 1
    fi

    log_info "从 $EXPORTS_DIR 导入镜像..."

    local img_count=0
    for img in "$EXPORTS_DIR"/*.tar; do
        if [ -f "$img" ]; then
            log_info "导入: $(basename "$img")"
            docker load -i "$img"
            ((img_count++))
        fi
    done

    if [ $img_count -eq 0 ]; then
        log_error "没有找到镜像文件！"
        log_error "请确保镜像tar文件在: $EXPORTS_DIR"
        exit 1
    fi

    log_info "成功导入 $img_count 个镜像！"
    echo ""
    log_info "已导入的镜像:"
    docker images | grep -E 'mysql|redis|pdf-service'
}

# 启动服务
start_services() {
    log_header "启动服务"

    cd "$SCRIPT_DIR"

    mkdir -p upload output logs

    chmod +x offline-deploy.sh
    ./offline-deploy.sh start

    log_info "等待服务启动..."
    sleep 15

    log_info "服务状态："
    docker-compose ps

    log_info ""
    log_info "验证组件..."
    if docker-compose exec -T pdf-service /verify.sh 2>/dev/null; then
        log_info "✓ 组件验证通过"
    else
        log_warn "组件验证失败，请检查日志"
        log_warn "运行: docker-compose logs pdf-service"
    fi

    log_info ""
    log_info "=========================================="
    log_info "部署完成！"
    log_info ""
    log_info "访问地址: http://localhost:8080"
    log_info "Swagger文档: http://localhost:8080/swagger-ui/"
    log_info "查看日志: ./offline-deploy.sh logs"
    log_info "进入容器: docker-compose exec pdf-service bash"
    log_info ""
    log_info "常用命令:"
    log_info "  查看状态: docker-compose ps"
    log_info "  查看日志: docker-compose logs -f"
    log_info "  重启服务: docker-compose restart"
    log_info "  停止服务: docker-compose stop"
    log_info "=========================================="
}

# 主流程
main() {
    clear
    log_header "PDF 转换系统 - 离线服务器部署"

    log_info "本脚本将自动完成以下步骤："
    echo ""
    echo "1. 检测操作系统类型"
    echo "2. 检查系统配置"
    echo "3. 应用系统优化（麒麟V10）"
    echo "4. 导入Docker镜像"
    echo "5. 启动服务"
    echo "6. 验证组件"
    echo ""
    read -p "按 Enter 开始部署，或 Ctrl+C 取消..."

    detect_os
    check_system
    optimize_kylin
    import_images
    start_services
}

main "$@"
EOF

    chmod +x "$DEPLOY_DIR/import-and-run.sh"

    # Windows 版本的导入脚本
    cat > "$DEPLOY_DIR/import-and-run.bat" << 'EOF'
@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set EXPORTS_DIR=%SCRIPT_DIR%..\

echo ========================================
echo PDF 转换系统 - 离线部署
echo ========================================
echo.

echo 导入 Docker 镜像...
for %%i in ("%EXPORTS_DIR%*.tar") do (
    echo 导入: %%~nxi
    docker load -i "%%i"
)

echo.
echo 启动服务...
cd /d "%SCRIPT_DIR%"
mkdir upload output logs 2>nul

offline-deploy.bat start

timeout /t 10 /nobreak >nul

echo.
echo ========================================
echo 部署完成！
echo 访问地址: http://localhost:8080
echo ========================================
EOF

    # 创建镜像目录说明
    cat > "$DEPLOY_DIR/../images/README.txt" << EOF
镜像文件说明
=============

这些镜像文件是从有网络的服务器导出的，包含以下组件：

- mysql-5.7.tar: MySQL 数据库
- redis-6.2-alpine.tar: Redis 缓存
- pdf-service.tar: PDF 转换服务（包含完整OCR功能）

导入方法：
1. Linux/Mac: docker load -i mysql-5.7.tar
2. Windows: docker load -i mysql-5.7.tar

或使用自动化脚本: ./import-and-run.sh
EOF

    # 打包
    log_info "创建部署包: $PACKAGE_NAME"
    cd "$EXPORT_DIR"
    tar czf "$PACKAGE_NAME" deployment/ *.tar

    log_info "部署包创建完成！"
    ls -lh "$PACKAGE_NAME"

    local pkg_size=$(du -h "$PACKAGE_NAME" | cut -f1)
    log_info "部署包大小: $pkg_size"

    # 清理临时文件
    rm -rf deployment

    log_info ""
    log_info "=========================================="
    log_info "导出完成！"
    log_info ""
    log_info "部署文件位置: $EXPORT_DIR/$PACKAGE_NAME"
    log_info "文件大小: $pkg_size"
    log_info ""
    log_info "下一步："
    log_info "1. 将 $PACKAGE_NAME 传输到离线服务器"
    log_info "2. 在离线服务器解压: tar xzf $PACKAGE_NAME"
    log_info "3. 进入解压后的目录"
    log_info "4. 运行: ./import-and-run.sh (Linux) 或 import-and-run.bat (Windows)"
    log_info "=========================================="
}

# 主流程
main() {
    clear

    log_header "PDF 转换系统 - 镜像构建和导出工具"

    echo "本脚本将执行以下步骤："
    echo ""
    echo "1 ✓ 检查 Docker 环境"
    echo "2 ✓ 清理旧资源"
    echo "3 ✓ 构建 Docker 镜像（包含: LibreOffice + OCRmyPDF + PaddleOCR）"
    echo "4 ✓ 启动服务并验证"
    echo "5 ✓ (可选) 预加载 PaddleOCR 模型"
    echo "6 ✓ 导出镜像为 tar 包"
    echo "7 ✓ 创建部署包"
    echo ""
    read -p "按 Enter 开始，或 Ctrl+C 取消..."

    check_docker
    cleanup_old
    build_image
    verify_image
    preload_models
    stop_services
    export_image
    package_deployment

    log_header "全部完成！"
}

# 执行主流程
main "$@"
