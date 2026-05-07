import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useOpsStore = defineStore('ops', () => {
  /* === Tab State === */
  const activeTab = ref<'overview' | 'files' | 'jobs' | 'knowledge' | 'failures'>('overview')
  const loading = ref(false)
  const loadingHealth = ref(false)

  /* === Dashboard State === */
  const dashboard = ref<Dashboard | null>(null)
  const systemHealth = ref<SystemHealth | null>(null)

  /* === Data Sources === */
  const selectedSourceId = ref('')
  const sourcePage = ref(1)
  const sourcePageSize = ref(6)
  const sourceView = ref<'active' | 'completed'>('active')
  const sourceModalOpen = ref(false)
  const sourceForm = ref({
    sourceName: '',
    rootPath: '',
    includePatterns: '*.pdf,*.doc,*.docx,*.xls,*.xlsx,*.txt,*.md',
    excludePatterns: '',
    recursive: true,
  })
  const sourceActionState = ref<Record<string, boolean>>({})

  /* === Files === */
  const files = ref<FileRecord[]>([])
  const filesTotal = ref(0)
  const filesPage = ref(1)
  const pageSize = ref(20)

  /* === Jobs === */
  const jobs = ref<Job[]>([])
  const jobsTotal = ref(0)
  const jobsPage = ref(1)

  /* === Failures === */
  const failures = ref<FailureRecord[]>([])
  const failuresTotal = ref(0)
  const failuresPage = ref(1)

  /* === Knowledge Compilation === */
  const knowledgeSubTab = ref<'overview' | 'discovery' | 'jobs' | 'packs' | 'agent'>('overview')
  const knowledgeDomains = ref<KnowledgeDomain[]>([])
  const selectedDomainKnowledgeId = ref('')
  const knowledgeTopics = ref<KnowledgeTopic[]>([])
  const knowledgeResultFilter = ref<'all' | 'user' | 'auto'>('all')
  const knowledgeJobs = ref<RefineJob[]>([])
  const knowledgePacks = ref<KnowledgePack[]>([])
  const knowledgeAgentContextPacks = ref<KnowledgePack[]>([])
  const domainCandidates = ref<DomainCandidate[]>([])
  const domainCandidateStatusFilter = ref('all')
  const domainCandidateDiscoveryControl = ref<DiscoveryControl | null>(null)
  const loadingKnowledge = ref(false)
  const knowledgeActionState = ref(false)
  const domainCandidateActionState = ref(false)
  const knowledgeRefineForm = ref({ modelProfile: '' })
  const knowledgeJobsPage = ref(1)
  const knowledgePacksPage = ref(1)
  const domainCandidatesPage = ref(1)
  const knowledgePageSize = ref(8)

  /* === Knowledge Modals === */
  const knowledgeDomainModalOpen = ref(false)
  const knowledgeTopicModalOpen = ref(false)
  const editingKnowledgeDomainId = ref('')
  const editingKnowledgeTopicId = ref('')
  const knowledgeDomainForm = ref<DomainForm>({
    name: '',
    autoRefreshEnabled: false,
    autoRefreshMode: 'daily',
    autoRefreshTime: '03:00',
    autoRefreshWeekday: 'MON',
    assistantQuestion: '',
    assistantAnswer: '',
    assistantHistory: [],
    assistantDraft: { goal: '', description: '', seedQueries: [], excludeTerms: [] },
    assistantCurrentDimension: '',
    assistantCoveredDimensions: [],
    assistantNextDimension: '',
    assistantReady: false,
    assistantReason: '',
    assistantStreamingPreview: '',
  })
  const knowledgeTopicForm = ref({
    name: '',
    description: '',
    seedQueries: '',
    priority: 0,
    status: 'active',
  })

  /* === Selected Knowledge Pack === */
  const selectedKnowledgePackId = ref('')
  const selectedKnowledgePack = ref<KnowledgePack | null>(null)
  const selectedKnowledgeEvidence = ref<EvidenceRef[]>([])
  const selectedEvidenceContext = ref<EvidenceContext | null>(null)
  const loadingKnowledgeDetail = ref(false)

  /* === Polling === */
  const pollTimer = ref<ReturnType<typeof setTimeout> | null>(null)
  const refreshAllRunning = ref(false)
  const refreshQueued = ref(false)

  /* === Computed === */
const overview = computed<DashboardOverview>(() => dashboard.value?.overview || {
    totalDataSources: 0,
    totalFiles: 0,
    acceptedFiles: 0,
    queuedFiles: 0,
    runningFiles: 0,
    readyFiles: 0,
    failedFiles: 0,
  })

  const dataSources = computed(() => dashboard.value?.dataSources || [])

  const sourceOptions = computed(() =>
    dataSources.value.map((item) => ({ id: item.id, name: item.sourceName }))
  )

  const sortedDataSources = computed(() => {
    const items = [...dataSources.value]
    return items.sort((a, b) => {
      const aDone = isCompletedSource(a)
      const bDone = isCompletedSource(b)
      if (aDone !== bDone) return aDone ? 1 : -1
      const aPending = Number(a.runningFiles || 0) + Number(a.queuedFiles || 0)
      const bPending = Number(b.runningFiles || 0) + Number(b.queuedFiles || 0)
      if (aPending !== bPending) return bPending - aPending
      return String(a.sourceName || '').localeCompare(String(b.sourceName || ''))
    })
  })

  const filteredDataSources = computed(() =>
    sortedDataSources.value.filter((source) =>
      sourceView.value === 'active' ? !isCompletedSource(source) : isCompletedSource(source)
    )
  )

  const visibleDataSources = computed(() => {
    const from = (sourcePage.value - 1) * sourcePageSize.value
    const to = from + sourcePageSize.value
    return filteredDataSources.value.slice(from, to)
  })

  const sourceTotalPages = computed(() =>
    Math.max(1, Math.ceil(filteredDataSources.value.length / sourcePageSize.value))
  )

  const filesTotalPages = computed(() =>
    Math.max(1, Math.ceil(filesTotal.value / pageSize.value))
  )

  const jobsTotalPages = computed(() =>
    Math.max(1, Math.ceil(jobsTotal.value / pageSize.value))
  )

  const failuresTotalPages = computed(() =>
    Math.max(1, Math.ceil(failuresTotal.value / pageSize.value))
  )

  const selectedKnowledgeDomain = computed(() =>
    knowledgeDomains.value.find((item) => item.id === selectedDomainKnowledgeId.value) || null
  )

  const filteredKnowledgeJobs = computed(() =>
    knowledgeJobs.value.filter((job) => matchesKnowledgeResultFilter(job?.triggerSource))
  )

  const filteredKnowledgePacks = computed(() =>
    knowledgePacks.value.filter((pack) => matchesKnowledgeResultFilter(pack?.triggerSource))
  )

  const knowledgeJobsTotalPages = computed(() =>
    Math.max(1, Math.ceil(filteredKnowledgeJobs.value.length / knowledgePageSize.value))
  )

  const knowledgePacksTotalPages = computed(() =>
    Math.max(1, Math.ceil(filteredKnowledgePacks.value.length / knowledgePageSize.value))
  )

  const pagedKnowledgeJobs = computed(() => {
    const from = (knowledgeJobsPage.value - 1) * knowledgePageSize.value
    return filteredKnowledgeJobs.value.slice(from, from + knowledgePageSize.value)
  })

  const pagedKnowledgePacks = computed(() => {
    const from = (knowledgePacksPage.value - 1) * knowledgePageSize.value
    return filteredKnowledgePacks.value.slice(from, from + knowledgePageSize.value)
  })

  const filteredDomainCandidates = computed(() => {
    if (domainCandidateStatusFilter.value === 'all') return domainCandidates.value
    return domainCandidates.value.filter(
      (c) => String(c?.status || '').toLowerCase() === domainCandidateStatusFilter.value
    )
  })

  const domainCandidatesTotalPages = computed(() =>
    Math.max(1, Math.ceil(filteredDomainCandidates.value.length / knowledgePageSize.value))
  )

  const pagedDomainCandidates = computed(() => {
    const from = (domainCandidatesPage.value - 1) * knowledgePageSize.value
    return filteredDomainCandidates.value.slice(from, from + knowledgePageSize.value)
  })

  /* === Helper Functions === */
  function isCompletedSource(source: DataSource): boolean {
    const total = Number(source.totalFiles || 0)
    if (!total) return false
    return (
      Number(source.runningFiles || 0) === 0 &&
      Number(source.queuedFiles || 0) === 0 &&
      Number(source.readyFiles || 0) + Number(source.failedFiles || 0) >= total
    )
  }

  function matchesKnowledgeResultFilter(triggerSource?: string): boolean {
    if (knowledgeResultFilter.value === 'all') return true
    return normalizeTriggerSource(triggerSource) === knowledgeResultFilter.value
  }

  function normalizeTriggerSource(value?: string): string {
    const lowered = String(value || '').trim().toLowerCase()
    return lowered || 'unknown'
  }

  function actionKey(sourceId: string, action: string): string {
    return `${sourceId}:${action}`
  }

  function isSourceActionRunning(sourceId: string, action: string): boolean {
    return !!sourceActionState.value[actionKey(sourceId, action)]
  }

  function setSourceActionRunning(sourceId: string, action: string, running: boolean): void {
    const key = actionKey(sourceId, action)
    if (running) {
      sourceActionState.value = { ...sourceActionState.value, [key]: true }
    } else {
      const next = { ...sourceActionState.value }
      delete next[key]
      sourceActionState.value = next
    }
  }

  function statusClass(status?: string): 'default' | 'success' | 'warning' | 'error' | 'info' {
    const lowered = String(status || '').toLowerCase()
    if (['success', 'fully_ready'].includes(lowered)) return 'success'
    if (['needs_approval', 'paused', 'review_required'].includes(lowered)) return 'warning'
    if (['cancelled', 'cancelling'].includes(lowered)) return 'info'
    if (['failed', 'build_failed'].includes(lowered)) return 'error'
    if (['running', 'queued', 'partial_failed', 'processing'].includes(lowered)) return 'warning'
    return 'info'
  }

  function formatDate(value?: string): string {
    if (!value) return '-'
    try {
      return new Date(value).toLocaleString()
    } catch {
      return value
    }
  }

  function stageLabel(stage?: string): string {
    const map: Record<string, string> = {
      probe: '探测',
      parse: '解析',
      extract: '抽取',
      vector: '向量',
      completed: '完成',
      needs_approval: '待批准',
    }
    return map[String(stage || '').toLowerCase()] || stage || '-'
  }

  function stagePercent(stage?: { total?: number; completed?: number }): number {
    const total = Number(stage?.total || 0)
    const completed = Number(stage?.completed || 0)
    if (!total) return 0
    return Math.min(100, Math.round((completed * 100) / total))
  }

  function loadKnowledgePreferences(): void {
    try {
      const savedDomainId = localStorage.getItem('ops.knowledge.selectedDomainId')
      const savedSubTab = localStorage.getItem('ops.knowledge.subTab')
      if (savedDomainId !== null) selectedDomainKnowledgeId.value = savedDomainId
      if (savedSubTab) knowledgeSubTab.value = savedSubTab as any
    } catch {
      // Ignore
    }
  }

  function persistKnowledgePreferences(): void {
    try {
      localStorage.setItem('ops.knowledge.selectedDomainId', selectedDomainKnowledgeId.value || '')
      localStorage.setItem('ops.knowledge.subTab', knowledgeSubTab.value)
    } catch {
      // Ignore
    }
  }

  function pageWindow(current: number, total: number): number[] {
    const safeCurrent = Math.max(1, current)
    const safeTotal = Math.max(1, total)
    const start = Math.max(1, safeCurrent - 2)
    const end = Math.min(safeTotal, start + 4)
    const adjustedStart = Math.max(1, end - 4)
    const pages: number[] = []
    for (let i = adjustedStart; i <= end; i++) pages.push(i)
    return pages
  }

  return {
    /* State */
    activeTab,
    loading,
    loadingHealth,
    dashboard,
    systemHealth,
    selectedSourceId,
    sourcePage,
    sourcePageSize,
    sourceView,
    sourceModalOpen,
    sourceForm,
    sourceActionState,
    files,
    filesTotal,
    filesPage,
    pageSize,
    jobs,
    jobsTotal,
    jobsPage,
    failures,
    failuresTotal,
    failuresPage,
    knowledgeSubTab,
    knowledgeDomains,
    selectedDomainKnowledgeId,
    knowledgeTopics,
    knowledgeResultFilter,
    knowledgeJobs,
    knowledgePacks,
    knowledgeAgentContextPacks,
    domainCandidates,
    domainCandidateStatusFilter,
    domainCandidateDiscoveryControl,
    loadingKnowledge,
    knowledgeActionState,
    domainCandidateActionState,
    knowledgeRefineForm,
    knowledgeJobsPage,
    knowledgePacksPage,
    domainCandidatesPage,
    knowledgePageSize,
    knowledgeDomainModalOpen,
    knowledgeTopicModalOpen,
    editingKnowledgeDomainId,
    editingKnowledgeTopicId,
    knowledgeDomainForm,
    knowledgeTopicForm,
    selectedKnowledgePackId,
    selectedKnowledgePack,
    selectedKnowledgeEvidence,
    selectedEvidenceContext,
    loadingKnowledgeDetail,
    pollTimer,
    refreshAllRunning,
    refreshQueued,
    /* Computed */
    overview,
    dataSources,
    sourceOptions,
    sortedDataSources,
    filteredDataSources,
    visibleDataSources,
    sourceTotalPages,
    filesTotalPages,
    jobsTotalPages,
    failuresTotalPages,
    selectedKnowledgeDomain,
    filteredKnowledgeJobs,
    filteredKnowledgePacks,
    knowledgeJobsTotalPages,
    knowledgePacksTotalPages,
    pagedKnowledgeJobs,
    pagedKnowledgePacks,
    filteredDomainCandidates,
    domainCandidatesTotalPages,
    pagedDomainCandidates,
    /* Functions */
    isCompletedSource,
    matchesKnowledgeResultFilter,
    normalizeTriggerSource,
    actionKey,
    isSourceActionRunning,
    setSourceActionRunning,
    statusClass,
    formatDate,
    stageLabel,
    stagePercent,
    loadKnowledgePreferences,
    persistKnowledgePreferences,
    pageWindow,
  }
})

/* === Type Imports === */
import type {
  Dashboard,
  DashboardOverview,
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
  DomainForm,
  EvidenceRef,
  EvidenceContext,
} from '@/types/ops'
import type { SystemHealth } from '@/types/ops'
