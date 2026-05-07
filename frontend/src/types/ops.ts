/* === Ops Types === */

import type { Status, PipelineStage, StageTask } from './common'

// Re-export for convenience
export type { PipelineStage } from './common'

export interface SystemHealthCheck {
  name: string
  ok: boolean
  error?: string
  detail?: Record<string, unknown>
}

export interface SystemHealth {
  checks: SystemHealthCheck[]
}

export interface DashboardOverview {
  totalDataSources: number
  totalFiles: number
  acceptedFiles: number
  queuedFiles: number
  runningFiles: number
  readyFiles: number
  failedFiles: number
}

export interface Dashboard {
  overview: DashboardOverview
  dataSources: DataSource[]
  activeFiles: ActiveFile[]
  recentJobs: Job[]
  recentFailures: FailureRecord[]
}

export interface DataSource {
  id: string
  sourceName: string
  sourceType: string
  rootPath: string
  totalFiles: number
  acceptedFiles: number
  queuedFiles: number
  runningFiles: number
  readyFiles: number
  failedFiles: number
  stages: PipelineStage[]
}

export interface ActiveFile {
  id: string
  fileName: string
  dataSourceName?: string
  lifecycleStatus: Status
  lifecycleLabel?: string
  currentStage: string
  progressPercent: number
  errorSummary?: string
  stageTasks: StageTask[]
  relativePath?: string
  filePath?: string
}

export interface FileRecord {
  id: string
  fileName: string
  relativePath?: string
  filePath?: string
  lifecycleStatus: Status
  lifecycleLabel?: string
  currentStage: string
  progressPercent: number
  errorSummary?: string
  errorDetail?: string
  stageTasks: StageTask[]
}

export interface Job {
  id: string
  jobKind: string
  dataSourceName?: string
  status: Status
  totalFiles: number
  completedFiles: number
  progressPercent: number
  currentStageSummary?: string
  stages: StageTask[]
  startedAt?: string
}

export interface FailureRecord {
  id: string
  fileName: string
  dataSourceName?: string
  failedStage?: string
  errorSummary?: string
  errorDetail?: string
  updatedAt?: string
}

export interface SourceForm {
  sourceName: string
  rootPath: string
  includePatterns: string
  excludePatterns: string
  recursive: boolean
}

/* === Knowledge Compilation Types === */

export interface KnowledgeDomain {
  id: string
  name: string
  description?: string
  goal?: string
  scopeRules?: Record<string, unknown>
  seedQueries?: string[]
  includeDataSources?: string[]
  excludeDataSources?: string[]
  createdBy?: string
  autoRefreshEnabled: boolean
  autoRefreshCron?: string
  status: string
  metadata?: {
    setupHistory?: AssistantHistoryItem[]
    setupAssistantCurrentDimension?: string
    setupAssistantCoveredDimensions?: string[]
    setupAssistantNextDimension?: string
    setupAssistantReason?: string
  }
}

export interface KnowledgeTopic {
  id: string
  name: string
  description?: string
  seedQueries: string[]
  priority: number
  status: string
  domainId?: string
}

export interface DomainCandidate {
  id: string
  name: string
  description?: string
  status: 'suggested' | 'accepted' | 'rejected'
  keywords?: string[]
  evidenceRefs?: string[]
  discoveryWindowStart?: string
  discoveryWindowEnd?: string
}

export interface DiscoveryControl {
  configEnabled: boolean
  runningEnabled: boolean
  pausedByUser: boolean
  windowStartHour?: number
  windowEndHour?: number
  lookbackHours?: number
  maxDocuments?: number
}

export interface RefineJob {
  id: string
  jobType: string
  triggerSource?: string
  modelProfile?: string
  status: Status
  domainId?: string
  topicId?: string
  hasMemoryPack?: boolean
  errorMessage?: string
  outputSummary?: {
    phase?: string
    retrievalPass?: string
    retrievalPassIndex?: number
    retrievalPassCount?: number
    processedTerms?: number
    totalTerms?: number
    documentCount?: number
    knowledgeUnitCount?: number
    chunkCount?: number
    requiredDocumentCount?: number
    requiredKnowledgeUnitCount?: number
    requiredChunkCount?: number
    excludedTerms?: string[]
    memoryPackId?: string
    draftSummary?: string
    pauseReason?: string
    pauseMetadata?: Record<string, unknown>
    [key: string]: unknown
  }
  heartbeatAt?: string
  updatedAt?: string
  createdAt?: string
  finishedAt?: string
}

export interface KnowledgePack {
  id: string
  title: string
  domainId?: string
  topicId?: string
  artifactType?: string
  triggerSource?: string
  status: string
  summary?: string
  keyPoints?: string[]
  evidenceRefs?: Array<string | EvidenceRef>
  contentMarkdown?: string
  sourceSnapshot?: {
    domainName?: string
    topicName?: string
    domainSeedQueries?: string[]
    topicSeedQueries?: string[]
    excludedTerms?: string[]
    retrievalTerms?: string[]
    documents?: Array<Record<string, unknown>>
    knowledgeUnits?: Array<Record<string, unknown>>
    chunks?: Array<Record<string, unknown>>
    includeDataSources?: Array<Record<string, unknown>>
    excludeDataSources?: string[]
    refinement?: {
      refined?: boolean
      reason?: string
      llmProvider?: string
      llmModel?: string
    }
    review?: {
      status?: string
      reviewedBy?: string
      reviewedAt?: string
      note?: string
    }
  }
  structuredContent?: StructuredKnowledgeContent
  modelProfile?: string
  updatedAt?: string
  createdAt?: string
}

export interface StructuredKnowledgeContent {
  version?: string
  catalog?: StructuredCatalogNode[]
  cards?: StructuredKnowledgeCard[]
  evidenceBindings?: StructuredEvidenceBinding[]
  validation?: StructuredKnowledgeValidation
  agentView?: Record<string, unknown>
}

export interface StructuredCatalogNode {
  id?: string
  parentId?: string | null
  level?: number
  title?: string
  summary?: string
  keywords?: string[]
  evidenceRefs?: string[]
  quality?: {
    status?: string
    evidenceCount?: number
    documentCount?: number
    warnings?: string[]
    [key: string]: unknown
  }
}

export interface StructuredKnowledgeCard {
  id?: string
  catalogId?: string
  type?: string
  title?: string
  summary?: string
  claims?: Array<{
    text?: string
    confidence?: string
    evidenceRefs?: string[]
    [key: string]: unknown
  }>
}

export interface StructuredEvidenceBinding {
  evidenceRef?: string
  catalogIds?: string[]
  cardIds?: string[]
  claimTexts?: string[]
}

export interface StructuredKnowledgeValidation {
  status?: string
  catalogNodeCount?: number
  level1NodeCount?: number
  maxDepth?: number
  cardCount?: number
  claimCount?: number
  boundClaimCount?: number
  boundClaimRatio?: number
  evidenceCount?: number
  documentCount?: number
  lowEvidenceNodeCount?: number
  excludedHitCount?: number
  evidenceBindingCount?: number
  warnings?: string[]
  [key: string]: unknown
}

export interface EvidenceRef {
  evidenceRef: string
  evidenceType?: string
  title?: string
  snippet?: string
  sourceFile?: string
  pageNo?: number
}

export interface EvidenceContext {
  evidenceRef: string
  title?: string
  sourceFile?: string
  content?: string
  context?: string
}

export interface DomainSetupEvent {
  type: 'status' | 'delta' | 'result' | 'error'
  payload: {
    message?: string
    preview?: string
    content?: string
    question?: string
    goal?: string
    description?: string
    seedQueries?: string[]
    excludeTerms?: string[]
    currentDimension?: string
    coveredDimensions?: string[]
    nextDimension?: string
    ready?: boolean
    reason?: string
  }
}

export interface AssistantHistoryItem {
  role: 'user' | 'assistant'
  content: string
}

export interface DomainForm {
  name: string
  autoRefreshEnabled: boolean
  autoRefreshMode: 'daily' | 'weekly'
  autoRefreshTime: string
  autoRefreshWeekday: string
  assistantQuestion: string
  assistantAnswer: string
  assistantHistory: AssistantHistoryItem[]
  assistantDraft: {
    goal: string
    description: string
    seedQueries: string[]
    excludeTerms: string[]
  }
  assistantCurrentDimension: string
  assistantCoveredDimensions: string[]
  assistantNextDimension: string
  assistantReady: boolean
  assistantReason: string
  assistantStreamingPreview: string
}
