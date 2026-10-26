<template>
  <div>
    <div class="global-ai-fab" @click="toggleAiPanel">
      <el-icon size="24"><Cpu /></el-icon>
    </div>

    <el-drawer
        v-model="isAiPanelVisible"
        size="400px"
        :modal="false"
        :with-header="false"
        class="global-ai-drawer"
    >
      <div class="chat-layout">
        <div class="drawer-header">
          <div class="header-title">
            <div class="ai-icon-wrapper">
              <el-icon color="#fff" size="14"><Cpu /></el-icon>
            </div>
            <span>OmniDoc 全局助理</span>
          </div>

          <div class="header-actions">
            <el-select
                v-model="currentModel"
                size="small"
                placeholder="切换模型"
                style="width: 120px; margin-right: 8px;"
            >
              <el-option v-for="m in modelOptions" :key="m.code" :label="m.name" :value="m.code" />
            </el-select>

            <!-- 新对话按钮 -->
            <el-button
                link
                icon="Plus"
                data-testid="conversation-new"
                :disabled="isConversationBusy"
                @click="handleNewConversation"
                style="font-size: 16px; color: #3370ff; margin-right: 8px;"
                title="开始新对话"
            >
            </el-button>

            <el-button
                link
                icon="Close"
                @click="toggleAiPanel"
                style="font-size: 18px; color: #8f959e;"
            >
            </el-button>
          </div>
        </div>

        <div class="conversation-toolbar">
          <select
              class="conversation-select"
              :value="currentConvId || ''"
              :disabled="isConversationBusy"
              aria-label="选择历史会话"
              @change="handleConversationSelect"
          >
            <option value="" disabled>选择历史会话</option>
            <optgroup v-if="activeConversations.length" label="进行中">
              <option v-for="item in activeConversations" :key="item.conversationId" :value="item.conversationId">
                {{ item.title || '未命名会话' }}
              </option>
            </optgroup>
            <optgroup v-if="archivedConversations.length" label="已归档">
              <option v-for="item in archivedConversations" :key="item.conversationId" :value="item.conversationId">
                {{ item.title || '未命名会话' }}（已归档）
              </option>
            </optgroup>
          </select>
          <el-button
              v-if="activeConversation"
              :data-testid="activeConversation.status === 'ARCHIVED' ? 'conversation-restore' : 'conversation-archive'"
              link
              size="small"
              :disabled="isConversationBusy"
              @click="handleArchiveToggle"
          >
            {{ activeConversation.status === 'ARCHIVED' ? '恢复' : '归档' }}
          </el-button>
          <el-button
              v-if="activeConversation"
              data-testid="conversation-delete"
              link
              size="small"
              type="danger"
              :disabled="isConversationBusy"
              @click="handleDeleteConversation"
          >删除</el-button>
          <el-button
              v-if="activeHasMore"
              link
              size="small"
              :loading="isLoadingConversations"
              @click="loadMoreConversations('active')"
          >更多进行中</el-button>
          <el-button
              v-if="archivedHasMore"
              link
              size="small"
              :loading="isLoadingConversations"
              @click="loadMoreConversations('archived')"
          >更多归档</el-button>
        </div>

        <div class="chat-scroll-area">
          <el-button
              v-if="hasOlderMessages"
              data-testid="conversation-load-older"
              class="load-older-btn"
              link
              :loading="isLoadingMessages"
              @click="loadOlderMessages"
          >加载更早消息</el-button>
          <div class="message-row ai" v-if="chatHistory.length === 0">
            <div class="avatar-box ai-avatar">
              <el-icon size="16" color="#fff"><Cpu /></el-icon>
            </div>
            <div class="bubble ai-bubble">
              你好！我是 OmniDoc 全局助理。无论是管理云端文档、清理回收站，还是跨文件查阅资料，随时吩咐我。
            </div>
          </div>

          <div v-for="msg in chatHistory" :key="msg.id" :class="['message-row', msg.role]">
            <div class="avatar-box" :class="msg.role === 'user' ? 'user-avatar' : 'ai-avatar'">
              <el-icon size="16" color="#fff">
                <User v-if="msg.role === 'user'" />
                <Cpu v-else />
              </el-icon>
            </div>

            <div class="message-content">
              <div :class="['bubble', msg.role === 'user' ? 'user-bubble' : 'ai-bubble', 'preserve-format']">
                {{ msg.text }}
              </div>
              <DocumentReferences v-if="msg.role === 'ai'" :references="msg.references" />

              <div v-if="msg.actionResult" class="agent-file-card" @click="handleDownload(msg.actionResult)">
                <el-icon size="24" color="#3370ff"><Document /></el-icon>
                <div class="file-info">
                  <div class="file-name">{{ msg.actionResult.fileName || '生成的文档' }}</div>
                  <div class="file-desc">点击查看或下载</div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="isAiThinking" class="message-row ai">
            <div class="avatar-box ai-avatar">
              <el-icon size="16" color="#fff"><Cpu /></el-icon>
            </div>
            <div class="bubble ai-bubble thinking">
              <el-icon class="is-loading"><Loading /></el-icon> 正在调用智能体...
            </div>
          </div>
        </div>

        <div class="input-area">
          <div class="input-wrapper" :class="{ 'is-focused': isInputFocused }">
            <input
                v-model="userInput"
                class="custom-input"
                :placeholder="activeConversation?.status === 'ARCHIVED' ? '请先恢复该会话' : '发送全局指令，如：查阅资料、清理垃圾...'"
                :disabled="activeConversation?.status === 'ARCHIVED' || isConversationBusy"
                @keyup.enter="handleSend"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
            />
            <div class="send-btn" :class="{ active: userInput.trim().length > 0 }" @click="handleSend">
              <el-icon size="18"><Top /></el-icon>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi } from '../../api/agent'
import { aiApi } from '../../api/ai'
import { docApi } from '../../api/document.js'
import {
  CONVERSATION_ERROR,
  CONVERSATION_STATUS,
  normalizeConversationError,
} from '../../api/conversationContract.js'
import {
  CONVERSATION_ENTRY,
} from '../../composables/useConversationSession.js'
import {
  toConversationChatMessage,
  useConversationWorkspace,
} from '../../composables/useConversationWorkspace.js'
import { useGlobalAi } from '../../utils/useGlobalAi.js'
import { getAuthenticatedUserId } from '../../utils/jwt.js'
import DocumentReferences from './DocumentReferences.vue'

const route = useRoute()
const authenticatedUserId = getAuthenticatedUserId()
if (!authenticatedUserId) throw new Error('Authenticated conversation scope is unavailable')

const conversationWorkspace = useConversationWorkspace({
  userId: authenticatedUserId,
  entry: CONVERSATION_ENTRY.GLOBAL_ASSISTANT,
})
const { isAiPanelVisible, toggleAiPanel } = useGlobalAi()
const currentModel = ref('glm-5')
const modelOptions = ref([])
const isInputFocused = ref(false)
const isAiThinking = ref(false)
const isLifecycleBusy = ref(false)
const pendingPreview = ref(null)
const latestActionResult = ref(null)

const currentConvId = conversationWorkspace.activeConversationId
const activeConversation = conversationWorkspace.activeConversation
const activeConversations = conversationWorkspace.activeConversations
const archivedConversations = conversationWorkspace.archivedConversations
const activeHasMore = conversationWorkspace.activeHasMore
const archivedHasMore = conversationWorkspace.archivedHasMore
const hasOlderMessages = conversationWorkspace.hasOlderMessages
const isLoadingConversations = conversationWorkspace.isLoadingConversations
const isLoadingMessages = conversationWorkspace.isLoadingMessages
const userInput = computed({
  get: () => conversationWorkspace.session.draft.value,
  set: (value) => conversationWorkspace.setDraft(value),
})
const isConversationBusy = computed(() => isAiThinking.value
  || isLifecycleBusy.value
  || conversationWorkspace.isLoadingMessages.value)
const chatHistory = computed(() => {
  const items = conversationWorkspace.messages.value.map(toConversationChatMessage)
  if (latestActionResult.value) {
    const lastAssistant = items.findLastIndex((item) => item.role === 'ai')
    if (lastAssistant >= 0) items[lastAssistant] = {
      ...items[lastAssistant],
      actionResult: latestActionResult.value,
    }
  }
  if (pendingPreview.value) items.push(pendingPreview.value)
  return items
})

onMounted(async () => {
  const modelRequest = aiApi.getModels()
    .then((res) => {
      modelOptions.value = res.data
      currentModel.value = 'glm-5'
    })
    .catch(() => {
      modelOptions.value = [
        { code: 'glm-5', name: '智谱GLM-5', available: true },
        { code: 'qwen3.7-max-2026-05-20', name: '通义千问 3.7 Max', available: true },
      ]
      currentModel.value = 'glm-5'
    })
  await Promise.all([modelRequest, conversationWorkspace.initialize()])
  scrollToBottom()
})

const scrollToBottom = () => {
  nextTick(() => {
    const container = document.querySelector('.global-ai-drawer .chat-scroll-area')
    if (container) container.scrollTop = container.scrollHeight
  })
}

const conversationTitle = (content) => content.trim().slice(0, 40) || '新对话'

const handleSend = async () => {
  if (!userInput.value.trim() || isConversationBusy.value) return
  const draftBeforeSend = userInput.value
  let submission = null
  isAiThinking.value = true
  latestActionResult.value = null
  try {
    if (!currentConvId.value) {
      await conversationWorkspace.createConversation({ title: conversationTitle(draftBeforeSend) })
    }
    submission = conversationWorkspace.beginSubmission()
    pendingPreview.value = {
      id: `pending-${submission.clientRequestId}`,
      role: 'user',
      text: submission.content,
    }
    scrollToBottom()

    let pageContext = `用户当前所处页面路径：${route.path}。`
    const paperElement = document.querySelector('.paper')
    if (paperElement?.innerText) {
      pageContext += `\n当前页面文档内容：${paperElement.innerText.substring(0, 1500)}`
    }
    const res = await agentApi.executeTask({
      task: submission.content,
      model: currentModel.value,
      conversationId: submission.conversationId,
      clientRequestId: submission.clientRequestId,
      context: {
        isGlobalContext: true,
        pageContext,
      },
    })
    const agentResult = res.data
    latestActionResult.value = parseToolResults(agentResult?.toolResults)
    pendingPreview.value = null
    const completion = await conversationWorkspace.completeSubmission(submission.clientRequestId)
    if (completion.historyReloadFailed) {
      ElMessage.warning('任务已完成，但历史暂时未刷新；重新打开会话后可再次加载。')
    }
    scrollToBottom()

    if (agentResult?.status === 'action_required' && agentResult.pendingApproval) {
      await handleApproval(agentResult.pendingApproval, agentResult)
    }
  } catch (error) {
    pendingPreview.value = null
    if (submission) conversationWorkspace.markSubmissionFailed(error)
    const normalized = normalizeConversationError(error)
    if (normalized.code === CONVERSATION_ERROR.NOT_FOUND) {
      conversationWorkspace.invalidateActiveConversation()
      await conversationWorkspace.refreshConversationLists().catch(() => {})
      ElMessage.warning('当前会话已失效，草稿已保留。请新建或选择其他会话后重试。')
    } else if (normalized.code === CONVERSATION_ERROR.ARCHIVED) {
      ElMessage.warning('该会话已归档，请先恢复后再发送。')
    } else {
      ElMessage.error(normalized.message || '全局助理暂时无法响应，草稿已保留。')
    }
    await conversationWorkspace.reloadMessages().catch(() => {})
  } finally {
    isAiThinking.value = false
    scrollToBottom()
  }
}

const cancelApproval = async (approval) => {
  try {
    if (approval?.agentApprovalToken) {
      await agentApi.cancelApproval(approval.agentApprovalToken)
    }
  } catch {
    ElMessage.warning('审批取消状态同步失败，请勿重复确认旧操作。')
    return
  }
  ElMessage.info('操作已取消')
}

const handleApproval = async (approval, agentResult) => {
  const toolName = approval.toolName
  const params = approval.params || {}
  try {
    if (toolName === 'document-delete') {
      const fileName = params.objectName || ''
      const keyword = fileName.replace('.docx', '').replace('.doc', '')
      const searchRes = await docApi.searchDocs(keyword)
      const docs = searchRes.data?.data || []
      if (!docs.length) {
        ElMessage.warning(`未找到文档：${fileName}`)
        await cancelApproval(approval)
        return
      }
      const targetDoc = docs[0]
      await ElMessageBox.confirm(
        `即将删除文档：${targetDoc.title}\n\n确认继续吗？`,
        '删除确认',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
      await executeApproval(approval, agentResult, {
        documentId: targetDoc.id,
        bucketName: targetDoc.bucketName || 'documents',
        objectName: targetDoc.objectName || targetDoc.fileName || params.objectName,
      })
      return
    }

    await ElMessageBox.confirm(
      `即将执行操作：${toolName}\n\n确认继续吗？`,
      '操作确认',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
    await executeApproval(approval, agentResult)
  } catch (error) {
    if (error === 'cancel' || error === 'close' || error?.message === 'cancel') {
      await cancelApproval(approval)
      return
    }
    ElMessage.error(error?.message || '审批操作失败')
  }
}

const executeApproval = async (approval, agentResult, extraParams = {}) => {
  isAiThinking.value = true
  try {
    const confirmRes = await agentApi.confirmApproval({
      task: agentResult.task,
      conversationId: currentConvId.value,
      model: currentModel.value,
      agentApprovalToken: approval.agentApprovalToken,
      ragEnabled: true,
      dryRun: false,
      maxIterations: 3,
      maxToolCalls: 8,
      knowledgeBaseId: 'default',
      autoReplan: true,
      returnIntermediateSteps: true,
      context: {
        ...agentResult.context,
        agentApprovalToken: approval.agentApprovalToken,
        documentId: extraParams.documentId,
        bucketName: 'omnidoc',
        ...extraParams,
      },
    })
    latestActionResult.value = parseToolResults(confirmRes.data?.toolResults)
    await conversationWorkspace.reloadMessages()
    ElMessage.success('操作已完成')
    scrollToBottom()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '操作失败')
  } finally {
    isAiThinking.value = false
  }
}

const parseToolResults = (toolResults) => {
  if (!Array.isArray(toolResults)) return null
  for (const result of toolResults) {
    if (result.status === 'error') continue
    const data = result.data || {}
    if (result.toolName?.includes('ppt') || data.htmlContent || data.fileName?.endsWith('.html')) {
      return {
        type: 'ppt',
        title: data.title || 'PPT演示文稿',
        slideCount: data.slideCount || 0,
        htmlContent: data.htmlContent || null,
        fileName: data.fileName || null,
      }
    }
    if (data.fileName?.endsWith('.docx') || data.objectName?.endsWith('.docx')) {
      return {
        type: 'doc',
        fileName: data.fileName || data.objectName,
        objectName: data.objectName || data.fileName,
        bucketName: 'omnidoc',
        url: data.url || data.downloadUrl || null,
      }
    }
    if (data.fileName || data.url) {
      return {
        type: 'file',
        fileName: data.fileName,
        url: data.url || data.downloadUrl,
      }
    }
  }
  return null
}

const handleDownload = (actionResult) => {
  if (!actionResult) return
  if (actionResult.type === 'ppt') {
    if (!actionResult.htmlContent) {
      ElMessage.warning('PPT 内容为空，无法下载')
      return
    }
    const blob = new Blob([actionResult.htmlContent], { type: 'text/html;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${actionResult.title || 'PPT'}.html`
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    URL.revokeObjectURL(url)
    ElMessage.success('PPT 下载成功')
    return
  }
  if (actionResult.url) {
    window.open(actionResult.url, '_blank')
  } else if (actionResult.objectName || actionResult.fileName) {
    const name = actionResult.objectName || actionResult.fileName
    window.location.href = `/api/skills/file/download?bucket=omnidoc&objectName=${encodeURIComponent(name)}`
  } else {
    ElMessage.warning('无法获取文件下载地址')
  }
}

const handleConversationSelect = async (event) => {
  const conversationId = event.target.value
  if (!conversationId || conversationId === currentConvId.value || isConversationBusy.value) return
  isLifecycleBusy.value = true
  try {
    latestActionResult.value = null
    await conversationWorkspace.openConversation(conversationId)
    scrollToBottom()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  } finally {
    isLifecycleBusy.value = false
  }
}

const handleNewConversation = async () => {
  if (isConversationBusy.value) return
  isLifecycleBusy.value = true
  try {
    if (currentConvId.value) {
      await ElMessageBox.confirm(
        '将创建一个独立的新对话；当前会话会保留在历史中。',
        '开始新对话',
        { confirmButtonText: '创建', cancelButtonText: '取消', type: 'info' },
      )
    }
    await conversationWorkspace.createConversation({ title: '新对话' })
    latestActionResult.value = null
    ElMessage.success('新对话已创建')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && error?.message !== 'cancel') {
      ElMessage.error(normalizeConversationError(error).message)
    }
  } finally {
    isLifecycleBusy.value = false
  }
}

const handleArchiveToggle = async () => {
  if (!activeConversation.value || isConversationBusy.value) return
  isLifecycleBusy.value = true
  try {
    if (activeConversation.value.status === CONVERSATION_STATUS.ARCHIVED) {
      await conversationWorkspace.restoreConversation()
      ElMessage.success('会话已恢复')
    } else {
      await conversationWorkspace.archiveConversation()
      ElMessage.success('会话已归档')
    }
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  } finally {
    isLifecycleBusy.value = false
  }
}

const handleDeleteConversation = async () => {
  if (!activeConversation.value || isConversationBusy.value) return
  isLifecycleBusy.value = true
  try {
    await ElMessageBox.confirm(
      `确定永久删除“${activeConversation.value.title || '未命名会话'}”吗？此操作无法恢复。`,
      '永久删除会话',
      {
        confirmButtonText: '永久删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      },
    )
    await conversationWorkspace.deleteConversation()
    latestActionResult.value = null
    ElMessage.success('会话已永久删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && error?.message !== 'cancel') {
      ElMessage.error(normalizeConversationError(error).message)
    }
  } finally {
    isLifecycleBusy.value = false
  }
}

const loadOlderMessages = async () => {
  try {
    await conversationWorkspace.loadOlderMessages()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  }
}

const loadMoreConversations = async (status) => {
  try {
    if (status === 'archived') await conversationWorkspace.loadMoreArchivedConversations()
    else await conversationWorkspace.loadMoreActiveConversations()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  }
}
</script>

<style scoped>
:deep(.el-drawer__body) { padding: 0; overflow: hidden; }

/* 悬浮球 */
.global-ai-fab {
  position: fixed; bottom: 40px; right: 40px; width: 52px; height: 52px;
  border-radius: 50%; background: #3370ff; color: white;
  display: flex; justify-content: center; align-items: center;
  box-shadow: 0 4px 16px rgba(51, 112, 255, 0.3); cursor: pointer; z-index: 9999; transition: all 0.25s;
}
.global-ai-fab:hover { transform: scale(1.06); background: #2b5cd9; }

.chat-layout { display: flex; flex-direction: column; height: 100%; background: #f4f5f7; }

/* 顶部 Header */
.drawer-header {
  height: 56px; padding: 0 16px; background: #fff;
  display: flex; justify-content: space-between; align-items: center;
  border-bottom: 1px solid #edeef0; z-index: 10;
}
.header-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 600; color: #1f2329; }
.ai-icon-wrapper {
  width: 24px; height: 24px; background: #3370ff; border-radius: 6px;
  display: flex; justify-content: center; align-items: center;
}
.header-actions {
  display: flex;
  align-items: center;
}

.conversation-toolbar {
  min-height: 42px;
  padding: 6px 12px;
  background: #fff;
  border-bottom: 1px solid #edeef0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.conversation-select {
  flex: 1;
  min-width: 0;
  height: 30px;
  border: 1px solid #dee0e3;
  border-radius: 6px;
  padding: 0 8px;
  color: #1f2329;
  background: #fff;
}
.load-older-btn { align-self: center; }

/* 滚动区域 */
.chat-scroll-area { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }

.message-row { display: flex; gap: 12px; align-items: flex-start; }
.message-row.user { flex-direction: row-reverse; }
.message-content { max-width: 78%; display: flex; flex-direction: column; gap: 6px; }

.avatar-box {
  width: 32px; height: 32px; border-radius: 50%;
  display: flex; justify-content: center; align-items: center; flex-shrink: 0;
}
.ai-avatar { background: #3370ff; box-shadow: 0 2px 6px rgba(51, 112, 255, 0.2); }
.user-avatar { background: #646a73; box-shadow: 0 2px 6px rgba(100, 106, 115, 0.2); }

/* 气泡 */
.bubble { padding: 10px 14px; font-size: 14px; line-height: 1.5; border-radius: 8px; word-break: break-all; }
.user-bubble { background: #3370ff; color: #fff; border-top-right-radius: 2px; }
.ai-bubble { background: #fff; color: #1f2329; border-top-left-radius: 2px; border: 1px solid #e8eaec; }
.ai-bubble.thinking { color: #8f959e; display: flex; align-items: center; gap: 6px; background: #fafafa; }

/* 文件卡片 */
.agent-file-card {
  display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid #dde0e3;
  padding: 10px 14px; border-radius: 6px; cursor: pointer; transition: all 0.2s;
}
.agent-file-card:hover { border-color: #3370ff; background: #fdfeff; }
.file-info .file-name { font-weight: 600; font-size: 13px; color: #1f2329; }
.file-info .file-desc { font-size: 11px; color: #8f959e; margin-top: 2px; }

/* 输入区 */
.input-area { background: #fff; padding: 14px 16px; border-top: 1px solid #edeef0; }
.input-wrapper {
  display: flex; align-items: center; background: #f4f5f7; border-radius: 20px;
  padding: 4px 4px 4px 14px; border: 1px solid transparent; transition: all 0.2s;
}
.input-wrapper.is-focused { border-color: #3370ff; background: #fff; box-shadow: 0 0 0 2px rgba(51,112,255,0.08); }
.custom-input { flex: 1; border: none; background: transparent; outline: none; font-size: 14px; color: #1f2329; }
.send-btn {
  width: 28px; height: 28px; border-radius: 50%; background: #dee0e3; color: #fff;
  display: flex; justify-content: center; align-items: center; cursor: not-allowed; transition: all 0.2s;
}
.send-btn.active { background: #3370ff; cursor: pointer; }
.send-btn.active:hover { background: #2b5cd9; }
.preserve-format { white-space: pre-wrap !important; }
.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.header-actions .el-button {
  padding: 4px;
}

</style>
