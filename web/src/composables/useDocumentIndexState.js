import { computed, onBeforeUnmount, ref } from 'vue'
import { docApi } from '../api/document'

export const INDEX_LABELS = Object.freeze({
  NOT_INDEXED: '尚未建立索引', QUEUED: '索引更新中（排队）', BUILDING: '索引更新中',
  READY: '当前保存版本可用于知识问答', FAILED: '索引更新失败，可重试',
  DISABLED: '索引已暂停', MIGRATION_REQUIRED: '旧文档需保存后建立可信索引', DELETED: '文档已删除',
})
export function normalizeIndexState(data) {
  if (!data || !Object.hasOwn(INDEX_LABELS, data.indexState) || !Number.isInteger(data.currentVersion)
      || typeof data.queryable !== 'boolean' || (data.indexedVersion !== null && !Number.isInteger(data.indexedVersion))
      || (data.queryable && (data.indexState !== 'READY' || data.indexedVersion !== data.currentVersion || !data.activeGenerationId))) {
    throw new Error('Invalid document index state')
  }
  return data
}
export function indexStateLabel(state) {
  if (state.errorCode === 'PARSE_FAILED') return '文档解析失败，请重试解析'
  if (state.errorCode === 'EMPTY_CONTENT') return '文档没有可索引的文字'
  if (state.indexState === 'READY' && !state.queryable) return '索引尚不可用于知识问答'
  if (state.indexState === 'FAILED' && state.retryable === false) return '索引更新失败'
  return INDEX_LABELS[state.indexState]
}
export function useDocumentIndexState(documentId, documentVersion, api = docApi) {
  const state = ref(null), error = ref(''), loading = ref(false)
  let requestGeneration = 0, timer = null, disposed = false
  const cancelTimer = () => { if (timer) clearTimeout(timer); timer = null }
  const invalidate = () => { requestGeneration++; cancelTimer(); state.value = null; error.value = ''; loading.value = false }
  const queryable = computed(() => state.value?.queryable === true && state.value.currentVersion === documentVersion.value)
  const label = computed(() => error.value || (!state.value ? '索引状态待确认'
    : state.value.currentVersion !== documentVersion.value ? '文档版本已变化，请重新读取；当前编辑版本不可用于知识问答'
      : state.value.indexState === 'READY' && !queryable.value ? '索引尚不可用于知识问答'
        : indexStateLabel(state.value)))
  const accept = (data) => {
    const normalized = normalizeIndexState(data)
    if (String(normalized.documentId) !== String(documentId)) throw new Error('Mismatched document')
    if (normalized.currentVersion < documentVersion.value) throw new Error('Stale index state')
    state.value = normalized
  }
  const refresh = async () => {
    cancelTimer()
    const generation = ++requestGeneration
    loading.value = true; error.value = ''
    try {
      const result = await api.getIndexState(documentId)
      if (disposed || generation !== requestGeneration) return
      accept(result?.data)
    } catch {
      if (disposed || generation !== requestGeneration) return
      state.value = null; error.value = '索引状态读取失败，当前可用性未知。'
    } finally {
      if (!disposed && generation === requestGeneration) {
        loading.value = false
        if (['QUEUED', 'BUILDING'].includes(state.value?.indexState)) timer = setTimeout(refresh, 3000)
      }
    }
  }
  onBeforeUnmount(() => { disposed = true; invalidate() })
  return { state, error, loading, queryable, label, refresh, invalidate }
}
