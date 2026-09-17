const PDFJS_VERSION = '3.11.174'
const PDFJS_CDN = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${PDFJS_VERSION}`

function loadPdfJs() {
  if (window.pdfjsLib) return Promise.resolve(window.pdfjsLib)
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = `${PDFJS_CDN}/pdf.min.js`
    script.async = true
    script.onload = () => {
      if (!window.pdfjsLib) {
        reject(new Error('PDF.js 加载失败'))
        return
      }
      window.pdfjsLib.GlobalWorkerOptions.workerSrc = `${PDFJS_CDN}/pdf.worker.min.js`
      resolve(window.pdfjsLib)
    }
    script.onerror = () => reject(new Error('无法加载 PDF.js（网络或 CDN 不可用）'))
    document.head.appendChild(script)
  })
}

function normalizeText(value) {
  return String(value || '').replace(/\s+/g, '').toLowerCase()
}

function findHighlightRanges(items, query) {
  const needle = normalizeText(query)
  if (!needle || !items || !items.length) return []

  const parts = []
  let joined = ''
  items.forEach((item, index) => {
    const text = String(item.str || '')
    const normalized = normalizeText(text)
    if (!normalized) return
    const start = joined.length
    joined += normalized
    parts.push({ index, start, end: joined.length, item })
  })

  let hit = joined.indexOf(needle)
  if (hit < 0 && needle.length > 24) {
    hit = joined.indexOf(needle.slice(0, 24))
  }
  if (hit < 0) return []

  const hitEnd = hit + Math.min(needle.length, Math.max(8, Math.floor(needle.length * 0.6)))
  return parts.filter(part => part.start < hitEnd && part.end > hit)
}

/**
 * 在指定 canvas 上渲染 PDF 页，并按高亮文本在文字层近似框选。
 * @returns {Promise<{ pageCount: number, matched: boolean }>}
 */
export async function renderPdfPageWithHighlight({
  data,
  pageNumber,
  highlightText,
  canvas,
  overlay
}) {
  const pdfjsLib = await loadPdfJs()
  const loadingTask = pdfjsLib.getDocument({ data })
  const pdf = await loadingTask.promise
  const pageCount = pdf.numPages
  const page = await pdf.getPage(Math.min(Math.max(pageNumber || 1, 1), pageCount))
  const viewport = page.getViewport({ scale: 1.35 })

  canvas.width = viewport.width
  canvas.height = viewport.height
  overlay.width = viewport.width
  overlay.height = viewport.height
  overlay.style.width = `${viewport.width}px`
  overlay.style.height = `${viewport.height}px`
  canvas.style.width = `${viewport.width}px`
  canvas.style.height = `${viewport.height}px`

  const context = canvas.getContext('2d')
  await page.render({ canvasContext: context, viewport }).promise

  const overlayCtx = overlay.getContext('2d')
  overlayCtx.clearRect(0, 0, overlay.width, overlay.height)

  const textContent = await page.getTextContent()
  const ranges = findHighlightRanges(textContent.items, highlightText)
  overlayCtx.fillStyle = 'rgba(255, 214, 0, 0.45)'
  ranges.forEach(({ item }) => {
    const tx = pdfjsLib.Util.transform(viewport.transform, item.transform)
    const fontHeight = Math.sqrt((tx[2] * tx[2]) + (tx[3] * tx[3])) || 12
    const scaleX = Math.sqrt((tx[0] * tx[0]) + (tx[1] * tx[1])) || viewport.scale
    const width = (item.width || (String(item.str || '').length * 0.5)) * scaleX
    const height = Math.max(item.height ? item.height * scaleX : fontHeight, fontHeight)
    const x = tx[4]
    const y = tx[5] - height * 0.85
    overlayCtx.fillRect(x, y, Math.max(width, 8), Math.max(height, 10))
  })

  return { pageCount, matched: ranges.length > 0 }
}
