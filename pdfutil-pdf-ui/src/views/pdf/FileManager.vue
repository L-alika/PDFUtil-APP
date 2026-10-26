<template>
  <div class="flex-1 p-6 space-y-6">
    <!-- Header & Action Toolbar -->
    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
      <div>
        <!-- Breadcrumb Title -->
        <div class="flex items-center flex-wrap gap-1.5 text-[20px] font-bold tracking-tight text-ink-primary">
          <button
            type="button"
            class="inline-flex items-center gap-2 transition-craft cursor-pointer"
            :class="docStore.folderBreadcrumbs.length === 0 ? 'text-ink-primary font-bold' : 'text-ink-muted hover:text-ink-primary font-semibold'"
            @click="selectFolder('root')"
          >
            <svg class="w-5 h-5 text-amberWarm-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
              <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
            </svg>
            <span>全部文件</span>
          </button>

          <template v-for="(crumb, idx) in docStore.folderBreadcrumbs" :key="crumb.id">
            <span class="text-ink-faint font-normal mx-1">/</span>
            <button
              type="button"
              class="transition-craft cursor-pointer"
              :class="idx === docStore.folderBreadcrumbs.length - 1 ? 'text-ink-primary font-bold' : 'text-ink-muted hover:text-ink-primary font-semibold'"
              @click="selectFolder(crumb.id)"
            >
              {{ crumb.name }}
            </button>
          </template>
        </div>

        <p class="text-[12.5px] text-ink-muted mt-1">
          当前位置：{{ currentFolderLocationName }} · 智能双层 OCR 与全文检索索引
        </p>
      </div>

      <!-- Folder & File Controls (Single clean toolbar on the right, no duplicates) -->
      <div class="flex flex-wrap items-center gap-2.5 shrink-0">
        <!-- Local Directory Filter -->
        <div class="relative w-52">
          <svg class="w-3.5 h-3.5 text-ink-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"/>
          </svg>
          <input
            v-model="localFilterText"
            type="text"
            placeholder="筛选当前目录文档..."
            class="w-full pl-8 pr-7 py-2 rounded-xl bg-white border border-canvas-border focus:border-brand-500 focus:ring-2 focus:ring-brand-500/15 text-[12.5px] text-ink-primary placeholder-ink-muted outline-none transition-craft shadow-craft-sm"
          />
          <button
            v-if="localFilterText"
            type="button"
            class="absolute right-2.5 top-1/2 -translate-y-1/2 text-ink-muted hover:text-ink-primary cursor-pointer text-sm"
            @click="localFilterText = ''"
          >
            ×
          </button>
        </div>

        <!-- Single New Folder / Subfolder Button -->
        <button
          type="button"
          class="px-3.5 py-2 rounded-xl bg-white hover:bg-canvas-hover text-ink-primary border border-canvas-border text-[12.5px] font-medium shadow-craft-sm transition-craft flex items-center gap-1.5 cursor-pointer shrink-0"
          @click="docStore.openFolderModal(isRoot ? '0' : docStore.currentFolderId)"
        >
          <svg class="w-4 h-4 text-ink-secondary" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 10.5v6m3-3H9m4.06-7.19l-2.12-2.12a1.5 1.5 0 00-1.061-.44H4.5A2.25 2.25 0 002.25 6v12a2.25 2.25 0 002.25 2.25h15A2.25 2.25 0 0021.75 18V9a2.25 2.25 0 00-2.25-2.25h-5.379a1.5 1.5 0 01-1.06-.44z"/>
          </svg>
          <span>{{ isRoot ? '新建文件夹' : '新建子文件夹' }}</span>
        </button>

        <!-- Upload Document Button -->
        <button
          type="button"
          class="px-4 py-2 rounded-xl bg-brand-500 hover:bg-brand-600 active:scale-[0.99] text-white text-[12.5px] font-medium shadow-sm transition-craft flex items-center gap-1.5 cursor-pointer shrink-0"
          @click="docStore.openUploadModal(isRoot ? '0' : docStore.currentFolderId)"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"/>
          </svg>
          <span>上传文档</span>
        </button>
      </div>
    </div>

    <!-- Sub-directory Cards Section (Shown when child folders exist) -->
    <div v-if="docStore.currentSubfolders.length > 0" class="space-y-3">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5">
          <span class="text-[13px] font-semibold text-ink-secondary">
            {{ isRoot ? '目录列表' : '子文件夹' }}
          </span>
          <span class="text-[12px] font-mono text-ink-muted">({{ docStore.currentSubfolders.length }})</span>
        </div>
      </div>

      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3.5">
        <div
          v-for="cf in docStore.currentSubfolders"
          :key="cf.id"
          class="group bg-white hover:bg-canvas-hover/50 rounded-2xl p-4 shadow-craft-sm hover:shadow-craft-md border border-canvas-border/80 flex items-center justify-between cursor-pointer transition-craft"
          @click="selectFolder(cf.id)"
        >
          <div class="flex items-center gap-3.5 min-w-0">
            <div class="w-11 h-11 rounded-xl bg-amberWarm-50 border border-amberWarm-100 flex items-center justify-center text-amberWarm-500 group-hover:scale-105 transition-craft shrink-0">
              <svg class="w-6 h-6" fill="currentColor" viewBox="0 0 20 20">
                <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
              </svg>
            </div>
            <div class="min-w-0">
              <div class="text-[14px] font-semibold text-ink-primary truncate group-hover:text-brand-600 transition-craft" :title="cf.name">
                {{ cf.name }}
              </div>
              <div class="text-[11.5px] text-ink-muted mt-0.5 flex items-center gap-2">
                <span class="font-mono">{{ (cf.createTime || cf.updateTime || '2026-10-23').split(' ')[0] }}</span>
                <span>·</span>
                <span>{{ getFolderDocCount(cf.id) }} 份文档</span>
              </div>
            </div>
          </div>

          <div class="flex items-center gap-1">
            <button
              type="button"
              class="opacity-0 group-hover:opacity-100 w-7 h-7 rounded-lg hover:bg-rose-50 text-ink-muted hover:text-rose-500 flex items-center justify-center transition-craft cursor-pointer"
              title="删除目录"
              @click.stop="docStore.openDeleteModal('folder', cf.id, cf.name)"
            >
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
              </svg>
            </button>
            <span class="w-7 h-7 rounded-lg group-hover:bg-white text-ink-muted flex items-center justify-center">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7"/>
              </svg>
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Document List Card Container (White elevated card matching D:\pdfutil_saas.html) -->
    <div class="bg-white rounded-2xl shadow-craft-md p-5 space-y-4 border border-canvas-border/80">
      <!-- Card Header -->
      <div class="flex items-center justify-between gap-2">
        <div class="flex items-center gap-2.5">
          <h2 class="text-[15px] font-bold text-ink-primary">
            {{ isRoot ? '全部文档列表' : currentFolderLocationName + ' · 文件列表' }}
          </h2>
          <span class="text-[11.5px] font-medium px-2.5 py-0.5 rounded-full bg-canvas-subtle text-ink-secondary border border-canvas-border/60">
            共 {{ filteredDocuments.length }} 份文档
          </span>
        </div>

        <!-- View Layout Toggle (List vs Grid) -->
        <div class="inline-flex p-0.5 rounded-xl bg-canvas-subtle border border-canvas-border/80">
          <button
            type="button"
            title="列表视图"
            class="p-1.5 rounded-lg transition-craft cursor-pointer"
            :class="viewMode === 'list' ? 'bg-white text-ink-primary shadow-sm' : 'text-ink-muted hover:text-ink-primary'"
            @click="viewMode = 'list'"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16"/>
            </svg>
          </button>
          <button
            type="button"
            title="网格卡片视图"
            class="p-1.5 rounded-lg transition-craft cursor-pointer"
            :class="viewMode === 'grid' ? 'bg-white text-ink-primary shadow-sm' : 'text-ink-muted hover:text-ink-primary'"
            @click="viewMode = 'grid'"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6zM14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6zM4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2zM14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- List Table View -->
      <div v-if="filteredDocuments.length > 0 && viewMode === 'list'" class="overflow-x-auto">
        <table class="w-full text-left border-collapse min-w-[680px]">
          <thead>
            <tr class="border-b border-canvas-border/80 text-[12px] font-medium text-ink-muted bg-canvas-bg/50">
              <th class="py-2.5 pl-3.5 pr-3 rounded-l-xl w-[36%] min-w-[200px] whitespace-nowrap">文档名称</th>
              <th class="py-2.5 px-3 w-[20%] min-w-[120px] whitespace-nowrap">所在目录</th>
              <th class="py-2.5 px-3 w-[16%] min-w-[110px] whitespace-nowrap">检索状态</th>
              <th class="py-2.5 px-3 w-[16%] min-w-[120px] whitespace-nowrap">更新时间</th>
              <th class="py-2.5 pl-3 pr-3.5 text-right rounded-r-xl w-[12%] min-w-[90px] whitespace-nowrap">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-canvas-border/50 text-[13px]">
              <tr
                v-for="doc in filteredDocuments"
                :key="doc.id"
                class="group cursor-pointer transition-craft"
                :class="docStore.selectedDoc && docStore.selectedDoc.id === doc.id ? 'bg-brand-50/65' : 'hover:bg-canvas-bg/70'"
                @click="handleRowClick(doc)"
                @dblclick="handlePreview(doc)"
              >
              <td class="py-3.5 pl-3.5 pr-3 rounded-l-xl">
                <div class="flex items-center gap-3">
                  <div
                    class="w-9 h-9 rounded-xl text-white font-mono font-bold text-[10.5px] flex items-center justify-center shadow-sm shrink-0"
                    style="background-color: #F43F5E; color: #FFFFFF;"
                  >
                    {{ (doc.fileExt || (doc.fileName && doc.fileName.split('.').pop()) || 'PDF').toUpperCase() }}
                  </div>
                  <div class="min-w-0">
                    <div class="font-semibold text-[13.5px] text-ink-primary group-hover:text-brand-600 transition-craft truncate" :title="doc.fileName || doc.name">
                      {{ doc.fileName || doc.name }}
                    </div>
                    <div class="text-[11.5px] text-ink-muted font-mono mt-0.5">
                      {{ doc.sizeLabel || (doc.fileSize ? `${doc.fileSize} B` : '1.2 MB') }} · {{ (doc.pages ? doc.pages.length : (doc.pageCount || 1)) }}页
                    </div>
                  </div>
                </div>
              </td>
              <td class="py-3.5 px-3 whitespace-nowrap">
                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-amberWarm-50 hover:bg-amberWarm-100 text-amberWarm-600 border border-amberWarm-100 text-[12px] font-medium transition-craft cursor-pointer"
                  @click.stop="selectFolder(doc.folderId)"
                >
                  <svg class="w-3.5 h-3.5 text-amberWarm-500 shrink-0" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M2 6a2 2 0 012-2h5l2 2h5a2 2 0 012 2v6a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"/>
                  </svg>
                  <span>{{ getFolderName(doc.folderId) }}</span>
                </button>
              </td>
              <td class="py-3.5 px-3 whitespace-nowrap">
                <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-sage-50 text-sage-600 border border-sage-100 text-[12px] font-medium">
                  <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>
                  <span>{{ doc.statusLabel || doc.status || '已建正文索引' }}</span>
                </span>
              </td>
              <td class="py-3.5 px-3 font-mono text-[12px] text-ink-secondary whitespace-nowrap">
                {{ doc.updateTime || doc.createTime || '2026-10-23 11:39:32' }}
              </td>
              <td class="py-3.5 pl-3 pr-3.5 text-right rounded-r-xl whitespace-nowrap">
                <div class="inline-flex items-center gap-3 text-[12.5px] font-medium" @click.stop>
                  <button
                    type="button"
                    class="text-brand-600 hover:text-brand-700 inline-flex items-center gap-1 cursor-pointer"
                    @click="handlePreview(doc)"
                  >
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"/>
                      <path stroke-linecap="round" stroke-linejoin="round" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"/>
                    </svg>
                    <span>预览</span>
                  </button>
                  <button
                    type="button"
                    class="text-brand-600 hover:text-brand-700 inline-flex items-center gap-1 cursor-pointer"
                    @click="docStore.downloadDoc(doc)"
                  >
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/>
                    </svg>
                    <span>下载</span>
                  </button>
                  <button
                    type="button"
                    class="text-rose-500 hover:text-rose-600 inline-flex items-center gap-1 cursor-pointer"
                    @click="docStore.openDeleteModal('doc', doc.id, doc.fileName || doc.name)"
                  >
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/>
                    </svg>
                    <span>删除</span>
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Grid Cards View -->
      <div v-else-if="filteredDocuments.length > 0 && viewMode === 'grid'" class="grid grid-cols-1 sm:grid-cols-2 gap-3.5">
        <div
          v-for="doc in filteredDocuments"
          :key="doc.id"
          class="p-4 rounded-2xl border cursor-pointer transition-craft flex flex-col justify-between gap-3"
          :class="docStore.selectedDoc && docStore.selectedDoc.id === doc.id
            ? 'bg-brand-50/50 border-brand-500/40 shadow-craft-md'
            : 'bg-canvas-bg/40 hover:bg-white border-canvas-border shadow-craft-sm'"
          @click="handlePreview(doc)"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex items-center gap-3 min-w-0">
              <div
                class="w-10 h-10 rounded-xl font-mono font-bold text-[11px] flex items-center justify-center shrink-0 shadow-sm"
                style="background-color: #F43F5E; color: #FFFFFF;"
              >
                {{ (doc.fileExt || (doc.fileName && doc.fileName.split('.').pop()) || 'PDF').toUpperCase() }}
              </div>
              <div class="min-w-0">
                <div class="font-semibold text-[13.5px] text-ink-primary truncate" :title="doc.fileName || doc.name">
                  {{ doc.fileName || doc.name }}
                </div>
                <div class="text-[11.5px] text-ink-muted font-mono mt-0.5">
                  {{ doc.sizeLabel || (doc.fileSize ? `${doc.fileSize} B` : '1.2 MB') }} · {{ (doc.pages ? doc.pages.length : (doc.pageCount || 1)) }}页
                </div>
              </div>
            </div>
            <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-sage-50 text-sage-600 text-[11px] font-medium shrink-0">
              <span class="w-1.5 h-1.5 rounded-full bg-sage-600"></span>
              已索引
            </span>
          </div>
          <div class="flex items-center justify-between pt-2 border-t border-canvas-border/60 text-[11.5px]">
            <span class="px-2 py-0.5 rounded bg-amberWarm-50 text-amberWarm-600 font-medium">
              {{ getFolderName(doc.folderId) }}
            </span>
            <div class="flex items-center gap-2.5 font-medium" @click.stop>
              <button type="button" class="text-brand-600 hover:underline cursor-pointer" @click="handlePreview(doc)">预览</button>
              <button type="button" class="text-ink-secondary hover:text-ink-primary cursor-pointer" @click="docStore.downloadDoc(doc)">下载</button>
              <button type="button" class="text-rose-500 hover:underline cursor-pointer" @click="docStore.openDeleteModal('doc', doc.id, doc.fileName || doc.name)">删除</button>
            </div>
          </div>
        </div>
      </div>

      <!-- Empty Directory Dropzone State (Shown when current directory has 0 files, e.g. 个人文档) -->
      <div v-if="filteredDocuments.length === 0" class="space-y-3 pt-1">
        <div class="text-center py-3 text-[12.5px] text-ink-muted border-b border-canvas-border/40">
          暂无数据
        </div>
        <div
          class="border-2 border-dashed border-canvas-borderStrong/80 hover:border-brand-500/60 bg-canvas-bg/40 hover:bg-brand-50/20 rounded-2xl p-10 text-center cursor-pointer transition-craft group"
          @click="docStore.openUploadModal(isRoot ? '0' : docStore.currentFolderId)"
        >
          <div class="w-12 h-12 rounded-2xl bg-white shadow-craft-sm border border-canvas-border flex items-center justify-center mx-auto text-ink-muted group-hover:text-brand-500 group-hover:scale-105 transition-craft">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" stroke-width="1.8" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" d="M9 13h6m-3-3v6m5 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/>
            </svg>
          </div>
          <div class="mt-3.5 text-[14px] font-semibold text-ink-primary">此目录下暂无文档</div>
          <p class="mt-1 text-[12.5px] text-ink-muted">
            点击此处或拖拽 PDF / Word / 图片文件至此即可上传解析
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useDocStore } from '@/stores/doc'

const route = useRoute()
const router = useRouter()
const docStore = useDocStore()

const viewMode = ref('list')
const localFilterText = ref('')

const isRoot = computed(() => {
  return !docStore.currentFolderId || docStore.currentFolderId === 'root' || docStore.currentFolderId === '0'
})

const currentFolderLocationName = computed(() => {
  if (isRoot.value) return '根目录'
  const found = docStore.folders.find(f => f.id === docStore.currentFolderId)
  return found ? found.name : '根目录'
})

onMounted(() => {
  syncRouteFolder()
})

watch(() => route.query.folderId, () => {
  syncRouteFolder()
})

const syncRouteFolder = () => {
  const fid = route.query.folderId
  if (fid) {
    docStore.currentFolderId = fid === '0' ? 'root' : fid
  } else if (!docStore.currentFolderId) {
    docStore.currentFolderId = 'root'
  }
}

// 筛选文档
const filteredDocuments = computed(() => {
  const validIds = docStore.getDescendantFolderIds(docStore.currentFolderId)
  let list = docStore.documents.filter(doc => validIds.includes(doc.folderId))

  if (localFilterText.value.trim()) {
    const q = localFilterText.value.trim().toLowerCase()
    list = list.filter(doc => {
      const name = (doc.fileName || doc.name || '').toLowerCase()
      const folderName = getFolderName(doc.folderId).toLowerCase()
      return name.includes(q) || folderName.includes(q)
    })
  }
  return list
})

const selectFolder = (id) => {
  docStore.currentFolderId = id
  router.push({ path: '/pdf/files', query: { folderId: id === 'root' ? '0' : id } })
}

const getFolderName = (folderId) => {
  if (!folderId || folderId === 'root' || folderId === '0') return '根目录'
  const found = docStore.folders.find(f => f.id === folderId)
  return found ? found.name : '根目录'
}

const getFolderDocCount = (folderId) => {
  const descendantIds = docStore.getDescendantFolderIds(folderId)
  return docStore.documents.filter(d => descendantIds.includes(d.folderId)).length
}

const handleRowClick = (doc) => {
  docStore.selectDocForPreview(doc, 0, '')
  docStore.previewOpen = true
}

const handlePreview = (doc) => {
  if (typeof docStore.openPreviewModal === 'function') {
    docStore.openPreviewModal(doc, 0, '')
  } else {
    if (typeof docStore.selectDocForPreview === 'function') {
      docStore.selectDocForPreview(doc, 0, '')
    }
    docStore.previewModalDoc = doc
    docStore.previewModalPageIndex = 0
    docStore.previewModalKeyword = ''
    docStore.previewModalOpen = true
    docStore.previewOpen = true
  }
}
</script>
