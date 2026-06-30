#!/bin/bash

# PDF 转换系统 Docker 离线部署脚本
# 适用于无网络环境的服务器部署

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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
    echo -e "${BLUE}[STEP]${NC} $1"
}

# 检查 Docker 是否安装
check_docker() {
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        echo ""
        echo "安装方法："
        echo "  # CentOS/RHEL/麒麟系统"
        echo "  yum install -y docker"
        echo "  systemctl start docker"
        echo "  systemctl enable docker"
        exit 1
    fi

    log_info "Docker 版本: $(docker --version)"
}

# 切换到 docker 目录
cd_docker_dir() {
    cd "$SCRIPT_DIR" || exit 1
}

# 加载镜像
load_images() {
    cd_docker_dir
    log_step "加载 Docker 镜像..."

    if [ ! -d "images" ]; then
        log_error "images 目录不存在！"
        log_error "请先在有网络的环境执行导出操作"
        exit 1
    fi

    image_count=$(ls -1 images/*.tar 2>/dev/null | wc -l)
    if [ "$image_count" -eq 0 ]; then
        log_error "images 目录中没有镜像文件！"
        exit 1
    fi

    log_info "找到 $image_count 个镜像文件"

    for image in images/*.tar; do
        if [ -f "$image" ]; then
            log_info "加载镜像: $(basename "$image")"
            docker load -i "$image"
        fi
    done

    log_info "镜像加载完成"
}

# 启动服务
start_services() {
    cd_docker_dir
    log_step "启动服务..."

    # 创建必要的目录
    mkdir -p upload output logs

    docker-compose up -d

    log_info "服务启动完成"
    log_info "等待服务初始化..."
    sleep 5

    log_info "服务状态:"
    docker-compose ps
}

# 停止服务
stop_services() {
    cd_docker_dir
    log_step "停止服务..."
    docker-compose stop
    log_info "服务已停止"
}

# 重启服务
restart_services() {
    cd_docker_dir
    log_step "重启服务..."
    docker-compose restart
    log_info "服务已重启"
}

# 查看日志
view_logs() {
    cd_docker_dir
    log_info "查看日志 (Ctrl+C 退出)..."
    docker-compose logs -f
}

# 清理资源
clean_resources() {
    cd_docker_dir
    log_warn "清理所有容器和数据卷？(y/N)"
    read -r response
    if [[ "$response" =~ ^([yY][eE][sS]|[yY])+$ ]]; then
        log_info "清理资源..."
        docker-compose down -v
        log_info "资源已清理"
    else
        log_info "取消清理"
    fi
}

# 查看状态
show_status() {
    cd_docker_dir
    log_info "服务状态:"
    docker-compose ps

    echo ""
    log_info "容器资源使用:"
    docker stats --no-stream
}

# 进入容器
enter_container() {
    cd_docker_dir
    log_info "进入 PDF 服务容器..."
    docker-compose exec pdf-service bash
}

# 导出镜像（在有网络的环境执行）
export_images() {
    log_step "导出 Docker 镜像..."

    mkdir -p images

    log_info "导出 MySQL 镜像..."
    docker save mysql:5.7 -o images/mysql-5.7.tar

    log_info "导出 Redis 镜像..."
    docker save redis:6.2-alpine -o images/redis-6.2-alpine.tar

    log_info "导出 PDF 服务镜像（如果已构建）..."
    if docker images | grep -q "pdfUtil.*pdf-service"; then
        IMAGE_NAME=$(docker images | grep "pdfUtil.*pdf-service" | head -1 | awk '{print $1 ":" $2}')
        docker save "$IMAGE_NAME" -o images/pdf-service.tar
    else
        log_warn "PDF 服务镜像未找到，跳过"
        log_warn "请先运行: docker-compose build pdf-service"
    fi

    log_info "镜像导出完成！"
    log_info "镜像文件位置: $SCRIPT_DIR/images/"
    ls -lh images/
}

# 打包部署文件
package_deployment() {
    log_step "打包部署文件..."

    PACKAGE_NAME="pdf-util-offline-$(date +%Y%m%d_%H%M%S).tar.gz"

    log_info "创建部署包: $PACKAGE_NAME"

    # 临时目录
    TEMP_DIR=$(mktemp -d)

    # 复制必要文件
    mkdir -p "$TEMP_DIR/pdfUtil/docker"
    cp -r "$PROJECT_ROOT"/{pdfutil-admin,pdfutil-common,pdfutil-framework,pdfutil-pdf,pom.xml} "$TEMP_DIR/pdfUtil/"
    cp -r "$SCRIPT_DIR"/{docker-compose.yml,mysql,redis} "$TEMP_DIR/pdfUtil/docker/"
    cp "$0" "$TEMP_DIR/pdfUtil/docker/"

    # 创建 images 目录（说明）
    mkdir -p "$TEMP_DIR/pdfUtil/docker/images"
    echo "请将在有网络环境导出的镜像文件放在此目录" > "$TEMP_DIR/pdfUtil/docker/images/README.txt"

    # 打包
    tar czf "$PACKAGE_NAME" -C "$TEMP_DIR" pdfUtil

    # 清理
    rm -rf "$TEMP_DIR"

    log_info "部署包创建完成: $PACKAGE_NAME"
    log_info "文件大小: $(du -h "$PACKAGE_NAME" | cut -f1)"

    log_info ""
    log_info "后续步骤："
    log_info "1. 将 $PACKAGE_NAME 传输到目标服务器"
    log_info "2. 在目标服务器解压: tar xzf $PACKAGE_NAME"
    log_info "3. 将镜像文件放到 docker/images/ 目录"
    log_info "4. 运行: docker/offline-deploy.sh load"
    log_info "5. 运行: docker/offline-deploy.sh start"
}

# 帮助信息
show_help() {
    cat << EOF
${BLUE}PDF 转换系统 Docker 离线部署脚本${NC}

${YELLOW}使用方法:${NC}
    ./offline-deploy.sh [命令]

${YELLOW}离线服务器使用命令:${NC}
    load        加载 Docker 镜像（从 images 目录）
    start       启动所有服务
    stop        停止所有服务
    restart     重启所有服务
    logs        查看日志
    status      查看服务状态
    clean       清理所有容器和数据卷
    shell       进入 PDF 服务容器

${YELLOW}有网络环境使用命令（准备阶段）:${NC}
    export      导出 Docker 镜像到 images 目录
    package     打包完整部署文件（用于传输）

${YELLOW}示例:${NC}
    # 在有网络的环境
    ./offline-deploy.sh export     # 导出镜像
    ./offline-deploy.sh package    # 打包部署文件

    # 在离线服务器
    ./offline-deploy.sh load       # 加载镜像
    ./offline-deploy.sh start      # 启动服务
    ./offline-deploy.sh logs       # 查看日志

${YELLOW}目录结构:${NC}
    docker/
    ├── images/              # 存放导出的镜像文件
    │   ├── mysql-5.7.tar
    │   ├── redis-6.2-alpine.tar
    │   └── pdf-service.tar
    ├── upload/              # 上传文件目录（自动创建）
    ├── output/              # 输出文件目录（自动创建）
    └── logs/                # 日志目录（自动创建）

${YELLOW}注意事项:${NC}
    1. 确保目标服务器已安装 Docker
    2. 镜像文件必须放在 docker/images/ 目录
    3. 首次部署会自动创建 upload、output、logs 目录

EOF
}

# 主函数
main() {
    case "$1" in
        load)
            check_docker
            load_images
            ;;
        start)
            check_docker
            start_services
            ;;
        stop)
            stop_services
            ;;
        restart)
            restart_services
            ;;
        logs)
            view_logs
            ;;
        status)
            show_status
            ;;
        clean)
            clean_resources
            ;;
        shell)
            enter_container
            ;;
        export)
            check_docker
            export_images
            ;;
        package)
            package_deployment
            ;;
        help|--help|-h|"")
            show_help
            ;;
        *)
            log_error "未知命令: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"
