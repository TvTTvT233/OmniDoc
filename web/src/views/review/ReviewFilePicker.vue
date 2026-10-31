<template>
  <div class="file-picker">
    <label>选择账号内已有文件<select v-model="selectedId" :disabled="disabled || busy" @change="select"><option value="">请选择…</option><option v-for="file in files" :key="file.id" :value="file.id">{{ file.name }}</option></select></label>
    <div class="inline"><button type="button" :disabled="disabled || busy || page <= 1" @click="load(page - 1)">上一页文件</button><span class="muted">第 {{ page }} 页</span><button type="button" :disabled="disabled || busy || !hasMore" @click="load(page + 1)">下一页文件</button><button type="button" :disabled="disabled || busy" @click="load(1)">刷新文件库</button></div>
    <label>{{ label }}<input type="file" accept=".pdf,.docx,.xlsx,.jpg,.png,.zip" :disabled="disabled || busy" @change="upload"></label>
    <p class="muted">仅显示当前账号拥有的文件。旧文件若未记录账号归属，不会进入此列表；可以重新上传原件后使用。</p>
    <p v-if="busy" role="status">正在处理文件…</p><p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="selectedFile" class="muted">已选择：{{ selectedFile.name }}。点击下方按钮后才加入当前案例。</p>
  </div>
</template>
<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { reviewM0Api } from '../../api/reviewM0'
const props = defineProps({ label: { type: String, default: '上传文件' }, disabled: Boolean })
const emit = defineEmits(['selected'])
const files = ref([]), selectedId = ref(''), page = ref(1), hasMore = ref(false), busy = ref(false), error = ref('')
const selectedFile = computed(() => files.value.find(file => file.id === selectedId.value))
const normalize = file => ({ id: file.fileId, name: file.originalFileName })
let alive = true
function select() { emit('selected', selectedFile.value || null) }
async function list(target) {
  const result = await reviewM0Api.listFiles(target)
  if (!Array.isArray(result?.items) || typeof result?.hasMore !== 'boolean') throw new Error('Invalid owned file list')
  if (!alive) return
  files.value = result.items.map(normalize); page.value = target; hasMore.value = result.hasMore
}
async function load(target) {
  if (busy.value) return
  busy.value = true; error.value = ''; selectedId.value = ''; select()
  try { await list(target) }
  catch { if (alive) { files.value = []; hasMore.value = false; error.value = '账号文件库读取失败，请刷新重试。' } }
  finally { if (alive) busy.value = false }
}
async function upload(event) {
  const file = event.target.files?.[0]
  if (!file || props.disabled || busy.value) return
  if (!/\.(pdf|docx|xlsx|jpg|png|zip)$/i.test(file.name)) { error.value = '请选择 PDF、DOCX、XLSX、JPG、PNG 或 ZIP 文件。'; return }
  busy.value = true; error.value = ''; selectedId.value = ''; emit('selected', null)
  try {
    const value = await reviewM0Api.uploadFile(file)
    if (!value?.fileId) throw new Error('Missing file ID')
    if (!alive) return
    const added = normalize(value)
    try { await list(1) } catch { if (alive) { files.value = []; page.value = 1; hasMore.value = false; error.value = '文件已上传，列表刷新失败；可继续加入案例或刷新文件库。' } }
    if (!alive) return
    files.value = [added, ...files.value.filter(item => item.id !== added.id)]; selectedId.value = added.id; select()
    event.target.value = ''
  } catch { if (alive) error.value = '上传未完成，请重试；尚未将该文件加入案例。' }
  finally { if (alive) busy.value = false }
}
onMounted(() => load(1)); onBeforeUnmount(() => { alive = false })
</script>
