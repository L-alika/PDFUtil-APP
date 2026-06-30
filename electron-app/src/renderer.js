// 格式转换工具 - 渲染进程脚本
const API_BASE = '';  // 同源，无需前缀

// ==================== 统一通知提示系统 ====================
const Toast = {
    container: null,
    maxToasts: 3,
    toasts: [],

    init() {
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.className = 'fixed top-20 right-6 z-50 flex flex-col gap-2 pointer-events-none';
            document.body.appendChild(this.container);
        }
    },

    show(message, type = 'info', duration = 3000) {
        this.init();

        // 限制最大数量
        if (this.toasts.length >= this.maxToasts) {
            const oldToast = this.toasts.shift();
            oldToast.remove();
        }

        const toast = document.createElement('div');
        const styles = this.getStyles(type);

        toast.className = `
            pointer-events-auto flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg
            transform transition-all duration-300 ease-out
            translate-x-full opacity-0
            ${styles.bg} ${styles.border} border
            min-w-[280px] max-w-[400px]
        `;

        toast.innerHTML = `
            <span class="material-symbols-outlined ${styles.iconColor}">${styles.icon}</span>
            <div class="flex-1">
                <p class="text-sm font-medium ${styles.textColor}">${message}</p>
            </div>
            <button class="opacity-60 hover:opacity-100 transition-opacity" onclick="Toast.dismiss(this.parentElement)">
                <span class="material-symbols-outlined text-sm ${styles.iconColor}">close</span>
            </button>
        `;

        this.container.appendChild(toast);
        this.toasts.push(toast);

        // 显示动画
        requestAnimationFrame(() => {
            toast.classList.remove('translate-x-full', 'opacity-0');
        });

        // 自动隐藏
        if (duration > 0) {
            setTimeout(() => {
                this.dismiss(toast);
            }, duration);
        }

        return toast;
    },

    dismiss(toast) {
        if (!toast || toast.classList.contains('dismissing')) return;
        toast.classList.add('dismissing');
        toast.classList.add('translate-x-full', 'opacity-0');

        setTimeout(() => {
            const index = this.toasts.indexOf(toast);
            if (index > -1) {
                this.toasts.splice(index, 1);
            }
            toast.remove();
        }, 300);
    },

    getStyles(type) {
        const styles = {
            success: {
                bg: 'bg-green-50',
                border: 'border-green-200',
                icon: 'check_circle',
                iconColor: 'text-green-600',
                textColor: 'text-green-800'
            },
            error: {
                bg: 'bg-red-50',
                border: 'border-red-200',
                icon: 'error',
                iconColor: 'text-red-600',
                textColor: 'text-red-800'
            },
            warning: {
                bg: 'bg-amber-50',
                border: 'border-amber-200',
                icon: 'warning',
                iconColor: 'text-amber-600',
                textColor: 'text-amber-800'
            },
            info: {
                bg: 'bg-blue-50',
                border: 'border-blue-200',
                icon: 'info',
                iconColor: 'text-blue-600',
                textColor: 'text-blue-800'
            }
        };
        return styles[type] || styles.info;
    },

    success(message, duration) { return this.show(message, 'success', duration); },
    error(message, duration) { return this.show(message, 'error', duration); },
    warning(message, duration) { return this.show(message, 'warning', duration); },
    info(message, duration) { return this.show(message, 'info', duration); }
};

// 文件列表
let filesToConvert = [];
let currentPage = 1;
const pageSize = 10;

// DOM元素
const uploadArea = document.getElementById('uploadArea');
const fileInput = document.getElementById('fileInput');
const uploadBtn = document.getElementById('uploadBtn');
const clearBtn = document.getElementById('clearBtn');
const filesList = document.getElementById('filesList');
const startConvertBtn = document.getElementById('startConvertBtn');
const recordsList = document.getElementById('recordsList');
const refreshRecordsBtn = document.getElementById('refreshRecordsBtn');
const clearAllBtn = document.getElementById('clearAllBtn');
const pagination = document.getElementById('pagination');
const statusText = document.getElementById('statusText');

// 初始化
document.addEventListener('DOMContentLoaded', () => {
    initUploadArea();
    loadRecords();
});

// 初始化上传区域
function initUploadArea() {
    // 点击上传区域触发文件选择
    uploadArea.addEventListener('click', () => fileInput.click());
    uploadBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        fileInput.click();
    });

    // 文件选择变化
    fileInput.addEventListener('change', handleFileSelect);

    // 拖拽上传
    uploadArea.addEventListener('dragover', (e) => {
        e.preventDefault();
        uploadArea.classList.add('dragover');
    });

    uploadArea.addEventListener('dragleave', () => {
        uploadArea.classList.remove('dragover');
    });

    uploadArea.addEventListener('drop', (e) => {
        e.preventDefault();
        uploadArea.classList.remove('dragover');
        handleFiles(e.dataTransfer.files);
    });

    // 清空按钮
    clearBtn.addEventListener('click', () => {
        filesToConvert = [];
        renderFilesList();
    });

    // 开始转换按钮
    startConvertBtn.addEventListener('click', startConversion);

    // 刷新记录按钮
    refreshRecordsBtn.addEventListener('click', loadRecords);

    // 清空全部按钮
    clearAllBtn.addEventListener('click', clearAllRecords);
}

// 处理文件选择
function handleFileSelect(e) {
    handleFiles(e.target.files);
    fileInput.value = '';  // 允许重复选择同一文件
}

// 处理文件
function handleFiles(fileList) {
    for (const file of fileList) {
        // 检查是否已存在
        if (!filesToConvert.some(f => f.name === file.name && f.size === file.size)) {
            filesToConvert.push({
                id: Date.now() + Math.random(),
                file: file,
                name: file.name,
                size: file.size,
                status: 'pending',
                progress: 0
            });
        }
    }
    renderFilesList();
}

// 渲染文件列表
function renderFilesList() {
    if (filesToConvert.length === 0) {
        filesList.innerHTML = `
            <tr>
                <td colspan="5" class="empty-state">
                    <div class="empty-state-icon">📂</div>
                    <p>暂无文件，请上传需要转换的文件</p>
                </td>
            </tr>
        `;
        startConvertBtn.disabled = true;
        return;
    }

    filesList.innerHTML = filesToConvert.map(item => `
        <tr data-id="${item.id}">
            <td title="${item.name}">${truncateText(item.name, 30)}</td>
            <td>${formatFileSize(item.size)}</td>
            <td><span class="status-badge status-${item.status}">${getStatusText(item.status)}</span></td>
            <td>
                ${item.status === 'processing' ? `
                    <div class="progress-bar">
                        <div class="progress-fill" style="width: ${item.progress}%"></div>
                    </div>
                    <div class="progress-text">${item.progress}%</div>
                ` : item.status === 'completed' ? `
                    <span style="color: var(--success-color)">✓ 完成</span>
                ` : item.status === 'failed' ? `
                    <span style="color: var(--danger-color)">✗ 失败</span>
                ` : '-'}
            </td>
            <td>
                <button class="btn btn-sm btn-danger" onclick="removeFile('${item.id}')" ${item.status === 'processing' ? 'disabled' : ''}>
                    删除
                </button>
            </td>
        </tr>
    `).join('');

    startConvertBtn.disabled = filesToConvert.every(f => f.status !== 'pending');
}

// 移除文件
function removeFile(id) {
    filesToConvert = filesToConvert.filter(f => f.id != id);
    renderFilesList();
}

// 开始转换
async function startConversion() {
    const pendingFiles = filesToConvert.filter(f => f.status === 'pending');
    if (pendingFiles.length === 0) return;

    startConvertBtn.disabled = true;
    statusText.textContent = '正在转换...';

    // 检查是否全是图片文件
    const imageExts = ['jpg', 'jpeg', 'png', 'tif', 'tiff', 'bmp'];
    const allImages = pendingFiles.every(item => {
        const ext = item.name.split('.').pop().toLowerCase();
        return imageExts.includes(ext);
    });

    // 获取输出模式
    const ocrTypeValue = document.getElementById('ocrType').value;
    const outputMode = ocrTypeValue === 'double' ? 'double' : 'single';

    if (allImages && pendingFiles.length > 1) {
        // 批量图片优化模式：一次性OCR所有图片
        console.log('使用批量图片优化模式，共 ' + pendingFiles.length + ' 个图片');
        statusText.textContent = `批量OCR处理 ${pendingFiles.length} 个图片...`;

        // 标记所有文件为处理中
        pendingFiles.forEach(item => {
            item.status = 'processing';
            item.progress = 10;
        });
        renderFilesList();

        try {
            const result = await uploadImagesBatch(pendingFiles, outputMode);
            if (result.success) {
                // 标记所有文件为完成
                pendingFiles.forEach(item => {
                    item.status = 'completed';
                    item.progress = 100;
                });
                statusText.textContent = `批量转换完成，成功 ${result.imageCount} 个图片`;
            } else {
                throw new Error(result.message || '批量转换失败');
            }
        } catch (err) {
            console.error('批量转换失败:', err);
            pendingFiles.forEach(item => {
                item.status = 'failed';
            });
            statusText.textContent = '批量转换失败: ' + err.message;
        }

        renderFilesList();
    } else {
        // 传统模式：逐个文件转换
        for (const item of pendingFiles) {
            item.status = 'processing';
            item.progress = 0;
            renderFilesList();

            try {
                await uploadAndConvert(item);
                item.status = 'completed';
                item.progress = 100;
            } catch (err) {
                console.error('转换失败:', err);
                item.status = 'failed';
            }

            renderFilesList();
        }

        statusText.textContent = '转换完成';
    }

    startConvertBtn.disabled = false;
    loadRecords();  // 刷新记录
}

// 批量上传图片（性能优化版）
function uploadImagesBatch(files, outputMode) {
    return new Promise((resolve, reject) => {
        const formData = new FormData();
        files.forEach(item => {
            formData.append('files', item.file);
        });

        // 获取输出目录
        const outputDir = document.getElementById('outputDir').value || '';
        formData.append('outputDir', outputDir);
        formData.append('outputMode', outputMode);

        // 进度模拟
        let progress = 10;
        const progressInterval = setInterval(() => {
            if (progress < 90) {
                progress += Math.random() * 5;
                files.forEach(item => {
                    item.progress = Math.min(progress, 90);
                });
                renderFilesList();
            }
        }, 1000);

        fetch(`${API_BASE}/api/pdf/upload-images-batch`, {
            method: 'POST',
            body: formData
        })
            .then(response => {
                clearInterval(progressInterval);
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                if (data.code === 0) {
                    resolve({
                        success: true,
                        imageCount: data.data.imageCount,
                        taskId: data.data.taskId,
                        message: data.msg
                    });
                } else {
                    reject(new Error(data.msg || '批量上传失败'));
                }
            })
            .catch(err => {
                clearInterval(progressInterval);
                reject(err);
            });
    });
}

// 上传并转换文件
function uploadAndConvert(item) {
    return new Promise((resolve, reject) => {
        const formData = new FormData();
        formData.append('file', item.file);

        const ocrType = document.getElementById('ocrType').value;
        formData.append('ocrType', ocrType);

        // 模拟进度更新
        const progressInterval = setInterval(() => {
            if (item.progress < 90) {
                item.progress += Math.random() * 10;
                renderFilesList();
            }
        }, 500);

        fetch(`${API_BASE}/api/pdf/upload`, {
            method: 'POST',
            body: formData
        })
            .then(response => {
                clearInterval(progressInterval);
                if (!response.ok) {
                    throw new Error(`HTTP ${response.status}`);
                }
                return response.json();
            })
            .then(data => {
                if (data.code === 0) {
                    item.progress = 100;
                    resolve(data);
                } else {
                    throw new Error(data.msg || '转换失败');
                }
            })
            .catch(err => {
                clearInterval(progressInterval);
                reject(err);
            });
    });
}

// 加载转换记录
async function loadRecords() {
    try {
        const response = await fetch(`${API_BASE}/api/pdf/records?page=${currentPage}&size=${pageSize}`);
        const data = await response.json();

        if (data.code === 0) {
            renderRecords(data.rows || []);
            renderPagination(data.total || 0);
        }
    } catch (err) {
        console.error('加载记录失败:', err);
        recordsList.innerHTML = `
            <tr>
                <td colspan="5" class="empty-state">
                    <p>加载记录失败，请检查服务是否正常</p>
                </td>
            </tr>
        `;
    }
}

// 渲染记录列表
function renderRecords(records) {
    if (records.length === 0) {
        recordsList.innerHTML = `
            <tr>
                <td colspan="5" class="empty-state">
                    <div class="empty-state-icon">📋</div>
                    <p>暂无转换记录</p>
                </td>
            </tr>
        `;
        return;
    }

    recordsList.innerHTML = records.map(record => `
        <tr>
            <td title="${record.fileName}">${truncateText(record.fileName || '-', 30)}</td>
            <td>${formatDateTime(record.createTime)}</td>
            <td>${record.duration ? record.duration + 'ms' : '-'}</td>
            <td>
                <span class="status-badge status-${record.status === '1' ? 'completed' : 'failed'}">
                    ${record.status === '1' ? '成功' : '失败'}
                </span>
            </td>
            <td>
                ${record.outputPath ? `
                    <button class="btn btn-sm btn-success" onclick="downloadRecord('${record.id}')">下载</button>
                ` : '-'}
                <button class="btn btn-sm btn-danger" onclick="deleteRecord('${record.id}')">删除</button>
            </td>
        </tr>
    `).join('');
}

// 渲染分页
function renderPagination(total) {
    const totalPages = Math.ceil(total / pageSize);

    if (totalPages <= 1) {
        pagination.innerHTML = '';
        return;
    }

    let html = '';

    // 上一页
    html += `<button ${currentPage === 1 ? 'disabled' : ''} onclick="goToPage(${currentPage - 1})">上一页</button>`;

    // 页码
    for (let i = 1; i <= totalPages; i++) {
        if (i === 1 || i === totalPages || (i >= currentPage - 2 && i <= currentPage + 2)) {
            html += `<button class="${i === currentPage ? 'active' : ''}" onclick="goToPage(${i})">${i}</button>`;
        } else if (i === currentPage - 3 || i === currentPage + 3) {
            html += `<button disabled>...</button>`;
        }
    }

    // 下一页
    html += `<button ${currentPage === totalPages ? 'disabled' : ''} onclick="goToPage(${currentPage + 1})">下一页</button>`;

    pagination.innerHTML = html;
}

// 跳转页面
function goToPage(page) {
    currentPage = page;
    loadRecords();
}

// 下载记录
function downloadRecord(id) {
    window.open(`${API_BASE}/api/pdf/download/${id}`, '_blank');
}

// 删除记录
async function deleteRecord(id) {
    if (!confirm('确定要删除此记录吗？')) return;

    try {
        const response = await fetch(`${API_BASE}/api/pdf/records/${id}`, {
            method: 'DELETE'
        });
        const data = await response.json();

        if (data.code === 0) {
            loadRecords();
        } else {
            Toast.error(data.msg || '删除失败');
        }
    } catch (err) {
        console.error('删除失败:', err);
        Toast.error('删除失败');
    }
}

// 清空全部记录
async function clearAllRecords() {
    if (!confirm('确定要清空所有转换记录吗？此操作不可恢复！')) return;

    try {
        const response = await fetch(`${API_BASE}/pdf/record/clear`, {
            method: 'DELETE'
        });
        const data = await response.json();

        if (data.code === 200) {
            Toast.success(data.msg);
            loadRecords();
        } else {
            Toast.error(data.msg || '清空失败');
        }
    } catch (err) {
        console.error('清空记录失败:', err);
        Toast.error('清空失败，请检查网络连接');
    }
}

// 工具函数：格式化文件大小
function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// 工具函数：格式化日期时间
function formatDateTime(dateStr) {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    return date.toLocaleString('zh-CN');
}

// 工具函数：截断文本
function truncateText(text, maxLen) {
    if (!text) return '-';
    return text.length > maxLen ? text.substring(0, maxLen) + '...' : text;
}

// 工具函数：获取状态文本
function getStatusText(status) {
    const statusMap = {
        'pending': '等待中',
        'processing': '处理中',
        'completed': '已完成',
        'failed': '失败'
    };
    return statusMap[status] || status;
}
