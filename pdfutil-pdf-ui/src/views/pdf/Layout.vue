<template>
  <div class="h-screen w-screen overflow-hidden flex flex-col select-none bg-[#F5F3EF] p-2.5 gap-2.5">
    <div class="flex-1 flex overflow-hidden gap-2.5">

      <!-- ================= LEFT SIDEBAR (Craft Warm Rail) ================= -->
      <aside class="w-64 shrink-0 bg-canvas-sidebar rounded-2xl shadow-craft-md flex flex-col justify-between overflow-hidden border border-white/80">
        <!-- Top Brand & Primary CTA -->
        <div class="p-4 space-y-4">
          <!-- Brand Header -->
          <div class="flex items-center justify-between px-1">
            <div class="flex items-center gap-3">
              <div class="w-9 h-9 rounded-xl bg-gradient-to-br from-brand-500 to-brand-700 flex items-center justify-center text-white shadow-sm">
                <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/>
                </svg>
              </div>
              <div>
                <div class="flex items-center gap-1.5">
                  <span class="font-bold text-[15px] tracking-tight text-ink-primary">PDFUtil</span>
                  <span class="text-[10px] font-medium px-1.5 py-0.5 rounded-md bg-canvas-subtle text-ink-secondary">v2.4</span>
                </div>
                <p class="text-[11px] text-ink-muted leading-tight mt-0.5">智能文档与全文检索</p>
              </div>
            </div>
          </div>

          <!-- Primary Upload Button -->
          <button
            type="button"
            class="w-full py-2.5 px-4 rounded-xl bg-brand-500 hover:bg-brand-600 active:scale-[0.99] text-white font-medium text-[13px] shadow-sm hover:shadow transition-craft flex items-center justify-center gap-2 cursor-pointer"
            @click="docStore.openUploadModal()"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/>
            </svg>
            <span>上传文件</span>
          </button>

          <!-- Primary Navigation -->
          <nav class="space-y-1 pt-1" aria-label="主导航">
            <button
              type="button"
              class="w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-[13px] font-medium transition-craft cursor-pointer"
              :class="isAllFilesActive ? 'bg-brand-50 text-brand-600 shadow-craft-sm border border-brand-100' : 'text-ink-secondary hover:bg-canvas-hover hover:text-ink-primary'"
              @click="navigateAllFiles"
            >
              <span class="flex items-center gap-2.5">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"/>
                </svg>
                <span>全部文档</span>
              </span>
              <span class="text-[11px] font-mono px-1.5 py-0.5 rounded-md bg-canvas-subtle text-ink-secondary">
                {{ docStore.totalDocs }}
              </span>
            </button>

            <button
              type="button"
              class="w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-[13px] font-medium transition-craft cursor-pointer"
              :class="currentRoute.includes('/pdf/search') ? 'bg-brand-50 text-brand-600 shadow-craft-sm border border-brand-100' : 'text-ink-secondary hover:bg-canvas-hover hover:text-ink-primary'"
              @click="navigateSearchHall"
            >
              <span class="flex items-center gap-2.5">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"/>
                </svg>
                <span>全文检索大厅</span>
              </span>
              <span class="text-[10px] px-1.5 py-0.5 rounded-md bg-brand-50 text-brand-600 font-medium">OCR</span>
            </button>
          </nav>

          <!-- Divider -->
          <div class="h-px bg-canvas-border/80 my-1"></div>

          <!-- Directory Tree Section -->
          <div class="space-y-1.5">
            <div class="flex items-center justify-between px-2">
              <span class="text-[11px] font-semibold tracking-wider text-ink-muted uppercase">我的目录</span>
              <button
                type="button"
                title="新建根目录"
                class="w-6 h-6 rounded-lg hover:bg-canvas-hover text-ink-secondary flex items-center justify-center transition-craft cursor-pointer"
                @click="docStore.openFolderModal('0')"
              >
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/>
                </svg>
              </button>
            </div>

            <!-- Dynamic Tree Container -->
            <div class="space-y-0.5 max-h-[290px] overflow-y-auto pr-0.5">
              <div
                v-for="folder in docStore.flatFolderTree"
                :key="folder.id"
                class="group flex items-center justify-between px-2.5 py-2 rounded-xl cursor-pointer text-[12.5px] transition-craft"
                :class="docStore.currentFolderId === folder.id && currentRoute.includes('/pdf/files')
                  ? 'bg-brand-50 text-brand-600 font-semibold border border-brand-100/80 shadow-craft-sm'
                  : 'text-ink-secondary hover:bg-canvas-hover hover:text-ink-primary font-medium'"
                :style="{ paddingLeft: (10 + folder.depth * 14) + 'px' }"
                @click="selectFolder(folder.id)"
              >
                <div class="flex items-center gap-2 min-w-0 flex-1">
                  <span v-if="folder.depth > 0" class="text-ink-faint font-mono text-[11px] -mr-0.5">└</span>
                  <svg class="w-4 h-4 text-amberWarm-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
                  </svg>
                  <span class="truncate">{{ folder.name }}</span>
                </div>

                <!-- Hover Add Subfolder & Count -->
                <div class="flex items-center gap-1 shrink-0">
                  <button
                    type="button"
                    title="在当前目录下新建子目录"
                    class="opacity-0 group-hover:opacity-100 w-5 h-5 rounded hover:bg-white text-ink-muted hover:text-brand-600 flex items-center justify-center transition-craft"
                    @click.stop="docStore.openFolderModal(folder.id)"
                  >
                    <svg class="w-3 h-3" fill="none" stroke="currentColor" stroke-width="2.5" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/>
                    </svg>
                  </button>
                  <span
                    class="text-[10.5px] font-mono px-1.5 py-0.2 rounded"
                    :class="docStore.currentFolderId === folder.id ? 'text-brand-600 bg-white/80' : 'text-ink-muted group-hover:text-ink-secondary'"
                  >
                    {{ getFolderDocCount(folder.id) }}
                  </span>
                </div>
              </div>

              <div v-if="docStore.folders.length === 0" class="text-center py-4 text-[12px] text-ink-muted">
                暂无目录，点击上方 + 新建
              </div>
            </div>
          </div>
        </div>

        <!-- Bottom OCR Engine Status Card -->
        <div class="p-3.5 border-t border-canvas-border/70 bg-canvas-bg/50">
          <div class="p-3 rounded-xl bg-white shadow-craft-sm flex items-center justify-between">
            <div class="flex items-center gap-2.5">
              <span class="relative flex h-2.5 w-2.5">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-sage-600 opacity-75"></span>
                <span class="relative inline-flex rounded-full h-2.5 w-2.5 bg-sage-600"></span>
              </span>
              <div>
                <div class="text-[12px] font-semibold text-ink-primary leading-tight">OCR 智能检索引擎</div>
                <div class="text-[11px] text-sage-600 font-medium mt-0.5">实时在线 · 毫秒解析</div>
              </div>
            </div>
            <span class="text-[10px] font-mono text-ink-muted bg-canvas-subtle px-1.5 py-0.5 rounded">99.8%</span>
          </div>
        </div>
      </aside>

      <!-- ================= MAIN WORKSPACE (Header + Dual-Pane Split Area) ================= -->
      <div class="flex-1 flex flex-col min-w-0 gap-2.5 overflow-hidden">

        <!-- Top Header Bar (Warm Floating Header) -->
        <header class="h-14 shrink-0 bg-canvas-sidebar rounded-2xl shadow-craft-sm px-5 flex items-center justify-between gap-4 border border-white/80">
          <!-- Left Cluster Info & Route Indicator -->
          <div class="flex items-center gap-3 min-w-0">
            <div class="inline-flex items-center gap-2 px-3 py-1.5 rounded-xl bg-canvas-subtle/90 border border-canvas-border/70 text-[12px] font-medium text-ink-secondary">
              <svg class="w-3.5 h-3.5 text-brand-500 shrink-0" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"/>
              </svg>
              <span class="truncate">文档管理空间 · RapidOCR 高可用引擎</span>
            </div>

            <!-- Route Pill Indicator -->
            <div class="hidden xl:flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-white border border-canvas-border text-[11px] font-mono text-ink-muted">
              <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>
              <span>{{ routeUrlDisplay }}</span>
            </div>
          </div>

          <!-- Right Global Search, Dual-Pane Toggle & Admin Profile -->
          <div class="flex items-center gap-3">
            <!-- Global Header Quick Search -->
            <div class="relative w-64 sm:w-72">
              <svg class="w-4 h-4 text-ink-muted absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"/>
              </svg>
              <input
                v-model="quickSearchKeyword"
                type="text"
                placeholder="正文关键字极速检索..."
                class="w-full pl-9 pr-16 py-1.5 rounded-xl bg-white border border-canvas-border focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 text-[12.5px] text-ink-primary placeholder-ink-muted outline-none transition-craft"
                @keyup.enter="doQuickSearch"
              />
              <button
                type="button"
                class="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] font-mono px-1.5 py-0.5 rounded bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary border border-canvas-border/80 transition-craft cursor-pointer"
                @click="doQuickSearch"
              >
                Enter ↵
              </button>
            </div>

            <!-- Toggle Right Instant PDF Preview Pane -->
            <button
              type="button"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[12px] font-medium border transition-craft cursor-pointer"
              :class="docStore.previewOpen ? 'bg-brand-50 text-brand-600 border-brand-100 hover:bg-brand-100/70' : 'bg-white text-ink-secondary border-canvas-border hover:bg-canvas-hover'"
              title="切换右侧同屏 PDF 即时预览栏"
              @click="docStore.togglePreviewPane"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 4.5v15m6-15v15m-10.875 0h15.75c.621 0 1.125-.504 1.125-1.125V5.625c0-.621-.504-1.125-1.125-1.125H4.125C3.504 4.5 3 5.004 3 5.625v12.75c0 .621.504 1.125 1.125 1.125z"/>
              </svg>
              <span>同屏预览</span>
            </button>

            <div class="h-5 w-px bg-canvas-border"></div>

            <!-- User Profile -->
            <div class="flex items-center gap-2.5 pl-0.5">
              <div class="w-8 h-8 rounded-full bg-brand-500/10 border border-brand-500/20 text-brand-600 font-semibold text-[13px] flex items-center justify-center">
                管
              </div>
              <div class="hidden sm:block leading-tight">
                <div class="text-[12.5px] font-semibold text-ink-primary">管理员</div>
                <div class="text-[11px] text-ink-muted">系统超级管理</div>
              </div>
            </div>
          </div>
        </header>

        <!-- ================= DUAL-PANE SPLIT BODY ================= -->
        <div class="flex-1 flex gap-2.5 min-h-0 overflow-hidden">

          <!-- LEFT / PRIMARY CONTENT PANE -->
          <main class="flex-1 bg-canvas-sidebar rounded-2xl shadow-craft-md border border-white/80 overflow-y-auto flex flex-col min-w-0">

            <!-- Quick Directory & Hall Navigation Bar -->
            <div class="px-6 pt-3.5 pb-2.5 border-b border-canvas-border/60 bg-canvas-bg/40 flex flex-wrap items-center justify-between gap-2 shrink-0">
              <div class="flex items-center gap-2">
                <span class="text-[11px] font-semibold text-ink-muted uppercase tracking-wider">空间导航：</span>
                <div class="inline-flex p-0.5 rounded-xl bg-canvas-subtle border border-canvas-border/70">
                  <button
                    type="button"
                    class="px-3 py-1 rounded-lg text-[11.5px] font-medium transition-craft cursor-pointer"
                    :class="isSceneActive('root') ? 'bg-white text-brand-600 font-semibold shadow-sm' : 'text-ink-secondary hover:text-ink-primary'"
                    @click="switchScene('root')"
                  >
                    全部文件
                  </button>
                  <button
                    v-for="folder in docStore.folders.slice(0, 3)"
                    :key="folder.id"
                    type="button"
                    class="px-3 py-1 rounded-lg text-[11.5px] font-medium transition-craft cursor-pointer"
                    :class="isSceneActive(folder.id) ? 'bg-white text-brand-600 font-semibold shadow-sm' : 'text-ink-secondary hover:text-ink-primary'"
                    @click="switchScene(folder.id)"
                  >
                    {{ folder.name }}
                  </button>
                  <button
                    type="button"
                    class="px-3 py-1 rounded-lg text-[11.5px] font-medium transition-craft cursor-pointer"
                    :class="isSceneActive('search') ? 'bg-white text-brand-600 font-semibold shadow-sm' : 'text-ink-secondary hover:text-ink-primary'"
                    @click="switchScene('search')"
                  >
                    全文检索大厅
                  </button>
                </div>
              </div>

              <div class="flex items-center gap-2.5">
                <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-white border border-canvas-border text-[11px] font-mono text-ink-secondary shadow-craft-sm">
                  <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>
                  <span>实时库：{{ docStore.totalDocs }} 份文档</span>
                </span>
                <button
                  type="button"
                  class="text-[11.5px] px-3 py-1 rounded-lg bg-brand-500 hover:bg-brand-600 text-white font-medium transition-craft flex items-center gap-1.5 cursor-pointer shadow-sm"
                  @click="docStore.openUploadModal('0')"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M12 4.5v15m7.5-7.5h-15"/>
                  </svg>
                  <span>上传新文档</span>
                </button>
              </div>
            </div>

            <!-- Main Route Content View -->
            <router-view />
          </main>

          <!-- ================= RIGHT PANE: INSTANT PDF & OCR DUAL-LAYER PREVIEW ================= -->
          <!-- ================= RIGHT PANE: MULTI-FORMAT REAL FILE & OCR DUAL-LAYER PREVIEW ================= -->
          <aside
            v-if="docStore.previewOpen"
            class="shrink-0 bg-canvas-sidebar rounded-2xl shadow-craft-md border border-white/80 flex flex-col overflow-hidden transition-craft"
            :class="isPreviewExpanded ? 'w-[720px] xl:w-[860px]' : 'w-[440px] xl:w-[480px]'"
          >
            <!-- Preview Header -->
            <div class="px-4 py-3 border-b border-canvas-border/80 bg-white/70 flex items-center justify-between gap-2">
              <div class="flex items-center gap-2.5 min-w-0">
                <div
                  class="w-8 h-8 rounded-lg font-bold text-[10px] font-mono flex items-center justify-center shrink-0 shadow-sm"
                  style="background-color: #F43F5E; color: #FFFFFF;"
                >
                  {{ (currentDocExt || 'PDF').toUpperCase() }}
                </div>
                <div class="min-w-0">
                  <div class="flex items-center gap-1.5">
                    <h3 class="text-[13.5px] font-bold text-ink-primary truncate" :title="previewDocTitle">
                      {{ previewDocTitle }}
                    </h3>
                    <span class="text-[10px] px-1.5 py-0.5 rounded bg-sage-50 text-sage-600 font-medium border border-sage-100 shrink-0">已索引</span>
                  </div>
                  <p class="text-[11px] text-ink-muted truncate">
                    {{ previewDocMeta }}
                  </p>
                </div>
              </div>

              <!-- Right Pane Mode Switch & Window Controls -->
              <div class="flex items-center gap-1 shrink-0">
                <div class="inline-flex p-0.5 rounded-lg bg-canvas-subtle border border-canvas-border/70 text-[11px]">
                  <button
                    type="button"
                    class="px-2.5 py-1 rounded-md font-medium transition-craft cursor-pointer"
                    :class="docStore.previewTab === 'canvas' ? 'bg-white text-ink-primary shadow-sm font-semibold' : 'text-ink-secondary hover:text-ink-primary'"
                    @click="docStore.previewTab = 'canvas'"
                  >
                    文档原件
                  </button>
                  <button
                    type="button"
                    class="px-2.5 py-1 rounded-md font-medium transition-craft cursor-pointer"
                    :class="docStore.previewTab === 'ocr' ? 'bg-white text-ink-primary shadow-sm font-semibold' : 'text-ink-secondary hover:text-ink-primary'"
                    @click="docStore.previewTab = 'ocr'"
                  >
                    OCR文本层
                  </button>
                </div>

                <!-- Open Full Preview Modal Button -->
                <button
                  type="button"
                  title="独立全屏大窗预览"
                  class="w-7 h-7 rounded-lg hover:bg-brand-50 hover:text-brand-600 text-ink-muted flex items-center justify-center cursor-pointer transition-craft"
                  @click="openModalPreview(docStore.selectedDoc, docStore.selectedPageIndex, docStore.activeHighlightKeyword)"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M13.5 6H5.25A2.25 2.25 0 003 8.25v10.5A2.25 2.25 0 005.25 21h10.5A2.25 2.25 0 0018 18.75V10.5m-10.5 6L21 3m0 0h-5.25M21 3v5.25"/>
                  </svg>
                </button>

                <!-- Expand/Collapse Width Button -->
                <button
                  type="button"
                  :title="isPreviewExpanded ? '还原窗口宽度' : '加宽窗口预览'"
                  class="w-7 h-7 rounded-lg hover:bg-canvas-hover text-ink-muted hover:text-ink-primary flex items-center justify-center cursor-pointer transition-craft"
                  @click="isPreviewExpanded = !isPreviewExpanded"
                >
                  <svg v-if="!isPreviewExpanded" class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15"/>
                  </svg>
                  <svg v-else class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M9 9V4.5M9 9H4.5M9 9L3.75 3.75M9 15v4.5M9 15H4.5M9 15l-5.25 5.25M15 9h4.5M15 9V4.5M15 9l5.25-5.25M15 15h4.5M15 15v4.5m0-4.5l5.25 5.25"/>
                  </svg>
                </button>

                <!-- Close Button -->
                <button
                  type="button"
                  title="收起预览栏"
                  class="w-7 h-7 rounded-lg hover:bg-canvas-hover text-ink-muted hover:text-ink-primary flex items-center justify-center cursor-pointer"
                  @click="docStore.previewOpen = false"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/>
                  </svg>
                </button>
              </div>
            </div>

            <!-- Preview Sub-Toolbar (Adaptive per format) -->
            <div class="px-4 py-2 bg-canvas-bg/70 border-b border-canvas-border/70 flex items-center justify-between text-[11.5px] text-ink-secondary">
              <!-- When viewing Original Document: Format-specific tools -->
              <template v-if="docStore.previewTab === 'canvas'">
                <!-- PDF Controls -->
                <div v-if="currentDocExt === 'pdf'" class="flex items-center justify-between w-full">
                  <span class="inline-flex items-center gap-1.5 font-medium text-ink-secondary">
                    <span class="w-2 h-2 rounded-full bg-brand-500"></span>
                    PDF 原件流式视图
                  </span>
                  <span class="text-[11px] text-ink-muted font-mono">浏览器原生 PDF 播放器支持缩放与翻页</span>
                </div>

                <!-- Word docx Tools -->
                <div v-else-if="currentDocExt === 'docx'" class="flex items-center justify-between w-full">
                  <span class="inline-flex items-center gap-1.5 font-medium text-brand-700">
                    <span class="w-2 h-2 rounded-full bg-brand-500"></span>
                    Word docx-preview 前端渲染引擎
                  </span>
                  <button type="button" class="hover:text-ink-primary font-medium cursor-pointer" @click="docStore.downloadDoc(docStore.selectedDoc)">下载原件</button>
                </div>

                <!-- Word doc (Legacy) Tools -->
                <div v-else-if="currentDocExt === 'doc'" class="flex items-center justify-between w-full">
                  <span class="inline-flex items-center gap-1 text-ink-secondary">
                    <span>二进制 Word 97-2003 格式</span>
                  </span>
                  <button type="button" class="text-brand-600 hover:text-brand-700 font-medium cursor-pointer" @click="docStore.downloadDoc(docStore.selectedDoc)">下载原件</button>
                </div>

                <!-- TXT Tools -->
                <div v-else-if="currentDocExt === 'txt'" class="flex items-center justify-between w-full">
                  <div class="flex items-center gap-2">
                    <span>共 {{ txtLineCount }} 行</span>
                    <button type="button" class="px-1.5 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="txtFontSize = Math.max(11, txtFontSize - 1)">A-</button>
                    <span class="font-mono text-[11px]">{{ txtFontSize }}px</span>
                    <button type="button" class="px-1.5 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="txtFontSize = Math.min(18, txtFontSize + 1)">A+</button>
                    <button type="button" class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="txtLineWrap = !txtLineWrap">{{ txtLineWrap ? '自动折行' : '不折行' }}</button>
                  </div>
                  <button type="button" class="text-brand-600 hover:text-brand-700 font-medium cursor-pointer" @click="copyTxtContent">复制文本</button>
                </div>

                <!-- Markdown Tools -->
                <div v-else-if="currentDocExt === 'md'" class="flex items-center justify-between w-full">
                  <div class="inline-flex p-0.5 rounded bg-white border border-canvas-border text-[11px]">
                    <button type="button" class="px-2 py-0.5 rounded" :class="mdViewMode === 'rendered' ? 'bg-brand-50 text-brand-600 font-bold' : 'text-ink-muted'" @click="mdViewMode = 'rendered'">排版视图</button>
                    <button type="button" class="px-2 py-0.5 rounded" :class="mdViewMode === 'source' ? 'bg-brand-50 text-brand-600 font-bold' : 'text-ink-muted'" @click="mdViewMode = 'source'">源码视图</button>
                  </div>
                  <button type="button" class="text-brand-600 hover:text-brand-700 font-medium cursor-pointer" @click="copyMdContent">复制源码</button>
                </div>

                <!-- Image / Other Tools -->
                <div v-else class="flex items-center justify-between w-full">
                  <span>{{ currentDocExt.toUpperCase() }} 文件原件</span>
                  <button type="button" class="text-brand-600 hover:text-brand-700 font-medium cursor-pointer" @click="docStore.downloadDoc(docStore.selectedDoc)">下载原件</button>
                </div>
              </template>

              <!-- When viewing OCR text layer: OCR tools -->
              <template v-else>
                <div class="flex items-center gap-1.5">
                  <button type="button" class="w-6 h-6 rounded hover:bg-white border border-transparent hover:border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.selectedPageIndex <= 0" @click="prevPage">‹</button>
                  <span class="font-mono text-[11.5px] px-1.5 py-0.5 bg-white rounded border border-canvas-border/80">{{ docStore.selectedPageIndex + 1 }} / {{ totalPages }}</span>
                  <button type="button" class="w-6 h-6 rounded hover:bg-white border border-transparent hover:border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.selectedPageIndex >= totalPages - 1" @click="nextPage">›</button>
                </div>
                <div class="flex items-center gap-2">
                  <div class="inline-flex p-0.5 rounded-lg bg-canvas-subtle border border-canvas-border/80 text-[11px]">
                    <button
                      type="button"
                      class="px-2 py-0.5 rounded cursor-pointer transition-craft"
                      :class="ocrViewStyle === 'cards' ? 'bg-white text-ink-primary font-bold shadow-xs' : 'text-ink-muted hover:text-ink-primary'"
                      @click="ocrViewStyle = 'cards'"
                    >
                      结构卡片
                    </button>
                    <button
                      type="button"
                      class="px-2 py-0.5 rounded cursor-pointer transition-craft"
                      :class="ocrViewStyle === 'text' ? 'bg-white text-ink-primary font-bold shadow-xs' : 'text-ink-muted hover:text-ink-primary'"
                      @click="ocrViewStyle = 'text'"
                    >
                      连续文本
                    </button>
                  </div>
                  <button
                    type="button"
                    class="text-brand-600 hover:text-brand-700 text-[11px] font-medium cursor-pointer"
                    title="导出本页 OCR 识别文本"
                    @click="docStore.downloadOcrText(docStore.selectedDoc, docStore.selectedPageIndex)"
                  >
                    导出TXT
                  </button>
                </div>
              </template>
            </div>

            <!-- Active Keyword Match Banner (if keyword active) -->
            <div
              v-if="docStore.activeHighlightKeyword && docStore.activeHighlightKeyword.trim()"
              class="px-4 py-2 bg-rose-50/90 border-b border-rose-200/70 flex items-center justify-between text-[11.5px] text-rose-800"
            >
              <div class="flex items-center gap-1.5 truncate">
                <span class="w-2 h-2 rounded-full bg-rose-500 shrink-0"></span>
                <span class="truncate">已标红定位关键词：“{{ docStore.activeHighlightKeyword }}”</span>
              </div>
              <button
                type="button"
                class="text-[11px] text-rose-600 hover:underline shrink-0 ml-2 cursor-pointer"
                @click="docStore.activeHighlightKeyword = ''"
              >
                清除标红
              </button>
            </div>

            <!-- Preview Body Area (Full Height, Scrollable) -->
            <div class="flex-1 overflow-y-auto p-4 bg-canvas-bg/60 flex flex-col items-center min-h-0">
              <div v-if="!docStore.selectedDoc" class="text-center py-20 text-ink-muted text-[13px]">
                点击左侧列表中的任意文档即可在此即时预览
              </div>

              <!-- ================= 模式 1：文档原件真实预览 ================= -->
              <template v-else-if="docStore.previewTab === 'canvas'">
                <!-- 1.1 PDF 原件流式内嵌预览 -->
                <div v-if="currentDocExt === 'pdf'" class="w-full h-full flex flex-col flex-1 min-h-[520px]">
                  <iframe
                    :src="pdfStreamUrl"
                    class="w-full flex-1 h-full min-h-[520px] border border-canvas-border rounded-xl bg-white shadow-craft-sm"
                  ></iframe>
                </div>

                <!-- 1.2 Word .docx 纯前端高保真排版预览 (基于 docx-preview) -->
                <div v-else-if="currentDocExt === 'docx'" class="w-full flex flex-col items-center">
                  <div v-if="docxLoading" class="py-16 flex flex-col items-center gap-3 text-ink-muted text-[13px]">
                    <svg class="w-6 h-6 animate-spin text-brand-600" fill="none" viewBox="0 0 24 24">
                      <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                      <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path>
                    </svg>
                    <span>正在解析并排版 Word 文档...</span>
                  </div>
                  <div v-else-if="docxError" class="p-6 bg-white rounded-xl border border-canvas-border shadow-craft-sm text-center space-y-3 w-full">
                    <p class="text-[13px] text-ink-muted">{{ docxError }}</p>
                    <button type="button" class="px-3.5 py-1.5 rounded-lg bg-brand-500 text-white text-[12px] font-medium cursor-pointer" @click="docStore.downloadDoc(docStore.selectedDoc)">下载原件查看</button>
                  </div>
                  <div ref="docxContainerRef" class="docx-render-container w-full bg-white rounded-xl p-4 shadow-paper border border-canvas-border overflow-x-auto"></div>
                </div>

                <!-- 1.3 Word .doc (老格式 97-2003) 友好卡片与正文阅读 -->
                <div v-else-if="currentDocExt === 'doc'" class="w-full p-5 bg-white rounded-2xl border border-canvas-border shadow-craft-sm space-y-4">
                  <div class="flex items-center justify-between gap-3 border-b border-canvas-border/70 pb-3">
                    <div class="flex items-center gap-2.5 min-w-0">
                      <div class="w-9 h-9 rounded-xl bg-blue-50 border border-blue-200 text-blue-600 font-mono font-bold text-[12px] flex items-center justify-center shrink-0">
                        DOC
                      </div>
                      <div class="min-w-0">
                        <h4 class="font-bold text-[13.5px] text-ink-primary truncate" :title="docStore.selectedDoc.fileName || docStore.selectedDoc.name">{{ docStore.selectedDoc.fileName || docStore.selectedDoc.name }}</h4>
                        <p class="text-[11.5px] text-ink-muted mt-0.5">Word 97-2003 结构化正文已转译</p>
                      </div>
                    </div>
                    <button
                      type="button"
                      class="px-2.5 py-1 rounded-lg bg-brand-50 hover:bg-brand-100 text-brand-600 text-[11px] font-medium border border-brand-100 transition-craft cursor-pointer shrink-0"
                      @click="openModalPreview(docStore.selectedDoc)"
                    >
                      大窗阅读
                    </button>
                  </div>

                  <div v-if="docLinesLoading" class="py-8 text-center text-ink-muted text-[12px]">
                    正在提取正文段落...
                  </div>
                  <div v-else class="space-y-2 text-[12.5px] text-ink-primary bg-canvas-bg/50 p-3 rounded-xl border border-canvas-border/60 max-h-[360px] overflow-y-auto">
                    <div
                      v-for="(line, lIdx) in docLines"
                      :key="lIdx"
                      class="py-1 border-b border-canvas-border/40 last:border-0 leading-relaxed"
                      v-html="highlightKeywordHtml(line, docStore.activeHighlightKeyword)"
                    ></div>
                  </div>
                  <div class="flex items-center justify-between pt-1 text-[11.5px]">
                    <span class="text-ink-muted">已建立 OCR 全文检索索引</span>
                    <button type="button" class="text-brand-600 hover:underline cursor-pointer" @click="docStore.downloadDoc(docStore.selectedDoc)">下载原件</button>
                  </div>
                </div>

                <!-- 1.4 TXT 文本原生阅读器 (带行号与关键词高亮) -->
                <div v-else-if="currentDocExt === 'txt'" class="w-full flex flex-col flex-1">
                  <div v-if="txtLoading" class="py-16 text-center text-ink-muted text-[13px]">加载文本内容...</div>
                  <div v-else class="w-full bg-white rounded-xl shadow-craft-sm border border-canvas-border overflow-hidden flex text-[13px]">
                    <div class="bg-canvas-bg/80 border-r border-canvas-border py-3 px-2.5 text-right font-mono text-ink-muted text-[11.5px] select-none">
                      <div v-for="n in txtLineCount" :key="n">{{ n }}</div>
                    </div>
                    <pre
                      class="p-3.5 font-mono text-ink-primary overflow-x-auto flex-1 leading-relaxed"
                      :class="txtLineWrap ? 'whitespace-pre-wrap' : 'whitespace-pre'"
                      :style="{ fontSize: `${txtFontSize}px` }"
                      v-html="highlightKeywordHtml(txtRawContent, docStore.activeHighlightKeyword)"
                    ></pre>
                  </div>
                </div>

                <!-- 1.5 Markdown 文档阅读器 (基于 marked，支持排版/源码双模式) -->
                <div v-else-if="currentDocExt === 'md'" class="w-full flex flex-col flex-1">
                  <div v-if="mdLoading" class="py-16 text-center text-ink-muted text-[13px]">解析 Markdown 中...</div>
                  <div v-else-if="mdViewMode === 'rendered'" class="w-full bg-white rounded-xl shadow-craft-sm border border-canvas-border p-6 markdown-body text-ink-primary text-[13.5px] leading-relaxed" v-html="renderedMarkdownHtml"></div>
                  <div v-else class="w-full bg-stone-900 text-stone-100 font-mono text-[12.5px] rounded-xl p-4 overflow-auto shadow-craft-sm">
                    <pre><code>{{ mdRawContent }}</code></pre>
                  </div>
                </div>

                <!-- 1.6 图片原件直接预览 -->
                <div v-else-if="['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp'].includes(currentDocExt)" class="w-full h-full flex items-center justify-center p-4">
                  <img :src="'/api/server/doc/stream-raw/' + docStore.selectedDoc.id" class="max-w-full max-h-[75vh] object-contain rounded-xl shadow-craft-md border border-canvas-border" />
                </div>

                <!-- 1.7 演示文档高质量排版回退展示 -->
                <div
                  v-else
                  class="w-full transition-craft origin-top flex flex-col items-center"
                  :style="{ transform: `scale(${docStore.previewZoom / 100})` }"
                >
                  <div id="pdfPaperSheet" class="w-full bg-white rounded-xl shadow-paper border border-canvas-border/90 p-6 space-y-4 relative mx-auto max-w-[390px]">
                    <div class="flex items-center justify-between border-b border-stone-200 pb-3 gap-3">
                      <div class="min-w-0">
                        <div class="text-[10px] font-mono uppercase tracking-wider text-ink-muted">RAPID-OCR 全文检索</div>
                        <div class="text-[13px] font-semibold text-ink-primary truncate mt-0.5" :title="docStore.selectedDoc.fileName || docStore.selectedDoc.name">{{ docStore.selectedDoc.fileName || docStore.selectedDoc.name }}</div>
                      </div>
                      <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-sage-50 text-sage-600 text-[11px] font-medium border border-sage-200/60 shrink-0">
                        <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>已索引
                      </span>
                    </div>

                    <div class="space-y-3 text-[12.5px] leading-relaxed text-ink-primary">
                      <div v-for="(block, bIdx) in currentPageBlocks" :key="bIdx" class="p-2.5 rounded-lg ocr-bbox transition-craft" :class="getOcrBoxClass(block.text)">
                        <div v-if="docStore.showOcrBoxes" class="flex items-center justify-between text-[10px] font-mono text-ink-muted mb-1">
                          <span>{{ block.label }}</span>
                          <span class="text-sage-600">OCR 99.{{ 9 - (bIdx % 3) }}%</span>
                        </div>
                        <div :class="bIdx === 0 ? 'font-bold text-[14px] text-ink-primary' : 'text-[12.5px] text-ink-primary leading-relaxed'" v-html="highlightKeywordHtml(block.text, docStore.activeHighlightKeyword)"></div>
                      </div>
                    </div>

                    <div class="pt-4 mt-4 border-t border-stone-200/80 flex items-center justify-between text-[11px] text-ink-muted">
                      <span>文件大小：{{ docStore.selectedDoc.sizeLabel || (docStore.selectedDoc.fileSize ? `${docStore.selectedDoc.fileSize} B` : '1.2 MB') }}</span>
                      <span class="font-mono">Page {{ docStore.selectedPageIndex + 1 }} of {{ totalPages }}</span>
                    </div>
                  </div>
                </div>
              </template>

              <!-- ================= 模式 2：OCR 字符级抽取与文本图层 ================= -->
              <div v-else class="w-full space-y-3">
                <!-- Stats Header -->
                <div class="p-3 bg-white rounded-xl shadow-craft-xs border border-canvas-border flex items-center justify-between text-[11.5px]">
                  <div class="flex items-center gap-1.5">
                    <span class="w-2 h-2 rounded-full bg-sage-500"></span>
                    <span class="font-semibold text-ink-primary">RapidOCR 检索引擎</span>
                  </div>
                  <div class="text-[11px] font-mono text-ink-muted flex items-center gap-2">
                    <span>{{ currentPageBlocks.length }} 个区块</span>
                    <span>·</span>
                    <span>{{ currentPageCharCount }} 字符</span>
                    <span>·</span>
                    <span class="text-sage-600 font-medium">99.8%</span>
                  </div>
                </div>

                <!-- View Mode 1: Cards -->
                <div v-if="ocrViewStyle === 'cards'" class="space-y-2.5">
                  <div
                    v-for="(block, bIdx) in currentPageBlocks"
                    :key="bIdx"
                    class="p-3 bg-white rounded-xl border border-canvas-border/80 shadow-craft-xs hover:border-brand-400 hover:shadow-craft-sm transition-craft space-y-1.5 ocr-bbox group"
                    :class="getOcrBoxClass(block.text)"
                  >
                    <div class="flex items-center justify-between text-[10.5px]">
                      <div class="flex items-center gap-1.5">
                        <span
                          class="px-1.5 py-0.5 rounded font-mono font-medium border"
                          :class="getBlockTagBadge(block.type).badgeClass"
                        >
                          {{ block.label }}
                        </span>
                        <span class="text-sage-600 font-mono">{{ block.confidence || '99.8%' }}</span>
                      </div>
                      <div class="flex items-center gap-2">
                        <span class="text-[10px] text-ink-muted font-mono">{{ (block.text || '').length }} 字</span>
                        <button
                          type="button"
                          class="opacity-0 group-hover:opacity-100 px-1.5 py-0.5 rounded bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary text-[10px] transition-craft cursor-pointer"
                          :class="{ '!opacity-100 !text-brand-600 !bg-brand-50': copiedBlockKey === bIdx }"
                          @click="copySingleBlock(block.text, bIdx)"
                        >
                          {{ copiedBlockKey === bIdx ? '已复制 ✓' : '复制' }}
                        </button>
                      </div>
                    </div>
                    <div
                      :class="block.type === 'title' ? 'font-bold text-[13.5px] text-ink-primary leading-snug' : 'text-[12.5px] text-ink-primary leading-relaxed'"
                      v-html="highlightKeywordHtml(block.text, docStore.activeHighlightKeyword)"
                    ></div>
                  </div>

                  <div v-if="currentPageBlocks.length === 0" class="text-center py-12 text-ink-muted text-[12.5px]">
                    本页暂无 OCR 文本区块
                  </div>
                </div>

                <!-- View Mode 2: Continuous Text -->
                <div v-else class="bg-white rounded-xl shadow-craft-sm border border-canvas-border overflow-hidden">
                  <div class="px-3.5 py-2 bg-canvas-bg/60 border-b border-canvas-border/80 flex items-center justify-between text-[11.5px]">
                    <span class="font-medium text-ink-primary">连续纯文本</span>
                    <button
                      type="button"
                      class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover text-brand-600 text-[11px] font-medium border border-canvas-border/70 transition-craft cursor-pointer"
                      @click="copyOcrText"
                    >
                      复制本页全文本
                    </button>
                  </div>
                  <pre
                    class="p-4 font-mono text-[12px] text-ink-primary leading-relaxed whitespace-pre-wrap outline-none max-h-[500px] overflow-y-auto select-text"
                    v-html="currentPageContinuousTextHtml"
                  ></pre>
                </div>
              </div>
            </div>

            <!-- Preview Footer Action Bar -->
            <div class="px-4 py-3 bg-white border-t border-canvas-border/80 flex items-center justify-between gap-2 text-[12px]">
              <div class="flex items-center gap-2 min-w-0">
                <span class="w-2 h-2 rounded-full bg-sage-600 shrink-0"></span>
                <span class="text-[11.5px] text-ink-muted truncate font-mono">
                  更新时间: {{ docStore.selectedDoc ? (docStore.selectedDoc.updateTime || docStore.selectedDoc.createTime || '2026-10-23') : '' }}
                </span>
              </div>
              <div class="flex items-center gap-2 shrink-0">
                <button
                  type="button"
                  class="px-3 py-1.5 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-ink-primary font-medium text-[12px] transition-craft flex items-center gap-1 cursor-pointer"
                  @click="docStore.downloadDoc(docStore.selectedDoc)"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/>
                  </svg>
                  <span>下载原件</span>
                </button>
              </div>
            </div>
          </aside>

        </div>
      </div>
    </div>

    <!-- ================= CRAFT MODAL 1: 新建档案目录 ================= -->
    <transition name="modal-fade">
      <div
        v-if="docStore.folderModalOpen"
        class="fixed inset-0 z-50 bg-stone-900/40 backdrop-blur-[4px] flex items-center justify-center p-4"
        @click.self="docStore.closeFolderModal"
      >
        <div class="modal-card bg-white rounded-2xl shadow-craft-float border border-canvas-border max-w-sm w-full p-6 space-y-4">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-[16px] font-bold text-ink-primary">新建文件夹</h3>
              <p class="text-[12px] text-ink-muted mt-0.5">创建新的文档分类目录</p>
            </div>
            <button
              type="button"
              class="w-8 h-8 rounded-lg hover:bg-canvas-hover text-ink-muted hover:text-ink-primary flex items-center justify-center transition-craft cursor-pointer"
              @click="docStore.closeFolderModal"
            >
              ✕
            </button>
          </div>

          <div class="space-y-3.5">
            <div>
              <label class="block text-[12px] font-medium text-ink-secondary mb-1">上级位置</label>
              <select
                v-model="docStore.folderModalParentId"
                class="w-full px-3 py-2.5 rounded-xl bg-canvas-bg border border-canvas-border text-[13px] text-ink-primary outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 cursor-pointer transition-craft"
              >
                <option value="0">根目录（全部文件）</option>
                <option
                  v-for="f in docStore.flatFolderTree"
                  :key="f.id"
                  :value="f.id"
                >
                  {{ f.depth > 0 ? '　'.repeat(f.depth) + '└ ' + f.name : f.name }}
                </option>
              </select>
            </div>

            <div>
              <label class="block text-[12px] font-medium text-ink-secondary mb-1">目录名称</label>
              <input
                v-model="folderInputName"
                type="text"
                placeholder="例如：2026专项税务凭证"
                class="w-full px-3.5 py-2.5 rounded-xl bg-white border border-canvas-border focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 text-[13px] text-ink-primary outline-none transition-craft"
                @keyup.enter="handleConfirmFolder"
              />
            </div>
          </div>

          <div class="flex items-center justify-end gap-2.5 pt-2">
            <button
              type="button"
              class="px-4 py-2 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-[13px] font-medium text-ink-secondary transition-craft cursor-pointer"
              @click="docStore.closeFolderModal"
            >
              取消
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 active:scale-[0.99] text-white text-[13px] font-medium shadow-sm transition-craft cursor-pointer disabled:opacity-50"
              :disabled="creatingFolder"
              @click="handleConfirmFolder"
            >
              {{ creatingFolder ? '创建中...' : '创建目录' }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- ================= CRAFT MODAL 2: 上传文档并建立 OCR 索引 ================= -->
    <transition name="modal-fade">
      <div
        v-if="docStore.uploadModalOpen"
        class="fixed inset-0 z-50 bg-stone-900/40 backdrop-blur-[4px] flex items-center justify-center p-4"
        @click.self="docStore.closeUploadModal"
      >
        <div class="modal-card bg-white rounded-2xl shadow-craft-float border border-canvas-border max-w-md w-full p-6 space-y-5">
          <div class="flex items-center justify-between">
            <div>
              <h3 class="text-[16px] font-bold text-ink-primary">上传文档并建立全文索引</h3>
              <p class="text-[12px] text-ink-muted mt-0.5">支持 PDF、Word、扫描件图片，上传后自动执行双层毫秒解析</p>
            </div>
            <button
              type="button"
              class="w-8 h-8 rounded-lg hover:bg-canvas-hover text-ink-muted hover:text-ink-primary flex items-center justify-center transition-craft cursor-pointer"
              @click="docStore.closeUploadModal"
            >
              ✕
            </button>
          </div>

          <div class="space-y-4">
            <!-- Target Folder Select -->
            <div>
              <label class="block text-[12px] font-medium text-ink-secondary mb-1">目标文件夹</label>
              <select
                v-model="docStore.uploadTargetFolderId"
                class="w-full px-3 py-2.5 rounded-xl bg-canvas-bg border border-canvas-border text-[13px] text-ink-primary outline-none focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 cursor-pointer transition-craft"
              >
                <option value="0">根目录（全部文件）</option>
                <option
                  v-for="f in docStore.flatFolderTree"
                  :key="f.id"
                  :value="f.id"
                >
                  {{ f.depth > 0 ? '　'.repeat(f.depth) + '└ ' + f.name : f.name }}
                </option>
              </select>
            </div>

            <!-- File Upload Dropzone -->
            <div>
              <label class="block text-[12px] font-medium text-ink-secondary mb-1">选择文件</label>
              <div
                v-if="!selectedFile"
                class="border-2 border-dashed border-canvas-borderStrong/80 hover:border-brand-500/60 bg-canvas-bg/40 hover:bg-brand-50/20 rounded-2xl p-6 text-center cursor-pointer transition-craft group"
                @click="triggerFilePicker"
              >
                <div class="w-11 h-11 rounded-xl bg-white shadow-craft-sm border border-canvas-border flex items-center justify-center mx-auto text-ink-muted group-hover:text-brand-500 group-hover:scale-105 transition-craft">
                  <svg class="w-5 h-5 text-brand-500" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/>
                  </svg>
                </div>
                <div class="mt-2 text-[13.5px] font-semibold text-ink-primary">点击选择或拖拽文件至此</div>
                <p class="mt-0.5 text-[11.5px] text-ink-muted">
                  支持 PDF、Word、扫描件图片（单文件最大 50MB）
                </p>
              </div>

              <!-- File Chosen Card -->
              <div
                v-else
                class="p-3.5 rounded-xl bg-brand-50/40 border border-brand-200/70 flex items-center justify-between gap-3"
              >
                <div class="flex items-center gap-3 min-w-0">
                  <div class="w-9 h-9 rounded-lg bg-rose-500 text-white font-mono font-bold text-[10px] flex items-center justify-center shrink-0">
                    PDF
                  </div>
                  <div class="min-w-0">
                    <div class="text-[13px] font-semibold text-ink-primary truncate">{{ selectedFile.name }}</div>
                    <div class="text-[11px] text-ink-muted font-mono mt-0.5">{{ (selectedFile.size / 1024).toFixed(1) }} KB</div>
                  </div>
                </div>
                <button
                  type="button"
                  class="text-[11.5px] text-ink-muted hover:text-rose-600 px-2 py-1 rounded hover:bg-white transition-craft cursor-pointer"
                  @click="selectedFile = null"
                >
                  更换文件
                </button>
              </div>

              <input
                ref="fileInputRef"
                type="file"
                class="hidden"
                accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.png,.jpg,.jpeg"
                @change="handleFilePicked"
              />
            </div>

            <!-- Optional Document Name & OCR content for instant index test -->
            <div>
              <label class="block text-[12px] font-medium text-ink-secondary mb-1">
                OCR 识别正文内容（可选快速体验）
              </label>
              <textarea
                v-model="uploadFileContent"
                rows="2"
                placeholder="上传后将以此内容建立 RapidOCR 字符级倒排索引，支持毫秒级全文检索..."
                class="w-full px-3.5 py-2 rounded-xl bg-white border border-canvas-border focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 text-[12.5px] text-ink-primary placeholder-ink-muted outline-none transition-craft resize-none"
              ></textarea>
            </div>
          </div>

          <div class="flex items-center justify-end gap-2.5 pt-2">
            <button
              type="button"
              class="px-4 py-2 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-[13px] font-medium text-ink-secondary transition-craft cursor-pointer"
              @click="docStore.closeUploadModal"
            >
              取消
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 active:scale-[0.99] text-white text-[13px] font-medium shadow-sm transition-craft cursor-pointer disabled:opacity-50"
              :disabled="uploadingFile || (!selectedFile && !uploadFileContent.trim())"
              @click="handleConfirmUpload"
            >
              {{ uploadingFile ? '上传解析中...' : '上传并建立索引' }}
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- ================= CRAFT MODAL 3: 删除确认弹窗 ================= -->
    <transition name="modal-fade">
      <div
        v-if="docStore.deleteModalOpen"
        class="fixed inset-0 z-50 bg-stone-900/40 backdrop-blur-[4px] flex items-center justify-center p-4"
        @click.self="docStore.closeDeleteModal"
      >
        <div class="modal-card bg-white rounded-2xl shadow-craft-float border border-canvas-border max-w-sm w-full p-6 space-y-4">
          <div class="flex items-start gap-3.5">
            <div class="w-10 h-10 rounded-xl flex items-center justify-center shrink-0 border" style="background-color: #FFF1F2; border-color: #FECDD3; color: #E11D48;">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
              </svg>
            </div>
            <div>
              <h3 class="text-[15px] font-bold text-ink-primary">
                确定要删除该{{ docStore.deleteTarget?.type === 'folder' ? '目录' : '文档' }}吗？
              </h3>
              <p class="text-[12.5px] text-ink-muted mt-1 leading-relaxed">
                此操作将永久移除《{{ docStore.deleteTarget?.name }}》{{ docStore.deleteTarget?.type === 'folder' ? '及其下属文件' : '' }}以及已建立的双层 OCR 倒排正文索引，此操作无法撤销。
              </p>
            </div>
          </div>

          <div class="flex items-center justify-end gap-2.5 pt-2">
            <button
              type="button"
              class="px-4 py-2 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-[13px] font-medium text-ink-secondary transition-craft cursor-pointer"
              @click="docStore.closeDeleteModal"
            >
              取消
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-xl active:scale-[0.99] text-white text-[13px] font-medium shadow-sm transition-craft cursor-pointer hover:opacity-95"
              style="background-color: #E11D48; color: #FFFFFF;"
              @click="handleConfirmDelete"
            >
              确认删除
            </button>
          </div>
        </div>
      </div>
    </transition>

    <!-- ================= CRAFT TOAST BANNER (100% matches D:\pdfutil_saas.html) ================= -->
    <transition name="toast-slide">
      <div
        v-if="docStore.toastVisible"
        class="fixed bottom-5 right-5 z-50 px-4 py-2.5 rounded-xl bg-ink-primary text-white text-[12.5px] font-medium shadow-craft-float flex items-center gap-2"
      >
        <span class="w-2 h-2 rounded-full bg-sage-600 animate-pulse"></span>
        <span>{{ docStore.toastMessage }}</span>
      </div>
    </transition>

    <!-- ================= CRAFT MODAL 4: 全屏/大窗多格式文档沉浸式预览弹窗 ================= -->
    <DocPreviewModal />

  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useDocStore, parseStructuredOcrBlocks } from '@/stores/doc'
import { uploadServerDoc } from '@/api/doc'
import axios from 'axios'
import { renderAsync } from 'docx-preview'
import { marked } from 'marked'
import DocPreviewModal from './DocPreviewModal.vue'

const route = useRoute()
const router = useRouter()
const docStore = useDocStore()

const currentRoute = computed(() => route.path)
const isAllFilesActive = computed(() => currentRoute.value.includes('/pdf/files') && (!docStore.currentFolderId || docStore.currentFolderId === 'root' || docStore.currentFolderId === '0'))
const quickSearchKeyword = ref('')

// 新建目录表单
const folderInputName = ref('')
const creatingFolder = ref(false)

// 上传表单
const selectedFile = ref(null)
const uploadFileContent = ref('')
const fileInputRef = ref(null)
const uploadingFile = ref(false)

const routeUrlDisplay = computed(() => {
  if (currentRoute.value.includes('/pdf/search')) {
    return 'localhost/pdf/search'
  }
  const fid = route.query.folderId || docStore.currentFolderId
  if (!fid || fid === 'root' || fid === '0') {
    return 'localhost/pdf/files'
  }
  return `localhost/pdf/files?folderId=${fid}`
})

// 视图快速切换
const isSceneActive = (scene) => {
  if (scene === 'search') {
    return currentRoute.value.includes('/pdf/search')
  }
  if (!currentRoute.value.includes('/pdf/files')) return false
  if (scene === 'root') {
    return !docStore.currentFolderId || docStore.currentFolderId === 'root' || docStore.currentFolderId === '0'
  }
  return docStore.currentFolderId === scene
}

const switchScene = (scene) => {
  if (scene === 'search') {
    router.push('/pdf/search')
  } else if (scene === 'root') {
    docStore.currentFolderId = 'root'
    router.push({ path: '/pdf/files', query: { folderId: '0' } })
  } else {
    docStore.currentFolderId = scene
    router.push({ path: '/pdf/files', query: { folderId: scene } })
  }
}

onMounted(async () => {
  await docStore.loadFolders()
  await docStore.loadDocuments()
})

const navigateAllFiles = () => {
  docStore.currentFolderId = 'root'
  router.push({ path: '/pdf/files', query: { folderId: '0' } })
}

const navigateSearchHall = () => {
  router.push('/pdf/search')
}

const selectFolder = (id) => {
  docStore.currentFolderId = id
  router.push({ path: '/pdf/files', query: { folderId: id } })
}

const getFolderDocCount = (folderId) => {
  const descendantIds = docStore.getDescendantFolderIds(folderId)
  return docStore.documents.filter(d => descendantIds.includes(d.folderId)).length
}

const doQuickSearch = () => {
  if (quickSearchKeyword.value.trim()) {
    router.push({ path: '/pdf/search', query: { q: quickSearchKeyword.value.trim() } })
  } else {
    router.push('/pdf/search')
  }
}

// 预览计算属性
const previewDocTitle = computed(() => {
  return docStore.selectedDoc ? (docStore.selectedDoc.fileName || docStore.selectedDoc.name) : '文档即时预览'
})

const previewDocMeta = computed(() => {
  const doc = docStore.selectedDoc
  if (!doc) return ''
  const size = doc.sizeLabel || (doc.fileSize ? `${doc.fileSize} B` : '')
  const totalP = doc.pages ? doc.pages.length : (doc.pageCount || 1)
  const folderName = docStore.folders.find(f => f.id === doc.folderId)?.name || '根目录'
  return `${size} · 共 ${totalP} 页 · ${folderName}`
})

const totalPages = computed(() => {
  const doc = docStore.selectedDoc
  if (!doc) return 1
  if (doc.pages && doc.pages.length > 0) {
    return doc.pages.length
  }
  return doc.pageCount || 1
})

const ocrViewStyle = ref('cards')
const copiedBlockKey = ref(null)

const copySingleBlock = async (text, idx) => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copiedBlockKey.value = idx
    setTimeout(() => {
      if (copiedBlockKey.value === idx) copiedBlockKey.value = null
    }, 1500)
    docStore.showToast('已复制该区块文本至剪贴板')
  } catch {
    ElMessage.info('文本已复制')
  }
}

const getBlockTagBadge = (type) => {
  switch (type) {
    case 'title':
      return { badgeClass: 'bg-indigo-50 text-indigo-700 border-indigo-200/80' }
    case 'field':
      return { badgeClass: 'bg-emerald-50 text-emerald-700 border-emerald-200/80' }
    case 'seal':
      return { badgeClass: 'bg-amberWarm-50 text-amberWarm-700 border-amberWarm-200/80' }
    case 'clause':
      return { badgeClass: 'bg-purple-50 text-purple-700 border-purple-200/80' }
    default:
      return { badgeClass: 'bg-stone-100 text-stone-700 border-stone-200/80' }
  }
}

const currentPageBlocks = computed(() => {
  const doc = docStore.selectedDoc
  if (!doc) return []
  if (doc.pages && doc.pages.length > 0) {
    const pIdx = Math.min(docStore.selectedPageIndex, doc.pages.length - 1)
    return doc.pages[pIdx] || []
  }
  if (currentDocExt.value === 'md' && mdRawContent.value) {
    return parseStructuredOcrBlocks(mdRawContent.value, previewDocTitle.value)
  }
  if (currentDocExt.value === 'doc' && docLines.value.length > 0) {
    return parseStructuredOcrBlocks(docLines.value.join('\n'), previewDocTitle.value)
  }
  if (currentDocExt.value === 'txt' && txtRawContent.value) {
    return parseStructuredOcrBlocks(txtRawContent.value, previewDocTitle.value)
  }
  return [
    { label: '识别区块 #01', text: previewDocTitle.value, type: 'title', confidence: '99.8%' }
  ]
})

const currentPageCharCount = computed(() => {
  return currentPageBlocks.value.reduce((acc, b) => acc + (b.text ? b.text.length : 0), 0)
})

const currentPageContinuousTextHtml = computed(() => {
  const blocks = currentPageBlocks.value
  const kw = docStore.activeHighlightKeyword
  return blocks.map(b => highlightKeywordHtml(b.text, kw)).join('\n\n')
})

const escapeHtml = (str) => {
  if (!str) return ''
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

const highlightKeywordHtml = (text, keyword) => {
  if (!text) return ''
  if (!keyword || !keyword.trim()) return escapeHtml(text)
  const kw = keyword.trim()
  const cleanKw = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escaped = escapeHtml(text)
  const regex = new RegExp(`(${cleanKw})`, 'gi')
  return escaped.replace(regex, '<mark class="ocr-mark">$1</mark>')
}

const currentPageOcrTextHtml = computed(() => {
  const blocks = currentPageBlocks.value
  const kw = docStore.activeHighlightKeyword
  return blocks
    .map(b => `[${escapeHtml(b.label)}]\n${highlightKeywordHtml(b.text, kw)}`)
    .join('\n\n')
})

const getOcrBoxClass = (text) => {
  const kw = docStore.activeHighlightKeyword ? docStore.activeHighlightKeyword.trim().toLowerCase() : ''
  const hasMatch = kw && text && text.toLowerCase().includes(kw)
  if (!docStore.showOcrBoxes) {
    return hasMatch ? 'bg-rose-50/35 ocr-highlight-pulse' : ''
  }
  if (hasMatch) {
    return 'border border-rose-400 bg-rose-50/35 ocr-highlight-pulse'
  }
  return 'border border-brand-500/25 bg-brand-50/15'
}

const prevPage = () => {
  if (docStore.selectedPageIndex > 0) {
    docStore.selectedPageIndex--
  }
}

const nextPage = () => {
  if (docStore.selectedPageIndex < totalPages.value - 1) {
    docStore.selectedPageIndex++
  }
}

const zoomOut = () => {
  docStore.previewZoom = Math.max(80, docStore.previewZoom - 10)
}

const zoomIn = () => {
  docStore.previewZoom = Math.min(125, docStore.previewZoom + 10)
}

const copyOcrText = async () => {
  const blocks = currentPageBlocks.value
  const text = blocks.map(b => `[${b.label}]\n${b.text}`).join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    docStore.showToast('已复制本页 OCR 识别文本至剪贴板')
  } catch (e) {
    ElMessage.info('已提取本页 OCR 文本')
  }
}

// 多格式文件原件预览引擎状态与分流
const isPreviewExpanded = ref(false)
const docxContainerRef = ref(null)
const docxLoading = ref(false)
const docxError = ref('')

const docLines = ref([])
const docLinesLoading = ref(false)

const txtRawContent = ref('')
const txtLoading = ref(false)
const txtFontSize = ref(13)
const txtLineWrap = ref(true)

const mdRawContent = ref('')
const mdLoading = ref(false)
const mdViewMode = ref('rendered') // 'rendered' | 'source'

const currentDocExt = computed(() => {
  const doc = docStore.selectedDoc
  if (!doc) return 'pdf'
  return (doc.fileExt || (doc.fileName && doc.fileName.split('.').pop()) || 'pdf').toLowerCase()
})

const pdfStreamUrl = computed(() => {
  const doc = docStore.selectedDoc
  if (!doc) return ''
  return `/api/server/doc/stream-preview/${doc.id}`
})

const txtLineCount = computed(() => {
  if (!txtRawContent.value) return 1
  return txtRawContent.value.split('\n').length
})

const renderedMarkdownHtml = computed(() => {
  if (!mdRawContent.value) return ''
  try {
    return marked.parse(mdRawContent.value)
  } catch (e) {
    return escapeHtml(mdRawContent.value)
  }
})

const copyTxtContent = async () => {
  if (txtRawContent.value) {
    try {
      await navigator.clipboard.writeText(txtRawContent.value)
      docStore.showToast('TXT 文本内容已复制到剪贴板')
    } catch {
      ElMessage.info('文本已复制')
    }
  }
}

const copyMdContent = async () => {
  if (mdRawContent.value) {
    try {
      await navigator.clipboard.writeText(mdRawContent.value)
      docStore.showToast('Markdown 源码已复制到剪贴板')
    } catch {
      ElMessage.info('Markdown 源码已复制')
    }
  }
}

const openModalPreview = (doc, pageIndex = 0, keyword = '') => {
  if (typeof docStore.openPreviewModal === 'function') {
    docStore.openPreviewModal(doc, pageIndex, keyword)
  } else {
    if (typeof docStore.selectDocForPreview === 'function') {
      docStore.selectDocForPreview(doc, pageIndex, keyword)
    }
    docStore.previewModalDoc = doc
    docStore.previewModalPageIndex = pageIndex
    docStore.previewModalKeyword = typeof keyword === 'string' ? keyword : ''
    docStore.previewModalOpen = true
    docStore.previewOpen = true
  }
}

const loadCurrentFileContent = async () => {
  const doc = docStore.selectedDoc
  if (!doc) return
  const ext = currentDocExt.value

  if (ext === 'docx') {
    docxLoading.value = true
    docxError.value = ''
    try {
      if (docxContainerRef.value) {
        docxContainerRef.value.innerHTML = ''
      }
      const resp = await axios.get(`/api/server/doc/stream-raw/${doc.id}`, { responseType: 'blob' })
      if (docxContainerRef.value) {
        await renderAsync(resp.data, docxContainerRef.value, null, {
          className: 'docx-preview-content',
          inWrapper: false,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true
        })
      }
    } catch (err) {
      console.warn('Docx 渲染异常:', err)
      docxError.value = 'Word 渲染异常: ' + (err.message || '文件格式不支持')
    } finally {
      docxLoading.value = false
    }
  } else if (ext === 'doc') {
    docLinesLoading.value = true
    docLines.value = []
    try {
      const resp = await axios.get(`/api/server/doc/stream-raw/${doc.id}`, { responseType: 'arraybuffer' })
      const decoder = new TextDecoder('utf-16le', { fatal: false })
      const rawText = decoder.decode(resp.data)
      const rawLines = rawText.split(/[\r\n]+/)
      const cleanList = []

      const OLE_NOISE_REGEX = /WordDocument|SummaryInformation|DocumentSummaryInformation|Root Entry|WpsCustomData|Normal\.dot|userNormal|KSOProduct|TemplateDocer|commondata|eyJo|Table Paragraph|Times New Roman|宋体|仿宋|黑体|Cambria/i
      const CHINESE_REGEX = /[\u4e00-\u9fa5]/
      const READABLE_CHAR_REGEX = /[\u4e00-\u9fa50-9a-zA-Z，。、：；！？（）《》【】“”‘’—…、□■／/·%\-+=\s]/

      for (const rawLine of rawLines) {
        const line = rawLine.replace(/[\x00-\x1f\x7f-\x9f]/g, '').trim()
        if (line.length < 2) continue
        if (OLE_NOISE_REGEX.test(line)) continue

        let readableCount = 0
        for (let i = 0; i < line.length; i++) {
          if (READABLE_CHAR_REGEX.test(line[i])) {
            readableCount++
          }
        }
        const ratio = readableCount / line.length
        if (CHINESE_REGEX.test(line) && ratio >= 0.70) {
          cleanList.push(line)
        }
      }

      docLines.value = cleanList.length > 0 ? cleanList : ['（该文档暂未提取到可读文本）']
    } catch (err) {
      docLines.value = ['文档正文读取异常: ' + err.message]
    } finally {
      docLinesLoading.value = false
    }
  } else if (ext === 'txt') {
    txtLoading.value = true
    try {
      const resp = await axios.get(`/api/server/doc/stream-raw/${doc.id}`, { responseType: 'text' })
      txtRawContent.value = typeof resp.data === 'string' ? resp.data : JSON.stringify(resp.data, null, 2)
    } catch (err) {
      txtRawContent.value = '文本文件加载失败: ' + err.message
    } finally {
      txtLoading.value = false
    }
  } else if (ext === 'md') {
    mdLoading.value = true
    try {
      const resp = await axios.get(`/api/server/doc/stream-raw/${doc.id}`, { responseType: 'text' })
      mdRawContent.value = typeof resp.data === 'string' ? resp.data : String(resp.data)
    } catch (err) {
      mdRawContent.value = '# 加载失败\n\n' + err.message
    } finally {
      mdLoading.value = false
    }
  }
}

watch(
  () => [docStore.selectedDoc?.id, docStore.previewTab, docStore.previewOpen],
  ([newId, newTab, isOpen]) => {
    if (isOpen && newTab === 'canvas' && newId) {
      nextTick(() => {
        loadCurrentFileContent()
      })
    }
  },
  { immediate: true }
)

// 弹窗确认逻辑
const handleConfirmFolder = async () => {
  const name = folderInputName.value.trim()
  if (!name) {
    ElMessage.warning('请输入目录名称')
    return
  }
  creatingFolder.value = true
  try {
    await docStore.createFolder(name, docStore.folderModalParentId)
    docStore.showToast(`已创建目录「${name}」`)
    folderInputName.value = ''
    docStore.closeFolderModal()
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || '网络异常'))
  } finally {
    creatingFolder.value = false
  }
}

const triggerFilePicker = () => {
  if (fileInputRef.value) {
    fileInputRef.value.click()
  }
}

const handleFilePicked = (e) => {
  if (e.target.files && e.target.files.length > 0) {
    selectedFile.value = e.target.files[0]
  }
}

const handleConfirmUpload = async () => {
  uploadingFile.value = true
  try {
    if (selectedFile.value || uploadFileContent.value.trim()) {
      let fileToUpload = selectedFile.value
      if (!fileToUpload && uploadFileContent.value.trim()) {
        const customName = '文本纪要_' + new Date().toLocaleTimeString('zh-CN', { hour12: false }).replace(/:/g, '') + '.txt'
        const blob = new Blob([uploadFileContent.value.trim()], { type: 'text/plain;charset=utf-8' })
        fileToUpload = new File([blob], customName, { type: 'text/plain' })
      }

      // 真实后端上传
      const formData = new FormData()
      formData.append('file', fileToUpload)
      formData.append('folderId', docStore.uploadTargetFolderId || '0')
      const res = await uploadServerDoc(formData)
      if (res && res.data && res.data.id) {
        docStore.undeleteDocId(res.data.id)
      }
      docStore.showToast(`《${fileToUpload.name}》已成功上传并完成全文索引`)
      await docStore.loadDocuments()
      if (res && res.data) {
        docStore.selectDocForPreview(res.data, 0, '')
      }
    }

    docStore.previewOpen = true
    selectedFile.value = null
    uploadFileContent.value = ''
    docStore.closeUploadModal()
  } catch (e) {
    ElMessage.error('上传解析失败: ' + (e.message || '网络异常'))
  } finally {
    uploadingFile.value = false
  }
}

const handleConfirmDelete = async () => {
  const target = docStore.deleteTarget
  if (!target) return
  if (target.type === 'doc') {
    await docStore.deleteDoc(target.id)
    docStore.showToast(`已成功删除文档《${target.name}》`)
  } else if (target.type === 'folder') {
    await docStore.deleteFolder(target.id)
    docStore.showToast(`已成功删除文件夹「${target.name}」`)
  }
  docStore.closeDeleteModal()
}
</script>

<style scoped>
:deep(.docx-render-container) {
  min-height: 480px;
}
:deep(.docx-wrapper) {
  background: transparent !important;
  padding: 0 !important;
}
:deep(.docx-wrapper > section.docx) {
  margin-bottom: 16px !important;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.08) !important;
  border-radius: 8px !important;
  max-width: 100% !important;
  box-sizing: border-box !important;
}

:deep(.markdown-body) {
  color: #1F2937;
  font-size: 13.5px;
  line-height: 1.65;
}
:deep(.markdown-body h1),
:deep(.markdown-body h2),
:deep(.markdown-body h3) {
  font-weight: 700;
  margin-top: 1.25rem;
  margin-bottom: 0.5rem;
  color: #111827;
}
:deep(.markdown-body h1) {
  font-size: 1.4rem;
  border-bottom: 1px solid #E5E7EB;
  padding-bottom: 0.3rem;
}
:deep(.markdown-body h2) {
  font-size: 1.2rem;
  border-bottom: 1px solid #E5E7EB;
  padding-bottom: 0.25rem;
}
:deep(.markdown-body h3) {
  font-size: 1.05rem;
}
:deep(.markdown-body p) {
  margin-top: 0.5rem;
  margin-bottom: 0.75rem;
}
:deep(.markdown-body ul) {
  list-style-type: disc;
  margin-left: 1.5rem;
  margin-bottom: 0.75rem;
}
:deep(.markdown-body ol) {
  list-style-type: decimal;
  margin-left: 1.5rem;
  margin-bottom: 0.75rem;
}
:deep(.markdown-body blockquote) {
  border-left: 3px solid #3B5BDB;
  background-color: #F8FAFC;
  padding: 0.5rem 1rem;
  border-radius: 0 0.5rem 0.5rem 0;
  color: #4B5563;
  margin: 0.75rem 0;
}
:deep(.markdown-body table) {
  width: 100%;
  border-collapse: collapse;
  margin: 1rem 0;
  font-size: 12.5px;
}
:deep(.markdown-body table th),
:deep(.markdown-body table td) {
  border: 1px solid #E5E7EB;
  padding: 6px 12px;
}
:deep(.markdown-body table th) {
  background-color: #F9FAFB;
  font-weight: 600;
}
:deep(.markdown-body code) {
  background-color: #F3F4F6;
  padding: 2px 5px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}
:deep(.markdown-body pre) {
  background-color: #1F2937;
  color: #F9FAFB;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 0.75rem 0;
}
:deep(.markdown-body pre code) {
  background-color: transparent;
  color: inherit;
  padding: 0;
}
</style>
