import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useQueryStore = defineStore('query', () => {
  const mode = ref<'qa' | 'search'>('qa')
  const queryText = ref('')
  const topK = ref(5)
  const excludeDevDocs = ref(true)
  const matchTypeFilter = ref('all')
  const loading = ref(false)
  const answer = ref<QaAnswer | null>(null)
  const searchResult = ref<SearchResult | null>(null)
  const error = ref('')
  const overviewModal = ref<DocOverview | null>(null)
  const loadingOverviewId = ref('')

  const canSubmit = computed(() => !!queryText.value.trim() && !loading.value)

  const normalizedStructuredAnswer = computed(() => {
    return answer.value?.structuredAnswer || answer.value?.structured_answer || null
  })

  interface StructuredEntry {
    key: string
    label: string
    value: string | number | undefined
  }

  const structuredEntries = computed<StructuredEntry[]>(() => {
    const value = normalizedStructuredAnswer.value
    if (!value) return []
    const labels: Record<string, string> = {
      subject: '主题',
      action: '动作',
      constraint: '约束',
      exception: '例外',
      indicator: '指标',
      value: '数值',
      unitName: '单位',
      unit_name: '单位',
      time: '时间',
      region: '地区',
    }
    return Object.entries(labels)
      .map(([key, label]) => {
        const v = value[key as keyof typeof value]
        return { key, label, value: typeof v === 'string' || typeof v === 'number' ? v : undefined }
      })
      .filter((item) => item.value !== undefined && item.value !== '')
  })

  const normalizedDocHits = computed(() => {
    return searchResult.value?.docHits || searchResult.value?.doc_hits || []
  })

  const normalizedEvidenceHits = computed(() => {
    return searchResult.value?.evidenceHits || searchResult.value?.evidence_hits || searchResult.value?.items || []
  })

  const filteredSearchItems = computed(() => {
    const items = normalizedEvidenceHits.value
    if (matchTypeFilter.value === 'all') return items
    return items.filter((item) => {
      const mt = item.matchType || item.match_type
      return mt === matchTypeFilter.value
    })
  })

  const setMode = (newMode: 'qa' | 'search') => {
    mode.value = newMode
    error.value = ''
    matchTypeFilter.value = 'all'
  }

  const reset = () => {
    answer.value = null
    searchResult.value = null
    error.value = ''
    overviewModal.value = null
  }

  return {
    mode,
    queryText,
    topK,
    excludeDevDocs,
    matchTypeFilter,
    loading,
    answer,
    searchResult,
    error,
    overviewModal,
    loadingOverviewId,
    canSubmit,
    normalizedStructuredAnswer,
    structuredEntries,
    normalizedDocHits,
    normalizedEvidenceHits,
    filteredSearchItems,
    setMode,
    reset,
  }
})

/* === Type Imports === */
import type { QaAnswer, SearchResult, DocOverview } from '@/types/query'