<template>
  <transition name="modal-fade">
    <div
      v-if="docStore.previewModalOpen && doc"
      :class="{ 'docx-reader-overlay': fileExt === 'docx' }" class="fixed inset-0 z-50 bg-stone-900/60 backdrop-blur-md flex items-center justify-center p-3 sm:p-5"
      @click.self="docStore.closePreviewModal"
      @keydown.esc="docStore.closePreviewModal"
      tabindex="0"
    >
      <div
        class="bg-white rounded-3xl shadow-2xl border border-canvas-border flex flex-col w-full h-[90vh] max-h-[960px] transition-all duration-300 overflow-hidden"
        :class="fileExt === 'docx' ? 'docx-reader-window' : isFullscreen ? 'fixed inset-0 h-full max-h-none rounded-none' : 'max-w-6xl'"
      >
        <header v-if="fileExt === 'docx'" class="docx-reader-header">
          <div class="docx-file-pill" :title="docTitle"><span aria-hidden="true">▤</span><span class="docx-file-name">{{ docTitle }}</span></div>
          <div class="docx-reader-actions">
            <button type="button" class="docx-reader-button" title="下载原件" aria-label="下载原件" @click="docStore.downloadDoc(doc)">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M12 3v12m-5-5 5 5 5-5M4 15v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4"/></svg>
            </button>
            <button type="button" class="docx-reader-button" title="关闭预览 (Esc)" aria-label="关闭预览" @click="docStore.closePreviewModal">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path stroke-linecap="round" d="m6 6 12 12M6 18 18 6"/></svg>
            </button>
          </div>
        </header>
        <!-- ================= MODAL HEADER ================= -->
        <div v-if="fileExt !== 'docx'" class="px-5 py-3.5 border-b border-canvas-border/80 bg-white/95 flex items-center justify-between gap-3 shrink-0">
          <!-- Left: Document Identity & Metadata -->
          <div class="flex items-center gap-3 min-w-0">
            <div
              class="w-10 h-10 rounded-xl font-bold font-mono text-[11px] flex items-center justify-center shrink-0 shadow-sm text-white"
              :style="{ backgroundColor: getBadgeColor(fileExt) }"
            >
              {{ (fileExt || 'PDF').toUpperCase() }}
            </div>
            <div class="min-w-0">
              <div class="flex items-center gap-2">
                <h3 class="text-[15px] font-bold text-ink-primary truncate" :title="docTitle">
                  {{ docTitle }}
                </h3>
                <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-sage-50 text-sage-600 border border-sage-100 text-[11px] font-medium shrink-0">
                  <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>
                  已建立 OCR 索引
                </span>
              </div>
              <div class="text-[11.5px] text-ink-muted flex items-center gap-2 mt-0.5 truncate font-mono">
                <span>{{ docSize }}</span>
                <span>·</span>
                <span>共 {{ pageCount }} 页</span>
                <span>·</span>
                <span class="text-ink-secondary">{{ folderName }}</span>
                <span>·</span>
                <span>{{ doc.updateTime || doc.createTime || '2026-10-23' }}</span>
              </div>
            </div>
          </div>

          <!-- Center: Mode Switch (原件排版 vs 双栏对照 vs OCR 文本层) -->
          <div class="hidden md:inline-flex p-0.5 rounded-xl bg-canvas-subtle border border-canvas-border/80 text-[12px] shrink-0">
            <button
              type="button"
              class="px-3 py-1.5 rounded-lg font-medium transition-craft cursor-pointer"
              :class="activeTab === 'canvas' ? 'bg-white text-ink-primary shadow-sm font-semibold' : 'text-ink-secondary hover:text-ink-primary'"
              @click="switchModalTab('canvas')"
            >
              <span class="inline-flex items-center gap-1.5">
                <svg class="w-3.5 h-3.5 text-brand-600" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z"/>
                </svg>
                <span>文档原件</span>
              </span>
            </button>
            <button
              type="button"
              class="px-3 py-1.5 rounded-lg font-medium transition-craft cursor-pointer"
              :class="activeTab === 'split' ? 'bg-white text-ink-primary shadow-sm font-semibold' : 'text-ink-secondary hover:text-ink-primary'"
              @click="switchModalTab('split')"
            >
              <span class="inline-flex items-center gap-1.5">
                <svg class="w-3.5 h-3.5 text-indigo-600" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 4.5v15m6-15v15m-10.875 0h15.75c.621 0 1.125-.504 1.125-1.125V5.625c0-.621-.504-1.125-1.125-1.125H4.125C3.504 4.5 3 5.004 3 5.625v12.75c0 .621.504 1.125 1.125 1.125z"/>
                </svg>
                <span>双栏对照</span>
              </span>
            </button>
            <button
              type="button"
              class="px-3 py-1.5 rounded-lg font-medium transition-craft cursor-pointer"
              :class="activeTab === 'ocr' ? 'bg-white text-ink-primary shadow-sm font-semibold' : 'text-ink-secondary hover:text-ink-primary'"
              @click="switchModalTab('ocr')"
            >
              <span class="inline-flex items-center gap-1.5">
                <svg class="w-3.5 h-3.5 text-sage-600" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 01.865-.501 48.172 48.172 0 003.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z"/>
                </svg>
                <span>OCR 文本层</span>
              </span>
            </button>
          </div>

          <!-- Right: Action Buttons & Window Controls -->
          <div class="flex items-center gap-2 shrink-0">
            <!-- Download Button -->
            <button
              type="button"
              class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary hover:text-ink-primary border border-canvas-border text-[12px] font-medium transition-craft cursor-pointer"
              title="下载文档原件"
              @click="docStore.downloadDoc(doc)"
            >
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3"/>
              </svg>
              <span class="hidden sm:inline">下载原件</span>
            </button>

            <!-- Fullscreen Toggle -->
            <button
              type="button"
              class="w-8 h-8 rounded-xl bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary hover:text-ink-primary border border-canvas-border flex items-center justify-center transition-craft cursor-pointer"
              :title="isFullscreen ? '退出全屏' : '全屏展示'"
              @click="isFullscreen = !isFullscreen"
            >
              <svg v-if="!isFullscreen" class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15"/>
              </svg>
              <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 9V4.5M9 9H4.5M9 9L3.75 3.75M9 15v4.5M9 15H4.5M9 15l-5.25 5.25M15 9h4.5M15 9V4.5M15 9l5.25-5.25M15 15h4.5M15 15v4.5m0-4.5l5.25 5.25"/>
              </svg>
            </button>

            <!-- Close Modal -->
            <button
              type="button"
              class="w-8 h-8 rounded-xl bg-canvas-subtle hover:bg-rose-50 hover:text-rose-600 text-ink-secondary border border-canvas-border flex items-center justify-center transition-craft cursor-pointer ml-1"
              title="关闭预览 (Esc)"
              @click="docStore.closePreviewModal"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2.2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- ================= MODAL SUB-TOOLBAR (FORMAT-AWARE) ================= -->
        <div v-if="fileExt !== 'docx'" class="px-5 py-2.5 bg-canvas-bg/80 border-b border-canvas-border/70 flex items-center justify-between text-[12px] text-ink-secondary shrink-0">
          <!-- Left Tool Controls based on format & tab -->
          <div class="flex items-center gap-3">
            <template v-if="activeTab === 'canvas'">
              <!-- PDF Tools -->
              <div v-if="fileExt === 'pdf'" class="flex items-center gap-3">
                <span class="inline-flex items-center gap-1.5 font-medium text-brand-700">
                  <span class="w-2 h-2 rounded-full bg-brand-500"></span>
                  PDF 高清流式预览
                </span>
                <div v-if="!isRealBackendDoc" class="flex items-center gap-1.5">
                  <button type="button" class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" :disabled="docStore.previewModalPageIndex <= 0" @click="prevPage">上一页</button>
                  <span class="font-mono text-[11px]">{{ docStore.previewModalPageIndex + 1 }} / {{ pageCount }}</span>
                  <button type="button" class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" :disabled="docStore.previewModalPageIndex >= pageCount - 1" @click="nextPage">下一页</button>
                </div>
                <span v-else class="text-[11px] text-ink-muted font-mono hidden sm:inline">Chromium 内置高性能 PDF 阅读器已就绪</span>
              </div>

              <!-- Word docx Tools -->
              <div v-else-if="fileExt === 'docx'" class="flex items-center gap-2">
                <span class="inline-flex items-center gap-1.5 font-medium text-blue-700">
                  <span class="w-2 h-2 rounded-full bg-blue-500"></span>
                  Word (.docx) 前端原生渲染
                </span>
                <span class="text-[11px] text-ink-muted">docx-preview 高保真排版排版解析</span>
              </div>

              <!-- Word doc Tools -->
              <div v-else-if="fileExt === 'doc'" class="flex items-center gap-2">
                <span class="inline-flex items-center gap-1.5 font-medium text-blue-700">
                  <span class="w-2 h-2 rounded-full bg-blue-500"></span>
                  Office 文档分页预览
                </span>
                <span class="text-[11px] text-ink-muted">保留页面、表格与幻灯片布局</span>
              </div>

              <!-- TXT Tools -->
              <div v-else-if="fileExt === 'txt'" class="flex items-center gap-2.5">
                <span class="inline-flex items-center gap-1.5 font-medium text-emerald-700">
                  <span class="w-2 h-2 rounded-full bg-emerald-500"></span>
                  纯文本阅读器 (共 {{ txtLineCount }} 行)
                </span>
                <div class="flex items-center gap-1">
                  <button type="button" class="px-1.5 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="txtFontSize = Math.max(11, txtFontSize - 1)">A-</button>
                  <span class="font-mono text-[11px] px-1">{{ txtFontSize }}px</span>
                  <button type="button" class="px-1.5 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="txtFontSize = Math.min(20, txtFontSize + 1)">A+</button>
                  <button type="button" class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px] ml-1" @click="txtLineWrap = !txtLineWrap">
                    {{ txtLineWrap ? '自动换行' : '单行滚动' }}
                  </button>
                </div>
              </div>

              <!-- Markdown Tools -->
              <div v-else-if="fileExt === 'md'" class="flex items-center gap-2.5">
                <span class="inline-flex items-center gap-1.5 font-medium text-purple-700">
                  <span class="w-2 h-2 rounded-full bg-purple-500"></span>
                  Markdown 文档阅读器
                </span>
                <div class="inline-flex p-0.5 rounded-lg bg-white border border-canvas-border text-[11px]">
                  <button type="button" class="px-2.5 py-0.5 rounded" :class="mdMode === 'rendered' ? 'bg-purple-50 text-purple-700 font-bold' : 'text-ink-muted'" @click="mdMode = 'rendered'">排版视图</button>
                  <button type="button" class="px-2.5 py-0.5 rounded" :class="mdMode === 'source' ? 'bg-purple-50 text-purple-700 font-bold' : 'text-ink-muted'" @click="mdMode = 'source'">源码视图</button>
                </div>
              </div>

              <!-- Image Tools -->
              <div v-else-if="isImage" class="flex items-center gap-2">
                <span class="inline-flex items-center gap-1.5 font-medium text-amberWarm-700">
                  <span class="w-2 h-2 rounded-full bg-amberWarm-500"></span>
                  图片原件视图
                </span>
                <button type="button" class="px-2 py-0.5 rounded bg-white hover:bg-canvas-hover border text-[11px]" @click="imgRotation = (imgRotation + 90) % 360">旋转 90°</button>
              </div>

              <!-- Other Files -->
              <div v-else class="flex items-center gap-2">
                <span class="font-medium text-ink-primary">{{ fileExt.toUpperCase() }} 文档文件</span>
              </div>
            </template>

            <!-- Split Comparison Tab Tools -->
            <template v-else-if="activeTab === 'split'">
              <div class="flex items-center gap-2">
                <button type="button" class="w-6 h-6 rounded hover:bg-white border border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.previewModalPageIndex <= 0" @click="prevPage">‹</button>
                <span class="font-mono text-[11.5px] px-2 py-0.5 bg-white rounded border border-canvas-border">
                  第 {{ docStore.previewModalPageIndex + 1 }} 页 / 共 {{ pageCount }} 页
                </span>
                <button type="button" class="w-6 h-6 rounded hover:bg-white border border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.previewModalPageIndex >= pageCount - 1" @click="nextPage">›</button>
              </div>
              <span class="text-[11px] text-indigo-700 bg-indigo-50 border border-indigo-100 font-medium px-2 py-0.5 rounded hidden sm:inline">
                双层分栏对照模式
              </span>
              <div class="inline-flex p-0.5 rounded-lg bg-white border border-canvas-border/80 text-[11px]">
                <button type="button" class="px-2 py-0.5 rounded cursor-pointer transition-craft" :class="ocrViewMode === 'cards' ? 'bg-indigo-50 text-indigo-700 font-bold' : 'text-ink-muted'" @click="ocrViewMode = 'cards'">结构分块</button>
                <button type="button" class="px-2 py-0.5 rounded cursor-pointer transition-craft" :class="ocrViewMode === 'text' ? 'bg-indigo-50 text-indigo-700 font-bold' : 'text-ink-muted'" @click="ocrViewMode = 'text'">连续纯文本</button>
              </div>
            </template>

            <!-- OCR Tab Tools -->
            <template v-else>
              <div class="flex items-center gap-2">
                <button type="button" class="w-6 h-6 rounded hover:bg-white border border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.previewModalPageIndex <= 0" @click="prevPage">‹</button>
                <span class="font-mono text-[11.5px] px-2 py-0.5 bg-white rounded border border-canvas-border">
                  第 {{ docStore.previewModalPageIndex + 1 }} 页 / 共 {{ pageCount }} 页
                </span>
                <button type="button" class="w-6 h-6 rounded hover:bg-white border border-canvas-border flex items-center justify-center disabled:opacity-40 cursor-pointer" :disabled="docStore.previewModalPageIndex >= pageCount - 1" @click="nextPage">›</button>
              </div>
              <span class="text-[11px] text-sage-600 font-mono hidden md:inline">置信度 99.8% · {{ currentOcrBlocks.length }} 个区块</span>
              <div class="inline-flex p-0.5 rounded-lg bg-white border border-canvas-border/80 text-[11px]">
                <button type="button" class="px-2 py-0.5 rounded cursor-pointer transition-craft" :class="ocrViewMode === 'cards' ? 'bg-brand-50 text-brand-700 font-bold' : 'text-ink-muted'" @click="ocrViewMode = 'cards'">分块卡片</button>
                <button type="button" class="px-2 py-0.5 rounded cursor-pointer transition-craft" :class="ocrViewMode === 'text' ? 'bg-brand-50 text-brand-700 font-bold' : 'text-ink-muted'" @click="ocrViewMode = 'text'">连续排版</button>
              </div>
            </template>
          </div>

          <!-- Right Action: Copy text or Export TXT -->
          <div class="flex items-center gap-2">
            <template v-if="activeTab === 'canvas'">
              <button
                v-if="fileExt === 'txt'"
                type="button"
                class="px-2.5 py-1 rounded-lg bg-white hover:bg-canvas-hover border border-canvas-border text-[11px] font-medium text-brand-600 cursor-pointer"
                @click="copyTxtContent"
              >
                复制全文
              </button>
              <button
                v-else-if="fileExt === 'md'"
                type="button"
                class="px-2.5 py-1 rounded-lg bg-white hover:bg-canvas-hover border border-canvas-border text-[11px] font-medium text-brand-600 cursor-pointer"
                @click="copyMdContent"
              >
                复制代码
              </button>
              <button
                type="button"
                class="px-2.5 py-1 rounded-lg bg-white hover:bg-canvas-hover border border-canvas-border text-[11px] font-medium text-ink-secondary hover:text-ink-primary cursor-pointer"
                @click="docStore.downloadDoc(doc)"
              >
                下载原件
              </button>
            </template>
            <template v-else>
              <button
                type="button"
                class="px-2.5 py-1 rounded-lg bg-white hover:bg-canvas-hover border border-canvas-border text-[11px] font-medium text-ink-secondary hover:text-ink-primary cursor-pointer"
                title="导出本页 OCR 文本为 TXT 文件"
                @click="downloadCurrentPageOcrTxt"
              >
                导出TXT
              </button>
              <button
                type="button"
                class="px-2.5 py-1 rounded-lg bg-brand-50 hover:bg-brand-100 border border-brand-200/80 text-[11px] font-medium text-brand-700 cursor-pointer"
                @click="copyOcrText"
              >
                复制本页文本
              </button>
            </template>
          </div>
        </div>

        <!-- Optional Keyword Highlight Banner -->
        <div
          v-if="activeKeyword"
          class="px-5 py-2 bg-rose-50/90 border-b border-rose-200/70 flex items-center justify-between text-[12px] text-rose-800 shrink-0"
        >
          <div class="flex items-center gap-2 truncate">
            <span class="w-2 h-2 rounded-full bg-rose-500 shrink-0"></span>
            <span class="truncate">已标红定位全文检索关键词：“{{ activeKeyword }}”</span>
          </div>
          <button
            type="button"
            class="text-[11.5px] text-rose-600 hover:underline shrink-0 ml-3 cursor-pointer"
            @click="activeKeyword = ''"
          >
            清除高亮
          </button>
        </div>

        <!-- ================= MODAL BODY VIEWPORT ================= -->
        <div :class="{ 'docx-reader-canvas': fileExt === 'docx' }" class="flex-1 overflow-y-auto bg-stone-100/60 p-4 sm:p-6 flex flex-col items-center min-h-0">

          <!-- ================= TAB 1: ORIGINAL DOCUMENT RENDERING ================= -->
          <template v-if="activeTab === 'canvas'">

            <!-- 1. PDF Preview -->
            <div v-if="fileExt === 'pdf'" class="w-full h-full flex flex-col flex-1">
              <!-- Real Backend PDF: Embed Stream -->
              <div v-if="isRealBackendDoc" class="w-full h-full flex-1 min-h-[560px]">
                <PdfDocumentPreview :doc-id="doc.id" @download="docStore.downloadDoc(doc)" />
              </div>
              <!-- Mock Demo PDF: Styled Paper View -->
              <div v-else class="w-full flex flex-col items-center py-4">
                <div class="w-full max-w-2xl bg-white rounded-2xl shadow-paper border border-canvas-border p-8 space-y-6">
                  <div class="flex items-center justify-between border-b border-canvas-border pb-4">
                    <div>
                      <h4 class="text-[17px] font-bold text-ink-primary">{{ docTitle }}</h4>
                      <p class="text-[12px] text-ink-muted mt-1 font-mono">
                        第 {{ docStore.previewModalPageIndex + 1 }} 页 / 共 {{ pageCount }} 页 · 电子正本
                      </p>
                    </div>
                    <span class="px-2.5 py-1 rounded-full bg-brand-50 text-brand-600 border border-brand-100 text-[11px] font-medium font-mono">
                      PAGE {{ docStore.previewModalPageIndex + 1 }}
                    </span>
                  </div>

                  <!-- Simulated Paper Document Content -->
                  <div class="space-y-4 text-[13.5px] text-ink-primary leading-relaxed">
                    <div
                      v-for="(block, bIdx) in currentOcrBlocks"
                      :key="bIdx"
                      class="p-3.5 rounded-xl transition-craft"
                      :class="highlightOcrBoxes ? 'border border-brand-500/25 bg-brand-50/15' : 'bg-canvas-bg/50'"
                    >
                      <div class="text-[10px] font-mono text-ink-muted mb-1">{{ block.label }}</div>
                      <div v-html="highlightKeywordHtml(block.text, activeKeyword)"></div>
                    </div>
                  </div>

                  <div class="pt-6 border-t border-canvas-border/80 flex items-center justify-between text-[11.5px] text-ink-muted">
                    <span>文档检索安全水印 · RapidOCR 引擎驱动</span>
                    <span class="font-mono">{{ doc.id }}</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- 2. Word .docx Preview (docx-preview Native Render) -->
            <div v-else-if="fileExt === 'docx'" class="w-full flex flex-col items-center py-2 flex-1">
              <div v-if="docxLoading" class="py-24 flex flex-col items-center gap-3 text-ink-muted text-[13px]">
                <svg class="w-7 h-7 animate-spin text-brand-600" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path>
                </svg>
                <span>正在高保真排版 Word (.docx) 原件...</span>
              </div>
              <div v-else-if="docxError" class="p-8 bg-white rounded-2xl border border-canvas-border shadow-craft-sm text-center space-y-3 max-w-md w-full">
                <div class="w-12 h-12 rounded-2xl bg-amberWarm-50 text-amberWarm-600 flex items-center justify-center mx-auto">
                  <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z"/>
                  </svg>
                </div>
                <h4 class="font-bold text-[14px] text-ink-primary">Word 排版预览提示</h4>
                <p class="text-[12.5px] text-ink-muted">{{ docxError }}</p>
                <div class="flex items-center justify-center gap-2 pt-2">
                  <button type="button" class="px-4 py-2 rounded-xl bg-brand-500 text-white text-[12.5px] font-medium cursor-pointer" @click="activeTab = 'ocr'">查阅正文</button>
                  <button type="button" class="px-4 py-2 rounded-xl bg-canvas-subtle border text-[12.5px] font-medium cursor-pointer" @click="docStore.downloadDoc(doc)">下载原件</button>
                </div>
              </div>
              <div
                v-show="!docxLoading && !docxError"
                ref="modalDocxContainerRef"
                class="docx-modal-container"
              ></div>
            </div>

            <!-- 3. Word .doc Preview (Legacy 97-2003 Formatted Text View) -->
            <div v-else-if="['doc', 'xls', 'xlsx', 'ppt', 'pptx'].includes(fileExt)" class="w-full flex-1">
                <PdfDocumentPreview :doc-id="doc.id" @download="docStore.downloadDoc(doc)" />
              </div>

              <!-- 4. TXT Preview -->
            <div v-else-if="fileExt === 'txt'" class="w-full max-w-5xl flex flex-col flex-1 py-2">
              <div v-if="txtLoading" class="py-24 text-center text-ink-muted text-[13px]">正在加载文本文档...</div>
              <div v-else class="w-full bg-white rounded-2xl shadow-craft-md border border-canvas-border overflow-hidden flex flex-1 min-h-[460px]">
                <!-- Gutter -->
                <div class="bg-canvas-bg/90 border-r border-canvas-border py-4 px-3 text-right font-mono text-ink-muted text-[12px] select-none shrink-0">
                  <div v-for="n in txtLineCount" :key="n">{{ n }}</div>
                </div>
                <!-- Content -->
                <pre
                  class="p-5 font-mono text-ink-primary overflow-x-auto flex-1 leading-relaxed outline-none"
                  :class="txtLineWrap ? 'whitespace-pre-wrap' : 'whitespace-pre'"
                  :style="{ fontSize: `${txtFontSize}px` }"
                  v-html="highlightKeywordHtml(txtRaw, activeKeyword)"
                ></pre>
              </div>
            </div>

            <!-- 5. Markdown Preview -->
            <div v-else-if="fileExt === 'md'" class="w-full max-w-5xl flex flex-col flex-1 py-2">
              <div v-if="mdLoading" class="py-24 text-center text-ink-muted text-[13px]">正在解析 Markdown 排版...</div>
              <div v-else-if="mdMode === 'rendered'" class="w-full bg-white rounded-2xl shadow-craft-md border border-canvas-border p-8 sm:p-12 markdown-body text-ink-primary text-[14px] leading-relaxed" v-html="renderedMdHtml"></div>
              <div v-else class="w-full bg-stone-900 text-stone-100 font-mono text-[13px] rounded-2xl p-6 overflow-auto shadow-craft-md">
                <pre><code>{{ mdRaw }}</code></pre>
              </div>
            </div>

            <!-- 6. Image Preview -->
            <div v-else-if="isImage" class="w-full h-full flex flex-col items-center justify-center p-4">
              <img
                :src="'/api/server/doc/stream-raw/' + doc.id"
                class="max-w-full max-h-[72vh] object-contain rounded-2xl shadow-craft-md border border-canvas-border transition-transform duration-200"
                :style="{ transform: `rotate(${imgRotation}deg)` }"
              />
            </div>

            <!-- 7. Fallback Generic View -->
            <div v-else class="w-full max-w-lg bg-white rounded-2xl border border-canvas-border p-8 text-center space-y-4 my-auto shadow-craft-sm">
              <div class="w-14 h-14 rounded-2xl bg-brand-50 text-brand-600 font-mono font-bold text-[14px] flex items-center justify-center mx-auto">
                {{ fileExt.toUpperCase() }}
              </div>
              <h4 class="text-[16px] font-bold text-ink-primary">{{ docTitle }}</h4>
              <p class="text-[12.5px] text-ink-muted">该文件格式已建立双层全文索引，你可以查阅右侧 OCR 提取文本，或直接下载原件查看。</p>
              <div class="flex items-center justify-center gap-3 pt-2">
                <button type="button" class="px-4 py-2 rounded-xl bg-brand-500 text-white text-[13px] font-medium cursor-pointer" @click="activeTab = 'ocr'">查看 OCR 文本</button>
                <button type="button" class="px-4 py-2 rounded-xl bg-canvas-subtle border text-[13px] font-medium cursor-pointer" @click="docStore.downloadDoc(doc)">下载文件原件</button>
              </div>
            </div>
          </template>

          <!-- ================= TAB 2: DUAL-PANE SPLIT COMPARISON ================= -->
          <template v-else-if="activeTab === 'split'">
            <div class="w-full flex-1 flex flex-col lg:flex-row gap-4 min-h-[640px] max-w-7xl mx-auto">
              <!-- Left Column: Original Document View -->
              <div class="flex-1 bg-white rounded-2xl border border-canvas-border shadow-craft-sm p-4 sm:p-5 flex flex-col overflow-y-auto min-h-[500px]">
                <div class="flex items-center justify-between border-b border-canvas-border pb-3 mb-4 shrink-0">
                  <div class="flex items-center gap-2">
                    <span class="w-2.5 h-2.5 rounded-full bg-brand-500"></span>
                    <h4 class="font-bold text-[14px] text-ink-primary">文档原件对照区</h4>
                    <span class="text-[11px] font-mono px-2 py-0.5 rounded bg-brand-50 text-brand-600 border border-brand-100 uppercase">
                      {{ fileExt }}
                    </span>
                  </div>
                  <span class="text-[11.5px] text-ink-muted font-mono">
                    第 {{ docStore.previewModalPageIndex + 1 }} 页 / 共 {{ pageCount }} 页
                  </span>
                </div>

                <!-- PDF -->
                <div v-if="fileExt === 'pdf'" class="w-full flex-1 flex flex-col min-h-[500px]">
                  <PdfDocumentPreview v-if="isRealBackendDoc" :doc-id="doc.id" @download="docStore.downloadDoc(doc)" />
                  <div v-else class="space-y-3 p-4 bg-canvas-bg/50 rounded-xl">
                    <div v-for="(block, bIdx) in currentOcrBlocks" :key="bIdx" class="p-2.5 bg-white rounded-lg border border-canvas-border text-[13px]">
                      {{ block.text }}
                    </div>
                  </div>
                </div>

                <!-- Word .docx -->
                <div v-else-if="fileExt === 'docx'" class="w-full flex-1 flex flex-col">
                  <div v-if="docxLoading" class="py-16 text-center text-ink-muted text-[13px]">正在渲染 Word 排版...</div>
                  <div v-else-if="docxError" class="p-4 bg-amberWarm-50 text-amberWarm-700 text-[12px] rounded-xl">{{ docxError }}</div>
                  <div ref="modalDocxSplitContainerRef" class="docx-split-container overflow-x-auto"></div>
                </div>

                <!-- Word .doc -->
                <div v-else-if="['doc', 'xls', 'xlsx', 'ppt', 'pptx'].includes(fileExt)" class="w-full flex-1">
                  <PdfDocumentPreview :doc-id="doc.id" @download="docStore.downloadDoc(doc)" />
                </div>
                <!-- TXT -->
                <div v-else-if="fileExt === 'txt'" class="w-full flex-1 flex flex-col">
                  <pre class="font-mono text-[12px] p-4 bg-canvas-bg rounded-xl overflow-auto leading-relaxed" v-html="highlightKeywordHtml(txtRaw, activeKeyword)"></pre>
                </div>

                <!-- Markdown -->
                <div v-else-if="fileExt === 'md'" class="w-full flex-1 flex flex-col">
                  <div class="markdown-body text-[13px] p-2 leading-relaxed" v-html="renderedMdHtml"></div>
                </div>

                <!-- Image -->
                <div v-else-if="isImage" class="w-full flex-1 flex items-center justify-center p-2">
                  <img :src="'/api/server/doc/stream-raw/' + doc.id" class="max-w-full max-h-[60vh] object-contain rounded-xl border border-canvas-border" :style="{ transform: `rotate(${imgRotation}deg)` }" />
                </div>

                <!-- Other -->
                <div v-else class="text-center py-20 text-ink-muted text-[13px]">
                  该格式原件直接支持右侧 OCR 双层检索提取
                </div>
              </div>

              <!-- Right Column: Structured OCR Text Layer View -->
              <div class="flex-1 bg-white rounded-2xl border border-canvas-border shadow-craft-md p-4 sm:p-5 flex flex-col overflow-y-auto min-h-[500px]">
                <div class="flex items-center justify-between border-b border-canvas-border pb-3 mb-4 shrink-0">
                  <div class="flex items-center gap-2">
                    <span class="w-2.5 h-2.5 rounded-full bg-sage-500"></span>
                    <h4 class="font-bold text-[14px] text-ink-primary">RapidOCR 双层文本层</h4>
                    <span class="text-[11px] font-mono px-2 py-0.5 rounded bg-sage-50 text-sage-600 border border-sage-100">
                      置信度 99.8%
                    </span>
                  </div>
                  <div class="flex items-center gap-2">
                    <span class="text-[11px] font-mono text-ink-muted">{{ currentOcrBlocks.length }} 区块 · {{ currentOcrCharCount }} 字</span>
                  </div>
                </div>

                <!-- Cards Mode in Split -->
                <div v-if="ocrViewMode === 'cards'" class="space-y-2.5">
                  <div
                    v-for="(block, bIdx) in filteredOcrBlocks"
                    :key="bIdx"
                    class="p-3.5 rounded-xl border border-canvas-border/80 bg-white hover:border-brand-400 hover:shadow-craft-sm transition-craft space-y-1.5 ocr-bbox group"
                    :class="highlightOcrBoxes ? 'border-brand-500/30' : ''"
                  >
                    <div class="flex items-center justify-between text-[11px]">
                      <div class="flex items-center gap-1.5">
                        <span class="px-2 py-0.5 rounded font-mono font-medium border" :class="getSemanticBadge(block.type).badgeClass">
                          {{ block.label }}
                        </span>
                        <span class="text-sage-600 font-mono text-[10.5px]">{{ block.confidence || '99.8%' }}</span>
                      </div>
                      <div class="flex items-center gap-2">
                        <span class="text-[10px] text-ink-muted font-mono">{{ (block.text || '').length }} 字</span>
                        <button
                          type="button"
                          class="opacity-0 group-hover:opacity-100 px-2 py-0.5 rounded bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary text-[11px] transition-craft cursor-pointer"
                          :class="{ '!opacity-100 !text-brand-600 !bg-brand-50': copiedBlockIdx === bIdx }"
                          @click="copySingleBlock(block.text, bIdx)"
                        >
                          {{ copiedBlockIdx === bIdx ? '已复制 ✓' : '复制' }}
                        </button>
                      </div>
                    </div>
                    <div
                      :class="block.type === 'title' ? 'font-bold text-[14px] text-ink-primary leading-snug' : 'text-[13px] text-ink-primary leading-relaxed'"
                      v-html="highlightKeywordHtml(block.text, activeKeyword || ocrSearchKeyword)"
                    ></div>
                  </div>
                  <div v-if="filteredOcrBlocks.length === 0" class="text-center py-12 text-ink-muted text-[13px]">
                    本页暂无匹配的 OCR 文本区块
                  </div>
                </div>

                <!-- Text Mode in Split -->
                <div v-else class="flex flex-col flex-1">
                  <div class="p-3 bg-canvas-bg/60 border border-canvas-border rounded-xl font-mono text-[12.5px] leading-relaxed max-h-[520px] overflow-y-auto whitespace-pre-wrap select-text" v-html="currentOcrContinuousTextHtml"></div>
                </div>
              </div>
            </div>
          </template>

          <!-- ================= TAB 3: OCR TEXT LAYER FULL VIEW ================= -->
          <template v-else>
            <div class="w-full max-w-4xl flex flex-col flex-1 py-2 space-y-4">
              <!-- OCR Stats & Engine Banner -->
              <div class="p-4 bg-white rounded-2xl border border-canvas-border shadow-craft-sm flex flex-wrap items-center justify-between gap-3 text-[12px]">
                <div class="flex items-center gap-2.5">
                  <div class="w-8 h-8 rounded-xl bg-sage-50 border border-sage-200 text-sage-600 font-bold text-[12px] flex items-center justify-center font-mono">
                    OCR
                  </div>
                  <div>
                    <div class="font-bold text-[13px] text-ink-primary">RapidOCR PP-OCRv4 字符级智能检索引擎</div>
                    <div class="text-[11px] text-ink-muted mt-0.5">字符级文本图层 · 双层全文检索已建立索引</div>
                  </div>
                </div>
                <div class="flex items-center gap-3 text-ink-secondary text-[11.5px] font-mono">
                  <span class="px-2 py-0.5 rounded bg-canvas-subtle border">本页 {{ currentOcrBlocks.length }} 块</span>
                  <span class="px-2 py-0.5 rounded bg-canvas-subtle border">{{ currentOcrCharCount }} 字符</span>
                  <span class="px-2 py-0.5 rounded bg-sage-50 text-sage-600 border border-sage-200/60 font-medium">置信度 99.8%</span>
                </div>
              </div>

              <div class="bg-white rounded-2xl p-6 sm:p-8 border border-canvas-border shadow-craft-md space-y-5">
                <!-- Action & Filter Bar -->
                <div class="flex flex-wrap items-center justify-between gap-3 border-b border-canvas-border pb-4">
                  <div class="flex items-center gap-2">
                    <span class="text-[13.5px] font-bold text-ink-primary">OCR 结构化图层</span>
                    <span class="text-[11px] font-mono px-2 py-0.5 rounded bg-canvas-subtle text-ink-muted border">
                      第 {{ docStore.previewModalPageIndex + 1 }} 页 / 共 {{ pageCount }} 页
                    </span>
                  </div>

                  <div class="flex items-center gap-2.5">
                    <!-- In-page Filter -->
                    <div class="relative">
                      <input
                        v-model="ocrSearchKeyword"
                        type="text"
                        placeholder="过滤本页文本..."
                        class="w-36 sm:w-44 px-2.5 py-1 text-[11.5px] bg-canvas-bg rounded-lg border border-canvas-border focus:border-brand-500 outline-none"
                      />
                    </div>

                    <!-- View Switcher -->
                    <div class="inline-flex p-0.5 rounded-lg bg-canvas-subtle border border-canvas-border/80 text-[11.5px]">
                      <button
                        type="button"
                        class="px-2.5 py-1 rounded cursor-pointer transition-craft"
                        :class="ocrViewMode === 'cards' ? 'bg-white text-ink-primary font-bold shadow-xs' : 'text-ink-muted hover:text-ink-primary'"
                        @click="ocrViewMode = 'cards'"
                      >
                        结构分块
                      </button>
                      <button
                        type="button"
                        class="px-2.5 py-1 rounded cursor-pointer transition-craft"
                        :class="ocrViewMode === 'text' ? 'bg-white text-ink-primary font-bold shadow-xs' : 'text-ink-muted hover:text-ink-primary'"
                        @click="ocrViewMode = 'text'"
                      >
                        连续排版
                      </button>
                    </div>

                    <!-- Export TXT -->
                    <button
                      type="button"
                      class="px-3 py-1 rounded-lg bg-canvas-subtle hover:bg-canvas-hover border border-canvas-border text-[11.5px] font-medium text-ink-secondary hover:text-ink-primary transition-craft cursor-pointer"
                      title="导出本页 OCR 文本为 TXT 文件"
                      @click="downloadCurrentPageOcrTxt"
                    >
                      导出TXT
                    </button>
                    <button
                      type="button"
                      class="px-3 py-1 rounded-lg bg-brand-50 hover:bg-brand-100 border border-brand-200/60 text-[11.5px] font-medium text-brand-600 transition-craft cursor-pointer"
                      @click="copyOcrText"
                    >
                      复制本页文本
                    </button>
                  </div>
                </div>

                <!-- Page Blocks Mode 1: Cards -->
                <div v-if="ocrViewMode === 'cards'" class="space-y-3">
                  <div
                    v-for="(block, bIdx) in filteredOcrBlocks"
                    :key="bIdx"
                    class="p-4 rounded-xl border border-canvas-border/80 bg-white hover:border-brand-400 hover:shadow-craft-sm transition-craft space-y-2 ocr-bbox group"
                    :class="highlightOcrBoxes ? 'border-brand-500/30' : ''"
                  >
                    <div class="flex items-center justify-between text-[11px]">
                      <div class="flex items-center gap-2">
                        <span class="px-2 py-0.5 rounded font-mono font-medium border" :class="getSemanticBadge(block.type).badgeClass">
                          {{ block.label }}
                        </span>
                        <span class="text-sage-600 font-mono text-[11px]">{{ block.confidence || '99.8%' }}</span>
                      </div>
                      <div class="flex items-center gap-2.5">
                        <span class="text-[11px] text-ink-muted font-mono">{{ (block.text || '').length }} 字符</span>
                        <button
                          type="button"
                          class="opacity-0 group-hover:opacity-100 px-2 py-0.5 rounded bg-canvas-subtle hover:bg-canvas-hover text-ink-secondary text-[11px] transition-craft cursor-pointer"
                          :class="{ '!opacity-100 !text-brand-600 !bg-brand-50': copiedBlockIdx === bIdx }"
                          @click="copySingleBlock(block.text, bIdx)"
                        >
                          {{ copiedBlockIdx === bIdx ? '已复制 ✓' : '复制此段' }}
                        </button>
                      </div>
                    </div>
                    <div
                      :class="block.type === 'title' ? 'font-bold text-[15px] text-ink-primary leading-snug' : 'text-[13.5px] text-ink-primary leading-relaxed'"
                      v-html="highlightKeywordHtml(block.text, activeKeyword || ocrSearchKeyword)"
                    ></div>
                  </div>

                  <div v-if="filteredOcrBlocks.length === 0" class="text-center py-16 text-ink-muted text-[13px]">
                    本页暂无匹配的 OCR 文本区块
                  </div>
                </div>

                <!-- Page Blocks Mode 2: Continuous Text -->
                <div v-else class="rounded-xl border border-canvas-border overflow-hidden">
                  <div class="px-4 py-2.5 bg-canvas-bg/80 border-b border-canvas-border flex items-center justify-between text-[11.5px] text-ink-muted">
                    <span>连续纯文本阅读模式（含关键词定位高亮）</span>
                    <span class="font-mono">{{ currentOcrCharCount }} 字符</span>
                  </div>
                  <pre
                    class="p-5 font-mono text-[13px] text-ink-primary leading-relaxed whitespace-pre-wrap outline-none max-h-[560px] overflow-y-auto select-text bg-white"
                    v-html="currentOcrContinuousTextHtml"
                  ></pre>
                </div>
              </div>
            </div>
          </template>

        </div>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useDocStore, parseStructuredOcrBlocks } from '@/stores/doc'
import { ElMessage } from 'element-plus'
import axios from 'axios'
import PdfDocumentPreview from '@/components/PdfDocumentPreview.vue'
import { renderDocx, disposeDocx } from '@/utils/renderDocx'
import { marked } from 'marked'

const docStore = useDocStore()
const handlePreviewKeydown = (event) => {
  if (event.key === 'Escape' && docStore.previewModalOpen) docStore.closePreviewModal()
}
onMounted(() => window.addEventListener('keydown', handlePreviewKeydown))
onUnmounted(() => window.removeEventListener('keydown', handlePreviewKeydown))

const doc = computed(() => docStore.previewModalDoc)
const isFullscreen = ref(false)
const activeTab = ref('canvas') // 'canvas' | 'split' | 'ocr'
const ocrViewMode = ref('cards') // 'cards' | 'text'
const ocrSearchKeyword = ref('')
const copiedBlockIdx = ref(null)
const highlightOcrBoxes = ref(true)
const activeKeyword = ref('')

// Docx state
const modalDocxContainerRef = ref(null)
watch(modalDocxContainerRef, (_, previous) => { if (previous) disposeDocx(previous) })
const modalDocxSplitContainerRef = ref(null)
watch(modalDocxSplitContainerRef, (_, previous) => { if (previous) disposeDocx(previous) })
const docxLoading = ref(false)
const docxError = ref('')

const switchModalTab = (tab) => {
  activeTab.value = tab
  if (tab === 'split' && fileExt.value === 'docx') {
    nextTick(async () => {
      if (modalDocxSplitContainerRef.value && doc.value) {
        try {
          const resp = await axios.get(`/api/server/doc/stream-raw/${doc.value.id}`, { responseType: 'blob' })
          await renderDocx(resp.data, modalDocxSplitContainerRef.value)
        } catch (e) {
          console.warn('Docx split render exception:', e)
        }
      }
    })
  }
}

// Doc legacy state
const docLines = ref([])
const docLinesLoading = ref(false)

// TXT state
const txtRaw = ref('')
const txtLoading = ref(false)
const txtFontSize = ref(13)
const txtLineWrap = ref(true)

// Markdown state
const mdRaw = ref('')
const mdLoading = ref(false)
const mdMode = ref('rendered') // 'rendered' | 'source'

// Image state
const imgRotation = ref(0)

const docTitle = computed(() => {
  if (!doc.value) return ''
  return doc.value.fileName || doc.value.name || '文档预览'
})

const fileExt = computed(() => {
  if (!doc.value) return 'pdf'
  const ext = (doc.value.fileExt || (doc.value.fileName && doc.value.fileName.split('.').pop()) || 'pdf').toLowerCase()
  return ext
})

const isRealBackendDoc = computed(() => {
  if (!doc.value) return false
  return !String(doc.value.id).startsWith('doc_')
})

const docSize = computed(() => {
  if (!doc.value) return ''
  return doc.value.sizeLabel || (doc.value.fileSize ? `${(doc.value.fileSize / 1024).toFixed(1)} KB` : '1.2 MB')
})

const pageCount = computed(() => {
  if (!doc.value) return 1
  if (doc.value.pages && doc.value.pages.length > 0) {
    return doc.value.pages.length
  }
  return doc.value.pageCount || 1
})

const folderName = computed(() => {
  if (!doc.value) return '根目录'
  const found = docStore.folders.find(f => f.id === doc.value.folderId)
  return found ? found.name : '根目录'
})

const isImage = computed(() => {
  return ['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'bmp'].includes(fileExt.value)
})

const pdfStreamUrl = computed(() => {
  if (!doc.value || !isRealBackendDoc.value) return ''
  return `/api/server/doc/stream-preview/${doc.value.id}`
})

const txtLineCount = computed(() => {
  if (!txtRaw.value) return 1
  return txtRaw.value.split('\n').length
})

const renderedMdHtml = computed(() => {
  if (!mdRaw.value) return ''
  try {
    return marked.parse(mdRaw.value)
  } catch {
    return escapeHtml(mdRaw.value)
  }
})

const currentOcrBlocks = computed(() => {
  if (!doc.value) return []
  if (doc.value.pages && doc.value.pages.length > 0) {
    const idx = Math.min(docStore.previewModalPageIndex, doc.value.pages.length - 1)
    return doc.value.pages[idx] || []
  }
  if (fileExt.value === 'md' && mdRaw.value) {
    return parseStructuredOcrBlocks(mdRaw.value, docTitle.value)
  }
  if (fileExt.value === 'doc' && docLines.value.length > 0) {
    return parseStructuredOcrBlocks(docLines.value.join('\n'), docTitle.value)
  }
  if (fileExt.value === 'txt' && txtRaw.value) {
    return parseStructuredOcrBlocks(txtRaw.value, docTitle.value)
  }
  return [
    { label: '识别区块 #01', text: docTitle.value, type: 'title', confidence: '99.8%' }
  ]
})

const filteredOcrBlocks = computed(() => {
  if (!ocrSearchKeyword.value.trim()) return currentOcrBlocks.value
  const kw = ocrSearchKeyword.value.trim().toLowerCase()
  return currentOcrBlocks.value.filter(b => b.text && b.text.toLowerCase().includes(kw))
})

const currentOcrCharCount = computed(() => {
  return currentOcrBlocks.value.reduce((acc, b) => acc + (b.text ? b.text.length : 0), 0)
})

const currentOcrContinuousTextHtml = computed(() => {
  const kw = activeKeyword.value || ocrSearchKeyword.value
  return currentOcrBlocks.value.map(b => highlightKeywordHtml(b.text, kw)).join('\n\n')
})

const getSemanticBadge = (type) => {
  switch (type) {
    case 'title':
      return { label: '标题', badgeClass: 'bg-indigo-50 text-indigo-700 border-indigo-200/80', dot: 'bg-indigo-500' }
    case 'field':
      return { label: '字段', badgeClass: 'bg-emerald-50 text-emerald-700 border-emerald-200/80', dot: 'bg-emerald-500' }
    case 'seal':
      return { label: '落款/签章', badgeClass: 'bg-amberWarm-50 text-amberWarm-700 border-amberWarm-200/80', dot: 'bg-amberWarm-500' }
    case 'clause':
      return { label: '条款', badgeClass: 'bg-purple-50 text-purple-700 border-purple-200/80', dot: 'bg-purple-500' }
    default:
      return { label: '正文', badgeClass: 'bg-stone-100 text-stone-700 border-stone-200/80', dot: 'bg-stone-500' }
  }
}

const copySingleBlock = async (text, idx) => {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copiedBlockIdx.value = idx
    setTimeout(() => {
      if (copiedBlockIdx.value === idx) copiedBlockIdx.value = null
    }, 1500)
    docStore.showToast('已复制该区块文本至剪贴板')
  } catch {
    ElMessage.info('文本已复制')
  }
}

const downloadCurrentPageOcrTxt = () => {
  docStore.downloadOcrText(doc.value, docStore.previewModalPageIndex)
}

const getBadgeColor = (ext) => {
  switch (ext) {
    case 'pdf': return '#F43F5E'
    case 'doc':
    case 'docx': return '#2563EB'
    case 'md': return '#7C3AED'
    case 'txt': return '#059669'
    case 'png':
    case 'jpg':
    case 'jpeg': return '#EA580C'
    default: return '#4F46E5'
  }
}

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
  const cleanKw = keyword.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escaped = escapeHtml(text)
  const regex = new RegExp(`(${cleanKw})`, 'gi')
  return escaped.replace(regex, '<mark class="ocr-mark bg-amberWarm-200 text-amberWarm-900 px-1 py-0.5 rounded font-bold shadow-sm">$1</mark>')
}

const prevPage = () => {
  if (docStore.previewModalPageIndex > 0) {
    docStore.previewModalPageIndex--
  }
}

const nextPage = () => {
  if (docStore.previewModalPageIndex < pageCount.value - 1) {
    docStore.previewModalPageIndex++
  }
}

const copyOcrText = async () => {
  const blocks = currentOcrBlocks.value
  const text = blocks.map(b => `[${b.label}]\n${b.text}`).join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    docStore.showToast('已复制当前页 OCR 文本至剪贴板')
  } catch {
    ElMessage.info('已复制 OCR 文本')
  }
}

const copyTxtContent = async () => {
  if (txtRaw.value) {
    try {
      await navigator.clipboard.writeText(txtRaw.value)
      docStore.showToast('TXT 文本内容已复制至剪贴板')
    } catch {
      ElMessage.info('文本已复制')
    }
  }
}

const copyMdContent = async () => {
  if (mdRaw.value) {
    try {
      await navigator.clipboard.writeText(mdRaw.value)
      docStore.showToast('Markdown 源码已复制至剪贴板')
    } catch {
      ElMessage.info('源码已复制')
    }
  }
}

// 格式加载分发
let docxRequest = null
let docxLoadId = 0
const loadContentForModal = async () => {
  const loadId = ++docxLoadId
  docxRequest?.abort()
  docxRequest = new AbortController()
  const currentDoc = doc.value
  if (!currentDoc) return
  const ext = fileExt.value
  const isBackend = isRealBackendDoc.value

  activeKeyword.value = docStore.previewModalKeyword || docStore.activeHighlightKeyword || ''

  if (ext === 'docx') {
    docxLoading.value = true
    docxError.value = ''
    try {
      if (modalDocxContainerRef.value) {
        disposeDocx(modalDocxContainerRef.value)
        modalDocxContainerRef.value.replaceChildren()
      }
      const resp = await axios.get(`/api/server/doc/stream-raw/${currentDoc.id}`, { responseType: 'blob', signal: docxRequest.signal })
      if (loadId !== docxLoadId) return
      if (modalDocxContainerRef.value) {
        await renderDocx(resp.data, modalDocxContainerRef.value)
      }
    } catch (err) {
      if (loadId !== docxLoadId || axios.isCancel(err)) return
      console.warn('Docx 渲染异常:', err)
      docxError.value = 'Word 渲染异常: ' + (err.message || '文件格式不支持')
    } finally {
      if (loadId === docxLoadId) docxLoading.value = false
    }
  } else if (ext === 'txt') {
    txtLoading.value = true
    try {
      const resp = await axios.get(`/api/server/doc/stream-raw/${currentDoc.id}`, { responseType: 'text' })
      txtRaw.value = typeof resp.data === 'string' ? resp.data : JSON.stringify(resp.data, null, 2)
    } catch (err) {
      txtRaw.value = '文本读取失败: ' + err.message
    } finally {
      txtLoading.value = false
    }
  } else if (ext === 'md') {
    mdLoading.value = true
    try {
      const resp = await axios.get(`/api/server/doc/stream-raw/${currentDoc.id}`, { responseType: 'text' })
      mdRaw.value = typeof resp.data === 'string' ? resp.data : String(resp.data)
    } catch (err) {
      mdRaw.value = '# 加载失败\n\n' + err.message
    } finally {
      mdLoading.value = false
    }
  }
}

watch(
  () => [docStore.previewModalOpen, docStore.previewModalDoc?.id],
  ([isOpen, docId]) => {
    if (isOpen && docId) {
      if (fileExt.value === 'docx') activeTab.value = 'canvas'
      nextTick(() => {
        loadContentForModal()
      })
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
  transform: scale(0.97);
}

.docx-reader-overlay { padding: 0; background: #f7f7f7; backdrop-filter: none; }
.docx-reader-window { width: 100%; height: 100dvh; max-height: none; max-width: none; border: 0; border-radius: 0; background: #f7f7f7; }
.docx-reader-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 24px; flex-shrink: 0; }
.docx-file-pill, .docx-reader-button, .docx-zoom-select { border: 1px solid #e5e5e5; background: #fff; color: #242424; border-radius: 999px; box-shadow: 0 3px 14px #00000005; }
.docx-file-pill { display: flex; align-items: center; gap: 12px; padding: 12px 18px; min-width: 0; max-width: 45%; font-size: 16px; }
.docx-file-name { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.docx-reader-actions { display: flex; align-items: center; gap: 10px; }
.docx-reader-button { display: grid; place-items: center; width: 48px; height: 48px; cursor: pointer; }
.docx-reader-button:hover { background: #ededed; }
.docx-zoom-select { padding: 12px 16px; font-size: 16px; cursor: pointer; }
.docx-reader-button:focus-visible, .docx-zoom-select:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.docx-reader-canvas { background: #f7f7f7; padding: 48px 24px 80px; align-items: stretch; overflow: auto; }
.docx-reader-canvas > div { min-width: 0; }
.docx-modal-container { flex-shrink: 0; width: 100%; margin: 0 auto; }
.docx-modal-container :deep(.docx-preview-content-wrapper) { background: transparent; padding: 0; }
.docx-modal-container :deep(section.docx-preview-content) { background: white; margin: 0 auto 28px; border: 1px solid #dedede; box-shadow: 0 5px 24px #0000000a; }
@media (max-width: 640px) {
  .docx-reader-header { padding: 10px; gap: 8px; }
  .docx-file-pill { max-width: 42%; padding: 10px; gap: 6px; font-size: 13px; }
  .docx-reader-actions { gap: 6px; }
  .docx-reader-button { width: 38px; height: 38px; }
  .docx-zoom-select { padding: 9px 6px; font-size: 13px; }
  .docx-reader-canvas { padding: 24px 12px 48px; }
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  border-bottom: 1px solid #f0eeeb;
  padding-bottom: 0.3em;
  margin-top: 1.2em;
  margin-bottom: 0.6em;
  font-weight: 700;
}

.markdown-body :deep(pre) {
  background-color: #292524;
  color: #f5f5f4;
  padding: 1rem;
  border-radius: 0.75rem;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.markdown-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  background-color: #f5f5f4;
  padding: 0.2em 0.4em;
  border-radius: 0.25rem;
  font-size: 85%;
}

.markdown-body :deep(pre code) {
  background-color: transparent;
  padding: 0;
  font-size: 100%;
}

.markdown-body :deep(blockquote) {
  border-left: 4px solid #3B5BDB;
  padding-left: 1rem;
  color: #57534e;
  margin: 1rem 0;
  background: #f8fafc;
  padding: 0.5rem 1rem;
  border-radius: 0 0.5rem 0.5rem 0;
}

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 1rem 0;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #e2e8f0;
  padding: 0.5rem 0.75rem;
}

.markdown-body :deep(th) {
  background-color: #f8fafc;
}
</style>
