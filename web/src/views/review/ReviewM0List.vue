<template>
  <main class="review-page">
    <header class="review-header"><router-link to="/dashboard">← 返回工作台</router-link><span class="mode-badge">本地处理</span></header>
    <section class="review-hero"><p class="eyebrow">材料准备 · 提交前检查</p><h1>材料预审</h1><p>确认你的材料清单，检查材料是否齐全、文件名是否符合要求。</p></section>
    <div v-if="error" class="notice error" role="alert">{{ error.message }} <small>{{ error.code }}</small></div>
    <form class="panel create-form" @submit.prevent="create">
      <label for="case-title">新建审查案例</label><div class="inline"><input id="case-title" v-model="title" required maxlength="200" placeholder="例如：2026 年奖学金申报材料" :disabled="creating"><button class="primary" :disabled="creating || !title.trim()">{{ creating ? '创建中…' : '创建案例' }}</button></div>
      <p class="muted">材料和规则保存在当前部署内。本轮采用手工确认规则，不向外部模型发送材料。</p>
    </form>
    <div class="section-title"><h2>我的案例</h2><button :disabled="loading" @click="load(false)">刷新列表</button></div>
    <p v-if="loading" role="status">正在读取案例…</p>
    <p v-else-if="!items.length && !error" class="empty">还没有案例。创建后即可添加规则与材料。</p>
    <div class="case-grid"><router-link v-for="item in items" :key="item.caseId" class="panel case-card" :to="`/review-m0/${encodeURIComponent(item.caseId)}`"><span class="mode-badge">{{ item.processingMode === 'LOCAL_ONLY' ? '本地处理' : '远程模式案例' }}</span><h3>{{ item.title }}</h3><p>{{ item.currentChecklistSnapshotId ? '已发布正式清单' : '等待确认清单' }} · {{ item.currentReportId ? '可查看当前报告' : '尚无当前报告' }}</p><small>更新于 {{ formatTime(item.updatedAt) }}</small><span class="open-case">打开案例 →</span></router-link></div>
    <button v-if="nextCursor" :disabled="loading" @click="load(true)">加载更多案例</button>
    <p class="scope-note">当前仅检查材料齐全性与文件命名，不评判申请资格、正文填写或成果真实性。</p>
  </main>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { reviewM0Api, reviewError } from '../../api/reviewM0'
import { createMutationKeys } from '../../composables/useReviewM0'
import './reviewM0.css'
const router = useRouter(), items = ref([]), nextCursor = ref(null), title = ref(''), loading = ref(false), creating = ref(false), error = ref(null)
const keys = createMutationKeys()
let alive = true
const formatTime = value => value ? new Date(value).toLocaleString() : '—'
async function load(more) {
  if (loading.value) return
  loading.value = true; error.value = null
  try {
    const page = await reviewM0Api.list(more ? nextCursor.value : null)
    if (!alive) return
    const merged = more ? [...items.value, ...page.items] : page.items
    items.value = [...new Map(merged.map(item => [item.caseId, item])).values()]; nextCursor.value = page.nextCursor
  } catch (failure) { if (alive) error.value = reviewError(failure) }
  finally { if (alive) loading.value = false }
}
async function create() {
  if (creating.value || !title.value.trim()) return
  const value = title.value.trim(); creating.value = true; error.value = null
  try { const result = await reviewM0Api.create(value, keys.get('create', value)); keys.complete('create', value); if (alive) await router.push(`/review-m0/${encodeURIComponent(result.caseId)}`) }
  catch (failure) { if (alive) error.value = reviewError(failure) }
  finally { if (alive) creating.value = false }
}
onMounted(() => load(false)); onBeforeUnmount(() => { alive = false })
</script>
