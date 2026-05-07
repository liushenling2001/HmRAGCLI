/* === HTTP Utilities === */

import type { ApiError } from '@/types/common'

const DEFAULT_TIMEOUT = 15000

export class HttpError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HttpError'
  }
}

export async function parseError(response: Response): Promise<string> {
  const text = await response.text()
  if (!text) return `${response.status}`
  try {
    const parsed: ApiError = JSON.parse(text)
    return parsed.detail || parsed.message || parsed.error || text
  } catch {
    return text
  }
}

export async function fetchWithTimeout(
  url: string,
  options: RequestInit = {},
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<Response> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, { ...options, signal: controller.signal })
    return response
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw new HttpError(`请求超时 (${timeoutMs}ms): ${url}`)
    }
    throw error
  } finally {
    clearTimeout(timer)
  }
}

export async function getJson<T>(
  url: string,
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<T> {
  const response = await fetchWithTimeout(url, {}, timeoutMs)
  if (!response.ok) {
    throw new HttpError(await parseError(response))
  }
  return response.json()
}

export async function postJson<T>(
  url: string,
  body: unknown = {},
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<T> {
  const response = await fetchWithTimeout(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, timeoutMs)
  if (!response.ok) {
    throw new HttpError(await parseError(response))
  }
  return response.json()
}

export async function putJson<T>(
  url: string,
  body: unknown = {},
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<T> {
  const response = await fetchWithTimeout(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, timeoutMs)
  if (!response.ok) {
    throw new HttpError(await parseError(response))
  }
  return response.json()
}

export async function deleteJson(
  url: string,
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<boolean> {
  const response = await fetchWithTimeout(url, { method: 'DELETE' }, timeoutMs)
  if (!response.ok) {
    throw new HttpError(await parseError(response))
  }
  return true
}

/* === NDJSON Stream === */

export async function postNdjson<T>(
  url: string,
  body: unknown,
  onEvent: (event: T) => void,
  timeoutMs: number = DEFAULT_TIMEOUT
): Promise<void> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new HttpError(await parseError(response))
    }
    if (!response.body) {
      throw new HttpError('浏览器不支持流式响应')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const normalized = buffer.replace(/\r\n/g, '\n')
      const lines = normalized.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue
        onEvent(JSON.parse(trimmed))
      }
    }
    if (buffer.trim()) {
      onEvent(JSON.parse(buffer.trim()))
    }
  } finally {
    clearTimeout(timer)
  }
}