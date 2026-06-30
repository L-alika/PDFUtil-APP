const { app, BrowserWindow, Menu, dialog, shell, ipcMain } = require('electron');
const { spawn, exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const iconv = require('iconv-lite');
const https = require('https');
const http = require('http');

let javaProcess = null;
let mainWindow = null;
let loginWindow = null;
let licenseWindow = null;
let isQuitting = false;
let isLoggedIn = false;
let isLicensed = false;

// ==================== 单实例锁 ====================
// 确保只有一个应用实例运行，避免端口冲突
const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    // 如果获取锁失败，说明已有实例在运行，直接退出
    console.log('应用已在运行，退出当前实例');
    app.quit();
} else {
    // 当第二个实例启动时，聚焦到已有窗口
    app.on('second-instance', (event, commandLine, workingDirectory) => {
        console.log('检测到第二个实例，聚焦到已有窗口');
        // 如果主窗口存在，聚焦它
        if (mainWindow) {
            if (mainWindow.isMinimized()) {
                mainWindow.restore();
            }
            mainWindow.focus();
        } else if (loginWindow) {
            // 如果登录窗口存在，聚焦它
            if (loginWindow.isMinimized()) {
                loginWindow.restore();
            }
            loginWindow.focus();
        } else if (licenseWindow) {
            // 如果授权窗口存在，聚焦它
            if (licenseWindow.isMinimized()) {
                licenseWindow.restore();
            }
            licenseWindow.focus();
        }
    });
}

// Java后端端口
const SERVER_PORT = 8139;
const SERVER_URL = `http://localhost:${SERVER_PORT}`;

// 判断是否为开发环境
const isDev = !app.isPackaged;

// 日志文件路径
const logFilePath = isDev
    ? path.join(__dirname, 'app.log')
    : path.join(app.getPath('userData'), 'app.log');

// 配置文件路径
const configDir = isDev
    ? path.join(__dirname, 'config')
    : path.join(app.getPath('userData'), 'config');

const configFilePath = path.join(configDir, 'config.json');
const userDataFilePath = path.join(configDir, 'user-data.json');

// 默认配置
const defaultConfig = {
    username: 'admin',
    password: 'admin@123'
};

// 加载配置
function loadConfig() {
    try {
        if (!fs.existsSync(configDir)) {
            fs.mkdirSync(configDir, { recursive: true });
        }
        if (fs.existsSync(configFilePath)) {
            const content = fs.readFileSync(configFilePath, 'utf8');
            return { ...defaultConfig, ...JSON.parse(content) };
        }
    } catch (e) {
        log(`加载配置失败: ${e.message}`);
    }
    return defaultConfig;
}

// 保存配置
function saveConfig(config) {
    try {
        if (!fs.existsSync(configDir)) {
            fs.mkdirSync(configDir, { recursive: true });
        }
        fs.writeFileSync(configFilePath, JSON.stringify(config, null, 2), 'utf8');
        return true;
    } catch (e) {
        log(`保存配置失败: ${e.message}`);
        return false;
    }
}

// 加载用户数据
function loadUserData() {
    try {
        if (fs.existsSync(userDataFilePath)) {
            const content = fs.readFileSync(userDataFilePath, 'utf8');
            return JSON.parse(content);
        }
    } catch (e) {
        log(`加载用户数据失败: ${e.message}`);
    }
    return {};
}

// 保存用户数据
function saveUserData(data) {
    try {
        if (!fs.existsSync(configDir)) {
            fs.mkdirSync(configDir, { recursive: true });
        }
        const existing = loadUserData();
        fs.writeFileSync(userDataFilePath, JSON.stringify({ ...existing, ...data }, null, 2), 'utf8');
        return true;
    } catch (e) {
        log(`保存用户数据失败: ${e.message}`);
        return false;
    }
}

// 全局配置对象
let appConfig = loadConfig();

// 获取凭证保存路径
function getCredentialsPath() {
    return path.join(configDir, 'credentials.json');
}

// 加载保存的凭证
function loadSavedCredentials() {
    try {
        const credPath = getCredentialsPath();
        if (fs.existsSync(credPath)) {
            const content = fs.readFileSync(credPath, 'utf8');
            return JSON.parse(content);
        }
    } catch (e) {
        log(`加载凭证失败: ${e.message}`);
    }
    return null;
}

// 保存凭证
function saveCredentials(credentials) {
    try {
        if (!fs.existsSync(configDir)) {
            fs.mkdirSync(configDir, { recursive: true });
        }
        const credPath = getCredentialsPath();
        fs.writeFileSync(credPath, JSON.stringify(credentials, null, 2), 'utf8');
        return true;
    } catch (e) {
        log(`保存凭证失败: ${e.message}`);
        return false;
    }
}

// 清除凭证
function clearCredentials() {
    try {
        const credPath = getCredentialsPath();
        if (fs.existsSync(credPath)) {
            fs.unlinkSync(credPath);
        }
        return true;
    } catch (e) {
        log(`清除凭证失败: ${e.message}`);
        return false;
    }
}

// 写入日志
function log(message) {
    const timestamp = new Date().toISOString();
    const logMessage = `[${timestamp}] ${message}`;

    // 直接输出 UTF-8（控制台已设置 chcp 65001）
    console.log(logMessage);

    // 写入日志文件 (保持 UTF-8)
    try {
        fs.appendFileSync(logFilePath, logMessage + '\n');
    } catch (e) {
        // 忽略日志写入错误
    }
}

// 安全截断字符串（避免截断多字节字符）
function safeTruncate(str, maxLen) {
    if (str.length <= maxLen) return str;
    // 找到一个安全的截断点
    let len = 0;
    let i = 0;
    while (i < str.length && len < maxLen) {
        const code = str.charCodeAt(i);
        if (code >= 0xD800 && code <= 0xDBFF && i + 1 < str.length) {
            // 代理对（emoji等）
            i += 2;
            len += 2;
        } else if (code > 0x7F) {
            // 非ASCII字符
            i += 1;
            len += 2;
        } else {
            i += 1;
            len += 1;
        }
    }
    return str.substring(0, i) + '...';
}

// 获取资源路径
function getResourcePath() {
    if (isDev) {
        return path.join(__dirname, '..');
    } else {
        return process.resourcesPath;
    }
}

// 获取JAR文件路径
function getJarPath() {
    if (isDev) {
        return path.join(__dirname, '..', 'pdfutil-admin', 'target', 'pdfutil-admin.jar');
    } else {
        return path.join(process.resourcesPath, 'pdfutil-admin.jar');
    }
}

// ==================== 设备授权相关 ====================

// 检查授权状态
async function checkLicense() {
    try {
        const response = await new Promise((resolve, reject) => {
            const options = {
                hostname: 'localhost',
                port: SERVER_PORT,
                path: '/api/license/status',
                method: 'GET',
                timeout: 5000
            };

            const req = http.request(options, (res) => {
                let data = '';
                res.on('data', (chunk) => data += chunk);
                res.on('end', () => {
                    try {
                        resolve(JSON.parse(data));
                    } catch (e) {
                        reject(e);
                    }
                });
            });

            req.on('error', reject);
            req.on('timeout', () => {
                req.destroy();
                reject(new Error('timeout'));
            });

            req.end();
        });

        // PDFUtil框架：code 0 表示成功
        if ((response.code === 0 || response.code === 200) && response.data) {
            isLicensed = response.data.licensed;
            return isLicensed;
        }
        return false;
    } catch (error) {
        log(`检查授权状态失败: ${error.message}`);
        return false;
    }
}

// 创建授权窗口
function createLicenseWindow() {
    log('创建授权窗口...');

    licenseWindow = new BrowserWindow({
        width: 600,
        height: 700,
        resizable: false,
        maximizable: false,
        minimizable: true,
        title: '设备授权 - 格式转换工具',
        icon: path.join(__dirname, 'assets', 'icon.ico'),
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        }
    });

    licenseWindow.setMenu(null);

    // 授权窗口加载页面
    licenseWindow.loadFile(path.join(__dirname, 'src', 'license.html')).then(() => {
        log('授权页面加载成功');
    }).catch((err) => {
        log(`授权页面加载失败: ${err.message}`);
        dialog.showErrorBox('加载失败', `无法加载授权页面: ${err.message}`);
    });

    licenseWindow.on('closed', () => {
        log('授权窗口关闭');
        licenseWindow = null;
        if (!isLicensed) {
            log('用户未授权关闭窗口，退出应用');
            isQuitting = true;
            app.quit();
        }
    });
}

// 查找 Python 可执行文件路径
function findPythonPath() {
    const possiblePaths = [
        // 系统级安装 (InstallAllUsers=1)
        'C:\\Program Files\\Python310\\python.exe',
        // 用户安装的 Python 3.10
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Python', 'Python310', 'python.exe'),
        // 另一种系统安装路径
        'C:\\Python310\\python.exe',
        // 用户安装的 Python 3.11
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Python', 'Python311', 'python.exe'),
        // 用户安装的 Python 3.12
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'Python', 'Python312', 'python.exe'),
        // Windows Store Python
        path.join(process.env.LOCALAPPDATA || '', 'Microsoft', 'WindowsApps', 'python.exe'),
    ];

    for (const p of possiblePaths) {
        if (fs.existsSync(p)) {
            log(`找到 Python: ${p}`);
            return p;
        }
    }

    // 如果都找不到，返回默认的 python 命令
    log('使用默认 Python 命令');
    return 'python';
}

// 启动Java后端
function startJavaBackend() {
    return new Promise((resolve, reject) => {
        const jarPath = getJarPath();
        const modelsPath = path.join(getResourcePath(), 'models');
        const scriptsPath = path.join(getResourcePath(), 'scripts');
        const pythonPath = findPythonPath();

        log(`JAR路径: ${jarPath}`);
        log(`JAR存在: ${fs.existsSync(jarPath)}`);
        log(`Python路径: ${pythonPath}`);

        if (!fs.existsSync(jarPath)) {
            const error = `JAR文件不存在: ${jarPath}`;
            log(error);
            reject(new Error(`${error}\n请先运行 mvn package 构建项目`));
            return;
        }

        log('启动Java后端...');

        // 【修改】使用RapidOCR脚本（高性能，推荐）
        // 如果rapidocr_wrapper.py不存在，回退到paddleocr_wrapper.py
        let ocrScriptPath = path.join(scriptsPath, 'rapidocr_wrapper.py');
        const ocrServiceScriptPath = path.join(scriptsPath, 'rapidocr_service.py');
        let ocrType = 'rapid';

        if (!fs.existsSync(ocrScriptPath)) {
            log('[WARN] RapidOCR脚本不存在，回退到PaddleOCR');
            ocrScriptPath = path.join(scriptsPath, 'paddleocr_wrapper.py');
            ocrType = 'local_paddle';
        }

        log(`OCR引擎: ${ocrType}`);
        log(`脚本路径: ${ocrScriptPath}`);
        log(`脚本存在: ${fs.existsSync(ocrScriptPath)}`);
        log(`常驻OCR服务脚本: ${ocrServiceScriptPath}`);
        log(`常驻OCR服务脚本存在: ${fs.existsSync(ocrServiceScriptPath)}`);
        log(`模型路径: ${modelsPath}`);
        log(`模型存在: ${fs.existsSync(modelsPath)}`);

        const javaArgs = [
            '-Dfile.encoding=UTF-8',
            `-Dpdfutil.pdf.pythonPath=${pythonPath}`,
            `-Dpython.path=${pythonPath}`,
            '-jar',
            jarPath,
            `--server.port=${SERVER_PORT}`,
            '--spring.profiles.active=desktop',
            `--pdfutil.pdf.pythonPath=${pythonPath}`,
            // OCR引擎配置（与application.yml保持一致）
            `-Dpdfutil.pdf.ocrType=${ocrType}`,
            `-Docr.engine=${ocrType}`,
            `-DRAPIDOCR_SCRIPT=${ocrScriptPath}`,
            `-Dpdfutil.pdf.rapidOcrScriptPath=${ocrScriptPath}`,
            `-Drapidocr.service.script.path=${ocrServiceScriptPath}`,
            '-Dpdfutil.pdf.residentOcrEnabled=true',
            `-DRAPIDOCR_MODEL_DIR=${path.join(modelsPath, 'rapidocr')}`,
            `-Dpdfutil.pdf.rapidOcrModelDir=${path.join(modelsPath, 'rapidocr')}`,
            // PaddleOCR配置（兼容）
            `-DPADDLEOCR_SCRIPT=${path.join(scriptsPath, 'paddleocr_wrapper.py')}`,
            `-Dpdfutil.pdf.paddleOcrScriptPath=${path.join(scriptsPath, 'paddleocr_wrapper.py')}`,
        ];

        // 构建增强的 PATH 环境变量
        const extraPaths = [];

        // 添加 Poppler 路径
        const popplerPath = 'C:\\Program Files\\PDFUtil\\poppler-25.12.0\\Library\\bin';
        if (fs.existsSync(popplerPath)) {
            extraPaths.push(popplerPath);
            log(`添加 Poppler 到 PATH: ${popplerPath}`);
        }

        // 添加 Ghostscript 路径
        const gsDirs = ['C:\\Program Files\\gs'];
        for (const gsDir of gsDirs) {
            if (fs.existsSync(gsDir)) {
                const subDirs = fs.readdirSync(gsDir, { withFileTypes: true });
                for (const subDir of subDirs) {
                    if (subDir.isDirectory() && subDir.name.startsWith('gs')) {
                        const gsBin = path.join(gsDir, subDir.name, 'bin');
                        if (fs.existsSync(gsBin)) {
                            extraPaths.push(gsBin);
                            log(`添加 Ghostscript 到 PATH: ${gsBin}`);
                        }
                    }
                }
            }
        }

        // 添加 Java 路径
        const javaPaths = [
            'D:\\Alika_\\Java\\jdk-11\\bin',
            'C:\\Program Files\\Java\\jdk-11\\bin',
            'C:\\Program Files\\Eclipse Adoptium\\jdk-11\\bin'
        ];
        for (const jp of javaPaths) {
            if (fs.existsSync(jp)) {
                extraPaths.push(jp);
                break;
            }
        }

        // 添加 Python 路径
        if (pythonPath !== 'python' && fs.existsSync(pythonPath)) {
            const pythonDir = path.dirname(pythonPath);
            extraPaths.push(pythonDir);

            // 添加 PaddlePaddle DLL 路径 (Windows)
            if (process.platform === 'win32') {
                const paddleLibsPaths = [
                    path.join(pythonDir, '..', 'lib', 'site-packages', 'paddle', 'libs'),
                    path.join(pythonDir, 'Lib', 'site-packages', 'paddle', 'libs'),
                ];
                for (const libsPath of paddleLibsPaths) {
                    const absPath = path.resolve(libsPath);
                    if (fs.existsSync(absPath)) {
                        extraPaths.push(absPath);
                        console.log('Added PaddlePaddle DLL path:', absPath);
                        break;
                    }
                }
            }
        }

        // 构建新 PATH
        const originalPath = process.env.PATH || '';
        const newPath = extraPaths.length > 0
            ? [...extraPaths, originalPath].join(';')
            : originalPath;

        const env = {
            ...process.env,
            'PATH': newPath,
            'RAPIDOCR_SCRIPT': ocrScriptPath,
            'RAPIDOCR_SERVICE_SCRIPT': ocrServiceScriptPath,
            'RAPIDOCR_MODEL_DIR': path.join(modelsPath, 'rapidocr'),
            'PYTHON_PATH': pythonPath,
            'PYTHONIOENCODING': 'utf-8',
            'JAVA_TOOL_OPTIONS': '-Dfile.encoding=UTF-8',
            // 解决 OpenMP 多次加载问题
            'KMP_DUPLICATE_LIB_OK': 'TRUE',
        };

        javaProcess = spawn('java', javaArgs, {
            stdio: ['ignore', 'pipe', 'pipe'],
            detached: false,
            env: env
        });

        let startupOutput = '';

        javaProcess.stdout.on('data', (data) => {
            // Java 已设置 -Dfile.encoding=UTF-8，直接解码为 UTF-8
            const output = data.toString('utf8');
            startupOutput += output;
            log(`[Java stdout] ${safeTruncate(output.trim(), 200)}`);

            if (output.includes('Started PdfUtilApplication') || output.includes('Tomcat started on port')) {
                log('Java后端启动成功');
                resolve();
            }
        });

        javaProcess.stderr.on('data', (data) => {
            // Java 已设置 -Dfile.encoding=UTF-8，直接解码为 UTF-8
            const output = data.toString('utf8');
            log(`[Java stderr] ${safeTruncate(output.trim(), 200)}`);
            startupOutput += output;

            if (output.includes('Started PdfUtilApplication') || output.includes('Tomcat started on port')) {
                log('Java后端启动成功');
                resolve();
            }
        });

        javaProcess.on('error', (err) => {
            log(`Java进程启动失败: ${err.message}`);
            reject(new Error(`Java进程启动失败: ${err.message}\n请确保已安装Java运行环境`));
        });

        javaProcess.on('exit', (code, signal) => {
            log(`Java进程退出: code=${code}, signal=${signal}`);
            if (!isQuitting && code !== 0) {
                const errorMsg = `Java后端服务意外退出，退出码: ${code}`;
                dialog.showErrorBox('后端服务异常退出', errorMsg);
            }
        });

        setTimeout(() => {
            resolve();
        }, 60000);
    });
}

// 创建登录窗口
function createLoginWindow() {
    log('创建登录窗口...');

    loginWindow = new BrowserWindow({
        width: 520,
        height: 750,
        minWidth: 480,
        minHeight: 700,
        title: '登录 - 格式转换工具',
        resizable: false,
        maximizable: false,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        }
    });

    loginWindow.setMenu(null);

    loginWindow.loadFile(path.join(__dirname, 'src', 'login.html')).then(() => {
        log('登录页面加载成功');
    }).catch((err) => {
        log(`登录页面加载失败: ${err.message}`);
        dialog.showErrorBox('加载失败', `无法加载登录页面: ${err.message}`);
    });

    loginWindow.on('closed', () => {
        log('登录窗口关闭');
        loginWindow = null;
        if (!isLoggedIn && !mainWindow) {
            log('用户未登录关闭窗口，退出应用');
            isQuitting = true;
            app.quit();
        }
    });

    log('登录窗口创建完成');
}

// 处理登录请求
async function handleLogin(event, credentials) {
    log(`收到登录请求: ${credentials.username}`);

    const isValid = credentials.username === appConfig.username && credentials.password === appConfig.password;

    if (isValid) {
        log(`用户 ${credentials.username} 登录成功`);
        isLoggedIn = true;
        const currentUser = { username: credentials.username };

        if (credentials.rememberMe) {
            saveCredentials({
                username: credentials.username,
                password: credentials.password
            });
        } else {
            clearCredentials();
        }

        event.sender.send('login-success', currentUser);

        setTimeout(async () => {
            if (loginWindow) {
                loginWindow.close();
                loginWindow = null;
            }

            const loadingWindow = new BrowserWindow({
                width: 500,
                height: 350,
                frame: false,
                resizable: false,
                webPreferences: {
                    nodeIntegration: false,
                    contextIsolation: true
                }
            });
            loadingWindow.loadFile(path.join(__dirname, 'src', 'loading.html'));

            try {
                // 检查Java后端是否已运行，如未运行则启动
                if (!javaProcess || javaProcess.exitCode !== null) {
                    log('启动Java后端...');
                    await startJavaBackend();
                    log('等待服务器就绪...');
                    await new Promise(resolve => setTimeout(resolve, 2000));
                } else {
                    log('Java后端已在运行，跳过启动');
                }

                createWindow();

                loadingWindow.close();

                log('应用启动完成');
            } catch (err) {
                log(`启动失败: ${err.message}`);
                loadingWindow.close();
                dialog.showErrorBox('启动失败', `${err.message}`);
                app.quit();
            }
        }, 500);

        return { success: true };
    } else {
        log(`用户 ${credentials.username} 登录失败: 用户名或密码错误`);
        return { success: false, message: '用户名或密码错误' };
    }
}

// 创建主窗口
function createWindow() {
    log('创建主窗口...');

    mainWindow = new BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 1100,
        minHeight: 700,
        title: '格式转换工具 - 批量处理',
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.js')
        }
    });

    mainWindow.loadFile(path.join(__dirname, 'src', 'index.html')).then(() => {
        log('主页面加载成功');
    }).catch((err) => {
        log(`页面加载失败: ${err.message}`);
        dialog.showErrorBox('加载失败', `无法加载应用页面: ${err.message}`);
    });

    createMenu(mainWindow.isFullScreen());

    // 监听全屏状态变化，更新菜单标签
    mainWindow.on('enter-full-screen', () => {
        updateFullscreenMenuLabel(true);
    });

    mainWindow.on('leave-full-screen', () => {
        updateFullscreenMenuLabel(false);
    });

    mainWindow.on('closed', () => {
        log('主窗口关闭');
        mainWindow = null;
    });

    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: 'deny' };
    });

    log('主窗口创建完成');
}

// 创建应用菜单
function createMenu(isFullScreen = false) {
    const template = [
        {
            label: '文件',
            submenu: [
                { role: 'quit', label: '退出' }
            ]
        },
        {
            label: '视图',
            submenu: [
                { role: 'reload', label: '刷新' },
                { role: 'toggleDevTools', label: '开发者工具' },
                { type: 'separator' },
                { role: 'resetZoom', label: '重置缩放' },
                { role: 'zoomIn', label: '放大' },
                { role: 'zoomOut', label: '缩小' },
                { type: 'separator' },
                {
                    label: isFullScreen ? '退出全屏' : '全屏',
                    click: () => {
                        if (mainWindow) {
                            const currentFullScreen = mainWindow.isFullScreen();
                            mainWindow.setFullScreen(!currentFullScreen);
                        }
                    }
                }
            ]
        },
        {
            label: '帮助',
            submenu: [
                {
                    label: '关于',
                    click: () => {
                        dialog.showMessageBox(mainWindow, {
                            type: 'info',
                            title: '关于',
                            message: '格式转换工具',
                            detail: '版本: 1.0.0\n基于PaddleOCR的PDF OCR识别转换工具'
                        });
                    }
                }
            ]
        }
    ];

    const menu = Menu.buildFromTemplate(template);
    Menu.setApplicationMenu(menu);
}

// 更新全屏菜单标签（通过重新创建菜单）
function updateFullscreenMenuLabel(isFullScreen) {
    createMenu(isFullScreen);
}

// 停止Java后端
function stopJavaBackend() {
    if (javaProcess) {
        console.log('正在停止Java后端...');
        isQuitting = true;

        if (process.platform === 'win32') {
            spawn('taskkill', ['/pid', javaProcess.pid, '/f', '/t'], { shell: true });
        } else {
            javaProcess.kill('SIGTERM');
        }

        javaProcess = null;
    }
}

// 扫描目录中的图片文件
async function scanDirectory(dirPath) {
    try {
        const files = [];
        const supportedExts = ['.jpg', '.jpeg', '.png', '.tiff', '.tif', '.bmp', '.pdf', '.ofd', '.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx'];

        function scanDir(currentPath) {
            const items = fs.readdirSync(currentPath);
            for (const item of items) {
                const fullPath = path.join(currentPath, item);
                const stat = fs.statSync(fullPath);
                if (stat.isDirectory()) {
                    scanDir(fullPath);
                } else {
                    const ext = path.extname(item).toLowerCase();
                    if (supportedExts.includes(ext)) {
                        files.push({
                            path: fullPath,
                            name: item,
                            size: stat.size
                        });
                    }
                }
            }
        }

        scanDir(dirPath);
        return { success: true, files };
    } catch (error) {
        log(`扫描目录失败: ${error.message}`);
        return { success: false, message: error.message };
    }
}

// 读取文件内容
async function readFile(filePath) {
    try {
        if (!fs.existsSync(filePath)) {
            return null;
        }
        const buffer = fs.readFileSync(filePath);
        return buffer;
    } catch (error) {
        log(`读取文件失败: ${error.message}`);
        return null;
    }
}

// 下载文件
async function downloadFile(url, saveDir, filename) {
    return new Promise((resolve, reject) => {
        try {
            // 确保目录存在
            if (!fs.existsSync(saveDir)) {
                fs.mkdirSync(saveDir, { recursive: true });
            }

            const fileName = filename || url.split('/').pop() || 'download.pdf';
            const filePath = path.join(saveDir, fileName);

            // 判断使用 http 还是 https
            const client = url.startsWith('https:') ? https : http;

            const request = client.get(url, (response) => {
                if (response.statusCode === 302 || response.statusCode === 301) {
                    // 重定向
                    downloadFile(response.headers.location, saveDir, filename)
                        .then(resolve)
                        .catch(reject);
                    return;
                }

                if (response.statusCode !== 200) {
                    reject(new Error(`下载失败，状态码: ${response.statusCode}`));
                    return;
                }

                const fileStream = fs.createWriteStream(filePath);
                response.pipe(fileStream);

                fileStream.on('finish', () => {
                    fileStream.close();
                    resolve({ success: true, filePath });
                });

                fileStream.on('error', (err) => {
                    fs.unlinkSync(filePath);
                    reject(err);
                });
            });

            request.on('error', (err) => {
                reject(err);
            });
        } catch (error) {
            reject(error);
        }
    });
}

// ==================== 注册IPC处理器 ====================

ipcMain.handle('login', handleLogin);

ipcMain.handle('get-saved-credentials', () => {
    return loadSavedCredentials();
});

ipcMain.handle('save-credentials', (event, credentials) => {
    return saveCredentials(credentials);
});

ipcMain.handle('clear-credentials', () => {
    return clearCredentials();
});

ipcMain.handle('get-user-data', () => {
    return loadUserData();
});

ipcMain.handle('set-user-data', (event, data) => {
    return saveUserData(data);
});

ipcMain.on('logout', () => {
    log('用户退出登录');
    isLoggedIn = false;
    if (mainWindow) {
        mainWindow.close();
        mainWindow = null;
    }
    stopJavaBackend();
    createLoginWindow();
});

ipcMain.handle('select-input-dir', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openDirectory'],
        title: '选择输入目录'
    });
    if (!result.canceled && result.filePaths.length > 0) {
        return result.filePaths[0];
    }
    return null;
});

ipcMain.handle('select-output-dir', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openDirectory'],
        title: '选择输出目录'
    });
    if (!result.canceled && result.filePaths.length > 0) {
        const dir = result.filePaths[0];
        // 保存最后使用的输出目录
        saveUserData({ lastOutputPath: dir });
        return dir;
    }
    return null;
});

ipcMain.handle('select-files', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openFile', 'multiSelections'],
        title: '选择文件',
        filters: [
            { name: '所有支持的文件', extensions: ['jpg', 'jpeg', 'png', 'tiff', 'tif', 'bmp', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'] },
            { name: '图片文件', extensions: ['jpg', 'jpeg', 'png', 'tiff', 'tif', 'bmp'] },
            { name: 'PDF文件', extensions: ['pdf'] },
            { name: 'Word文档', extensions: ['doc', 'docx'] },
            { name: 'Excel表格', extensions: ['xls', 'xlsx'] },
            { name: 'PPT演示', extensions: ['ppt', 'pptx'] },
            { name: '所有文件', extensions: ['*'] }
        ]
    });
    if (!result.canceled) {
        return result.filePaths;
    }
    return [];
});

ipcMain.handle('select-archive-files', async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
        properties: ['openFile', 'multiSelections'],
        title: '选择档案著录文件',
        filters: [
            { name: 'PDF和图片文件', extensions: ['pdf', 'jpg', 'jpeg', 'png', 'tiff', 'tif', 'bmp'] },
            { name: 'PDF文件', extensions: ['pdf'] },
            { name: '图片文件', extensions: ['jpg', 'jpeg', 'png', 'tiff', 'tif', 'bmp'] },
            { name: '所有文件', extensions: ['*'] }
        ]
    });
    if (result.canceled) {
        return [];
    }

    return result.filePaths.map(filePath => {
        let size = 0;
        try {
            size = fs.statSync(filePath).size;
        } catch (error) {
            log(`读取档案著录文件大小失败: ${filePath}, ${error.message}`);
        }
        return {
            name: path.basename(filePath),
            path: filePath,
            size
        };
    });
});

ipcMain.handle('scan-directory', async (event, dirPath) => {
    return await scanDirectory(dirPath);
});

ipcMain.handle('read-file', async (event, filePath) => {
    return await readFile(filePath);
});

ipcMain.handle('get-default-output-path', () => {
    return path.join(app.getPath('documents'), 'PDF输出');
});

ipcMain.handle('get-desktop-path', () => {
    return app.getPath('desktop');
});

ipcMain.handle('download-file', async (event, url, saveDir) => {
    try {
        const result = await downloadFile(url, saveDir);
        return result;
    } catch (error) {
        return { success: false, message: error.message };
    }
});

// 旧版转换接口 - 已不再使用，保留向后兼容
ipcMain.handle('start-conversion', async (event, options) => {
    log(`收到转换请求: ${JSON.stringify(options)}`);
    // 前端现在直接调用后端API，这里不再需要处理
    return { success: true, message: '请使用前端直接调用API方式' };
});

// 授权相关IPC处理
ipcMain.on('license-validated', async () => {
    log('授权验证成功');
    isLicensed = true;

    // 关闭授权窗口
    if (licenseWindow) {
        licenseWindow.close();
        licenseWindow = null;
    }

    // 显示登录窗口
    createLoginWindow();
});

ipcMain.on('exit-app', () => {
    log('用户退出应用');
    isQuitting = true;
    app.quit();
});

// ==================== 应用生命周期 ====================

app.whenReady().then(async () => {
    try {
        log('应用启动...');
        log(`isPackaged: ${app.isPackaged}`);

        // 首先启动Java后端（授权API需要）
        log('启动Java后端以验证授权...');
        const loadingWindow = new BrowserWindow({
            width: 400,
            height: 300,
            frame: false,
            resizable: false,
            webPreferences: {
                nodeIntegration: false,
                contextIsolation: true
            }
        });

        loadingWindow.loadFile(path.join(__dirname, 'src', 'loading.html'));

        await startJavaBackend();

        // 等待服务就绪（最多等待30秒）
        log('等待Java后端就绪...');
        let retries = 0;
        let backendReady = false;
        while (retries < 30 && !backendReady) {
            try {
                await new Promise(resolve => setTimeout(resolve, 1000));
                const testResponse = await new Promise((resolve, reject) => {
                    const req = http.request({
                        hostname: 'localhost',
                        port: SERVER_PORT,
                        path: '/api/license/status',
                        method: 'GET',
                        timeout: 2000
                    }, (res) => {
                        let data = '';
                        res.on('data', chunk => data += chunk);
                        res.on('end', () => resolve(data));
                    });
                    req.on('error', reject);
                    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
                    req.end();
                });
                log('Java后端已就绪');
                backendReady = true;
            } catch (e) {
                retries++;
                log(`等待后端就绪... (${retries}/30)`);
            }
        }

        if (!backendReady) {
            throw new Error('Java后端启动超时，请检查日志');
        }

        // 检查授权状态
        const licensed = await checkLicense();
        loadingWindow.close();

        if (licensed) {
            log('设备已授权，显示登录窗口');
            createLoginWindow();
        } else {
            log('设备未授权，显示授权窗口');
            createLicenseWindow();
        }

    } catch (err) {
        log(`启动失败: ${err.message}`);
        dialog.showErrorBox('启动失败', `${err.message}`);
        app.quit();
    }
});

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('before-quit', () => {
    stopJavaBackend();
});

app.on('will-quit', () => {
    stopJavaBackend();
});
