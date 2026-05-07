/* === Query Types === */

export interface QueryRequest {
  query: string
  excludeDevDocs: boolean
  topK: number
}

export interface SearchRequest {
  keyword: string
  excludeDevDocs: boolean
  page: number
  pageSize: number
}

export interface QaAnswer {
  answer: string
  queryType: string
  query_type?: string
  citations: Citation[]
  docOverview?: DocOverview
  doc_overview?: DocOverview
  structuredAnswer?: StructuredAnswer
  structured_answer?: StructuredAnswer
}

export interface StructuredAnswer {
  subject?: string
  action?: string
  constraint?: string
  exception?: string
  indicator?: string
  value?: string
  unitName?: string
  unit_name?: string
  time?: string
  region?: string
  summaryPoints?: string[]
  summary_points?: string[]
}

export interface Citation {
  title?: string
  docId?: string
  doc_id?: string
  sourceFilename?: string
  source_filename?: string
  sourceFile?: string
  source_file?: string
  relativePath?: string
  relative_path?: string
  sourceSpan?: string
  source_span?: string
  pageNo?: number
  page_no?: number
}

export interface DocOverview {
  summary?: string
  keyTopics?: string[]
  keywords?: string[]
  sections?: string[]
  entities?: string[]
  timeRange?: string
  conclusions?: string[]
}

export interface SearchResult {
  docHits: DocHit[]
  doc_hits?: DocHit[]
  evidenceHits: EvidenceHit[]
  evidence_hits?: EvidenceHit[]
  items?: EvidenceHit[]
}

export interface DocHit {
  docId: string
  doc_id?: string
  docTitle?: string
  doc_title?: string
  sourceFilename?: string
  source_filename?: string
  sourceFile?: string
  source_file?: string
  relativePath?: string
  relative_path?: string
  score: number
  hitCount?: number
  hit_count?: number
  overview?: DocOverview
}

export interface EvidenceHit {
  unitId?: string
  unit_id?: string
  chunkId?: string
  chunk_id?: string
  docId?: string
  doc_id?: string
  docTitle?: string
  doc_title?: string
  docType?: string
  doc_type?: string
  docDomain?: string
  doc_domain?: string
  title?: string
  subject?: string
  indicator?: string
  snippet?: string
  content?: string
  score: number
  matchType?: string
  match_type?: string
  kind?: string
  sourceFile?: string
  source_file?: string
  sourceFilename?: string
  source_filename?: string
  relativePath?: string
  relative_path?: string
  sourceSpan?: string
  source_span?: string
  pageNo?: number
  page_no?: number
}