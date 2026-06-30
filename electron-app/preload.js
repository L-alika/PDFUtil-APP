const { contextBridge, ipcRenderer } = require('electron');

// 当前登录用户信息
let currentUser = null;

// 暴露安全的API给渲染进程
contextBridge.exposeInMainWorld('electronAPI', {
    // 获取平台信息
    platform: process.platform,

    // 打开外部链接
    openExternal: (url) => {
        require('electron').shell.openExternal(url);
    },

    // 获取应用版本
    getAppVersion: () => {
        return require('electron').app.getVersion();
    },

    // 登录请求
    login: (credentials) => ipcRenderer.invoke('login', credentials),

    // 监听登录成功事件
    onLoginSuccess: (callback) => ipcRenderer.on('login-success', (event, user) => {
        currentUser = user;
        callback(event, user);
    }),

    // 获取保存的账号密码
    getSavedCredentials: () => ipcRenderer.invoke('get-saved-credentials'),

    // 保存账号密码
    saveCredentials: (credentials) => ipcRenderer.invoke('save-credentials', credentials),

    // 清除保存的账号密码
    clearCredentials: () => ipcRenderer.invoke('clear-credentials'),

    // 获取当前登录用户信息
    getUserInfo: () => Promise.resolve(currentUser),

    // 退出登录
    logout: () => ipcRenderer.send('logout'),

    // 选择文件
    selectFiles: () => ipcRenderer.invoke('select-files'),

    // 选择档案著录文件
    selectArchiveFiles: () => ipcRenderer.invoke('select-archive-files'),

    // 选择输入目录
    selectInputDir: () => ipcRenderer.invoke('select-input-dir'),

    // 选择输出目录
    selectOutputDir: () => ipcRenderer.invoke('select-output-dir'),

    // 扫描目录中的文件
    scanDirectory: (dirPath) => ipcRenderer.invoke('scan-directory', dirPath),

    // 读取文件（用于从目录选择的文件）
    readFile: (filePath) => ipcRenderer.invoke('read-file', filePath),

    // 获取默认输出路径
    getDefaultOutputPath: () => ipcRenderer.invoke('get-default-output-path'),

    // 获取桌面路径
    getDesktopPath: () => ipcRenderer.invoke('get-desktop-path'),

    // 获取用户数据
    getUserData: () => ipcRenderer.invoke('get-user-data'),

    // 设置用户数据
    setUserData: (data) => ipcRenderer.invoke('set-user-data', data),

    // 下载文件
    downloadFile: (url, saveDir) => ipcRenderer.invoke('download-file', url, saveDir),

    // 移除监听器
    removeAllListeners: (channel) => ipcRenderer.removeAllListeners(channel),

    // 授权相关
    sendLicenseValidated: () => ipcRenderer.send('license-validated'),
    exitApp: () => ipcRenderer.send('exit-app')
});
