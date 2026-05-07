/* === Ops API === */

import { getJson, postJson, putJson, deleteJson, postNdjson } from './http'
import type {
  SystemHealth,
  Dashboard,
  DataSource,
  FileRecord,
  Job,
  FailureRecord,
  KnowledgeDomain,
  KnowledgeTopic,
  DomainCandidate,
  DiscoveryControl,
  RefineJob,
  KnowledgePack,
  EvidenceContext,
  DomainSetupEvent,
} from '@/types/ops'
import type { PaginatedResponse } from '@/types/common'

const API_BASE = '/api/v1'

export const opsApi = {
  systemHealth: `${API_BASE}/system/health`,
  dashboard: `${API_BASE}/operations/dashboard`,
  jobs: `${API_BASE}/operations/jobs`,
  failures: `${API_BASE}/operations/failures`,
  cleanupTempFailures: `${API_BASE}/operations/failures/cleanup-temp-files`,
  dataSources: `${API_BASE}/data-sources`,
  domains: `${API_BASE}/domains`,
  domainCandidates: `${API_BASE}/domain-candidates`,
  domainCandidateDiscoveryControl: `${API_BASE}/domain-candidates/discovery-control`,
  refineJobs: `${API_BASE}/refine-jobs`,
  domainMemoryPacks: `${API_BASE}/domain-memory-packs`,
  topics: `${API_BASE}/topics`,
}

/* === System & Dashboard === */

export async function getSystemHealth(): Promise<SystemHealth> {
  return getJson<SystemHealth>(opsApi.systemHealth)
}

export async function getDashboard(): Promise<Dashboard> {
  return getJson<Dashboard>(opsApi.dashboard)
}

/* === Data Sources === */

export async function getDataSourceFiles(
  sourceId: string,
  page: number,
  pageSize: number
): Promise<PaginatedResponse<FileRecord>> {
  const params = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  })
  return getJson<PaginatedResponse<FileRecord>>(
    `${opsApi.dataSources}/${sourceId}/files?${params.toString()}`
  )
}

export async function createDataSource(data: {
  sourceName: string
  sourceType: string
  rootPath: string
  includePatterns: string[]
  excludePatterns: string[]
  recursive: boolean
  metadata: Record<string, unknown>
}): Promise<DataSource> {
  return postJson<DataSource>(opsApi.dataSources, data)
}

export async function triggerSourceScan(sourceId: string): Promise<void> {
  await postJson(`${opsApi.dataSources}/${sourceId}/scan`, { forceRescan: false })
}

export async function triggerSourceIngest(
  sourceId: string,
  mode: 'incremental' | 'retry_failed'
): Promise<void> {
  await postJson(`${opsApi.dataSources}/${sourceId}/ingest`, {
    mode,
    reprocessFailed: mode === 'retry_failed',
  })
}

export async function cancelSourceJobs(sourceId: string): Promise<void> {
  await postJson(`${opsApi.dataSources}/${sourceId}/cancel`, {})
}

export async function approveDegradedProcessing(sourceId: string): Promise<void> {
  await postJson(`${opsApi.dataSources}/${sourceId}/approve-degraded-processing`, {})
}

export async function resetSourceIndex(sourceId: string): Promise<{
  deletedDocuments: number
  deletedChunks: number
  deletedKnowledgeUnits: number
  deletedIndexDirs: string[]
  indexDirErrors?: Record<string, string>
}> {
  return postJson(`${opsApi.dataSources}/${sourceId}/index/reset`, {}, 60000)
}

export async function deleteDataSource(sourceId: string): Promise<void> {
  await deleteJson(`${opsApi.dataSources}/${sourceId}`, 60000)
}

/* === Jobs & Failures === */

export async function getJobs(page: number, pageSize: number): Promise<PaginatedResponse<Job>> {
  const params = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  })
  return getJson<PaginatedResponse<Job>>(`${opsApi.jobs}?${params.toString()}`)
}

export async function getFailures(page: number, pageSize: number): Promise<PaginatedResponse<FailureRecord>> {
  const params = new URLSearchParams({
    page: String(page),
    pageSize: String(pageSize),
  })
  return getJson<PaginatedResponse<FailureRecord>>(`${opsApi.failures}?${params.toString()}`)
}

export async function cleanupTempFailures(): Promise<{ cleanedFiles: number }> {
  return postJson(opsApi.cleanupTempFailures, {})
}

/* === Knowledge Domains === */

export async function getDomains(): Promise<KnowledgeDomain[]> {
  return getJson<KnowledgeDomain[]>(opsApi.domains, 30000)
}

export async function createDomain(data: Partial<KnowledgeDomain>): Promise<KnowledgeDomain> {
  return postJson<KnowledgeDomain>(opsApi.domains, data)
}

export async function updateDomain(domainId: string, data: Partial<KnowledgeDomain>): Promise<void> {
  await putJson(`${opsApi.domains}/${domainId}`, data)
}

export async function deleteDomain(domainId: string): Promise<void> {
  await deleteJson(`${opsApi.domains}/${domainId}`)
}

export async function triggerDomainRefine(domainId: string, modelProfile?: string): Promise<RefineJob> {
  return postJson<RefineJob>(`${opsApi.domains}/${domainId}/refine`, {
    modelProfile: modelProfile?.trim() || null,
    triggerSource: 'user',
  })
}

export async function getDomainTopics(domainId: string): Promise<KnowledgeTopic[]> {
  return getJson<KnowledgeTopic[]>(`${opsApi.domains}/${domainId}/topics`, 30000)
}

export async function createDomainTopic(domainId: string, data: Partial<KnowledgeTopic>): Promise<KnowledgeTopic> {
  return postJson<KnowledgeTopic>(`${opsApi.domains}/${domainId}/topics`, data)
}

/* === Domain Setup Assistant (NDJSON Stream) === */

export async function runDomainSetupAssistant(
  name: string,
  history: Array<{ role: string; content: string }>,
  onEvent: (event: DomainSetupEvent) => void,
  timeoutMs: number = 130000
): Promise<void> {
  await postNdjson(`${opsApi.domains}/setup-assistant/stream`, { name, history }, onEvent, timeoutMs)
}

/* === Domain Candidates === */

export async function getDomainCandidates(status?: string): Promise<DomainCandidate[]> {
  const params = status ? new URLSearchParams({ status }) : new URLSearchParams()
  const query = params.toString() ? `?${params.toString()}` : ''
  return getJson<DomainCandidate[]>(`${opsApi.domainCandidates}${query}`, 30000)
}

export async function discoverDomainCandidates(): Promise<DomainCandidate[]> {
  return postJson<DomainCandidate[]>(`${opsApi.domainCandidates}/discover`, { triggerSource: 'user' }, 120000)
}

export async function acceptDomainCandidate(candidateId: string): Promise<void> {
  await postJson(`${opsApi.domainCandidates}/${candidateId}/accept`, { startRefineAfterAccept: false })
}

export async function rejectDomainCandidate(candidateId: string): Promise<void> {
  await postJson(`${opsApi.domainCandidates}/${candidateId}/reject`, { note: 'ops-ui-ignore' })
}

export async function getDiscoveryControl(): Promise<DiscoveryControl> {
  return getJson<DiscoveryControl>(opsApi.domainCandidateDiscoveryControl, 30000)
}

export async function startDiscovery(): Promise<DiscoveryControl> {
  return postJson(`${opsApi.domainCandidateDiscoveryControl}/start`, {})
}

export async function stopDiscovery(): Promise<DiscoveryControl> {
  return postJson(`${opsApi.domainCandidateDiscoveryControl}/stop`, {})
}

/* === Refine Jobs === */

export async function getRefineJobs(params?: { domainId?: string; triggerSource?: string }): Promise<RefineJob[]> {
  const searchParams = new URLSearchParams()
  if (params?.domainId) searchParams.set('domainId', params.domainId)
  if (params?.triggerSource) searchParams.set('triggerSource', params.triggerSource)
  const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
  return getJson<RefineJob[]>(`${opsApi.refineJobs}${query}`, 45000)
}

export async function resumeRefineJob(jobId: string): Promise<void> {
  await postJson(`${opsApi.refineJobs}/${jobId}/resume`, {})
}

export async function cancelRefineJob(jobId: string): Promise<void> {
  await postJson(`${opsApi.refineJobs}/${jobId}/cancel`, {})
}

/* === Knowledge Packs === */

export async function getKnowledgePacks(params?: { domainId?: string; triggerSource?: string }): Promise<KnowledgePack[]> {
  const searchParams = new URLSearchParams()
  if (params?.domainId) searchParams.set('domainId', params.domainId)
  if (params?.triggerSource) searchParams.set('triggerSource', params.triggerSource)
  const query = searchParams.toString() ? `?${searchParams.toString()}` : ''
  return getJson<KnowledgePack[]>(`${opsApi.domainMemoryPacks}${query}`, 45000)
}

export async function getAgentContextPacks(domainId?: string): Promise<KnowledgePack[]> {
  const params = new URLSearchParams()
  if (domainId) params.set('domainId', domainId)
  params.set('limit', '5')
  return getJson<KnowledgePack[]>(`${opsApi.domainMemoryPacks}/agent-context?${params.toString()}`, 30000)
}

export async function reviewKnowledgePack(packId: string, status: 'accepted' | 'reference'): Promise<KnowledgePack> {
  return postJson<KnowledgePack>(`${opsApi.domainMemoryPacks}/${packId}/review`, {
    status,
    note: status === 'accepted' ? 'ops-ui manual acceptance' : 'ops-ui manual reference mark',
    reviewedBy: 'ops-ui',
  })
}

export async function deleteKnowledgePack(packId: string): Promise<void> {
  await deleteJson(`${opsApi.domainMemoryPacks}/${packId}`)
}

export async function getPackEvidence(packId: string): Promise<Array<{
  evidenceRef: string
  evidenceType?: string
  title?: string
  snippet?: string
  sourceFile?: string
  pageNo?: number
}>> {
  return getJson(`${opsApi.domainMemoryPacks}/${packId}/evidence`, 30000)
}

export async function getEvidenceContext(packId: string, evidenceRef: string): Promise<EvidenceContext> {
  return getJson<EvidenceContext>(
    `${opsApi.domainMemoryPacks}/${packId}/context?evidenceRef=${encodeURIComponent(evidenceRef)}&window=1`,
    30000
  )
}

/* === Topics === */

export async function updateTopic(topicId: string, data: Partial<KnowledgeTopic>): Promise<void> {
  await putJson(`${opsApi.topics}/${topicId}`, data)
}

export async function deleteTopic(topicId: string): Promise<void> {
  await deleteJson(`${opsApi.topics}/${topicId}`)
}

export async function triggerTopicRefine(topicId: string, modelProfile?: string): Promise<RefineJob> {
  return postJson<RefineJob>(`${opsApi.topics}/${topicId}/refine`, {
    modelProfile: modelProfile?.trim() || null,
    triggerSource: 'user',
  })
}
