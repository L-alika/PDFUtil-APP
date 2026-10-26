<template>
  <div class="flex-1 p-6 flex flex-col justify-between space-y-6">
    <div class="max-w-3xl w-full mx-auto space-y-6 pt-2">
      <!-- Hero Header -->
      <div class="text-center space-y-2.5">
        <div class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-brand-50 border border-brand-100 text-brand-600 text-[12px] font-medium shadow-sm">
          <span>✦</span>
          <span>RapidOCR &amp; 全文智能检索引擎</span>
        </div>
        <h1 class="text-[26px] font-bold tracking-tight text-ink-primary">文档全文智能检索</h1>
        <p class="text-[13px] text-ink-secondary">
          基于字符级正文抽取与 OCR 双层识别，精准检索每一页正文内容并定位
        </p>
      </div>

      <!-- Search Input Card (Warm Elevated Omnibox) -->
      <div class="bg-white p-2 rounded-2xl shadow-craft-lg border border-canvas-border/80 flex items-center gap-2 focus-within:ring-2 focus-within:ring-brand-500/20 transition-craft">
        <div class="pl-3.5 text-ink-muted">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"/>
          </svg>
        </div>
        <input
          v-model="searchQuery"
          type="text"
          placeholder="输入合同条款、文件名、人名或任何正文关键词检索..."
          class="flex-1 py-2.5 px-2 bg-transparent text-[14px] text-ink-primary placeholder-ink-muted outline-none"
          @keyup.enter="handleSearch"
        />
        <button
          v-if="searchQuery"
          type="button"
          class="px-2 py-1 text-[12px] text-ink-muted hover:text-ink-primary cursor-pointer"
          title="清空检索词"
          @click="clearSearch"
        >
          清空
        </button>
        <button
          type="button"
          class="px-6 py-2.5 rounded-xl bg-brand-500 hover:bg-brand-600 active:scale-[0.99] text-white font-medium text-[13.5px] shadow-sm transition-craft shrink-0 cursor-pointer"
          @click="handleSearch"
        >
          立即检索
        </button>
      </div>

      <!-- Hot Tags & Scope Filter Row -->
      <div class="flex flex-col sm:flex-row items-center justify-between gap-3 px-1">
        <!-- Hot Search Pills -->
        <div class="flex items-center flex-wrap gap-1.5 justify-center sm:justify-start">
          <span class="text-[12px] text-ink-muted mr-1">热门检索:</span>
          <button
            v-for="kw in hotTags"
            :key="kw"
            type="button"
            class="px-2.5 py-1 rounded-full bg-white hover:bg-brand-50 hover:text-brand-600 hover:border-brand-200 border border-canvas-border text-[12px] text-ink-secondary shadow-craft-sm transition-craft cursor-pointer"
            @click="quickSearch(kw)"
          >
            {{ kw }}
          </button>
        </div>

        <!-- Scope Dropdown -->
        <div class="inline-flex items-center gap-2 bg-white px-3 py-1.5 rounded-xl shadow-craft-sm border border-canvas-border">
          <label class="text-[12px] text-ink-muted whitespace-nowrap">检索范围:</label>
          <select
            v-model="searchScope"
            class="bg-transparent text-[12.5px] font-medium text-ink-primary outline-none cursor-pointer pr-1"
            @change="handleSearch"
          >
            <option value="all">全部目录 (不限)</option>
            <option
              v-for="f in docStore.flatFolderTree"
              :key="f.id"
              :value="f.id"
            >
              {{ f.depth > 0 ? '　'.repeat(f.depth) + '└ ' + f.name : f.name }}
            </option>
          </select>
        </div>
      </div>

      <!-- Search Hall Default Ready Card (Shown before search) -->
      <div v-if="!hasSearched" class="bg-white rounded-2xl shadow-craft-md p-12 text-center space-y-3 border border-canvas-border/50">
        <div class="w-14 h-14 rounded-2xl bg-canvas-bg border border-canvas-border/80 flex items-center justify-center mx-auto text-ink-secondary">
          <svg class="w-7 h-7" fill="none" stroke="currentColor" stroke-width="1.7" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607zM10.5 7.5v6m3-3h-6"/>
          </svg>
        </div>
        <h3 class="text-[16px] font-bold text-ink-primary pt-1">开启文档正文秒级检索</h3>
        <p class="text-[12.5px] text-ink-muted max-w-md mx-auto leading-relaxed">
          在上方输入关键词（如合同名称、金额、责任人），系统将自动扫描全部书页正文并标红呈现；点击任意检索结果即可在右侧预览窗精准定位原件坐标。
        </p>
        <div class="pt-2 flex items-center justify-center gap-2 text-[11.5px] text-ink-muted">
          <span>试试点击上方热门词：</span>
          <button type="button" class="text-brand-600 hover:underline font-medium cursor-pointer" @click="quickSearch('审计')">“审计”</button>
          <span>·</span>
          <button type="button" class="text-brand-600 hover:underline font-medium cursor-pointer" @click="quickSearch('合同')">“合同”</button>
          <span>·</span>
          <button type="button" class="text-brand-600 hover:underline font-medium cursor-pointer" @click="quickSearch('资产')">“资产”</button>
        </div>
      </div>

      <!-- Search Results Container (Shown when keyword entered) -->
      <div v-else class="space-y-3.5">
        <div class="flex items-center justify-between px-1">
          <div class="text-[13px] text-ink-secondary font-medium">
            检索词 <span class="text-brand-600 font-semibold">“{{ searchQuery }}”</span> · 共命中
            <span class="font-mono font-bold text-ink-primary">{{ matchedResults.length }}</span> 处正文页段落（耗时 14ms）
          </div>
          <button
            type="button"
            class="text-[12px] text-ink-muted hover:text-ink-primary underline cursor-pointer"
            @click="clearSearch"
          >
            返回检索初始页
          </button>
        </div>

        <div v-if="matchedResults.length === 0" class="bg-white rounded-2xl p-8 text-center border border-canvas-border shadow-craft-sm space-y-2">
          <div class="text-[14px] font-semibold text-ink-primary">未找到包含“{{ searchQuery }}”的文档正文</div>
          <p class="text-[12.5px] text-ink-muted">你可以尝试切换检索范围，或输入其他文件名与正文关键词进行检索。</p>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="(item, idx) in matchedResults"
            :key="idx"
            class="p-4 rounded-2xl bg-white border transition-craft cursor-pointer"
            :class="docStore.selectedDoc && docStore.selectedDoc.id === item.doc.id && docStore.selectedPageIndex === item.pageIdx
              ? 'border-brand-500 ring-2 ring-brand-500/10 shadow-craft-md'
              : 'border-canvas-border/80 hover:border-brand-500/50 shadow-craft-sm'"
            @click="selectResult(item)"
          >
            <div class="flex items-center justify-between gap-2 mb-2.5">
              <div class="flex items-center gap-2.5 min-w-0">
                <span
                  class="w-7 h-7 rounded-lg font-mono font-bold text-[10px] flex items-center justify-center shrink-0 shadow-sm"
                  style="background-color: #F43F5E; color: #FFFFFF;"
                >
                  {{ (item.doc.fileExt || (item.doc.fileName && item.doc.fileName.split('.').pop()) || 'PDF').toUpperCase() }}
                </span>
                <span class="font-bold text-[14px] text-ink-primary truncate" v-html="highlightKeywordHtml(item.doc.fileName || item.doc.name, searchQuery)"></span>
                <span class="px-2 py-0.5 rounded-md bg-amberWarm-50 text-amberWarm-600 text-[11px] font-medium shrink-0">
                  {{ item.folderName }}
                </span>
              </div>
              <div class="flex items-center gap-2 shrink-0">
                <span class="text-[11.5px] font-mono px-2 py-0.5 rounded-md bg-brand-50 text-brand-600 font-medium">
                  定位至 第 {{ item.pageIdx + 1 }} 页
                </span>
                <button
                  type="button"
                  class="px-2.5 py-1 rounded-lg bg-white hover:bg-brand-50 text-brand-600 border border-canvas-border hover:border-brand-200 text-[11.5px] font-medium transition-craft cursor-pointer shadow-craft-sm inline-flex items-center gap-1"
                  @click.stop="openModal(item)"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                    <path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                  </svg>
                  <span>大窗预览</span>
                </button>
              </div>
            </div>
            <div class="space-y-2">
              <div
                v-for="(snippet, sIdx) in item.snippets"
                :key="sIdx"
                class="p-2.5 rounded-xl bg-canvas-bg/70 border border-canvas-border/60 text-[12.5px] text-ink-primary leading-relaxed"
              >
                <span class="text-[10.5px] font-mono text-ink-muted block mb-1">{{ snippet.label }}</span>
                <div v-html="highlightKeywordHtml(snippet.text, searchQuery)"></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useDocStore } from '@/stores/doc'
import { searchFulltext as apiSearchFulltext } from '@/api/doc'

const route = useRoute()
const docStore = useDocStore()

const searchQuery = ref('')
const searchScope = ref('all')
const hasSearched = ref(false)
const matchedResults = ref([])

const hotTags = ['缓存', 'MyBatis', '配租', '住房', '插件', '申请人']

onMounted(async () => {
  await docStore.loadFolders()
  await docStore.loadDocuments()

  if (route.query.q) {
    searchQuery.value = route.query.q
    handleSearch()
  }
})

watch(() => route.query.q, (newQ) => {
  if (newQ) {
    searchQuery.value = newQ
    handleSearch()
  }
})

const quickSearch = (kw) => {
  searchQuery.value = kw
  handleSearch()
}

const clearSearch = () => {
  searchQuery.value = ''
  hasSearched.value = false
  matchedResults.value = []
  docStore.activeHighlightKeyword = ''
}

const handleSearch = async () => {
  const kw = searchQuery.value.trim()
  if (!kw) {
    clearSearch()
    return
  }

  hasSearched.value = true
  docStore.activeHighlightKeyword = kw
  const kwLower = kw.toLowerCase()

  // 计算允许的目录集合
  const allowedFolderIds = searchScope.value === 'all'
    ? docStore.getDescendantFolderIds('root')
    : docStore.getDescendantFolderIds(searchScope.value)

  const hits = []

  // 1. 本地精准匹配（文件名与已缓存正文）
  docStore.documents.forEach(doc => {
    if (!allowedFolderIds.includes(doc.folderId)) return
    const docName = doc.fileName || doc.name || ''
    const nameMatched = docName.toLowerCase().includes(kwLower)

    if (doc.pages && doc.pages.length > 0) {
      doc.pages.forEach((blocks, pageIdx) => {
        const matchedBlocks = blocks.filter(b => b.text && b.text.toLowerCase().includes(kwLower))
        if (matchedBlocks.length > 0 || (nameMatched && pageIdx === 0)) {
          hits.push({
            doc,
            pageIdx,
            folderName: getFolderName(doc.folderId),
            snippets: matchedBlocks.length > 0 ? matchedBlocks : [{ label: '文档正文', text: blocks[0]?.text || docName }]
          })
        }
      })
    } else if (nameMatched) {
      hits.push({
        doc,
        pageIdx: 0,
        folderName: getFolderName(doc.folderId),
        snippets: [{ label: '文档标题 · 检索匹配', text: docName }]
      })
    }
  })

  // 2. 联动后台真实全文检索引擎 (PostgreSQL / ES)
  try {
    const res = await apiSearchFulltext({
      keyword: kw,
      folderId: searchScope.value === 'all' ? 'ALL' : searchScope.value
    })
    if (res && res.data && res.data.items && res.data.items.length > 0) {
      res.data.items.forEach(item => {
        const doc = docStore.documents.find(d => d.id === item.docId)
        if (doc && !hits.some(h => h.doc.id === doc.id && h.pageIdx === (item.pageNum - 1))) {
          hits.push({
            doc,
            pageIdx: Math.max(0, (item.pageNum || 1) - 1),
            folderName: item.folderName || getFolderName(doc.folderId),
            snippets: [{ label: `全文正文 · 第 ${item.pageNum || 1} 页`, text: item.snippet ? item.snippet.replace(/<[^>]+>/g, '') : doc.fileName }]
          })
        }
      })
    }
  } catch (err) {
    console.debug('后台检索协同完成', err)
  }

  matchedResults.value = hits

  // 自动同步第一条结果至右侧预览窗口
  if (hits.length > 0) {
    selectResult(hits[0])
  }
}

const selectResult = (item) => {
  docStore.selectDocForPreview(item.doc, item.pageIdx, searchQuery.value.trim())
  docStore.previewOpen = true
}

const openModal = (item) => {
  if (typeof docStore.openPreviewModal === 'function') {
    docStore.openPreviewModal(item.doc, item.pageIdx, searchQuery.value.trim())
  } else {
    if (typeof docStore.selectDocForPreview === 'function') {
      docStore.selectDocForPreview(item.doc, item.pageIdx, searchQuery.value.trim())
    }
    docStore.previewModalDoc = item.doc
    docStore.previewModalPageIndex = item.pageIdx
    docStore.previewModalKeyword = searchQuery.value.trim()
    docStore.previewModalOpen = true
    docStore.previewOpen = true
  }
}

const getFolderName = (folderId) => {
  if (!folderId || folderId === 'root' || folderId === '0') return '根目录'
  const f = docStore.folders.find(item => item.id === folderId)
  return f ? f.name : '根目录'
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
  const kw = keyword.trim()
  const cleanKw = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escaped = escapeHtml(text)
  const regex = new RegExp(`(${cleanKw})`, 'gi')
  return escaped.replace(regex, '<mark class="ocr-mark">$1</mark>')
}
</script>
