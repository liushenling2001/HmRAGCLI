/* === Query API === */

import { getJson, postJson } from './http'
import type { QaAnswer, SearchResult, DocOverview } from '@/types/query'

const API_BASE = '/api/v1'

export const queryApi = {
  qa: `${API_BASE}/qa/query`,
  search: `${API_BASE}/search`,
  documents: `${API_BASE}/documents`,
}

export async function submitQaQuery(request: {
  query: string
  excludeDevDocs: boolean
  topK: number
}): Promise<QaAnswer> {
  return postJson<QaAnswer>(queryApi.qa, request)
}

export async function submitSearch(params: {
  keyword: string
  excludeDevDocs: boolean
  page: number
  pageSize: number
}): Promise<SearchResult> {
  const searchParams = new URLSearchParams({
    keyword: params.keyword,
    excludeDevDocs: String(params.excludeDevDocs),
    page: String(params.page),
    pageSize: String(params.pageSize),
  })
  return getJson<SearchResult>(`${queryApi.search}?${searchParams.toString()}`)
}

export async function getDocOverview(docId: string): Promise<DocOverview> {
  return getJson<DocOverview>(`${queryApi.documents}/${docId}/overview`)
}