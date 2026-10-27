import { renderAsync } from 'docx-preview'
import serifFonts from '@fontsource/noto-serif-sc/400.css?inline'
import sansFonts from '@fontsource/noto-sans-sc/400.css?inline'

const readers = new WeakMap()
export function disposeDocx(container) {
  readers.get(container)?.()
  readers.delete(container)
}

// Isolate Word typography from application resets and keep original page dimensions.
export async function renderDocx(blob, container) {
  disposeDocx(container)
  let disposed = false
  let observer
  let releaseLoad
  readers.set(container, () => {
    disposed = true
    observer?.disconnect()
    releaseLoad?.()
  })
  const toolbar = document.createElement('div')
  toolbar.style.cssText = 'display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:10px;padding:10px;position:sticky;top:0;z-index:2;background:#f7f7f7;color:#242424;font:14px sans-serif;'
  const controlStyle = 'border:1px solid #ddd;border-radius:18px;padding:7px 12px;background:white;color:#242424;'
  const previous = document.createElement('button')
  previous.textContent = '上一页'
  previous.type = 'button'
  const next = document.createElement('button')
  next.textContent = '下一页'
  next.type = 'button'
  const pageInput = document.createElement('input')
  pageInput.type = 'number'
  pageInput.min = '1'
  pageInput.setAttribute('aria-label', '跳转页码')
  pageInput.style.width = '65px'
  const count = document.createElement('span')
  const mode = document.createElement('select')
  mode.setAttribute('aria-label', '文档缩放方式')
  for (const [value, label] of [['width', '适应宽度'], ['page', '整页'], ...[50, 75, 100, 125, 150, 200].map(n => [String(n), `${n}%`])]) {
    mode.add(new Option(label, value))
  }
  for (const control of [previous, pageInput, next, mode]) control.style.cssText += controlStyle
  toolbar.append(previous, pageInput, count, next, mode)
  const frame = document.createElement('iframe')
  frame.title = 'Word 文档分页预览'
  frame.setAttribute('sandbox', 'allow-same-origin')
  frame.style.cssText = 'display:block;border:0;width:100%;height:0;margin:0 auto;'
  const loaded = new Promise(resolve => { releaseLoad = resolve; frame.onload = resolve })
  frame.srcdoc = '<!doctype html><html><head><meta charset="utf-8"></head><body></body></html>'
  container.replaceChildren(toolbar, frame)
  await loaded
  if (disposed) return
  const view = frame.contentDocument
  const pages = view.createElement('div')
  view.body.append(pages)
  await renderAsync(blob, pages, pages, {
    className: 'word-page', inWrapper: true,
    ignoreWidth: false, ignoreHeight: false, ignoreFonts: false,
    breakPages: true, ignoreLastRenderedPageBreak: false,
    renderHeaders: true, renderFooters: true, renderFootnotes: true, renderEndnotes: true,
    renderAltChunks: false, useBase64URL: true
  })
  if (disposed) return
  const style = view.createElement('style')
  // Local open-source fallback fonts; do not distribute proprietary Word fonts.
  style.textContent = serifFonts + sansFonts + `
    html, body { margin: 0; padding: 0; background: transparent; }
    body { font-family: 'Times New Roman', 'SimSun', 'Songti SC', 'Noto Serif SC', serif; font-size: 12pt; line-height: normal; }
    .word-page-wrapper { background: transparent; padding: 0; width: max-content; }
    .word-page-wrapper > section.word-page {
      flex-shrink: 0; background: white; margin: 1px auto 28px;
      outline: 1px solid #dedede; box-shadow: 0 5px 24px #0000000a;
    }
  `
  // Map common Word font names to locally bundled, open-source substitutes.
  style.textContent += serifFonts.replaceAll("'Noto Serif SC'", "'SimSun'")
    + sansFonts.replaceAll("'Noto Sans SC'", "'SimHei'")
  style.textContent += serifFonts.replaceAll("'Noto Serif SC'", "'宋体'")
    + sansFonts.replaceAll("'Noto Sans SC'", "'黑体'")
    + sansFonts.replaceAll("'Noto Sans SC'", "'Microsoft YaHei'")
    + sansFonts.replaceAll("'Noto Sans SC'", "'微软雅黑'")
  view.head.append(style)
  await view.fonts.ready
  await Promise.all(Array.from(view.images, image => image.decode().catch(() => {})))
  if (disposed) return
  const sections = [...pages.querySelectorAll('section.word-page')]
  if (!sections.length) throw new Error('文档没有可预览的页面')
  const width = Math.ceil(pages.scrollWidth) + 2
  const height = Math.ceil(pages.getBoundingClientRect().height)
  let scale = 1
  let currentPage = 1
  const scrollArea = (() => {
    for (let parent = container.parentElement; parent; parent = parent.parentElement) {
      if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY)) return parent
    }
    return container.parentElement
  })()
  const updateSize = () => {
    if (disposed) return
    const availableWidth = Math.max(1, container.clientWidth - 4)
    const fitWidth = availableWidth / width
    const pageHeight = sections[0]?.getBoundingClientRect().height || height
    scale = mode.value === 'width' ? fitWidth : mode.value === 'page'
      ? Math.min(fitWidth, Math.max(100, scrollArea.clientHeight - toolbar.offsetHeight - 40) / pageHeight)
      : Number(mode.value) / 100
    pages.style.zoom = scale
    frame.style.width = `${Math.ceil(width * scale)}px`
    frame.style.height = `${Math.ceil(height * scale)}px`
  }
  const showPage = (value) => {
    currentPage = Math.min(sections.length, Math.max(1, Math.trunc(Number(value)) || 1))
    pageInput.value = currentPage
    previous.disabled = currentPage <= 1
    next.disabled = currentPage >= sections.length
  }
  const goToPage = (value) => {
    showPage(value)
    const page = sections[currentPage - 1]
    if (!page) return
    scrollArea.scrollTop += frame.getBoundingClientRect().top - scrollArea.getBoundingClientRect().top
      + page.getBoundingClientRect().top - toolbar.offsetHeight - 12
  }
  previous.onclick = () => goToPage(currentPage - 1)
  next.onclick = () => goToPage(currentPage + 1)
  pageInput.onchange = () => goToPage(pageInput.value)
  mode.onchange = updateSize
  count.textContent = `/ ${sections.length} 页`
  pageInput.max = String(sections.length)
  showPage(1)
  observer = new ResizeObserver(updateSize)
  observer.observe(container)
  observer.observe(scrollArea)
  const onScroll = () => {
    const top = scrollArea.getBoundingClientRect().top + toolbar.offsetHeight + 12
    const frameTop = frame.getBoundingClientRect().top
    let index = 0
    for (let i = 0; i < sections.length; i++) {
      if (frameTop + sections[i].getBoundingClientRect().top <= top) index = i
    }
    if (view.activeElement !== pageInput && document.activeElement !== pageInput) showPage(index + 1)
  }
  scrollArea.addEventListener('scroll', onScroll, { passive: true })
  const dispose = readers.get(container)
  readers.set(container, () => { dispose(); scrollArea.removeEventListener('scroll', onScroll) })
  updateSize()
}
