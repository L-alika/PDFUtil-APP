# 文档预览

- PDF 使用浏览器 PDF 阅读器显示服务器实际文件。
- DOC、XLS/XLSX、PPT/PPTX 由 LibreOffice 后台转换为保留文字层的 PDF，供侧栏、全屏和双栏预览共用。布局遵循文档打印设置；Excel 的列宽、打印区域和分页设置会影响结果。
- DOCX 默认使用前端分页渲染；后端也生成 PDF 供索引使用。
- 首次预览旧 Office 文件时，若没有 PDF，会提交补生成任务。转换中显示等待；失败提供重新加载和下载原件。不会将 Office 二进制伪装成 PDF。
- 转换保留原件，不在转换后强制栅格化或做 OCR；索引阶段沿用现有按页原生文字/OCR 策略。

## 运行依赖

后端需要 LibreOffice Writer、Calc 和 Impress，以及文档使用的字体。Debian/Ubuntu 可安装：

```sh
apt-get install --no-install-recommends libreoffice-writer libreoffice-calc libreoffice-impress fonts-noto-cjk
```

通过 `LIBREOFFICE_PATH` 指定可执行文件（默认 `libreoffice`），或配置 `pdfutil.pdf.libreofficePath`。路径包含空格时仍作为一个可执行文件路径传入，不经过 shell。部署机器必须安装这些依赖；只更新前端不能启用 Office 转换。

每个任务使用独立临时目录和用户配置，最多同时运行两个转换进程。等待并发槽超时为 30 秒，单次转换超时为 120 秒；转换结束验证 PDF 后存储预览并清理临时文件。损坏、加密或缺少转换依赖时任务标记失败。

LibreOffice 和 Microsoft Office 的字体、公式、复杂图形及分页可能存在差异；此预览不是交互式 Excel/PPT 编辑器。TIFF 和 OFD 尚未接入专用预览。

## 回归验证

1. 上传 DOC、DOCX、XLS、XLSX、PPT、PPTX 和 PDF，检查详情从 WAITING/PROCESSING 转为 SUCCESS。
2. 请求 `/api/server/doc/stream-preview/{id}`，检查 `Content-Type: application/pdf`、`%PDF-` 文件头、有效页面和可提取文字。
3. 在侧栏、全屏与双栏模式打开 Office/PDF，验证加载状态、文档切换、错误提示、重新加载和下载原件。
4. 使用缺少预览 PDF 的旧 Office 文件，验证前端 POST `/api/server/doc/prepare-preview/{id}` 后可重新生成，分页索引不会重复。
5. 上传损坏的 Office 文件，验证任务 FAILED，预览返回 404，界面显示错误而非空白阅读器。
