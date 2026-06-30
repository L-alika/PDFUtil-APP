# =========================
# 阶段1: 复制已编译的 jar
# =========================
FROM scratch AS builder
COPY pdfutil-admin/target/pdfutil-admin.jar /app/pdfutil-admin.jar

# =========================
# 阶段2: 运行环境基础镜像
# =========================
FROM eclipse-temurin:8-jre

LABEL maintainer="Alika" \
      version="1.1" \
      description="PDF Conversion with PaddleOCR + OCRmyPDF + LibreOffice"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/tmp \
    USER=app \
    PATH="/opt/venv/bin:$PATH" \
    PADDLEOCR_MODEL_DIR=/opt/pdfutil/models \
    PDF_UPLOAD_DIR=/opt/pdfutil/pdf/upload \
    PDF_OUTPUT_DIR=/opt/pdfutil/pdf/output \
    JAVA_OPTS="-Xmx2g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# =========================
# 时区 + 系统依赖 + 安装 Python 环境
# =========================
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone && \
    apt-get update && apt-get install -y \
    libreoffice-writer libreoffice-core \
    fonts-noto-cjk fonts-wqy-zenhei fonts-wqy-microhei \
    python3 python3-venv python3-pip python3-dev \
    build-essential wget curl swig \
    poppler-utils tesseract-ocr tesseract-ocr-chi-sim \
    tesseract-ocr-chi-tra tesseract-ocr-eng \
    ghostscript unpaper qpdf pngquant imagemagick fontconfig \
    libxrender1 libxi6 libxext6 libxinerama1 libxrandr2 \
    libfontconfig1 libfreetype6 libxcb1 \
    libgomp1 libglib2.0-0 libsm6 \
    zlib1g-dev libffi-dev libxml2-dev libxslt1-dev \
    libjpeg-dev libtiff-dev liblcms2-dev libwebp-dev \
    libharfbuzz-dev libjbig2dec0 libopenjp2-7 \
    && rm -rf /var/lib/apt/lists/*

# =========================
# 安装 Python 依赖
# =========================
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --upgrade pip setuptools wheel -i https://pypi.tuna.tsinghua.edu.cn/simple && \
    echo "numpy<2.0" > /tmp/constraints.txt && \
    /opt/venv/bin/pip install \
    paddlepaddle==2.6.2 \
    paddleocr==2.6.1.3 \
    ocrmypdf==14.2.0 \
    --constraint /tmp/constraints.txt \
    -i https://pypi.tuna.tsinghua.edu.cn/simple && \
    rm -rf /root/.cache/pip /tmp/constraints.txt

# =========================
# 预下载 PaddleOCR 模型
# =========================
RUN mkdir -p /opt/pdfutil/models && \
    /opt/venv/bin/python3 - <<EOF
from paddleocr import PaddleOCR
ocr = PaddleOCR(use_angle_cls=True, lang='ch', show_log=False)
ocr.download_and_load_model()  # 下载并加载模型
EOF

# =========================
# 复制 JAR + 创建启动脚本
# =========================
RUN mkdir -p /opt/pdfutil/pdf/upload /opt/pdfutil/pdf/output /opt/pdfutil/logs /tmp/ocr-temp /opt/pdfutil/models
WORKDIR /opt/pdfutil
COPY --from=builder /app/pdfutil-admin.jar ./app.jar

# 创建验证脚本
RUN echo "#!/bin/bash" > /verify.sh && \
    echo "echo \"===============================================\"" >> /verify.sh && \
    echo "echo \"PDF 转换系统 - 组件验证\"" >> /verify.sh && \
    echo "echo \"===============================================\"" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"Java 版本:\"" >> /verify.sh && \
    echo "java -version 2>&1 | head -1" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"LibreOffice 版本:\"" >> /verify.sh && \
    echo "soffice --version" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"Tesseract OCR 版本:\"" >> /verify.sh && \
    echo "tesseract --version | head -1" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"Python 版本:\"" >> /verify.sh && \
    echo "python3 --version" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"PaddlePaddle 版本:\"" >> /verify.sh && \
    echo "/opt/venv/bin/python3 -c \"import paddle; print(f'PaddlePaddle: {paddle.__version__}')\" 2>/dev/null || echo \"未安装\"" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"PaddleOCR 版本:\"" >> /verify.sh && \
    echo "/opt/venv/bin/python3 -c \"import paddleocr; print(f'PaddleOCR: {paddleocr.__version__}')\" 2>/dev/null || echo \"未安装\"" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"OCRmyPDF 版本:\"" >> /verify.sh && \
    echo "/opt/venv/bin/python3 -m ocrmypdf --version 2>/dev/null || echo \"未安装\"" >> /verify.sh && \
    echo "echo \"\"" >> /verify.sh && \
    echo "echo \"已安装中文字体:\"" >> /verify.sh && \
    echo "fc-list :lang=zh | head -5" >> /verify.sh && \
    echo "echo \"===============================================\"" >> /verify.sh && \
    echo "echo \"组件验证完成\"" >> /verify.sh && \
    echo "echo \"===============================================\"" >> /verify.sh && \
    chmod +x /verify.sh

# 创建启动脚本
RUN echo '#!/bin/bash' > /start.sh && \
    echo 'echo "Starting PDF Conversion System..."' >> /start.sh && \
    echo 'echo "Verifying components..."' >> /start.sh && \
    echo '/verify.sh' >> /start.sh && \
    echo 'echo ""' >> /start.sh && \
    echo 'echo "Starting application..."' >> /start.sh && \
    echo 'exec java $JAVA_OPTS \\' >> /start.sh && \
    echo '     -Dspring.profiles.active=prod \\' >> /start.sh && \
    echo '     -DLibreOfficePath=/usr/bin/libreoffice \\' >> /start.sh && \
    echo '     -Dpython.path=/opt/venv/bin/python3 \\' >> /start.sh && \
    echo '     -Dpaddleocr.model.dir=$PADDLEOCR_MODEL_DIR \\' >> /start.sh && \
    echo '     -Dpdf.uploadDir=$PDF_UPLOAD_DIR \\' >> /start.sh && \
    echo '     -Dpdf.outputDir=$PDF_OUTPUT_DIR \\' >> /start.sh && \
    echo '     -Duse.paddleocr=true \\' >> /start.sh && \
    echo '     -jar /opt/pdfutil/app.jar' >> /start.sh && \
    chmod +x /start.sh

# =========================
# 健康检查
# =========================
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -f http://localhost:8080/ || ps aux | grep '[j]ava' || exit 1

# =========================
# 数据卷
# =========================
VOLUME ["/opt/pdfutil/pdf/upload", "/opt/pdfutil/pdf/output", "/opt/pdfutil/logs", "/opt/pdfutil/models"]

CMD ["/bin/bash", "/start.sh"]
