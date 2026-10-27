<template>
  <div class="pdf-document-preview">
    <div v-if="loading" role="status" class="preview-message">正在准备预览，请稍候…</div>
    <div v-else-if="error" role="alert" class="preview-message">
      <p>{{ error }}</p>
      <button type="button" @click="load">重新加载</button>
      <button type="button" @click="$emit('download')">下载原件</button>
    </div>
    <iframe v-else-if="url" :src="url" title="文档 PDF 预览"></iframe>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'

const props = defineProps({ docId: { type: [String, Number], required: true } })
defineEmits(['download'])
const loading = ref(true)
const error = ref('')
const url = ref('')
let request
let generation = 0
let timer
let wake
const clear = () => {
  request?.abort()
  clearTimeout(timer)
  wake?.()
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
}
const load = async () => {
  const current = ++generation
  clear()
  request = new AbortController()
  const signal = request.signal
  loading.value = true
  error.value = ''
  try {
    let prepared = false
    for (let attempt = 0; attempt < 100; attempt++) {
      const response = await axios.get(`/api/server/doc/stream-preview/${encodeURIComponent(props.docId)}`, {
        responseType: 'blob', signal, timeout: 30000,
        validateStatus: status => status === 200 || status === 404
      })
      if (signal.aborted || current !== generation) return
      if (response.status === 200) {
        const prefix = await response.data.slice(0, 5).text()
        if (prefix !== '%PDF-') throw new Error('服务器返回的预览不是有效 PDF')
        if (signal.aborted || current !== generation) return
        url.value = URL.createObjectURL(new Blob([response.data], { type: 'application/pdf' }))
        return
      }
      const detail = await axios.get(`/api/server/doc/detail/${encodeURIComponent(props.docId)}`, { signal, timeout: 15000 })
      const document = detail.data?.data?.doc
      if (!document) throw new Error('文件不存在或无法访问')
      if (!prepared && !['WAITING', 'PROCESSING'].includes(document.status)) {
        const preparedResponse = await axios.post(`/api/server/doc/prepare-preview/${encodeURIComponent(props.docId)}`, null, { signal, timeout: 15000 })
        if (![0, 200].includes(preparedResponse.data?.code)) throw new Error(preparedResponse.data?.msg || '无法提交预览任务')
        prepared = true
        continue
      }
      if (document.status === 'FAILED') throw new Error('预览生成失败，请检查文件是否损坏、加密，或服务器是否已安装 LibreOffice')
      if (!['WAITING', 'PROCESSING'].includes(document.status)) throw new Error('此文件没有可用的 PDF 预览，请下载原件查看')
      await new Promise(resolve => { wake = resolve; timer = setTimeout(resolve, 1500) })
      if (signal.aborted) return
    }
    throw new Error('预览仍在处理中，请稍后重新加载')
  } catch (err) {
    if (signal.aborted || current !== generation) return
    error.value = err.message || '预览加载失败'
  } finally {
    if (current === generation) loading.value = false
  }
}
watch(() => props.docId, load, { immediate: true })
onBeforeUnmount(() => { generation++; clear() })
</script>

<style scoped>
.pdf-document-preview { width: 100%; height: 100%; min-height: 520px; display: flex; flex-direction: column; }
iframe { flex: 1; width: 100%; min-height: 520px; border: 1px solid #dedede; border-radius: 12px; background: white; }
.preview-message { margin: auto; padding: 48px 24px; text-align: center; color: #57534e; font-size: 14px; }
button { margin: 16px 6px 0; padding: 8px 16px; background: white; border: 1px solid #ddd; border-radius: 8px; cursor: pointer; }
</style>
