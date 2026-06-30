!macro customHeader
  !system "echo 'Custom NSIS Script for PDFUtil Desktop'"
!macroend

!macro customInstall
  ; 创建数据目录
  CreateDirectory "$APPDATA\pdfutil"
  CreateDirectory "$APPDATA\pdfutil\data"
  CreateDirectory "$APPDATA\pdfutil\upload"
  CreateDirectory "$APPDATA\pdfutil\output"
  CreateDirectory "$APPDATA\pdfutil\logs"

  ; 写入配置文件路径
  FileOpen $0 "$APPDATA\pdfutil\paths.ini" w
  FileWrite $0 "[Paths]$\r$\n"
  FileWrite $0 "ModelsDir=$INSTDIR\resources\models$\r$\n"
  FileWrite $0 "ScriptsDir=$INSTDIR\resources\scripts$\r$\n"
  FileWrite $0 "JarPath=$INSTDIR\resources\pdfutil-admin.jar$\r$\n"
  FileClose $0

  ; 创建桌面快捷方式（已由 electron-builder 处理）
  ; 创建开始菜单快捷方式（已由 electron-builder 处理）
!macroend

!macro customUnInstall
  ; 提示用户是否保留数据
  MessageBox MB_YESNO "是否保留应用数据（转换记录、输出文件等）？$\n$\n选择'否'将删除所有应用数据。" IDYES keepData

  ; 删除数据目录
  RMDir /r "$APPDATA\pdfutil"

keepData:
  ; 保留用户数据
!macroend