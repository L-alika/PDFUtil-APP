#!/bin/bash

# PDF 转换系统 Docker 部署脚本
# 使用方法: ./docker-deploy.sh [命令]

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# 检查 Docker 是否安装
check_docker() {
    if ! command -v docker &> /dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null; then
        log_error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi

    log_info "Docker 版本: $(docker --version)"
    log_info "Docker Compose 版本: $(docker-compose --version)"
    log_info "项目根目录: $PROJECT_ROOT"
}

# 切换到 docker 目录
cd_docker_dir() {
    cd "$SCRIPT_DIR" || exit 1
}

# 构建镜像
build_image() {
    cd_docker_dir
    log_info "开始构建 Docker 镜像..."
    docker-compose build
    log_info "镜像构建完成"
}

# 启动服务
start_services() {
    cd_docker_dir
    log_info "启动服务..."
    docker-compose up -d
    log_info "服务启动完成"

    log_info "服务状态:"
    docker-compose ps
}

# 停止服务
stop_services() {
    cd_docker_dir
    log_info "停止服务..."
    docker-compose stop
    log_info "服务已停止"
}

# 重启服务
restart_services() {
    cd_docker_dir
    log_info "重启服务..."
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
    log_info "资源使用:"
    docker stats --no-stream
}

# 进入容器
enter_container() {
    cd_docker_dir
    log_info "进入 PDF 服务容器..."
    docker-compose exec pdf-service bash
}

# 备份数据
backup_data() {
    backup_dir="$PROJECT_ROOT/backup_$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$backup_dir"

    log_info "备份数据到 $backup_dir ..."

    # 备份数据库
    docker-compose exec -T mysql mysqldump -u root -ppdfutil123456 ry-vue > "$backup_dir/database.sql"

    # 备份上传文件
    docker run --rm -v pdfUtil_pdf-upload:/data -v "$backup_dir":/backup ubuntu tar czf /backup/upload.tar.gz /data

    # 备份输出文件
    docker run --rm -v pdfUtil_pdf-output:/data -v "$backup_dir":/backup ubuntu tar czf /backup/output.tar.gz /data

    log_info "数据备份完成: $backup_dir"
}

# 帮助信息
show_help() {
    cat << EOF
PDF 转换系统 Docker 部署脚本

使用方法:
    ./docker-deploy.sh [命令]

可用命令:
    build       构建 Docker 镜像
    start       启动所有服务
    stop        停止所有服务
    restart     重启所有服务
    logs        查看日志
    status      查看服务状态
    clean       清理所有容器和数据卷
    shell       进入容器 Shell
    backup      备份数据
    help        显示此帮助信息

示例:
    ./docker-deploy.sh build    # 构建镜像
    ./docker-deploy.sh start    # 启动服务
    ./docker-deploy.sh logs     # 查看日志

EOF
}

# 主函数
main() {
    case "$1" in
        build)
            check_docker
            build_image
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
        backup)
            backup_data
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            show_help
            exit 1
            ;;
    esac
}

# 执行主函数
main "$@"
