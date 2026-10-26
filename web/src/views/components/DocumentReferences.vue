<template>
  <div v-if="references.length" class="document-references">
    <div class="reference-label">文档依据（点击查看对应版本）</div>
    <button v-for="item in references" :key="item.referenceId" type="button" class="reference-link" @click="openReference(item)">
      {{ item.documentTitle || '文档' }} · V{{ item.documentVersion }}
    </button>
    <section v-if="selected" class="reference-panel" role="dialog" aria-label="引用版本快照" data-testid="reference-panel">
      <button type="button" class="reference-close" @click="closeReference">关闭</button>
      <p v-if="loading" role="status">正在核验权限并读取引用…</p>
      <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="openReference(selected)">重试</button></p>
      <template v-else-if="snapshot">
        <strong>{{ snapshot.documentTitle || '文档' }} · V{{ snapshot.documentVersion }}</strong>
        <p data-testid="reference-version-status">{{ snapshot.status === 'CURRENT' ? '当前版本引用' : `历史版本引用（当前文档为 V${snapshot.currentVersion}）` }}</p>
        <p>以下为回答引用的原版本片段，仅供阅读。</p>
        <pre data-testid="reference-content">{{ snapshot.content }}</pre>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { docApi } from '../../api/document'
import { normalizeDocumentReferences } from '../../api/documentReferences'
const props = defineProps({ references: { type: Array, default: () => [] } })
const references = computed(() => normalizeDocumentReferences(props.references))
const selected = ref(null), snapshot = ref(null), error = ref(''), loading = ref(false)
let generation = 0
const closeReference = () => { generation++; selected.value = null; snapshot.value = null; error.value = ''; loading.value = false }
watch(() => props.references, closeReference)
onBeforeUnmount(closeReference)
const openReference = async (item) => {
  const requestGeneration = ++generation
  selected.value = item; snapshot.value = null; error.value = ''; loading.value = true
  try {
    const result = await docApi.getReference(item.documentId, item.referenceId)
    if (requestGeneration !== generation) return
    const data = result?.data
    if (!data || data.referenceId !== item.referenceId || data.documentId !== item.documentId
      || data.documentVersion !== item.documentVersion || data.segmentHash !== item.segmentHash
      || data.startOffset !== item.startOffset || data.endOffset !== item.endOffset
      || typeof data.content !== 'string' || !Number.isInteger(data.currentVersion)
      || !['CURRENT', 'HISTORICAL'].includes(data.status)
      || (data.status === 'CURRENT') !== (data.currentVersion === data.documentVersion)) throw new Error('Invalid reference snapshot')
    snapshot.value = data
  } catch (cause) {
    if (requestGeneration !== generation) return
    const status = cause?.response?.status
    error.value = status === 401 ? '登录已失效，请重新登录。'
      : [403, 404, 410].includes(status) ? '引用不可访问：文档已删除、不存在或当前账号无权查看。'
        : status === 409 ? '引用快照不完整，无法确认原始依据。'
          : '引用读取失败，未展示未经确认的内容。'
  } finally { if (requestGeneration === generation) loading.value = false }
}
</script>

<style scoped>
.document-references { margin-top: 12px; font-size: 13px; }
.reference-label { color: #646a73; margin-bottom: 6px; }
.reference-link { color: #245bdb; background: #f0f4ff; border: 1px solid #c9d6f4; border-radius: 5px; padding: 5px 8px; margin: 0 6px 6px 0; cursor: pointer; }
.reference-panel { border: 1px solid #c9d6f4; border-radius: 6px; background: #fff; padding: 14px; color: #242a35; }
.reference-close { float: right; }
.reference-panel pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; background: #f5f7fa; padding: 10px; max-height: 360px; overflow: auto; }
</style>
