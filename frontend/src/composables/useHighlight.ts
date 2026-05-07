export function escapeHtml(text: string): string {
  return String(text ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

export function highlightHtml(text: string, query?: string): string {
  const raw = String(text ?? '')
  const q = (query || '').trim()
  const escaped = escapeHtml(raw)
  if (!q) return escaped

  const parts = Array.from(
    new Set(
      q
        .split(/\s+/)
        .map((item) => item.trim())
        .filter(Boolean)
        .sort((a, b) => b.length - a.length)
    )
  )
  if (!parts.length) return escaped

  const safeQ = parts.map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')
  try {
    return escaped.replace(new RegExp(safeQ, 'gi'), (m) => `<mark>${m}</mark>`)
  } catch {
    return escaped
  }
}

export function matchTypeLabel(matchType?: string): string {
  const labels: Record<string, string> = {
    title: '标题命中',
    filename: '文件名命中',
    summary: '摘要命中',
    caption: '图表标题命中',
    content: '正文命中',
    semantic: '语义命中',
  }
  return labels[matchType || ''] || '内容命中'
}

export function useHighlight() {
  return {
    escapeHtml,
    highlightHtml,
    matchTypeLabel,
  }
}