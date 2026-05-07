<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useOpsStore } from '@/stores/useOpsStore'
import { useToastStore } from '@/stores/useToastStore'
import {
  acceptDomainCandidate,
  cancelRefineJob,
  createDomain,
  createDomainTopic,
  deleteDomain,
  deleteKnowledgePack,
  deleteTopic,
  discoverDomainCandidates,
  getAgentContextPacks,
  getDiscoveryControl,
  getDomainCandidates,
  getDomains,
  getDomainTopics,
  getEvidenceContext,
  getPackEvidence,
  getKnowledgePacks,
  getRefineJobs,
  rejectDomainCandidate,
  resumeRefineJob,
  reviewKnowledgePack,
  runDomainSetupAssistant,
  startDiscovery,
  stopDiscovery,
  triggerDomainRefine,
  triggerTopicRefine,
} from '@/api/ops'
import AppButton from '@/components/common/AppButton.vue'
import AppInput from '@/components/common/AppInput.vue'
import AppModal from '@/components/common/AppModal.vue'
import AppPanel from '@/components/common/AppPanel.vue'
import AppPill from '@/components/common/AppPill.vue'
import AppSelect from '@/components/common/AppSelect.vue'
import AppTextarea from '@/components/common/AppTextarea.vue'
import type { EvidenceRef, KnowledgePack } from '@/types/ops'

const opsStore = useOpsStore()
const toastStore = useToastStore()

const assistantLoading = ref(false)
const savingDomain = ref(false)
const discoveryActionRunning = ref(false)
const candidateActionState = ref<Record<string, boolean>>({})
const packActionState = ref<Record<string, boolean>>({})
const domainActionState = ref<Record<string, boolean>>({})
const jobActionState = ref<Record<string, boolean>>({})
const topicActionState = ref<Record<string, boolean>>({})
const creatingTopic = ref(false)
const newTopicName = ref('')
const newTopicSeedQueries = ref('')
const evidenceModalOpen = ref(false)
const evidenceLoading = ref(false)
const evidencePackTitle = ref('')
const evidencePackId = ref('')
const evidenceItems = ref<EvidenceRef[]>([])
const evidenceContextLoading = ref(false)
const selectedEvidenceRef = ref('')
const selectedEvidenceContext = ref('')
const expandedPackState = ref<Record<string, boolean>>({})
const rejectedCandidates = ref<Array<{ id: string; name: string; description?: string; updatedAt?: string }>>([])
const jobsPollTimer = ref<number | null>(null)

const knowledgeSubTabs = [
  { key: 'overview', label: '概览' },
  { key: 'discovery', label: '自动发现' },
  { key: 'jobs', label: '精炼任务' },
  { key: 'packs', label: '知识包' },
  { key: 'agent', label: '智能体消费' },
]

const autoJobs = computed(() =>
  opsStore.knowledgeJobs.filter(job => opsStore.normalizeTriggerSource(job?.triggerSource) === 'auto')
)

const autoPacks = computed(() =>
  opsStore.knowledgePacks.filter(pack => opsStore.normalizeTriggerSource(pack?.triggerSource) === 'auto')
)

const canContinueAssistant = computed(() => {
  if (assistantLoading.value) return false
  if (!opsStore.knowledgeDomainForm.name.trim()) return false
  if (!opsStore.knowledgeDomainForm.assistantQuestion.trim()) return true
  return !!opsStore.knowledgeDomainForm.assistantAnswer.trim()
})

function emptyDomainForm() {
  return {
    name: '',
    autoRefreshEnabled: false,
    autoRefreshMode: 'daily' as const,
    autoRefreshTime: '03:00',
    autoRefreshWeekday: 'MON',
    assistantQuestion: '',
    assistantAnswer: '',
    assistantHistory: [],
    assistantDraft: {
      goal: '',
      description: '',
      seedQueries: [],
      excludeTerms: [],
    },
    assistantCurrentDimension: '',
    assistantCoveredDimensions: [],
    assistantNextDimension: '',
    assistantReady: false,
    assistantReason: '',
    assistantStreamingPreview: '',
  }
}

function resetDomainForm() {
  opsStore.knowledgeDomainForm = emptyDomainForm()
}

function openDomainModal() {
  resetDomainForm()
  opsStore.knowledgeDomainModalOpen = true
}

function closeDomainModal() {
  if (assistantLoading.value || savingDomain.value) {
    return
  }
  opsStore.knowledgeDomainModalOpen = false
}

function applyAssistantResult(payload: {
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
}) {
  opsStore.knowledgeDomainForm.assistantQuestion = String(payload.question || '').trim()
  opsStore.knowledgeDomainForm.assistantDraft = {
    goal: String(payload.goal || '').trim(),
    description: String(payload.description || '').trim(),
    seedQueries: Array.isArray(payload.seedQueries) ? payload.seedQueries.filter(Boolean) : [],
    excludeTerms: Array.isArray(payload.excludeTerms) ? payload.excludeTerms.filter(Boolean) : [],
  }
  opsStore.knowledgeDomainForm.assistantCurrentDimension = String(payload.currentDimension || '').trim()
  opsStore.knowledgeDomainForm.assistantCoveredDimensions = Array.isArray(payload.coveredDimensions)
    ? payload.coveredDimensions.filter(Boolean)
    : []
  opsStore.knowledgeDomainForm.assistantNextDimension = String(payload.nextDimension || '').trim()
  opsStore.knowledgeDomainForm.assistantReady = !!payload.ready
  opsStore.knowledgeDomainForm.assistantReason = String(payload.reason || '').trim()
}

function assistantHistoryForRequest() {
  const history = [...opsStore.knowledgeDomainForm.assistantHistory]
  const answer = opsStore.knowledgeDomainForm.assistantAnswer.trim()
  const currentQuestion = opsStore.knowledgeDomainForm.assistantQuestion.trim()
  if (currentQuestion && answer) {
    history.push({ role: 'assistant', content: currentQuestion })
    history.push({ role: 'user', content: answer })
  }
  return history
}

async function continueAssistant() {
  const name = opsStore.knowledgeDomainForm.name.trim()
  if (!name) {
    toastStore.show('请先填写领域名称')
    return
  }
  if (opsStore.knowledgeDomainForm.assistantQuestion.trim() && !opsStore.knowledgeDomainForm.assistantAnswer.trim()) {
    toastStore.show('请先回答当前问题')
    return
  }
  const nextHistory = assistantHistoryForRequest()
  assistantLoading.value = true
  opsStore.knowledgeDomainForm.assistantStreamingPreview = ''
  try {
    await runDomainSetupAssistant(name, nextHistory, (event) => {
      if (event.type === 'delta') {
        opsStore.knowledgeDomainForm.assistantStreamingPreview = String(event.payload.preview || event.payload.content || '').trim()
        return
      }
      if (event.type === 'result') {
        opsStore.knowledgeDomainForm.assistantHistory = nextHistory
        opsStore.knowledgeDomainForm.assistantAnswer = ''
        opsStore.knowledgeDomainForm.assistantStreamingPreview = ''
        applyAssistantResult(event.payload)
        return
      }
      if (event.type === 'error') {
        throw new Error(String(event.payload.message || 'AI 引导失败'))
      }
      if (event.type === 'status') {
        opsStore.knowledgeDomainForm.assistantStreamingPreview = String(event.payload.message || '').trim()
      }
    })
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : 'AI 引导失败')
  } finally {
    assistantLoading.value = false
  }
}

function buildAutoRefreshCron(): string | undefined {
  if (!opsStore.knowledgeDomainForm.autoRefreshEnabled) {
    return undefined
  }
  const rawTime = opsStore.knowledgeDomainForm.autoRefreshTime || '03:00'
  const [hourPart, minutePart] = rawTime.split(':')
  const hour = Number.parseInt(hourPart || '3', 10)
  const minute = Number.parseInt(minutePart || '0', 10)
  const safeHour = Number.isFinite(hour) ? Math.max(0, Math.min(23, hour)) : 3
  const safeMinute = Number.isFinite(minute) ? Math.max(0, Math.min(59, minute)) : 0
  if (opsStore.knowledgeDomainForm.autoRefreshMode === 'weekly') {
    return `0 ${safeMinute} ${safeHour} * * ${opsStore.knowledgeDomainForm.autoRefreshWeekday || 'MON'}`
  }
  return `0 ${safeMinute} ${safeHour} * * *`
}

async function saveDomain() {
  const name = opsStore.knowledgeDomainForm.name.trim()
  if (!name) {
    toastStore.show('请先填写领域名称')
    return
  }
  if (opsStore.knowledgeDomainForm.assistantQuestion.trim() && opsStore.knowledgeDomainForm.assistantAnswer.trim()) {
    toastStore.show('你已经填写了当前回答，请先点“提交回答并继续”，让模型把排除项和检索种子纳入草稿')
    return
  }
  savingDomain.value = true
  try {
    const created = await createDomain({
      name,
      goal: opsStore.knowledgeDomainForm.assistantDraft.goal || `${name} 领域知识库构建与精炼`,
      description: opsStore.knowledgeDomainForm.assistantDraft.description || `${name} 的领域知识组织、证据回溯与精炼约束`,
      seedQueries: opsStore.knowledgeDomainForm.assistantDraft.seedQueries,
      scopeRules: {
        excludeTerms: opsStore.knowledgeDomainForm.assistantDraft.excludeTerms,
      },
      autoRefreshEnabled: opsStore.knowledgeDomainForm.autoRefreshEnabled,
      autoRefreshCron: buildAutoRefreshCron(),
      status: 'draft',
      createdBy: 'ops-ui',
      metadata: {
        setupHistory: opsStore.knowledgeDomainForm.assistantHistory,
        setupAssistantCurrentDimension: opsStore.knowledgeDomainForm.assistantCurrentDimension,
        setupAssistantCoveredDimensions: opsStore.knowledgeDomainForm.assistantCoveredDimensions,
        setupAssistantNextDimension: opsStore.knowledgeDomainForm.assistantNextDimension,
        setupAssistantReason: opsStore.knowledgeDomainForm.assistantReason,
      },
    })
    opsStore.knowledgeDomainModalOpen = false
    opsStore.selectedDomainKnowledgeId = created.id
    toastStore.show(`领域已创建: ${created.name}`)
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '保存领域失败')
  } finally {
    savingDomain.value = false
  }
}

async function loadKnowledge(silent: boolean = false) {
  if (silent && opsStore.loadingKnowledge) {
    return
  }
  opsStore.loadingKnowledge = true
  try {
    opsStore.knowledgeDomains = await getDomains()
    if (opsStore.selectedDomainKnowledgeId && !opsStore.knowledgeDomains.some(d => d.id === opsStore.selectedDomainKnowledgeId)) {
      opsStore.selectedDomainKnowledgeId = ''
    }
    opsStore.knowledgeTopics = opsStore.selectedDomainKnowledgeId
      ? await getDomainTopics(opsStore.selectedDomainKnowledgeId)
      : []
    opsStore.knowledgeJobs = await getRefineJobs(
      opsStore.selectedDomainKnowledgeId ? { domainId: opsStore.selectedDomainKnowledgeId } : undefined
    )
    opsStore.knowledgePacks = await getKnowledgePacks(
      opsStore.selectedDomainKnowledgeId ? { domainId: opsStore.selectedDomainKnowledgeId } : undefined
    )
    const [allCandidates, rejectedOnly] = await Promise.all([
      getDomainCandidates(),
      getDomainCandidates('rejected'),
    ])
    const activeCandidates = allCandidates.filter(item => {
      const status = String(item.status || '').toLowerCase()
      return status !== 'rejected'
    })
    const rejectedFromAll = allCandidates.filter(item => String(item.status || '').toLowerCase() === 'rejected')
    opsStore.domainCandidates = activeCandidates
    const rejectedMerged = rejectedOnly.length ? rejectedOnly : rejectedFromAll
    rejectedCandidates.value = rejectedMerged.map(item => ({
      id: item.id,
      name: item.name,
      description: item.description,
      updatedAt: (item as any).updatedAt,
    }))
    opsStore.domainCandidateDiscoveryControl = await getDiscoveryControl()
    opsStore.knowledgeAgentContextPacks = await getAgentContextPacks(opsStore.selectedDomainKnowledgeId)
    opsStore.knowledgeJobsPage = Math.min(opsStore.knowledgeJobsPage, opsStore.knowledgeJobsTotalPages)
    opsStore.knowledgePacksPage = Math.min(opsStore.knowledgePacksPage, opsStore.knowledgePacksTotalPages)
  } catch (error) {
    if (!silent) {
      toastStore.show(error instanceof Error ? error.message : '加载知识状态失败')
    }
  } finally {
    opsStore.loadingKnowledge = false
  }
}

function refreshKnowledge() {
  loadKnowledge(false)
}

async function onDomainChange() {
  opsStore.selectedKnowledgePackId = ''
  opsStore.selectedKnowledgePack = null
  opsStore.selectedKnowledgeEvidence = []
  opsStore.selectedEvidenceContext = null
  opsStore.persistKnowledgePreferences()
  await loadKnowledge()
}

async function onFilterChange() {
  opsStore.knowledgeJobsPage = 1
  opsStore.knowledgePacksPage = 1
  opsStore.selectedKnowledgePackId = ''
  opsStore.selectedKnowledgePack = null
  opsStore.selectedKnowledgeEvidence = []
  opsStore.selectedEvidenceContext = null
  await loadKnowledge()
}

async function startRefine() {
  if (!opsStore.selectedDomainKnowledgeId) {
    toastStore.show('请先选择领域')
    return
  }
  if (!confirm(`确认立即精炼领域「${opsStore.selectedKnowledgeDomain?.name}」吗？`)) return
  opsStore.knowledgeActionState = true
  try {
    const job = await triggerDomainRefine(opsStore.selectedDomainKnowledgeId, opsStore.knowledgeRefineForm.modelProfile)
    opsStore.knowledgeSubTab = 'jobs'
    opsStore.knowledgeJobs = [job, ...opsStore.knowledgeJobs.filter(j => j.id !== job.id)]
    opsStore.knowledgeJobsPage = 1
    toastStore.show(`领域精炼任务已入队: ${job.id}`)
    startJobsPolling()
    void loadKnowledge(true)
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '提交领域精炼任务失败')
  } finally {
    opsStore.knowledgeActionState = false
  }
}

function setSubTab(tab: string) {
  opsStore.knowledgeSubTab = tab as any
  opsStore.persistKnowledgePreferences()
}

function triggerSourceLabel(value?: string): string {
  const normalized = opsStore.normalizeTriggerSource(value)
  if (normalized === 'auto') return '自动'
  if (normalized === 'user') return '人工'
  return normalized
}

function triggerSourceClass(value?: string): 'success' | 'info' | 'warning' {
  const normalized = opsStore.normalizeTriggerSource(value)
  if (normalized === 'auto') return 'success'
  if (normalized === 'user') return 'info'
  return 'warning'
}

function phaseLabel(value?: string): string {
  const phase = String(value || '').trim()
  if (!phase) return '-'
  const mapping: Record<string, string> = {
    collecting_evidence: '收集证据',
    planning_retrieval_plan: 'LLM 规划检索计划',
    planning_retrieval: '生成检索计划',
    collecting_candidate_documents: '召回候选文档',
    collecting_vector_evidence: '向量召回证据',
    collecting_vector_evidence_skipped: '向量召回跳过',
    collecting_dimension_evidence: '按维度召回证据',
    collecting_dimension_backfill: '维度覆盖补检',
    collecting_evidence_backfill: '证据覆盖补检',
    evidence_selected: '精排筛选证据',
    insufficient_evidence: '证据不足',
    drafting_pack: '生成草稿',
    planning_catalog: '生成目录',
    validating_catalog: '校验目录',
    building_cards: '生成知识卡片',
    binding_evidence: '绑定证据',
    llm_refining: 'LLM 精炼',
    saving_pack: '保存知识包',
    paused: '暂停',
  }
  return mapping[phase] || phase
}

function asNumber(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

function hasLiveProgress(job: any): boolean {
  const summary = job?.outputSummary || {}
  return asNumber(summary.processedTerms) !== null
    || asNumber(summary.totalTerms) !== null
    || asNumber(summary.documentCount) !== null
    || asNumber(summary.knowledgeUnitCount) !== null
    || asNumber(summary.chunkCount) !== null
    || !!summary.retrievalPass
    || !!summary.activeTerm
    || !!summary.activeAction
    || !!summary.dimension
    || !!summary.candidateDocumentCount
}

function hasQualityGate(summary: Record<string, unknown> | undefined): boolean {
  if (!summary) return false
  return asNumber(summary.requiredDocumentCount) !== null
    || asNumber(summary.requiredKnowledgeUnitCount) !== null
    || asNumber(summary.requiredChunkCount) !== null
}

function qualityGap(actual: unknown, required: unknown): string {
  const a = asNumber(actual) ?? 0
  const r = asNumber(required) ?? 0
  return `${a}/${r}`
}

const hasActiveJobs = computed(() =>
  opsStore.knowledgeJobs.some(job => {
    const status = String(job.status || '').toLowerCase()
    return status === 'queued' || status === 'running' || status === 'cancelling'
  })
)

function startJobsPolling() {
  stopJobsPolling()
  jobsPollTimer.value = window.setInterval(() => {
    if (hasActiveJobs.value) {
      loadKnowledge(true)
    }
  }, 5000)
}

function stopJobsPolling() {
  if (jobsPollTimer.value != null) {
    window.clearInterval(jobsPollTimer.value)
    jobsPollTimer.value = null
  }
}

function excludeTermsLabel(scopeRules?: Record<string, unknown>): string {
  if (!scopeRules) return '-'
  const raw = scopeRules.excludeTerms
  if (!Array.isArray(raw)) return '-'
  const values = raw
    .map(item => String(item || '').trim())
    .filter(Boolean)
  return values.length ? values.join(' / ') : '-'
}

function togglePackDetails(packId: string): void {
  expandedPackState.value = {
    ...expandedPackState.value,
    [packId]: !expandedPackState.value[packId],
  }
}

function isPackExpanded(packId: string): boolean {
  return !!expandedPackState.value[packId]
}

function toStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value
    .map(item => String(item ?? '').trim())
    .filter(Boolean)
}

function toRecordArray(value: unknown): Array<Record<string, unknown>> {
  if (!Array.isArray(value)) return []
  return value.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
}

function packSnapshot(pack: KnowledgePack): Record<string, unknown> {
  return (pack.sourceSnapshot || {}) as Record<string, unknown>
}

function packEvidenceStats(pack: KnowledgePack): { documents: number; knowledgeUnits: number; chunks: number; total: number } {
  const snapshot = packSnapshot(pack)
  const documents = toRecordArray(snapshot.documents).length
  const knowledgeUnits = toRecordArray(snapshot.knowledgeUnits).length
  const chunks = toRecordArray(snapshot.chunks).length
  const fallbackTotal = Array.isArray(pack.evidenceRefs) ? pack.evidenceRefs.length : 0
  return {
    documents,
    knowledgeUnits,
    chunks,
    total: documents + knowledgeUnits + chunks || fallbackTotal,
  }
}

function packExcludedTerms(pack: KnowledgePack): string[] {
  return toStringArray(packSnapshot(pack).excludedTerms)
}

function packRetrievalTerms(pack: KnowledgePack): string[] {
  return toStringArray(packSnapshot(pack).retrievalTerms)
}

function packSeedQueries(pack: KnowledgePack): string[] {
  const snapshot = packSnapshot(pack)
  const domainSeeds = toStringArray(snapshot.domainSeedQueries)
  const topicSeeds = toStringArray(snapshot.topicSeedQueries)
  return [...domainSeeds, ...topicSeeds].slice(0, 24)
}

function packSourceNames(pack: KnowledgePack): string[] {
  const items = toRecordArray(packSnapshot(pack).includeDataSources)
  return items
    .map(item => String(item.sourceName ?? item.rootPath ?? item.id ?? '').trim())
    .filter(Boolean)
}

function packMarkdownText(pack: KnowledgePack): string {
  const raw = String(pack.contentMarkdown || '').trim()
  if (!raw) return ''
  return raw.length <= 4000 ? raw : `${raw.slice(0, 4000)}\n\n...（已截断，完整内容请走知识包详情接口）`
}

function packStructured(pack: KnowledgePack): Record<string, unknown> {
  return (pack.structuredContent || {}) as Record<string, unknown>
}

function packValidation(pack: KnowledgePack): Record<string, unknown> {
  const validation = packStructured(pack).validation
  return validation && typeof validation === 'object' ? validation as Record<string, unknown> : {}
}

function packCatalog(pack: KnowledgePack): Array<Record<string, unknown>> {
  return toRecordArray(packStructured(pack).catalog)
}

function packCards(pack: KnowledgePack): Array<Record<string, unknown>> {
  return toRecordArray(packStructured(pack).cards)
}

function packValidationWarnings(pack: KnowledgePack): string[] {
  return toStringArray(packValidation(pack).warnings)
}

function structuredCount(pack: KnowledgePack, key: string, fallback: number = 0): number {
  return asNumber(packValidation(pack)[key]) ?? fallback
}

function catalogNodePadding(node: Record<string, unknown>): string {
  const level = Math.max(1, asNumber(node.level) ?? 1)
  return `${(level - 1) * 18}px`
}

function catalogQuality(node: Record<string, unknown>): Record<string, unknown> {
  const quality = node.quality
  return quality && typeof quality === 'object' ? quality as Record<string, unknown> : {}
}

function qualityStatusLabel(value: unknown): string {
  const status = String(value || '').toLowerCase()
  if (status === 'ready' || status === 'passed') return '通过'
  if (status === 'review_required') return '需确认'
  if (status === 'failed') return '失败'
  return status || '-'
}

function cardClaims(card: Record<string, unknown>): Array<Record<string, unknown>> {
  return toRecordArray(card.claims)
}

function claimEvidenceRefs(claim: Record<string, unknown>): string[] {
  return toStringArray(claim.evidenceRefs).slice(0, 5)
}

function setActionState(state: typeof candidateActionState, id: string, running: boolean) {
  if (running) {
    state.value = { ...state.value, [id]: true }
    return
  }
  const next = { ...state.value }
  delete next[id]
  state.value = next
}

function isActionRunning(state: Record<string, boolean>, id: string): boolean {
  return !!state[id]
}

async function removeDomain(domainId: string, domainName: string) {
  if (!confirm(`确认删除领域「${domainName}」吗？`)) return
  setActionState(domainActionState, domainId, true)
  try {
    await deleteDomain(domainId)
    if (opsStore.selectedDomainKnowledgeId === domainId) {
      opsStore.selectedDomainKnowledgeId = ''
    }
    toastStore.show(`已删除领域: ${domainName}`)
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '删除领域失败')
  } finally {
    setActionState(domainActionState, domainId, false)
  }
}

async function removeKnowledgePack(packId: string) {
  if (!confirm(`确认删除知识包 ${packId} 吗？`)) return
  setActionState(packActionState, packId, true)
  try {
    await deleteKnowledgePack(packId)
    toastStore.show('知识包已删除')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '删除知识包失败')
  } finally {
    setActionState(packActionState, packId, false)
  }
}

async function reviewPack(packId: string, status: 'accepted' | 'reference') {
  setActionState(packActionState, `${packId}:${status}`, true)
  try {
    await reviewKnowledgePack(packId, status)
    toastStore.show(status === 'accepted' ? '知识包已采用' : '知识包已标记为参考')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '更新知识包状态失败')
  } finally {
    setActionState(packActionState, `${packId}:${status}`, false)
  }
}

async function openEvidence(packId: string, title: string) {
  evidenceModalOpen.value = true
  evidenceLoading.value = true
  evidencePackTitle.value = title
  evidencePackId.value = packId
  evidenceItems.value = []
  selectedEvidenceRef.value = ''
  selectedEvidenceContext.value = ''
  try {
    evidenceItems.value = await getPackEvidence(packId)
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '加载证据失败')
  } finally {
    evidenceLoading.value = false
  }
}

async function openEvidenceContext(evidenceRef: string) {
  if (!evidencePackId.value) return
  evidenceContextLoading.value = true
  selectedEvidenceRef.value = evidenceRef
  selectedEvidenceContext.value = ''
  try {
    const context = await getEvidenceContext(evidencePackId.value, evidenceRef)
    const pieces = [context.title || '', context.sourceFile || '', context.context || context.content || '']
      .map(v => String(v || '').trim())
      .filter(Boolean)
    selectedEvidenceContext.value = pieces.join('\n\n')
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '加载证据上下文失败')
  } finally {
    evidenceContextLoading.value = false
  }
}

async function resumeJob(jobId: string) {
  setActionState(jobActionState, `${jobId}:resume`, true)
  try {
    await resumeRefineJob(jobId)
    toastStore.show('任务已恢复')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '恢复任务失败')
  } finally {
    setActionState(jobActionState, `${jobId}:resume`, false)
  }
}

async function cancelJob(jobId: string) {
  if (!confirm(`确认取消任务 ${jobId} 吗？`)) return
  setActionState(jobActionState, `${jobId}:cancel`, true)
  try {
    await cancelRefineJob(jobId)
    toastStore.show('已提交取消')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '取消任务失败')
  } finally {
    setActionState(jobActionState, `${jobId}:cancel`, false)
  }
}

async function createTopicQuick() {
  if (!opsStore.selectedDomainKnowledgeId) {
    toastStore.show('请先选择领域')
    return
  }
  const name = newTopicName.value.trim()
  if (!name) {
    toastStore.show('请先输入专题名称')
    return
  }
  creatingTopic.value = true
  try {
    const seeds = newTopicSeedQueries.value
      .split(/[\n,，;；]/)
      .map(v => v.trim())
      .filter(Boolean)
    await createDomainTopic(opsStore.selectedDomainKnowledgeId, {
      name,
      description: '',
      seedQueries: seeds,
      status: 'active',
    })
    newTopicName.value = ''
    newTopicSeedQueries.value = ''
    toastStore.show('专题已创建')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '创建专题失败')
  } finally {
    creatingTopic.value = false
  }
}

async function removeTopic(topicId: string) {
  if (!confirm('确认删除该专题吗？')) return
  setActionState(topicActionState, `${topicId}:delete`, true)
  try {
    await deleteTopic(topicId)
    toastStore.show('专题已删除')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '删除专题失败')
  } finally {
    setActionState(topicActionState, `${topicId}:delete`, false)
  }
}

async function refineTopic(topicId: string) {
  setActionState(topicActionState, `${topicId}:refine`, true)
  try {
    const job = await triggerTopicRefine(topicId, opsStore.knowledgeRefineForm.modelProfile)
    toastStore.show(`专题精炼任务已入队: ${job.id}`)
    opsStore.knowledgeSubTab = 'jobs'
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '触发专题精炼失败')
  } finally {
    setActionState(topicActionState, `${topicId}:refine`, false)
  }
}

async function triggerDiscoveryOnce() {
  discoveryActionRunning.value = true
  try {
    await discoverDomainCandidates()
    toastStore.show('自动发现已触发')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '触发自动发现失败')
  } finally {
    discoveryActionRunning.value = false
  }
}

async function startDiscoverySchedule() {
  discoveryActionRunning.value = true
  try {
    opsStore.domainCandidateDiscoveryControl = await startDiscovery()
    toastStore.show('自动发现已启动')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '启动自动发现失败')
  } finally {
    discoveryActionRunning.value = false
  }
}

async function stopDiscoverySchedule() {
  discoveryActionRunning.value = true
  try {
    opsStore.domainCandidateDiscoveryControl = await stopDiscovery()
    toastStore.show('自动发现已停止')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '停止自动发现失败')
  } finally {
    discoveryActionRunning.value = false
  }
}

async function acceptCandidate(candidateId: string) {
  setActionState(candidateActionState, candidateId, true)
  try {
    await acceptDomainCandidate(candidateId)
    toastStore.show('候选领域已采纳')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '采纳候选领域失败')
  } finally {
    setActionState(candidateActionState, candidateId, false)
  }
}

async function rejectCandidate(candidateId: string) {
  setActionState(candidateActionState, candidateId, true)
  try {
    await rejectDomainCandidate(candidateId)
    toastStore.show('候选领域已拒绝')
    await loadKnowledge()
  } catch (error) {
    toastStore.show(error instanceof Error ? error.message : '拒绝候选领域失败')
  } finally {
    setActionState(candidateActionState, candidateId, false)
  }
}

onMounted(() => {
  opsStore.loadKnowledgePreferences()
  loadKnowledge()
  startJobsPolling()
})

onUnmounted(() => {
  stopJobsPolling()
})
</script>

<template>
  <AppPanel>
    <template #header>
      <h2>领域知识编译</h2>
    </template>
    <template #actions>
      <AppSelect
        :model-value="opsStore.selectedDomainKnowledgeId"
        :options="[
          { value: '', label: '全部领域' },
          ...opsStore.knowledgeDomains.map(d => ({ value: d.id, label: d.name }))
        ]"
        @update:model-value="opsStore.selectedDomainKnowledgeId = $event; onDomainChange()"
      />
      <AppButton variant="secondary" size="sm" @click="openDomainModal">新增领域</AppButton>
      <AppButton variant="secondary" size="sm" :loading="opsStore.loadingKnowledge" @click="refreshKnowledge">
        {{ opsStore.loadingKnowledge ? '刷新中...' : '刷新知识状态' }}
      </AppButton>
    </template>

    <div v-if="opsStore.selectedKnowledgeDomain" class="text-muted mb-lg">
      当前领域：{{ opsStore.selectedKnowledgeDomain.name }} · 状态 {{ opsStore.selectedKnowledgeDomain.status }} ·
      自动维护 {{ opsStore.selectedKnowledgeDomain.autoRefreshEnabled ? '开启' : '关闭' }}
    </div>
    <div v-else class="text-muted mb-lg">当前未选择领域，显示全部精炼任务和知识包。</div>

    <div class="refine-row flex gap-md items-center mb-lg">
      <AppInput
        v-model="opsStore.knowledgeRefineForm.modelProfile"
        placeholder="可选：modelProfile"
      />
      <AppSelect
        :model-value="opsStore.knowledgeResultFilter"
        :options="[
          { value: 'all', label: '全部结果' },
          { value: 'user', label: '仅人工' },
          { value: 'auto', label: '仅自动' }
        ]"
        @update:model-value="(v: string) => { opsStore.knowledgeResultFilter = v as 'all' | 'user' | 'auto'; onFilterChange() }"
      />
      <AppButton
        variant="primary"
        size="sm"
        class="refine-action-btn"
        :disabled="!opsStore.selectedDomainKnowledgeId || opsStore.knowledgeActionState"
        @click="startRefine"
      >
        {{ opsStore.knowledgeActionState ? '提交中...' : '立即精炼当前领域' }}
      </AppButton>
    </div>

    <div class="stats-row mb-lg">
      <span class="text-muted">自动任务 {{ autoJobs.length }} 个，自动知识包 {{ autoPacks.length }} 个</span>
    </div>

    <nav class="subtabs">
      <button
        v-for="tab in knowledgeSubTabs"
        :key="tab.key"
        class="subtab-btn"
        :class="{ 'subtab-active': tab.key === opsStore.knowledgeSubTab }"
        @click="setSubTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </nav>
  </AppPanel>

  <AppPanel v-if="opsStore.knowledgeSubTab === 'overview'" title="领域列表">
    <div class="topics-list">
      <div v-if="!opsStore.knowledgeDomains.length" class="empty-state">当前没有领域</div>
      <div v-for="domain in opsStore.knowledgeDomains" :key="domain.id" class="topic-item">
        <div class="flex justify-between items-center gap-md">
          <strong>{{ domain.name }}</strong>
          <div class="flex gap-sm items-center">
            <AppPill :variant="opsStore.statusClass(domain.status)">{{ domain.status }}</AppPill>
            <AppButton
              variant="secondary"
              size="sm"
              @click="opsStore.selectedDomainKnowledgeId = domain.id; onDomainChange()"
            >
              选中
            </AppButton>
            <AppButton
              variant="danger"
              size="sm"
              :loading="isActionRunning(domainActionState, domain.id)"
              @click="removeDomain(domain.id, domain.name)"
            >
              删除
            </AppButton>
          </div>
        </div>
        <div class="text-muted">{{ domain.description || '-' }}</div>
        <div class="text-muted">目标：{{ domain.goal || '-' }}</div>
        <div class="text-muted">种子问题：{{ (domain.seedQueries || []).join(' / ') || '-' }}</div>
        <div class="text-muted">排除项：{{ excludeTermsLabel(domain.scopeRules) }}</div>
      </div>
    </div>
    <div v-if="opsStore.selectedDomainKnowledgeId" class="topic-create-row">
      <AppInput
        v-model="newTopicName"
        placeholder="新增专题名称（当前选中领域）"
      />
      <AppInput
        v-model="newTopicSeedQueries"
        placeholder="专题种子问题，逗号分隔"
      />
      <AppButton variant="secondary" size="sm" :loading="creatingTopic" @click="createTopicQuick">
        新增专题
      </AppButton>
    </div>
    <div v-if="opsStore.selectedDomainKnowledgeId" class="topics-list">
      <div v-if="!opsStore.knowledgeTopics.length" class="empty-state">当前领域暂无专题</div>
      <div v-for="topic in opsStore.knowledgeTopics" :key="topic.id" class="topic-item">
        <div class="flex justify-between items-center gap-md">
          <strong>{{ topic.name }}</strong>
          <div class="flex gap-sm">
            <AppButton
              variant="secondary"
              size="sm"
              :loading="isActionRunning(topicActionState, `${topic.id}:refine`)"
              @click="refineTopic(topic.id)"
            >
              精炼
            </AppButton>
            <AppButton
              variant="danger"
              size="sm"
              :loading="isActionRunning(topicActionState, `${topic.id}:delete`)"
              @click="removeTopic(topic.id)"
            >
              删除
            </AppButton>
          </div>
        </div>
        <div class="text-muted">{{ topic.description || '-' }}</div>
        <div class="text-muted">种子问题：{{ (topic.seedQueries || []).join(' / ') || '-' }}</div>
      </div>
    </div>
  </AppPanel>

  <AppPanel v-if="opsStore.knowledgeSubTab === 'jobs'" title="精炼任务">
    <div v-if="!opsStore.pagedKnowledgeJobs.length" class="empty-state">当前筛选条件下暂无领域知识精炼任务</div>
    <div v-for="job in opsStore.pagedKnowledgeJobs" :key="job.id" class="job-item">
      <div class="job-header flex justify-between items-center gap-md mb-sm">
        <div>
          <AppPill variant="info">{{ job.jobType }}</AppPill>
          <div class="text-muted">{{ job.id }}</div>
        </div>
        <div>
          <AppPill :variant="opsStore.statusClass(job.status)">{{ job.status }}</AppPill>
          <AppPill :variant="triggerSourceClass(job.triggerSource)">{{ triggerSourceLabel(job.triggerSource) }}</AppPill>
        </div>
      </div>
      <div class="text-muted">{{ opsStore.formatDate(job.updatedAt) }}</div>
      <div class="text-muted">模型：{{ job.modelProfile || '-' }}</div>
      <div v-if="job.outputSummary?.phase" class="text-muted">阶段：{{ phaseLabel(String(job.outputSummary.phase || '')) }}</div>
      <div v-if="hasLiveProgress(job)" class="job-progress-grid">
        <div class="text-muted">
          检索轮次：
          {{ String(job.outputSummary?.retrievalPass || '-') }}
          <span v-if="job.outputSummary?.retrievalPassIndex && job.outputSummary?.retrievalPassCount">
            ({{ job.outputSummary.retrievalPassIndex }}/{{ job.outputSummary.retrievalPassCount }})
          </span>
        </div>
        <div v-if="job.outputSummary?.activeAction || job.outputSummary?.activeTerm || job.outputSummary?.dimension" class="text-muted">
          当前动作：{{ String(job.outputSummary?.activeAction || '-') }}
          <span v-if="job.outputSummary?.dimension"> · 维度：{{ String(job.outputSummary.dimension) }}</span>
          <span v-if="job.outputSummary?.activeTerm"> · 当前词：{{ String(job.outputSummary.activeTerm) }}</span>
        </div>
        <div class="text-muted">
          词项进度：
          {{ asNumber(job.outputSummary?.processedTerms) ?? 0 }}/{{ asNumber(job.outputSummary?.totalTerms) ?? 0 }}
        </div>
        <div class="text-muted">
          命中文档：{{ asNumber(job.outputSummary?.documentCount) ?? 0 }}
          · 知识单元：{{ asNumber(job.outputSummary?.knowledgeUnitCount) ?? 0 }}
          · 正文块：{{ asNumber(job.outputSummary?.chunkCount) ?? 0 }}
        </div>
      </div>
      <div v-if="hasQualityGate(job.outputSummary as any)" class="job-quality-gate">
        <div class="text-muted">
          质量闸门（实际/要求）：
          文档 {{ qualityGap(job.outputSummary?.documentCount, job.outputSummary?.requiredDocumentCount) }}
          · 知识单元 {{ qualityGap(job.outputSummary?.knowledgeUnitCount, job.outputSummary?.requiredKnowledgeUnitCount) }}
          · 正文块 {{ qualityGap(job.outputSummary?.chunkCount, job.outputSummary?.requiredChunkCount) }}
        </div>
        <div v-if="job.outputSummary?.excludedTerms && Array.isArray(job.outputSummary.excludedTerms)" class="text-muted">
          排除项数：{{ job.outputSummary.excludedTerms.length }}
        </div>
      </div>
      <div class="flex justify-end gap-sm">
        <AppButton
          variant="secondary"
          size="sm"
          :loading="isActionRunning(jobActionState, `${job.id}:resume`)"
          @click="resumeJob(job.id)"
        >
          恢复
        </AppButton>
        <AppButton
          variant="danger"
          size="sm"
          :loading="isActionRunning(jobActionState, `${job.id}:cancel`)"
          @click="cancelJob(job.id)"
        >
          取消
        </AppButton>
      </div>
    </div>
  </AppPanel>

  <AppPanel v-if="opsStore.knowledgeSubTab === 'packs'" title="知识包">
    <div v-if="!opsStore.pagedKnowledgePacks.length" class="empty-state">当前筛选条件下暂无知识包</div>
    <div v-for="pack in opsStore.pagedKnowledgePacks" :key="pack.id" class="pack-item">
      <div class="pack-header flex justify-between items-center gap-md mb-sm">
        <strong>{{ pack.title }}</strong>
        <div>
          <AppPill :variant="opsStore.statusClass(pack.status)">{{ qualityStatusLabel(pack.status) }}</AppPill>
          <AppPill variant="info">{{ pack.artifactType || '-' }}</AppPill>
          <AppPill :variant="triggerSourceClass(pack.triggerSource)">{{ triggerSourceLabel(pack.triggerSource) }}</AppPill>
        </div>
      </div>
      <div class="text-muted">{{ pack.summary || '-' }}</div>
      <div class="text-muted">证据数：{{ packEvidenceStats(pack).total }}</div>
      <div class="text-muted">
        证据覆盖：文档 {{ packEvidenceStats(pack).documents }} · 知识单元 {{ packEvidenceStats(pack).knowledgeUnits }} · 正文块 {{ packEvidenceStats(pack).chunks }}
      </div>
      <div v-if="Object.keys(packValidation(pack)).length" class="text-muted">
        目录质量：{{ qualityStatusLabel(packValidation(pack).status) }}
        · 目录 {{ structuredCount(pack, 'catalogNodeCount', packCatalog(pack).length) }}
        · 卡片 {{ structuredCount(pack, 'cardCount', packCards(pack).length) }}
        · 结论绑定率 {{ structuredCount(pack, 'boundClaimRatio') }}
      </div>
      <div class="text-muted">{{ opsStore.formatDate(pack.updatedAt) }}</div>
      <div v-if="isPackExpanded(pack.id)" class="pack-detail-grid mt-sm mb-sm">
        <div class="pack-detail-card">
          <div class="question-title">范围与种子</div>
          <div class="text-muted">领域：{{ String((pack.sourceSnapshot as any)?.domainName || '-') }}</div>
          <div class="text-muted">专题：{{ String((pack.sourceSnapshot as any)?.topicName || '-') }}</div>
          <div class="text-muted">种子问题：</div>
          <div v-if="packSeedQueries(pack).length" class="pill-row">
            <AppPill v-for="item in packSeedQueries(pack)" :key="`${pack.id}:seed:${item}`" variant="info">{{ item }}</AppPill>
          </div>
          <div v-else class="text-muted">-</div>
        </div>
        <div class="pack-detail-card">
          <div class="question-title">排除与检索词</div>
          <div class="text-muted">排除项：</div>
          <div v-if="packExcludedTerms(pack).length" class="pill-row">
            <AppPill v-for="item in packExcludedTerms(pack)" :key="`${pack.id}:exclude:${item}`" variant="warning">{{ item }}</AppPill>
          </div>
          <div v-else class="text-muted">-</div>
          <div class="text-muted mt-sm">检索词：</div>
          <div v-if="packRetrievalTerms(pack).length" class="pill-row">
            <AppPill v-for="item in packRetrievalTerms(pack).slice(0, 20)" :key="`${pack.id}:term:${item}`" variant="default">{{ item }}</AppPill>
          </div>
          <div v-else class="text-muted">-</div>
        </div>
        <div class="pack-detail-card">
          <div class="question-title">数据源与模型</div>
          <div class="text-muted">模型配置：{{ pack.modelProfile || '-' }}</div>
          <div class="text-muted">
            质量指标：证据 {{ structuredCount(pack, 'evidenceCount', packEvidenceStats(pack).total) }}
            · 文档 {{ structuredCount(pack, 'documentCount', packEvidenceStats(pack).documents) }}
            · 低证据节点 {{ structuredCount(pack, 'lowEvidenceNodeCount') }}
          </div>
          <div class="text-muted">数据源：</div>
          <div v-if="packSourceNames(pack).length" class="pill-row">
            <AppPill v-for="item in packSourceNames(pack)" :key="`${pack.id}:src:${item}`" variant="default">{{ item }}</AppPill>
          </div>
          <div v-else class="text-muted">-</div>
        </div>
        <div class="pack-detail-card pack-structured-card">
          <div class="question-title">结构化知识目录</div>
          <div v-if="packCatalog(pack).length" class="catalog-tree">
            <div
              v-for="node in packCatalog(pack)"
              :key="String(node.id || node.title)"
              class="catalog-node"
              :style="{ paddingLeft: catalogNodePadding(node) }"
            >
              <div>
                <strong>{{ String(node.title || '-') }}</strong>
                <span class="text-muted"> · {{ qualityStatusLabel(catalogQuality(node).status) }}</span>
              </div>
              <div class="text-muted">
                证据 {{ asNumber(catalogQuality(node).evidenceCount) ?? 0 }}
                · 文档 {{ asNumber(catalogQuality(node).documentCount) ?? 0 }}
              </div>
              <div v-if="node.summary" class="text-muted">{{ String(node.summary) }}</div>
            </div>
          </div>
          <div v-else class="text-muted">当前知识包还没有结构化目录</div>
        </div>
        <div class="pack-detail-card pack-structured-card">
          <div class="question-title">知识卡片</div>
          <div v-if="packCards(pack).length" class="card-list">
            <div v-for="card in packCards(pack).slice(0, 12)" :key="String(card.id || card.title)" class="knowledge-card">
              <div>
                <AppPill variant="default">{{ String(card.type || 'concept') }}</AppPill>
                <strong>{{ String(card.title || '-') }}</strong>
              </div>
              <div class="text-muted">{{ String(card.summary || '-') }}</div>
              <div v-for="claim in cardClaims(card).slice(0, 3)" :key="String(claim.text)" class="claim-item">
                <div>{{ String(claim.text || '-') }}</div>
                <div class="text-muted">证据：{{ claimEvidenceRefs(claim).join(' / ') || '-' }}</div>
              </div>
            </div>
          </div>
          <div v-else class="text-muted">当前知识包还没有知识卡片</div>
        </div>
        <div v-if="packValidationWarnings(pack).length" class="pack-detail-card pack-warning-card">
          <div class="question-title">目录校验提示</div>
          <div v-for="warning in packValidationWarnings(pack)" :key="warning" class="text-muted">- {{ warning }}</div>
        </div>
        <div class="pack-detail-card pack-markdown-card">
          <div class="question-title">精炼内容（Markdown）</div>
          <div v-if="packMarkdownText(pack)" class="pack-markdown-text">{{ packMarkdownText(pack) }}</div>
          <div v-else class="text-muted">当前无 Markdown 内容</div>
        </div>
      </div>
      <div class="flex justify-end gap-sm">
        <AppButton
          variant="secondary"
          size="sm"
          @click="togglePackDetails(pack.id)"
        >
          {{ isPackExpanded(pack.id) ? '收起详情' : '展开详情' }}
        </AppButton>
        <AppButton
          variant="secondary"
          size="sm"
          :loading="isActionRunning(packActionState, `${pack.id}:accepted`)"
          @click="reviewPack(pack.id, 'accepted')"
        >
          采用
        </AppButton>
        <AppButton
          variant="secondary"
          size="sm"
          :loading="isActionRunning(packActionState, `${pack.id}:reference`)"
          @click="reviewPack(pack.id, 'reference')"
        >
          参考
        </AppButton>
        <AppButton
          variant="secondary"
          size="sm"
          @click="openEvidence(pack.id, pack.title)"
        >
          证据
        </AppButton>
        <AppButton
          variant="danger"
          size="sm"
          :loading="isActionRunning(packActionState, pack.id)"
          @click="removeKnowledgePack(pack.id)"
        >
          删除知识包
        </AppButton>
      </div>
    </div>
  </AppPanel>

  <AppModal
    :open="evidenceModalOpen"
    title="知识包证据"
    size="xl"
    @close="evidenceModalOpen = false"
  >
    <div class="text-muted mb-md">知识包：{{ evidencePackTitle }}</div>
    <div v-if="evidenceLoading" class="text-muted">证据加载中...</div>
    <div v-else-if="!evidenceItems.length" class="text-muted">暂无证据</div>
    <div v-else class="evidence-layout">
      <div class="evidence-list">
        <div v-for="item in evidenceItems" :key="item.evidenceRef" class="evidence-item">
          <div><strong>{{ item.title || item.evidenceRef }}</strong></div>
          <div class="text-muted">{{ item.snippet || '-' }}</div>
          <div class="text-muted">来源：{{ item.sourceFile || '-' }} {{ item.pageNo ? `| 页码 ${item.pageNo}` : '' }}</div>
          <div class="flex justify-end">
            <AppButton
              variant="secondary"
              size="sm"
              :loading="evidenceContextLoading && selectedEvidenceRef === item.evidenceRef"
              @click="openEvidenceContext(item.evidenceRef)"
            >
              查看上下文
            </AppButton>
          </div>
        </div>
      </div>
      <div class="evidence-context">
        <div class="question-title">证据上下文</div>
        <div v-if="evidenceContextLoading" class="text-muted">上下文加载中...</div>
        <div v-else-if="selectedEvidenceContext" class="context-text">{{ selectedEvidenceContext }}</div>
        <div v-else class="text-muted">点击左侧“查看上下文”加载正文窗口</div>
      </div>
    </div>
  </AppModal>

  <AppPanel v-if="opsStore.knowledgeSubTab === 'agent'" title="智能体优先上下文">
    <div v-if="!opsStore.knowledgeAgentContextPacks.length" class="empty-state">当前领域暂无可供智能体消费的知识包</div>
    <div v-for="pack in opsStore.knowledgeAgentContextPacks" :key="pack.id" class="agent-pack-item">
      <div class="flex justify-between items-center gap-md mb-sm">
        <strong>{{ pack.title }}</strong>
        <AppPill :variant="opsStore.statusClass(pack.status)">{{ pack.status }}</AppPill>
      </div>
      <div class="text-muted">证据数：{{ (pack.evidenceRefs || []).length }}</div>
    </div>
  </AppPanel>

  <AppPanel v-if="opsStore.knowledgeSubTab === 'discovery'" title="候选领域发现与确认">
    <div class="flex justify-between items-center gap-md mb-md">
      <div class="text-muted">
        运行状态：{{ opsStore.domainCandidateDiscoveryControl?.runningEnabled ? '运行中' : '已停止' }}
      </div>
      <div class="flex gap-sm">
        <AppButton
          variant="secondary"
          size="sm"
          :loading="discoveryActionRunning"
          @click="triggerDiscoveryOnce"
        >
          立即发现
        </AppButton>
        <AppButton
          variant="secondary"
          size="sm"
          :loading="discoveryActionRunning"
          @click="startDiscoverySchedule"
        >
          启动自动发现
        </AppButton>
        <AppButton
          variant="secondary"
          size="sm"
          :loading="discoveryActionRunning"
          @click="stopDiscoverySchedule"
        >
          停止自动发现
        </AppButton>
      </div>
    </div>
    <div v-if="!opsStore.pagedDomainCandidates.length" class="empty-state">当前没有候选领域</div>
    <div v-for="candidate in opsStore.pagedDomainCandidates" :key="candidate.id" class="candidate-item">
      <div class="flex justify-between items-center gap-md mb-sm">
        <strong>{{ candidate.name }}</strong>
        <AppPill :variant="opsStore.statusClass(candidate.status)">{{ candidate.status }}</AppPill>
      </div>
      <div class="text-muted">{{ candidate.description || '-' }}</div>
      <div class="text-muted">关键词：{{ (candidate.keywords || []).join(' / ') || '-' }}</div>
      <div class="flex justify-end gap-sm">
        <AppButton
          variant="secondary"
          size="sm"
          :loading="isActionRunning(candidateActionState, candidate.id)"
          @click="acceptCandidate(candidate.id)"
        >
          采纳
        </AppButton>
        <AppButton
          variant="danger"
          size="sm"
          :loading="isActionRunning(candidateActionState, candidate.id)"
          @click="rejectCandidate(candidate.id)"
        >
          拒绝
        </AppButton>
      </div>
    </div>
    <div class="rejected-section">
      <div class="question-title">已拒绝列表</div>
      <div v-if="!rejectedCandidates.length" class="text-muted">暂无已拒绝候选</div>
      <div v-for="candidate in rejectedCandidates" :key="candidate.id" class="candidate-item rejected-item">
        <div class="flex justify-between items-center gap-md mb-sm">
          <strong>{{ candidate.name }}</strong>
          <AppPill variant="warning">rejected</AppPill>
        </div>
        <div class="text-muted">{{ candidate.description || '-' }}</div>
      </div>
    </div>
  </AppPanel>

  <AppModal
    :open="opsStore.knowledgeDomainModalOpen"
    title="新增领域"
    size="xl"
    @close="closeDomainModal"
  >
    <div class="domain-modal">
      <div class="form-grid">
        <label class="field-block">
          <span class="field-label">领域名称</span>
          <AppInput
            v-model="opsStore.knowledgeDomainForm.name"
            placeholder="例如：研究生教育知识智能管理平台"
          />
        </label>
        <label class="field-check">
          <input v-model="opsStore.knowledgeDomainForm.autoRefreshEnabled" type="checkbox">
          <span>启用自动汇聚与精炼</span>
        </label>
        <div v-if="opsStore.knowledgeDomainForm.autoRefreshEnabled" class="auto-refresh-row">
          <AppSelect
            :model-value="opsStore.knowledgeDomainForm.autoRefreshMode"
            :options="[
              { value: 'daily', label: '每天' },
              { value: 'weekly', label: '每周' }
            ]"
            @update:model-value="opsStore.knowledgeDomainForm.autoRefreshMode = $event as 'daily' | 'weekly'"
          />
          <AppInput
            v-model="opsStore.knowledgeDomainForm.autoRefreshTime"
            placeholder="03:00"
          />
          <AppSelect
            v-if="opsStore.knowledgeDomainForm.autoRefreshMode === 'weekly'"
            :model-value="opsStore.knowledgeDomainForm.autoRefreshWeekday"
            :options="[
              { value: 'MON', label: '周一' },
              { value: 'TUE', label: '周二' },
              { value: 'WED', label: '周三' },
              { value: 'THU', label: '周四' },
              { value: 'FRI', label: '周五' },
              { value: 'SAT', label: '周六' },
              { value: 'SUN', label: '周日' }
            ]"
            @update:model-value="opsStore.knowledgeDomainForm.autoRefreshWeekday = $event"
          />
        </div>
      </div>

      <div class="assistant-box">
        <div class="assistant-header">
          <div>
            <strong>AI 引导设置</strong>
            <div class="text-muted">模型会围绕领域知识库的边界、组织方式、证据回溯和排除项逐轮追问。</div>
          </div>
          <AppButton
            variant="secondary"
            size="sm"
            :loading="assistantLoading"
            :disabled="!canContinueAssistant"
            @click="continueAssistant"
          >
            {{ opsStore.knowledgeDomainForm.assistantQuestion ? '提交回答并继续' : '开始 AI 引导' }}
          </AppButton>
        </div>

        <div v-if="opsStore.knowledgeDomainForm.assistantHistory.length" class="history-list">
          <div
            v-for="(item, index) in opsStore.knowledgeDomainForm.assistantHistory"
            :key="`${item.role}-${index}`"
            class="history-item"
          >
            <AppPill :variant="item.role === 'assistant' ? 'info' : 'success'">
              {{ item.role === 'assistant' ? 'AI' : '你' }}
            </AppPill>
            <div>{{ item.content }}</div>
          </div>
        </div>

        <div v-if="opsStore.knowledgeDomainForm.assistantQuestion" class="question-card">
          <div class="question-title">当前问题</div>
          <div class="question-text">{{ opsStore.knowledgeDomainForm.assistantQuestion }}</div>
          <div class="question-meta text-muted">
            当前维度：{{ opsStore.knowledgeDomainForm.assistantCurrentDimension || '-' }} ·
            下一维度：{{ opsStore.knowledgeDomainForm.assistantNextDimension || '-' }}
          </div>
          <AppTextarea
            v-model="opsStore.knowledgeDomainForm.assistantAnswer"
            :rows="6"
            placeholder="在这里回答当前问题。模型会基于你的回答继续追问，并沉淀检索种子与排除项。"
          />
        </div>

        <div v-else class="question-card muted-card">
          <div class="question-title">当前问题</div>
          <div class="text-muted">先填写领域名称，再点击“开始 AI 引导”。</div>
        </div>

        <div v-if="assistantLoading || opsStore.knowledgeDomainForm.assistantStreamingPreview" class="stream-card">
          <div class="question-title">模型处理中</div>
          <div class="stream-preview">{{ opsStore.knowledgeDomainForm.assistantStreamingPreview || '正在生成下一轮问题...' }}</div>
        </div>

        <div class="draft-grid">
          <div class="draft-card">
            <div class="question-title">当前知识库目标</div>
            <div class="text-body">{{ opsStore.knowledgeDomainForm.assistantDraft.goal || '-' }}</div>
          </div>
          <div class="draft-card">
            <div class="question-title">当前范围草稿</div>
            <div class="text-body">{{ opsStore.knowledgeDomainForm.assistantDraft.description || '-' }}</div>
          </div>
          <div class="draft-card">
            <div class="question-title">检索种子</div>
            <div v-if="opsStore.knowledgeDomainForm.assistantDraft.seedQueries.length" class="pill-row">
              <AppPill v-for="item in opsStore.knowledgeDomainForm.assistantDraft.seedQueries" :key="item" variant="info">
                {{ item }}
              </AppPill>
            </div>
            <div v-else class="text-muted">暂无</div>
          </div>
          <div class="draft-card">
            <div class="question-title">明确排除项</div>
            <div v-if="opsStore.knowledgeDomainForm.assistantDraft.excludeTerms.length" class="pill-row">
              <AppPill v-for="item in opsStore.knowledgeDomainForm.assistantDraft.excludeTerms" :key="item" variant="warning">
                {{ item }}
              </AppPill>
            </div>
            <div v-else class="text-muted">暂无</div>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <AppButton variant="secondary" @click="closeDomainModal">关闭</AppButton>
      <AppButton
        variant="primary"
        :loading="savingDomain"
        :disabled="assistantLoading || !opsStore.knowledgeDomainForm.name.trim()"
        @click="saveDomain"
      >
        保存领域
      </AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.refine-row {
  margin-top: var(--space-lg);
}

.stats-row {
  display: flex;
  gap: var(--space-lg);
}

.refine-action-btn {
  min-width: 186px;
  width: 186px;
  flex-shrink: 0;
  white-space: nowrap;
}

.subtabs {
  display: flex;
  gap: var(--space-xs);
  margin-top: var(--space-lg);
  padding: var(--space-sm) 0;
}

.subtab-btn {
  height: 32px;
  padding: 0 var(--space-md);
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--text-muted);
  background: transparent;
  border-radius: var(--radius-md);
  transition: all var(--transition-fast);
}

.subtab-btn:hover {
  background: var(--bg-hover);
  color: var(--text);
}

.subtab-active {
  background: var(--bg-card);
  color: var(--text);
  border: 1px solid var(--border-strong);
}

.topics-list,
.job-item,
.pack-item,
.agent-pack-item,
.candidate-item {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  margin-bottom: var(--space-md);
}

.job-progress-grid {
  display: grid;
  gap: 4px;
  padding: var(--space-sm);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
}

.job-quality-gate {
  display: grid;
  gap: 4px;
  padding: var(--space-sm);
  border: 1px dashed var(--border);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--bg-panel) 88%, #f59e0b 12%);
}

.domain-modal {
  display: grid;
  gap: var(--space-lg);
}

.topic-create-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: var(--space-md);
  margin-top: var(--space-md);
  margin-bottom: var(--space-md);
}

.form-grid {
  display: grid;
  gap: var(--space-md);
}

.field-block {
  display: grid;
  gap: var(--space-sm);
}

.field-label,
.question-title {
  font-size: var(--text-sm);
  font-weight: 700;
}

.field-check {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.auto-refresh-row {
  display: grid;
  grid-template-columns: 160px 160px 160px;
  gap: var(--space-md);
}

.assistant-box {
  display: grid;
  gap: var(--space-md);
  padding: var(--space-lg);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
}

.assistant-header {
  display: flex;
  justify-content: space-between;
  gap: var(--space-lg);
  align-items: flex-start;
}

.history-list {
  display: grid;
  gap: var(--space-sm);
  max-height: 220px;
  overflow: auto;
}

.history-item,
.question-card,
.stream-card,
.draft-card {
  display: grid;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
}

.question-text,
.stream-preview,
.text-body {
  white-space: pre-wrap;
  line-height: 1.7;
}

.muted-card {
  background: var(--bg-hover);
}

.question-meta {
  font-size: var(--text-sm);
}

.draft-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-md);
}

.pill-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.evidence-list {
  display: grid;
  gap: var(--space-sm);
  max-height: 56vh;
  overflow: auto;
}

.evidence-item {
  display: grid;
  gap: var(--space-xs);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-sm);
  background: var(--bg-panel);
}

.pack-detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-md);
}

.pack-detail-card {
  display: grid;
  gap: var(--space-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  padding: var(--space-md);
}

.pack-markdown-card,
.pack-structured-card,
.pack-warning-card {
  grid-column: span 2;
}

.catalog-tree,
.card-list {
  display: grid;
  gap: var(--space-sm);
  max-height: 360px;
  overflow: auto;
}

.catalog-node,
.knowledge-card,
.claim-item {
  display: grid;
  gap: 4px;
  padding: var(--space-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
}

.knowledge-card {
  gap: var(--space-sm);
}

.claim-item {
  background: var(--bg-panel);
}

.pack-markdown-text {
  white-space: pre-wrap;
  line-height: 1.7;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: var(--space-sm);
  background: var(--bg-card);
}

.rejected-section {
  margin-top: var(--space-lg);
  display: grid;
  gap: var(--space-sm);
}

.rejected-item {
  background: color-mix(in srgb, var(--bg-panel) 90%, #d97706 10%);
}

.evidence-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-md);
}

.evidence-context {
  display: grid;
  gap: var(--space-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  background: var(--bg-panel);
  padding: var(--space-md);
  max-height: 56vh;
  overflow: auto;
}

.context-text {
  white-space: pre-wrap;
  line-height: 1.7;
}

@media (max-width: 960px) {
  .auto-refresh-row,
  .draft-grid,
  .topic-create-row {
    grid-template-columns: 1fr;
  }

  .evidence-layout {
    grid-template-columns: 1fr;
  }

  .pack-detail-grid {
    grid-template-columns: 1fr;
  }

  .pack-markdown-card,
  .pack-structured-card,
  .pack-warning-card {
    grid-column: auto;
  }

  .assistant-header {
    flex-direction: column;
  }
}
</style>
