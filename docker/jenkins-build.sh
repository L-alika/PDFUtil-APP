#!/bin/bash
set -e  # 遇到错误立即退出

# =========================
# PDF 转换系统 - Jenkins 构建脚本
# =========================

# 1. 检查Git变更（可选）
#GIT_CHANGES=$(git diff --name-only HEAD~1 HEAD)
#if [ -z "$GIT_CHANGES" ]; then
#  echo "⚠️ 代码无变更，跳过编译流程"
#  exit 0  # 正常退出，不标记构建失败
#fi
export MAVEN_HOME=/usr/local/apache-maven-3.9.3
export PATH=$MAVEN_HOME/bin:$PATH

# 2. 执行编译
echo "🔧 开始编译 jar 包..."
mvn clean package -DskipTests=true

# 3. 检查编译结果
if [ ! -f "pdfutil-admin/target/pdfutil-admin.jar" ]; then
  echo "❌ 编译失败：pdfutil-admin.jar 未找到"
  exit 1
fi

echo "✅ 编译成功：pdfutil-admin.jar"

# 4. 准备Docker环境（可选，如果 Dockerfile 直接从 target 复制则不需要）
# cd docker
# rm -rf ./jar
# mkdir -p ./jar
#
# # 复制 Jar 包到 docker 目录
# cp ../pdfutil-admin/target/pdfutil-admin.jar ./jar/

# 5. 构建 Docker 镜像
echo "🐳 开始构建 Docker 镜像..."
IMAGE_NAME="pdf-converter"
IMAGE_TAG=${BUILD_NUMBER:-latest}
FULL_IMAGE_NAME="$IMAGE_NAME:$IMAGE_TAG"

docker build -f docker/Dockerfile -t $FULL_IMAGE_NAME .
docker tag $FULL_IMAGE_NAME $IMAGE_NAME:latest

echo "✅ Docker 镜像构建成功: $FULL_IMAGE_NAME"

# 6. 可选：推送到镜像仓库（如果需要）
# docker tag $FULL_IMAGE_NAME your-registry.com/$IMAGE_NAME:$IMAGE_TAG
# docker tag $FULL_IMAGE_NAME your-registry.com/$IMAGE_NAME:latest
# docker push your-registry.com/$IMAGE_NAME:$IMAGE_TAG
# docker push your-registry.com/$IMAGE_NAME:latest

# 7. 可选：保存镜像为 tar 文件（离线部署用）
# mkdir -p ./docker-images
# docker save $FULL_IMAGE_NAME -o ./docker-images/pdf-converter-$IMAGE_TAG.tar
# echo "✅ 镜像已保存到: ./docker-images/pdf-converter-$IMAGE_TAG.tar"

echo "🎉 构建完成！"
