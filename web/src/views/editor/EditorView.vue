<template>
  <!-- ================= 模式 A：全局沉浸式 AI 对话模式 (对标飞书 aily) ================= -->
  <div v-if="isChatMode" class="feishu-chat-layout">
    <!-- 左侧会话历史导航 -->
    <aside class="chat-sidebar">
      <div class="sidebar-top">
        <div class="brand-back" @click="$router.push('/dashboard')">
          <el-icon><ArrowLeft /></el-icon> 返回工作台
        </div>
        <el-button class="new-chat-btn" data-testid="conversation-new" icon="Plus" plain :disabled="isConversationBusy" @click="startNewChat">开启新话题</el-button>

        <div class="history-group">
          <div class="group-title">历史会话</div>
          <div
              v-for="item in activeConversations"
              :key="item.conversationId"
              :data-testid="`conversation-item-${item.conversationId}`"
              :data-conversation-id="item.conversationId"
              :class="['history-item', { active: item.conversationId === currentConvId, disabled: isConversationBusy }]"
              :aria-disabled="isConversationBusy ? 'true' : 'false'"
              @click="openConversationFromHistory(item.conversationId)"
          >
            <el-icon><ChatLineRound /></el-icon>
            <span class="text">{{ item.title || '未命名会话' }}</span>
          </div>
          <div
              v-for="item in archivedConversations"
              :key="item.conversationId"
              :data-testid="`conversation-item-${item.conversationId}`"
              :data-conversation-id="item.conversationId"
              :class="['history-item', 'archived', { active: item.conversationId === currentConvId, disabled: isConversationBusy }]"
              :aria-disabled="isConversationBusy ? 'true' : 'false'"
              @click="openConversationFromHistory(item.conversationId)"
          >
            <el-icon><ChatLineRound /></el-icon>
            <span class="text">{{ item.title || '未命名会话' }}（已归档）</span>
          </div>
          <el-button v-if="activeHasMore" link :loading="isLoadingConversations" @click="loadMoreConversationList('active')">更多进行中</el-button>
          <el-button v-if="archivedHasMore" link :loading="isLoadingConversations" @click="loadMoreConversationList('archived')">更多归档</el-button>
        </div>
      </div>
      <div class="sidebar-bottom">
        <div class="user-profile">
          <el-avatar :size="30" style="background-color: #3370ff; font-weight: bold; color: white;">
            {{ currentUserName.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="username">{{ currentUserName }}</span>
        </div>
      </div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
      <header class="chat-header">
        <div class="header-inner">
          <span class="title">{{ chatTitle }}</span>
          <div class="header-actions">
            <el-button v-if="activeConversation" :data-testid="activeConversation.status === 'ARCHIVED' ? 'conversation-restore' : 'conversation-archive'" link :disabled="isConversationBusy" @click="archiveOrRestoreConversation">
              {{ activeConversation.status === 'ARCHIVED' ? '恢复' : '归档' }}
            </el-button>
            <el-button v-if="activeConversation" data-testid="conversation-delete" icon="Delete" link type="danger" :disabled="isConversationBusy" @click="deleteCurrentConversation">删除</el-button>
          </div>
        </div>
      </header>

      <!-- 聊天内容滚动区 -->
        <div class="chat-scroll-area" ref="globalChatRef">
          <div class="chat-content-container">
          <el-button v-if="hasOlderMessages" data-testid="conversation-load-older" link :loading="isLoadingMessages" @click="loadOlderConversationMessages">加载更早消息</el-button>
          <div v-if="chatHistory.length === 0" class="empty-conversation">Hi！我是 OmniDoc 智能伙伴，今天需要我帮你完成什么工作？</div>
          <div v-for="msg in chatHistory" :key="msg.id" :class="['message-row', msg.role]">
            <div class="avatar-col" v-if="msg.role === 'ai'">
              <el-avatar :size="36" src="https://api.dicebear.com/7.x/avataaars/svg?seed=AIAssistant" class="ai-avatar" />
            </div>
            <div class="message-content-col">
              <div v-if="msg.role === 'user'" class="user-bubble preserve-format">{{ msg.text }}</div>
              <div v-else class="ai-structured-card">
                <div class="card-body preserve-format">{{ msg.text }}</div>

                <div v-if="msg.actionResult" class="agent-action-box" style="margin-top: 16px;">
                  <el-divider border-style="dashed" style="margin: 12px 0;" />
                  <div style="font-size: 13px; color: #8f959e; margin-bottom: 10px; display: flex; align-items: center; gap: 6px;">
                    <el-icon><Check /></el-icon> 任务执行成功
                  </div>

                  <div v-if="msg.actionResult.type === 'document-write'" class="doc-write-card"
                       style="display: flex; align-items: center; gap: 12px; background: #f4f9f4; padding: 12px 16px; border-radius: 8px;">
                    <el-icon size="28" color="#35a853"><Document /></el-icon>
                    <div>
                      <div style="font-weight: 600; font-size: 14px; color: #1f2329;">已写入当前文档</div>
                      <div style="font-size: 12px; color: #6b7280; margin-top: 4px;">{{ msg.actionResult.changeLog || 'AI 内容已应用，尚未保存' }}</div>
                    </div>
                  </div>

                  <div v-else class="file-card" @click="handleDownloadAgentFile(msg.actionResult)"
                       style="display: flex; align-items: center; gap: 12px; background: #f4f5f7; padding: 12px 16px; border-radius: 8px; cursor: pointer; transition: background 0.2s;">
                    <el-icon size="28" color="#3370ff"><Document /></el-icon>
                    <div>
                      <div style="font-weight: 600; font-size: 14px; color: #1f2329;">
                        {{ msg.actionResult.fileName || '生成的文档' }}
                      </div>
                      <div style="font-size: 12px; color: #8f959e; margin-top: 4px;">点击查看或下载</div>
                    </div>
                  </div>
                </div>

              </div>
            </div>
          </div>

          <!-- 思考中状态 -->
          <div v-if="isAiThinking" class="message-row ai">
            <div class="avatar-col"><el-avatar :size="36" src="https://api.dicebear.com/7.x/avataaars/svg?seed=AIAssistant" /></div>
            <div class="message-content-col">
              <div class="ai-structured-card thinking">
                <el-icon class="is-loading"><Loading /></el-icon> 正在执行：搜索知识库并生成分析...
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部悬浮输入区 (支持 RAG 切换) -->
      <div class="chat-input-container">
        <div class="input-wrapper-inner" :class="{ 'is-focused': isInputFocused, 'rag-active': isRagMode }">
          <!-- 模式切换：气泡/书本图标 -->
          <el-tooltip :content="isRagMode ? '当前：基于云端文档库问答' : '当前：普通对话模式'" placement="top">
            <el-icon class="mode-icon" @click="isRagMode = !isRagMode">
              <Collection v-if="isRagMode" /><ChatDotRound v-else />
            </el-icon>
          </el-tooltip>

          <el-icon class="plus-icon"><Plus /></el-icon>
          <input
              v-model="userMsg"
              class="chat-input"
              placeholder="问问我关于工作的任何指令..."
              :disabled="activeConversation?.status === 'ARCHIVED' || isConversationBusy"
              @keyup.enter="handleSend"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
          />
          <div class="input-right">
            <el-icon class="mic-icon"><Microphone /></el-icon>
            <div class="send-btn-circle" :class="{ 'active': userMsg.length > 0 }" @click="handleSend">
              <el-icon size="20"><Top /></el-icon>
            </div>
          </div>
        </div>
        <div class="ai-hint">AI 可能生成错误内容，仅供参考</div>
      </div>
    </main>
  </div>

  <!-- ================= 模式 B：传统的文档阅读模式 (格式保护 + 悬浮球 + 优化侧边栏) ================= -->
  <div v-else class="editor-page">
    <header class="toolbar">
      <div class="left">
        <el-button icon="ArrowLeft" link @click="$router.push('/dashboard')"></el-button>
        <el-divider direction="vertical" />
        <span class="doc-title">{{ docName }}</span>
        <span class="save-status">{{ saveStatusText }}</span>
        <el-button circle icon="Clock" size="small" style="margin-left:10px;" @click="openHistoryDrawer"></el-button>
      </div>
      <div class="header-right">
        <el-button type="primary" size="small" round icon="Check" @click="handleManualSave" :loading="isSaving" :disabled="!canEditDocument">手动保存</el-button>
        <el-button size="small" round icon="Download" @click="handleDownload">下载最新版</el-button>
        <el-button type="primary" size="small" round icon="Share">协作</el-button>
        <el-avatar :size="30" style="background-color: #3370ff; font-weight: bold; color: white; margin-left:10px;">{{ currentUserName.charAt(0).toUpperCase() }}</el-avatar>
      </div>
    </header>

    <div class="workspace">
      <aside class="left-sidebar">
        <div class="sidebar-section">
          <div class="section-title"><el-icon><DataLine /></el-icon> 文本分析</div>
          <p v-if="textStatsLoading" role="status">正在分析文本…</p>
          <p v-else-if="textStatsError" role="alert">{{ textStatsError }} <el-button link @click="analyzeDocumentText">重试分析</el-button></p>
          <div v-else-if="textStats" class="stats-grid">
            <div class="stat-item"><span class="num">{{ textStats.totalCharacters || 0 }}</span><span class="desc">字数</span></div>
            <div class="stat-item"><span class="num">{{ textStats.lines || 0 }}</span><span class="desc">段落</span></div>
            <div class="stat-item"><span class="num">{{ textStats.chineseCharacters || 0 }}</span><span class="desc">中文字符</span></div>
            <div class="stat-item"><span class="num">{{ textStats.punctuations || 0 }}</span><span class="desc">标点</span></div>
          </div>
        </div>

        <div class="sidebar-section" v-if="aiSummary || keywordsError || summaryError || keywords.length">
          <div class="section-title"><el-icon><Document /></el-icon> ✨ 智能摘要</div>
          <p v-if="summaryError" role="alert">{{ summaryError }} <el-button link @click="generateDocSummary(currentDocumentText())">重试摘要</el-button></p>
          <div class="summary-text preserve-format">{{ aiSummary }}</div>
          <div class="keywords-area" v-loading="keywordsLoading">
            <p v-if="keywordsError" role="alert">{{ keywordsError }} <el-button link @click="refreshKeywords(currentDocumentText())">重试关键词</el-button></p>
            <el-tag
                v-for="(tag, index) in keywords"
                :key="index"
                class="keyword-tag"
                size="small"
                round
                effect="light"
                :type="['', 'success', 'warning', 'danger', 'info'][index % 5]"
            ># {{ tag }}</el-tag>
          </div>
        </div>

        <div class="sidebar-section" style="margin-top: auto;"> <CollaboratePanel v-if="canEditDocument" :documentId="docId" :userId="currentUserId" :userName="currentUserName" />
        </div>
      </aside>

      <main class="editor-main" v-loading="docLoading" @scroll="handleScroll">
        <div class="paper-container">
          <p v-if="docError" class="document-load-error" role="alert">{{ docError }} <el-button link @click="loadDocData">重试读取文档</el-button></p>
          <div class="paper preserve-format" :contenteditable="canEditDocument" v-html="docContent" @mouseup="handleTextSelection" @input="handleInput"></div>
        </div>

        <transition name="el-zoom-in-center">
          <div v-if="showAiBall" class="ai-float-ball" :style="ballStyle">
            <div class="ball-inner">
              <div class="menu-opt" @click.stop="askAiWithContext('润色')"><el-icon><MagicStick /></el-icon> 润色</div>
              <el-divider direction="vertical" />
              <div class="menu-opt" @click.stop="askAiWithContext('纠错')"><el-icon><Aim /></el-icon> 纠错</div>
              <div class="ball-arrow"></div>
            </div>
          </div>
        </transition>
      </main>

      <aside class="ai-sidebar expanded-ai">
        <div class="ai-sidebar-top">
          <div class="ai-header">
            <span style="display: flex; align-items: center; gap: 6px;">
              <el-icon color="#409EFF" size="18"><MagicStick /></el-icon> OmniDoc 灵感助理
            </span>
            <el-select v-model="currentModel" size="small" placeholder="切换模型" style="width: 130px;" @change="handleModelChange">
              <el-option v-for="m in modelOptions" :key="m.code" :label="m.name" :value="m.code" :disabled="m.available === false" />
            </el-select>
            <span v-if="modelsError" role="alert">{{ modelsError }} <el-button link @click="fetchModels">重试模型列表</el-button></span>
          </div>
          <div class="editor-conversation-bar">
            <select :value="currentConvId || ''" :disabled="isConversationBusy" aria-label="选择编辑器会话" @change="handleEditorConversationSelect">
              <option value="" disabled>选择历史会话</option>
              <option v-for="item in activeConversations" :key="item.conversationId" :value="item.conversationId">
                {{ item.title || '未命名会话' }}
              </option>
              <option v-for="item in archivedConversations" :key="item.conversationId" :value="item.conversationId">
                {{ item.title || '未命名会话' }}（已归档）
              </option>
            </select>
            <el-button link icon="Plus" title="新对话" :disabled="isConversationBusy" @click="startNewChat" />
            <el-button v-if="activeConversation" link :disabled="isConversationBusy" @click="archiveOrRestoreConversation">
              {{ activeConversation.status === 'ARCHIVED' ? '恢复' : '归档' }}
            </el-button>
            <el-button v-if="activeConversation" link type="danger" icon="Delete" title="永久删除" :disabled="isConversationBusy" @click="deleteCurrentConversation" />
          </div>
        </div>

        <div class="chat-area" ref="sidebarChatRef">
          <el-button v-if="hasOlderMessages" link :loading="isLoadingMessages" @click="loadOlderConversationMessages">加载更早消息</el-button>
          <div v-if="chatHistory.length === 0" class="empty-conversation">你好！你可以向我提问，或选中文本进行润色。</div>
          <div v-for="msg in chatHistory" :key="msg.id" :class="['chat-bubble', msg.role]">
            <div class="preserve-format">{{ msg.text }}</div>

            <div v-if="msg.actionResult" class="agent-action-box" style="margin-top: 10px;">
              <el-divider border-style="dashed" style="margin: 8px 0;" />
              <div style="font-size: 12px; color: #8f959e; margin-bottom: 8px; display: flex; align-items: center; gap: 4px;">
                <el-icon><Check /></el-icon> 执行结果
              </div>
              <div v-if="msg.actionResult.type === 'document-write'" class="doc-write-card"
                   style="display: flex; align-items: center; gap: 10px; background: #f4f9f4; padding: 10px; border-radius: 6px;">
                <el-icon size="24" color="#35a853"><Document /></el-icon>
                <div>
                  <div style="font-weight: bold; font-size: 13px; color: #1f2329;">已写入当前文档</div>
                  <div style="font-size: 12px; color: #6b7280; margin-top: 2px;">{{ msg.actionResult.changeLog || 'AI 内容已应用，尚未保存' }}</div>
                </div>
              </div>
              <div v-else class="file-card" @click="handleDownloadAgentFile(msg.actionResult)"
                   style="display: flex; align-items: center; gap: 10px; background: #f4f5f7; padding: 10px; border-radius: 6px; cursor: pointer;">
                <el-icon size="24" color="#3370ff"><Document /></el-icon>
                <div>
                  <div style="font-weight: bold; font-size: 13px; color: #1f2329;">{{ msg.actionResult.fileName || '生成的文档' }}</div>
                  <div style="font-size: 12px; color: #8f959e; margin-top: 2px;">点击下载</div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="isAiThinking" class="chat-bubble ai thinking">
            <el-icon class="is-loading"><Loading /></el-icon> AI 正在为您生成...
          </div>
        </div>

        <div class="input-area">
          <div class="selected-context" v-if="selectedText">已选：{{ selectedText.substring(0, 15) }}...</div>
          <div class="input-actions" style="margin-bottom: 8px; display: flex; justify-content: space-between; align-items: center;">
            <el-switch v-model="isRagMode" active-text="全局 RAG" size="small" />
            <el-button link size="small" type="primary" icon="Setting" @click="openRagSettings">知识分段</el-button>
          </div>
          <el-input v-model="userMsg" type="textarea" :rows="3" placeholder="下达任意指令，如生成PPT、润色、总结..." :disabled="activeConversation?.status === 'ARCHIVED' || isConversationBusy" @keyup.enter.native="handleSend" resize="none" />
          <el-button type="primary" class="send-btn" :loading="isAiThinking" :disabled="activeConversation?.status === 'ARCHIVED' || isConversationBusy" @click="handleSend" icon="Position">发送给 AI</el-button>
        </div>
      </aside>

      <el-drawer v-model="showHistory" title="文档历史版本" size="350px" direction="rtl">
        <el-timeline v-if="versionList.length > 0">
          <el-timeline-item v-for="ver in versionList" :key="ver.id" :timestamp="new Date(ver.createTime).toLocaleString()" placement="top">
            <el-card shadow="hover" class="version-card">
              <p>版本号：V{{ ver.versionNumber }}</p>
              <el-button size="small" type="primary" plain style="margin-top:10px" :disabled="!canEditDocument" @click="handleRestore(ver.versionNumber)">恢复此版本</el-button>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无历史记录" />
      </el-drawer>
    </div>
  </div>

  <!-- RAG 高级分段管理抽屉 -->
  <el-drawer v-model="ragDrawerVisible" title="🧠 RAG 知识切片管理" size="400px">
    <!-- 重新索引操作区 -->
    <div style="margin-bottom: 20px;">
      <h4 style="margin-bottom: 10px;">重新构建向量索引</h4>
      <div style="display: flex; gap: 10px;">
        <el-select v-model="selectedStrategy" size="small" style="flex: 1;">
          <el-option v-for="(name, key) in strategyMap" :key="key" :label="name" :value="key" />
        </el-select>
        <el-button type="primary" size="small" :loading="indexing" :disabled="!selectedStrategy || !canEditDocument" @click="doIndexDocument">执行分段</el-button>
      </div>
      <p v-if="strategyError" role="alert">{{ strategyError }} <el-button link @click="loadSegmentStrategies">重试加载策略</el-button></p>
      <p v-if="indexError" role="alert">{{ indexError }}</p>
      <p v-if="indexSucceeded" role="status">索引请求已完成。</p>
    </div>

    <el-divider />

    <!-- 当前分段预览区 -->
    <h4 style="margin-bottom: 10px; display: flex; justify-content: space-between;">
      <span>当前文档分段明细</span>
      <el-tag v-if="!segmentsError && !segmentsLoading" size="small" type="info">共 {{ segments.length }} 段</el-tag>
    </h4>
    <p v-if="segmentsLoading" role="status">正在加载分段…</p>
    <p v-if="segmentsError" role="alert">{{ segmentsError }} <el-button link @click="loadDocumentSegments">重试加载分段</el-button></p>
    <el-collapse v-if="segments.length > 0" accordion>
      <el-collapse-item v-for="(seg, idx) in segments" :key="idx" :title="`分片 #${idx + 1}`" :name="idx">
        <div style="font-size: 12px; color: #666; white-space: pre-wrap; background: #f5f7fa; padding: 8px; border-radius: 4px;">
          {{ seg.content }}
        </div>
      </el-collapse-item>
    </el-collapse>
    <el-empty v-else description="暂未获取到分段信息" :image-size="60" />
  </el-drawer>

</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fileApi } from '../../api/file'
import { userApi } from '../../api/user'
import { docApi } from '../../api/document'
import { aiApi } from '../../api/ai'
import { agentApi } from '../../api/agent'
import request from '../../utils/request'
import CollaboratePanel from '../../collaboration/CollaboratePanel.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  CONVERSATION_ERROR,
  CONVERSATION_STATUS,
  normalizeConversationError,
} from '../../api/conversationContract.js'
import { CONVERSATION_ENTRY } from '../../composables/useConversationSession.js'
import {
  toConversationChatMessage,
  useConversationWorkspace,
} from '../../composables/useConversationWorkspace.js'
import { getAuthenticatedUserId } from '../../utils/jwt.js'
const route = useRoute(); const router = useRouter(); const docId = route.params.id

const authenticatedUserId = getAuthenticatedUserId()
if (!authenticatedUserId) throw new Error('Authenticated conversation scope is unavailable')
const conversationWorkspace = useConversationWorkspace({
  userId: authenticatedUserId,
  entry: CONVERSATION_ENTRY.EDITOR,
})


// ===== 状态定义 =====
const isChatMode = computed(() => docId === 'chat-mode')
const chatTitle = computed(() => conversationWorkspace.activeConversation.value?.title || '新对话'); const isInputFocused = ref(false); const isSaving = ref(false); const saveStatusText = ref('已同步')
const docName = ref(sessionStorage.getItem('currentDocName') || '未命名文档'); const currentUserName = ref('User')
const currentUserId = ref(authenticatedUserId)
const docContent = ref(''); const docLoading = ref(false); const aiSummary = ref('')
const docError = ref(''); const docLoaded = ref(false)
const canEditDocument = computed(() => docLoaded.value && !docLoading.value && !docError.value)
const isAiThinking = ref(false); const pendingPreview = ref(null); const latestActionResult = ref(null); const isConversationLifecycleBusy = ref(false)
const currentConvId = conversationWorkspace.activeConversationId
const activeConversation = conversationWorkspace.activeConversation
const activeConversations = conversationWorkspace.activeConversations
const archivedConversations = conversationWorkspace.archivedConversations
const activeHasMore = conversationWorkspace.activeHasMore
const archivedHasMore = conversationWorkspace.archivedHasMore
const hasOlderMessages = conversationWorkspace.hasOlderMessages
const isLoadingConversations = conversationWorkspace.isLoadingConversations
const isLoadingMessages = conversationWorkspace.isLoadingMessages
const userMsg = computed({
  get: () => conversationWorkspace.session.draft.value,
  set: (value) => conversationWorkspace.setDraft(value),
})
const isConversationBusy = computed(() => isAiThinking.value
  || isConversationLifecycleBusy.value
  || isLoadingMessages.value)
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
const showAiBall = ref(false); const ballStyle = reactive({ top: '0px', left: '0px' }); const selectedText = ref('')
const showHistory = ref(false); const versionList = ref([])
const keywords = ref([]); const keywordsLoading = ref(false); const isRagMode = ref(false)
const textStats = ref(null)
const textStatsLoading = ref(false); const textStatsError = ref('')
const keywordsError = ref(''); const summaryError = ref('')
const currentDocumentText = () => {
  const paper = document.querySelector('.paper')
  if (paper && typeof paper.innerText === 'string') return paper.innerText
  // 非布局环境（组件测试）也保留块级文本边界。
  const html = paper?.innerHTML ?? docContent.value
  const element = document.createElement('div')
  element.innerHTML = html.replace(/<br\s*\/?\s*>|<\/(?:p|div|li|h[1-6]|tr)>/gi, '$&\n')
  return element.textContent || ''
}
const analyzeDocumentText = async () => {
  const plainText = currentDocumentText().trim()
  textStats.value = null
  textStatsError.value = ''
  if (!plainText) return
  textStatsLoading.value = true
  try {
    const res = await aiApi.analyzeText(plainText)
    const stats = res?.data
    if (!stats || !['totalCharacters', 'lines', 'chineseCharacters', 'punctuations'].every(key => Number.isFinite(stats[key]))) {
      throw new Error('Invalid text analysis response')
    }
    textStats.value = stats
  } catch {
    textStatsError.value = '文本分析失败，未生成统计结果。'
  } finally { textStatsLoading.value = false }
}
// RAG 分段管理逻辑
const ragDrawerVisible = ref(false)
const strategyMap = ref({})
const selectedStrategy = ref('')
const segments = ref([])
const indexing = ref(false)
const strategyError = ref(''); const segmentsError = ref(''); const segmentsLoading = ref(false)
const indexError = ref(''); const indexSucceeded = ref(false)
const currentModel = ref('') // 未选择时交给服务端使用其默认模型
const modelOptions = ref([])
const modelsError = ref('')

// 获取可用模型列表
const fetchModels = async () => {
  modelsError.value = ''
  try {
    const res = await aiApi.getModels()
    if (!Array.isArray(res?.data)) throw new Error('Invalid models')
    modelOptions.value = res.data
    if (!modelOptions.value.some(m => m.code === currentModel.value && m.available !== false)) {
      currentModel.value = modelOptions.value.find(m => m.available !== false)?.code || ''
    }
  } catch {
    modelsError.value = '模型列表加载失败，可用性未知。'
  }
}

// ===== 1. 加载文档与初始化 AI =====
const loadDocData = async () => {
  currentUserId.value = authenticatedUserId
  userApi.getUserInfo(authenticatedUserId).then(r => currentUserName.value = r.data.username || 'aa').catch(()=>{})

  if (isChatMode.value) {
    const task = sessionStorage.getItem('global_ai_task')
    if (task) {
      userMsg.value = task; sessionStorage.removeItem('global_ai_task'); setTimeout(() => handleSend(), 500)
    }
    return
  }
  docLoading.value = true
  docError.value = ''
  saveStatusText.value = '正在读取…'
  try {
    const docRes = await docApi.getDocDetail(docId)
    const data = docRes.data || docRes
    docName.value = data.title; aiSummary.value = data.summary; docContent.value = data.content || ''
    docLoaded.value = true
    saveStatusText.value = '已同步'

    await nextTick()
    void analyzeDocumentText()
  } catch (e) {
    docError.value = '读取文档失败，现有正文未被替换。请重试后再编辑或保存。'
    saveStatusText.value = '读取失败'
  } finally { docLoading.value = false }
}

// 首次加载
onMounted(async () => {
  const preferredConversationId = isChatMode.value ? route.query?.conversation : undefined
  await Promise.all([
    fetchModels(),
    conversationWorkspace.initialize({ preferredConversationId }),
  ])
  syncConversationRoute()
  loadDocData().then(() => {
    if (!canEditDocument.value) return
    const paperElement = document.querySelector('.paper')
    if (paperElement) {
      const content = paperElement.innerHTML
      // 带上默认模型提取关键词和摘要
      refreshKeywords(content)
      generateDocSummary(content)
    }
  })
})

// 改造关键词提取函数
const refreshKeywords = async (content) => {
  if (!content || content.length < 5) return
  keywordsLoading.value = true
  keywordsError.value = ''
  keywords.value = []
  try {
    // 💡剥离 A4 纸容器中的 HTML 标签，只传纯文本给 AI，防止标签干扰
    const plainText = content.replace(/<[^>]+>/g, '').trim()

    // 调用接口时，把当前选中的模型作为第三个参数（或者根据你后端的 DTO/Query 结构传参）
    const res = await aiApi.extractKeywords(plainText, 5, currentModel.value)

    if (res?.data?.keywords) {
      keywords.value = res.data.keywords.map(k => k.word)
    } else if (Array.isArray(res?.data)) {
      keywords.value = res.data
    } else {
      throw new Error('Invalid keyword response')
    }
  } catch {
    keywordsError.value = '关键词提取失败，未生成关键词。'
  } finally {
    keywordsLoading.value = false
  }
}

// 改造自动摘要函数：把 currentModel 传给后端
const generateDocSummary = async (content) => {
  if (!content) return
  summaryError.value = ''
  try {
    const plainText = content.replace(/<[^>]+>/g, '').trim()
    const res = await aiApi.summarizeText(plainText, 200, currentModel.value)
    if (res?.data?.summary) {
      aiSummary.value = res.data.summary
    } else {
      throw new Error('Invalid summary response')
    }
  } catch {
    summaryError.value = aiSummary.value ? '摘要更新失败，下方保留上次结果。' : '摘要生成失败，尚无结果。'
  }
}

// 当用户手动切换模型时，立刻重新触发当前文档的摘要和关键词刷新
const handleModelChange = () => {
  const paperElement = document.querySelector('.paper')
  if (paperElement) {
    const content = paperElement.innerHTML
    refreshKeywords(content)
    generateDocSummary(content)
  }
}

// ===== 2. 交互逻辑 =====
const handleSend = async () => {
  if (!isChatMode.value && !canEditDocument.value) return ElMessage.warning('请先成功读取文档，再执行文档任务')
  if (!userMsg.value.trim() || isConversationBusy.value) return

  const q = userMsg.value
  const pendingSelectedText = selectedText.value
  const frontendWriteIntent = shouldWriteToCurrentDocument(q, pendingSelectedText)
  const frontendWriteMode = inferFrontendWriteMode(q, pendingSelectedText)
  const frontendDeleteIntent = shouldDeleteCurrentDocument(q)
  const shouldSendCurrentDocumentId = frontendWriteIntent || frontendDeleteIntent

  if (frontendDeleteIntent) {
    if (!docId || isChatMode.value) {
      ElMessage.warning('当前没有可删除的文档')
      return
    }

    try {
      await ElMessageBox.confirm(
        `确定要永久删除「${docName.value || '当前文档'}」吗？删除后不会进入回收站，也无法通过回收站恢复。`,
        '确认永久删除文档',
        {
          confirmButtonText: '确认永久删除',
          cancelButtonText: '取消',
          type: 'warning',
          distinguishCancelAndClose: true,
          confirmButtonClass: 'el-button--danger'
        }
      )
    } catch {
      ElMessage.info('已取消删除')
      return
    }
  }

  let submission = null
  isAiThinking.value = true
  latestActionResult.value = null
  try {
    if (!currentConvId.value) {
      await conversationWorkspace.createConversation({ title: q.trim().slice(0, 40) || '新对话' })
      syncConversationRoute()
    }
    submission = conversationWorkspace.beginSubmission()
    pendingPreview.value = {
      id: `pending-${submission.clientRequestId}`,
      role: 'user',
      text: submission.content,
    }
    clearSelection()
    scrollToBottom()

    const contextText = document.querySelector('.paper')?.innerText || ''
    const res = await agentApi.executeTask({
      task: submission.content,
      model: currentModel.value,
      conversationId: submission.conversationId,
      clientRequestId: submission.clientRequestId,
      context: {
        documentId: shouldSendCurrentDocumentId ? docId : undefined,
        frontendDocumentWrite: frontendWriteIntent,
        frontendDeleteConfirmed: frontendDeleteIntent,
        documentContent: contextText.substring(0, 3000),
        selectedText: pendingSelectedText,
        writeMode: frontendWriteMode,
        ragEnabled: isRagMode.value
      }
    })

    const agentResult = res.data

    const writeResult = applyFrontendWriteFromToolResults(agentResult.toolResults)
    const fileResult = parseToolResults(agentResult.toolResults)
    latestActionResult.value = writeResult || fileResult
    pendingPreview.value = null
    const completion = await conversationWorkspace.completeSubmission(submission.clientRequestId)
    if (completion.historyReloadFailed) {
      ElMessage.warning('任务已完成，但历史暂时未刷新；重新打开会话后可再次加载。')
    }

    if (frontendDeleteIntent && hasDeletedCurrentDocument(agentResult.toolResults)) {
      ElMessage.success('文档已永久删除，即将返回工作台')
      setTimeout(() => router.push('/dashboard'), 600)
    }

    scrollToBottom()

  } catch (error) {
    console.error("AI 交互失败", error)
    pendingPreview.value = null
    if (submission) conversationWorkspace.markSubmissionFailed(error)
    const normalized = normalizeConversationError(error)
    if (normalized.code === CONVERSATION_ERROR.NOT_FOUND) {
      conversationWorkspace.invalidateActiveConversation()
      syncConversationRoute()
      ElMessage.warning('当前会话已失效，草稿已保留。请新建或选择其他会话。')
    } else if (normalized.code === CONVERSATION_ERROR.ARCHIVED) {
      ElMessage.warning('该会话已归档，请先恢复后再发送。')
    } else {
      ElMessage.error(normalized.message || 'AI 暂时无法响应，草稿已保留。')
    }
    await conversationWorkspace.reloadMessages().catch(() => {})
    scrollToBottom()
  } finally {
    isAiThinking.value = false
  }
}


const shouldWriteToCurrentDocument = (message, selection = '') => {
  if (isChatMode.value) return false
  const text = message || ''
  if (selection && /润色|纠错|改写|优化|替换|修改/.test(text)) return true
  return /写入|写进|写到|输出到文档|插入|追加|添加到|放到文档|生成到文档|生成在文档|保存到文档|应用到文档|更新文档|替换选中|替换这段|改到文档|直接生成在文档/.test(text)
}

const shouldDeleteCurrentDocument = (message) => {
  if (isChatMode.value) return false
  const text = (message || '').trim().toLowerCase()
  if (!text) return false

  const hasDeleteIntent = /删除|删掉|移除|清除|delete|remove/.test(text)
  const hasDocumentTarget = /当前文档|这个文档|该文档|本文档|文档|文件|current document|this document|current file/.test(text)
  const contentOnlyTarget = /选中|这段|段落|内容|文字|selection|paragraph|text/.test(text)

  return hasDeleteIntent && (hasDocumentTarget || (!contentOnlyTarget && text.length <= 16))
}

const hasDeletedCurrentDocument = (toolResults) => {
  return Array.isArray(toolResults) && toolResults.some(tr => {
    const data = tr?.data || {}
    return tr?.toolName === 'file-delete' && tr.status !== 'error' && data.status === 'deleted'
  })
}

const inferFrontendWriteMode = (message, selection = '') => {
  const text = message || ''
  if (selection) return 'replace-selection'
  if (/覆盖|替换全文|重写全文|改写全文|更新全文|修改全文/.test(text)) return 'overwrite'
  if (/插入|追加|添加|续写|写入|写进|写到|输出到文档|生成到文档|生成在文档|放到文档|保存到文档|直接生成在文档/.test(text)) return 'append'
  return 'append'
}

const applyFrontendWriteFromToolResults = (toolResults) => {
  if (!toolResults || !Array.isArray(toolResults) || isChatMode.value) return null

  const target = toolResults.find(tr => {
    const d = tr?.data || {}
    return tr.status !== 'error' && (d.requiresFrontendWrite || d.result === 'frontend-document-write')
  })
  if (!target) return null

  const data = target.data || {}
  const applied = applyFrontendDocumentWrite(data)
  if (!applied) return null

  return {
    type: 'document-write',
    changeLog: data.changeLog || 'AI 内容已应用，尚未保存',
    writeMode: data.writeMode || 'append',
    insertAfterText: data.insertAfterText || '',
    contentLength: data.contentLength || 0
  }
}

const applyFrontendDocumentWrite = (data) => {
  if (!canEditDocument.value) return false
  const paperElement = document.querySelector('.paper')
  if (!paperElement || !data?.content) return false

  const contentHtml = data.contentFormat === 'html'
      ? data.content
      : textToEditorHtml(data.content)
  const mode = data.writeMode || 'append'
  const insertAfterText = data.insertAfterText || ''

  if (mode === 'overwrite') {
    paperElement.innerHTML = contentHtml
  } else if (mode === 'replace-selection') {
    const replaced = replaceTextInElement(paperElement, data.selectionText || '', contentHtml)
    if (!replaced) appendHtmlToPaper(paperElement, contentHtml)
  } else if (insertAfterText && insertHtmlAfterAnchor(paperElement, insertAfterText, contentHtml)) {
    // 已按 Agent 返回的文档锚点完成局部插入。
  } else {
    appendHtmlToPaper(paperElement, contentHtml)
  }

  docContent.value = paperElement.innerHTML
  paperElement.dispatchEvent(new Event('input', { bubbles: true }))
  saveStatusText.value = 'AI 已写入，修改未保存'
  refreshKeywords(paperElement.innerHTML)
  generateDocSummary(paperElement.innerHTML)
  ElMessage.success('AI 内容已写入当前文档，保存后才会同步到云端')
  return true
}

const appendHtmlToPaper = (paperElement, html) => {
  const separator = paperElement.innerText.trim() ? '<p><br></p>' : ''
  paperElement.insertAdjacentHTML('beforeend', `${separator}${html}`)
}

const insertHtmlAfterAnchor = (root, anchorText, html) => {
  const normalizedAnchor = normalizeTextForSearch(anchorText)
  if (!normalizedAnchor) return false

  const blocks = Array.from(root.querySelectorAll('p, div, li, h1, h2, h3, h4, h5, h6'))
  for (const block of blocks) {
    const blockText = normalizeTextForSearch(block.innerText || block.textContent || '')
    if (blockText && (blockText.includes(normalizedAnchor) || (blockText.length >= 8 && normalizedAnchor.includes(blockText)))) {
      block.insertAdjacentHTML('afterend', html)
      return true
    }
  }

  const walker = document.createTreeWalker(root, window.NodeFilter.SHOW_TEXT)
  let node
  while ((node = walker.nextNode())) {
    const nodeText = normalizeTextForSearch(node.nodeValue || '')
    if (!nodeText || (!nodeText.includes(normalizedAnchor) && !(nodeText.length >= 8 && normalizedAnchor.includes(nodeText)))) continue

    const container = findBlockContainer(node, root)
    if (container) {
      container.insertAdjacentHTML('afterend', html)
      return true
    }

    const temp = document.createElement('div')
    temp.innerHTML = html
    const fragment = document.createDocumentFragment()
    while (temp.firstChild) fragment.appendChild(temp.firstChild)
    const range = document.createRange()
    range.setStartAfter(node)
    range.insertNode(fragment)
    return true
  }
  return false
}

const findBlockContainer = (node, root) => {
  const blockTags = new Set(['P', 'DIV', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'])
  let current = node.parentElement
  while (current && current !== root) {
    if (blockTags.has(current.tagName)) return current
    current = current.parentElement
  }
  return null
}

const replaceTextInElement = (root, targetText, replacementHtml) => {
  if (!targetText) return false
  const normalizedTarget = normalizeTextForSearch(targetText)
  if (!normalizedTarget) return false
  const walker = document.createTreeWalker(root, window.NodeFilter.SHOW_TEXT)
  let node
  while ((node = walker.nextNode())) {
    const exactIndex = node.nodeValue.indexOf(targetText)
    const index = exactIndex >= 0 ? exactIndex : (normalizeTextForSearch(node.nodeValue).includes(normalizedTarget) ? 0 : -1)
    if (index < 0) continue

    const replaceLength = exactIndex >= 0 ? targetText.length : node.nodeValue.length
    const before = node.nodeValue.slice(0, index)
    const after = node.nodeValue.slice(index + replaceLength)
    const fragment = document.createDocumentFragment()
    if (before) fragment.appendChild(document.createTextNode(before))

    const temp = document.createElement('div')
    temp.innerHTML = replacementHtml
    while (temp.firstChild) fragment.appendChild(temp.firstChild)

    if (after) fragment.appendChild(document.createTextNode(after))
    node.parentNode.replaceChild(fragment, node)
    return true
  }
  return false
}

const textToEditorHtml = (text) => {
  return normalizeAiDocumentText(text)
      .split(/\n{2,}/)
      .map(block => `<p>${escapeHtml(block).replace(/\n/g, '<br>')}</p>`)
      .join('')
}

const normalizeAiDocumentText = (text) => {
  return String(text || '')
      .replace(/\r\n/g, '\n')
      .replace(/\r/g, '\n')
      .replace(/^```[a-zA-Z]*\s*/g, '')
      .replace(/\s*```$/g, '')
      .replace(/^\s{0,3}#{1,6}\s*/gm, '')
      .replace(/^\s*>\s?/gm, '')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/\*\*([^*\n]+)\*\*/g, '$1')
      .replace(/__([^_\n]+)__/g, '$1')
      .replace(/`([^`\n]+)`/g, '$1')
      .replace(/\$([^$\n]{1,200})\$/g, '$1')
      .replace(/\$/g, '')
      .replace(/\\mathbb\{C\}/g, 'C')
      .replace(/\\mathbb\{Q\}/g, 'Q')
      .replace(/\\mathbb\{R\}/g, 'R')
      .replace(/\\mathbb\{Z\}/g, 'Z')
      .replace(/\\mathbb\{N\}/g, 'N')
      .replace(/\\in/g, '∈')
      .replace(/\\sum/g, '∑')
      .replace(/\\cdot/g, '·')
      .replace(/\\times/g, '×')
      .replace(/\\leq/g, '≤')
      .replace(/\\geq/g, '≥')
      .replace(/\\neq/g, '≠')
      .replace(/\\mid/g, '|')
      .replace(/\\[()[\]]/g, '')
      .replace(/[ \t]+$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim()
}

const normalizeTextForSearch = (text) => {
  return normalizeAiDocumentText(text)
      .replace(/[：:，,。；;、（）()【】[\]\s]/g, '')
      .toLowerCase()
}

const escapeHtml = (value) => {
  return String(value || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
}

// 解析 toolResults，提取文件信息
const parseToolResults = (toolResults) => {
  if (!toolResults || !Array.isArray(toolResults)) return null

  for (const tr of toolResults) {
    if (tr.status === 'error') continue
    const d = tr.data || {}
    if (d.requiresFrontendWrite || d.result === 'frontend-document-write') continue

    // PPT
    if (tr.toolName?.includes('ppt') || d.htmlContent || d.filePath?.endsWith('.html')) {
      return {
        type: 'ppt',
        title: d.title || 'PPT演示文稿',
        htmlContent: d.htmlContent || null,
        filePath: d.filePath || null,
        fileName: d.fileName || null,
        url: d.url || d.downloadUrl || null
      }
    }

    // Word
    if (d.fileName?.endsWith('.docx') || d.objectName?.endsWith('.docx')) {
      return {
        type: 'doc',
        fileName: d.fileName || d.objectName,
        objectName: d.objectName || d.fileName,
        bucketName: 'omnidoc',
        url: d.url || d.downloadUrl || null
      }
    }

    // 通用文件
    if (d.fileName || d.url) {
      return {
        type: 'file',
        fileName: d.fileName,
        url: d.url || d.downloadUrl
      }
    }
  }

  return null
}

// ===== 3. 手动保存与恢复逻辑 =====
const handleManualSave = async () => {
  if (isChatMode.value || !canEditDocument.value) return
  isSaving.value = true; saveStatusText.value = '正在保存...'
  try {
    const content = document.querySelector('.paper').innerHTML
    await docApi.updateDoc(docId, { title: docName.value, content: content, category: 'default' })
    saveStatusText.value = '已同步'; ElMessage.success('保存成功')
  } catch (e) { saveStatusText.value = '保存失败'; ElMessage.error('同步失败') }
  finally { isSaving.value = false }
}

const handleRestore = async (verNum) => {
  if (!canEditDocument.value) return
  ElMessageBox.confirm(`确定要将文档恢复到 V${verNum} 吗？当前未保存的修改将丢失。`, '版本回溯').then(async () => {
    docLoading.value = true
    try {
      await docApi.restoreVersion(docId, verNum)
      ElMessage.success('版本已回溯')

      // 恢复成功后重新拉取数据
      await loadDocData()

      showHistory.value = false
    } catch (e) {
      ElMessage.error('回溯失败')
    } finally {
      docLoading.value = false
    }
  }).catch(() => {})
}

// ===== 4. 文件导出与选中逻辑 =====
const handleDownload = async () => {
  isSaving.value = true
  saveStatusText.value = '正在导出最新版...'
  try {
    // 获取 A4 纸里的纯文本（innerText 会自动保留换行和空格）
    const latestText = document.querySelector('.paper').innerText

    // 包装成 Word 认得的格式，并强制要求保留空白符
    const wordHtml = `
      <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
      <head><meta charset='utf-8'></head>
      <body style="font-family: sans-serif;">
        <pre style="white-space: pre-wrap; word-break: break-all;">${latestText}</pre>
      </body>
      </html>`

    const blob = new Blob([wordHtml], { type: 'application/msword' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `[最新]_${docName.value}.doc`)
    document.body.appendChild(link); link.click(); document.body.removeChild(link)
    window.URL.revokeObjectURL(url)

    ElMessage.success('导出成功')
    saveStatusText.value = '已同步'
  } catch (e) { ElMessage.error('下载失败') }
  finally { isSaving.value = false }
}

const handleTextSelection = () => {
  const s = window.getSelection(); const t = s.toString().trim()
  if (t && s.rangeCount > 0) {
    selectedText.value = t; const r = s.getRangeAt(0).getBoundingClientRect()
    ballStyle.top = `${r.top - 55}px`; ballStyle.left = `${r.left + r.width / 2}px`; showAiBall.value = true
  } else { showAiBall.value = false }
}

const downloadBlob = (blob, fileName) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const downloadSkillFile = async (params, fileName = 'download') => {
  const blob = await request.get('/skills/file/download', {
    params,
    responseType: 'blob'
  })
  downloadBlob(blob, fileName)
}

const handleDownloadAgentFile = (actionResult) => {
  if (!actionResult) return

  // 优先用后端直接给的 URL
  if (actionResult.url) {
    window.open(actionResult.url, '_blank')
    return
  }

  // PPT 类型
  if (actionResult.type === 'ppt') {
    if (actionResult.htmlContent) {
      // 在这里给它包上一层完整的 HTML 模板
      const fullHtml = `
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${actionResult.title || 'PPT'}</title>
    <style>
        /* 强制给 PPT 内容加点样式，你可以根据需要调整 */
        body { font-family: 'Microsoft YaHei', sans-serif; background: #f0f0f0; padding: 50px; }
        .slide { background: white; padding: 40px; margin-bottom: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #3370ff; }
        p { line-height: 1.6; color: #333; }
        /* 清洗掉 Markdown 留下的多余符号 */
        pre { background: #f8f8f8; padding: 10px; border-radius: 4px; }
    </style>
</head>
<body>
    ${actionResult.htmlContent}
</body>
</html>`;
      const blob = new Blob([fullHtml], { type: 'text/html' })
      downloadBlob(blob, `${actionResult.title || 'PPT'}.html`)
    } else if (actionResult.fileName) {
      downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
          .catch(() => ElMessage.error('下载失败'))
    }
    return
  }

  // Word 类型
  if (actionResult.type === 'doc') {
    const objectName = actionResult.objectName || actionResult.fileName
    if (objectName) {
      downloadSkillFile({ bucket: 'omnidoc', objectName }, objectName)
          .catch(() => ElMessage.error('下载失败'))
    } else {
      ElMessage.warning('无法获取文件名')
    }
    return
  }

  // 通用文件
  if (actionResult.fileName) {
    ElMessage.success(`正在下载：${actionResult.fileName}`)
    downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
        .catch(() => ElMessage.error('下载失败'))
  } else {
    ElMessage.info('该任务没有返回可下载的文件')
  }
}

const loadSegmentStrategies = async () => {
  strategyError.value = ''
  try {
    const res = await aiApi.getSegmentStrategies()
    if (!res?.data || typeof res.data !== 'object' || Array.isArray(res.data) || !Object.keys(res.data).length) throw new Error('Invalid strategies')
    strategyMap.value = res.data
    if (!strategyMap.value[selectedStrategy.value]) selectedStrategy.value = Object.keys(res.data)[0]
  } catch {
    strategyError.value = Object.keys(strategyMap.value).length ? '策略更新失败，保留上次加载的选项。' : '分段策略加载失败，请重试。'
  }
}

const loadDocumentSegments = async () => {
  segmentsLoading.value = true
  segmentsError.value = ''
  segments.value = []
  try {
    const res = await aiApi.getDocumentSegments(docId)
    if (!Array.isArray(res?.data)) throw new Error('Invalid segments')
    segments.value = res.data
  } catch {
    segmentsError.value = '分段明细加载失败，当前结果未知。'
  } finally { segmentsLoading.value = false }
}

const openRagSettings = async () => {
  ragDrawerVisible.value = true
  await Promise.all([loadSegmentStrategies(), loadDocumentSegments()])
}

// 执行高级索引
const doIndexDocument = async () => {
  if (!canEditDocument.value) return
  // 获取 A4 纸里最新的纯文本内容
  const content = currentDocumentText()
  if (content.length < 10) return ElMessage.warning('文档内容过少，无法分段')
  if (!selectedStrategy.value || indexing.value) return

  indexing.value = true
  indexError.value = ''
  indexSucceeded.value = false
  segments.value = []
  segmentsError.value = ''
  try {
    // 提交给后端的 /index/segment 接口
    await aiApi.indexWithSegment(docId, content, selectedStrategy.value)
    indexSucceeded.value = true
    ElMessage.success('索引请求已完成')
    await loadDocumentSegments()
  } catch {
    indexError.value = '索引请求失败，无法确认是否完成；可重新执行分段。'
    segmentsError.value = '尚未确认最新分段，请重新加载明细。'
    ElMessage.error('索引请求失败')
  } finally {
    indexing.value = false
  }
}


const askAiWithContext = (type) => {
  userMsg.value = type === '纠错' ? `纠错并分析原因："${selectedText.value}"` : `润色使表达更专业："${selectedText.value}"`
  showAiBall.value = false;
  handleSend()
}
const handleScroll = () => { if(showAiBall.value) showAiBall.value = false }
const clearSelection = () => { selectedText.value = ''; showAiBall.value = false }
const handleInput = () => { saveStatusText.value = '修改未保存' }
const openHistoryDrawer = async () => { const res = await docApi.getVersions(docId); versionList.value = res.data; showHistory.value = true }
const syncConversationRoute = () => {
  if (!isChatMode.value || typeof router.replace !== 'function') return
  const query = { ...(route.query || {}) }
  if (currentConvId.value) query.conversation = currentConvId.value
  else delete query.conversation
  Promise.resolve(router.replace({ path: route.path, query })).catch(() => {})
}
const openConversationFromHistory = async (conversationId) => {
  if (!conversationId || conversationId === currentConvId.value || isConversationBusy.value) return
  isConversationLifecycleBusy.value = true
  try {
    latestActionResult.value = null
    await conversationWorkspace.openConversation(conversationId)
    syncConversationRoute()
    scrollToBottom()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  } finally {
    isConversationLifecycleBusy.value = false
  }
}
const handleEditorConversationSelect = (event) => openConversationFromHistory(event.target.value)
const startNewChat = async () => {
  if (isConversationBusy.value) return
  isConversationLifecycleBusy.value = true
  try {
    await conversationWorkspace.createConversation({ title: '新对话' })
    latestActionResult.value = null
    syncConversationRoute()
    ElMessage.success('新对话已创建')
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  } finally {
    isConversationLifecycleBusy.value = false
  }
}
const archiveOrRestoreConversation = async () => {
  if (!activeConversation.value || isConversationBusy.value) return
  isConversationLifecycleBusy.value = true
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
    isConversationLifecycleBusy.value = false
  }
}
const deleteCurrentConversation = async () => {
  if (!activeConversation.value || isConversationBusy.value) return
  isConversationLifecycleBusy.value = true
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
    syncConversationRoute()
    ElMessage.success('会话已永久删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && error?.message !== 'cancel') {
      ElMessage.error(normalizeConversationError(error).message)
    }
  } finally {
    isConversationLifecycleBusy.value = false
  }
}
const loadOlderConversationMessages = async () => {
  try {
    await conversationWorkspace.loadOlderMessages()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  }
}
const loadMoreConversationList = async (status) => {
  try {
    if (status === 'archived') await conversationWorkspace.loadMoreArchivedConversations()
    else await conversationWorkspace.loadMoreActiveConversations()
  } catch (error) {
    ElMessage.error(normalizeConversationError(error).message)
  }
}
const scrollToBottom = () => { nextTick(() => {
  const c = isChatMode.value ? document.querySelector('.chat-scroll-area') : document.querySelector('.chat-area'); if(c) c.scrollTop = c.scrollHeight
}) }
</script>


<style scoped>
/* ==================== 全局格式保护 ==================== */
.preserve-format {
  white-space: pre-wrap !important;
  word-break: break-word;
  text-align: left;
}

/* ==================== 模式 A：飞书全局对话样式 ==================== */
.feishu-chat-layout {
  height: 100vh;
  width: 100vw;
  display: flex;
  background: #fff;
  overflow: hidden; /* 防止出现外层滚动条 */
}

/* 聊天侧边栏 (固定宽度，上下分布) */
.chat-sidebar {
  width: 260px;
  flex-shrink: 0; /* 绝对不被压缩 */
  background: #f9fafb;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.sidebar-top { padding: 24px 20px; }
.brand-back { display: flex; align-items: center; gap: 8px; color: #5c5f66; font-size: 14px; cursor: pointer; margin-bottom: 30px; font-weight: 500;}
.brand-back:hover { color: #3370ff; }
.new-chat-btn { width: 100%; border-radius: 8px; margin-bottom: 30px; font-weight: 600; height: 36px; }
.group-title { font-size: 12px; color: #8f959e; margin-bottom: 12px; padding-left: 4px; }
.history-group { display: flex; flex-direction: column; gap: 4px; max-height: calc(100vh - 250px); overflow-y: auto; }
.history-item { padding: 12px 16px; border-radius: 8px; display: flex; align-items: center; gap: 10px; font-size: 14px; color: #4e5969; cursor: pointer; font-weight: 500; }
.history-item:hover { background: #f2f3f5; }
.history-item.disabled { cursor: not-allowed; opacity: 0.55; }
.history-item.disabled:hover { background: transparent; }
.history-item.active { background: #e1eaff; color: #3370ff; }
.history-item.archived { color: #8f959e; }
.history-item .text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 侧边栏底部用户 */
.sidebar-bottom { padding: 20px; border-top: 1px solid #ebeef5; }
.user-profile { display: flex; align-items: center; gap: 12px; }
.user-profile .username { font-size: 14px; font-weight: 500; color: #1f2329; }

/* 主聊天区 (占据剩余空间) */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  background: #fff;
  min-width: 0; /* 防止内容过长撑破 Flex 布局 */
}
.chat-header { height: 64px; display: flex; justify-content: center; border-bottom: 1px solid #f0f0f0; flex-shrink: 0;}
.header-inner { width: 100%; max-width: 900px; display: flex; justify-content: space-between; align-items: center; padding: 0 24px; }
.chat-header .title { font-size: 16px; font-weight: 600; color: #1f2329; }

/* 聊天滚动区 */
.chat-scroll-area {
  flex: 1;
  overflow-y: auto;
  padding: 40px 0 160px 0; /* 底部留出巨大空间给输入框 */
  display: flex;
  justify-content: center;
}
.chat-content-container { width: 100%; max-width: 850px; padding: 0 24px; display: flex; flex-direction: column; }
.empty-conversation { color: #8f959e; text-align: center; padding: 48px 20px; line-height: 1.7; }

.message-row { display: flex; gap: 16px; margin-bottom: 32px; }
.message-row.user { flex-direction: row-reverse; }
.message-content-col { max-width: 80%; }

.user-bubble { background: #3370ff; color: #fff; padding: 14px 20px; border-radius: 16px 4px 16px 16px; font-size: 15px; line-height: 1.6; box-shadow: 0 4px 12px rgba(51, 112, 255, 0.2); }
.ai-structured-card { background: #fff; border: 1px solid #dee0e3; border-radius: 4px 16px 16px 16px; padding: 24px; box-shadow: 0 6px 18px rgba(0,0,0,0.04); transition: all 0.3s; }
.ai-structured-card:hover { border-color: #3370ff; box-shadow: 0 8px 24px rgba(51, 112, 255, 0.08); }
.ai-card-content { font-size: 15px; color: #1f2329; line-height: 1.8; font-family: inherit; margin: 0; }
.ai-structured-card.thinking { color: #8f959e; font-style: italic; display: flex; align-items: center; gap: 10px; padding: 16px 24px; }

/* 底部居中悬浮输入框 */
.chat-input-container {
  position: absolute;
  bottom: 0; left: 0; width: 100%;
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 0 32px 0;
  background: linear-gradient(to top, #fff 80%, rgba(255,255,255,0));
}
.input-wrapper-inner {
  width: 100%; max-width: 800px; height: 60px; background: #fff; border-radius: 30px;
  box-shadow: 0 6px 24px rgba(31,35,41,0.08); display: flex; align-items: center; padding: 0 10px 0 24px;
  border: 1px solid #dee0e3; transition: all 0.3s;
}
.input-wrapper-inner.is-focused { border-color: #3370ff; box-shadow: 0 8px 32px rgba(51,112,255,0.15); }
.chat-input { flex: 1; border: none; outline: none; font-size: 16px; color: #1f2329; background: transparent; }
.input-right { display: flex; align-items: center; gap: 15px; }
.send-btn-circle {
  width: 44px; height: 44px; border-radius: 50%; background: #f2f3f5; color: #fff;
  display: flex; justify-content: center; align-items: center; cursor: pointer; transition: 0.3s;
}
.send-btn-circle.active { background: #3370ff; }
.ai-hint { font-size: 12px; color: #bbbfc4; margin-top: 16px; }


/* ==================== 模式 B：文档阅读模式样式 (保持不变) ==================== */
.editor-page { height: 100vh; display: flex; flex-direction: column; background: #f5f6f7; }
.toolbar { height: 56px; background: #fff; border-bottom: 1px solid #dee0e3; display: flex; align-items: center; padding: 0 24px; justify-content: space-between; flex-shrink: 0;}
.workspace { flex: 1; display: flex; overflow: hidden; position: relative; }
.editor-main { flex: 1; overflow-y: auto; display: flex; justify-content: center; padding: 50px 0; background: #f0f2f5; position: relative; }
.paper-container { position: relative; }
.document-load-error { max-width: 780px; box-sizing: border-box; padding: 12px 16px; margin: 0 0 16px; color: #a12622; background: #fef0f0; border: 1px solid #fbc4c4; border-radius: 6px; }
.paper { width: 780px; min-height: 1100px; background: #fff; padding: 80px 100px; box-shadow: 0 4px 16px rgba(0,0,0,0.06); outline: none; line-height: 1.8; font-size: 16px; color: #1f2329; }

.ai-sidebar { width: 360px; background: #fff; border-left: 1px solid #dee0e3; display: flex; flex-direction: column; height: 100%; flex-shrink: 0;}
.ai-sidebar-top { flex-shrink: 0; }
.ai-header {
  padding: 12px 20px;
  font-weight: 600;
  border-bottom: 1px solid #f0f0f0;
  color: #1f2329;
  display: flex; /* 变成 flex 布局 */
  align-items: center;
  justify-content: space-between;
}
.editor-conversation-bar { display: flex; align-items: center; gap: 6px; padding: 8px 12px; border-bottom: 1px solid #f0f0f0; }
.editor-conversation-bar select { flex: 1; min-width: 0; height: 30px; border: 1px solid #dee0e3; border-radius: 6px; padding: 0 8px; color: #4e5969; background: #fff; }
.chat-area { flex-grow: 1; padding: 20px; overflow-y: auto; background: #fafbfc; display: flex; flex-direction: column; }
.chat-bubble { max-width: 90%; padding: 12px 16px; border-radius: 14px; margin-bottom: 16px; font-size: 14px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.chat-bubble.ai { background: #fff; border: 1px solid #dee0e3; align-self: flex-start; border-bottom-left-radius: 2px;}
.chat-bubble.user { background: #3370ff; color: #fff; align-self: flex-end; margin-left: auto; border-bottom-right-radius: 2px;}
.summary-box { background: #fff; padding: 18px; border-radius: 12px; border: 1px solid #e1eaff; border-left: 5px solid #3370ff; margin: 15px; box-shadow: 0 4px 12px rgba(51,112,255,0.06); }
.input-area { flex-shrink: 0; padding: 20px; border-top: 1px solid #f0f0f0; background: #fff; }
.send-btn { width: 100%; margin-top: 12px; height: 42px; font-weight: 600; letter-spacing: 1px;}

.ai-float-ball { position: fixed; z-index: 9999; transform: translateX(-50%); cursor: pointer; filter: drop-shadow(0 4px 12px rgba(51, 112, 255, 0.4)); }
.ball-inner {background: #3370ff;color: #fff;padding: 0 12px;border-radius: 8px;display: flex;align-items: center;height: 36px;font-size: 13px;font-weight: 600;}
.ball-inner:after { content:''; position: absolute; bottom: -6px; left: 50%; transform: translateX(-50%); border-left: 6px solid transparent; border-right: 6px solid transparent; border-top: 6px solid #3370ff; }
.menu-opt {padding: 0 8px;cursor: pointer;display: flex;align-items: center;gap: 4px;transition: opacity 0.2s;}
/* 🚨 文本分析看板样式 */
.stats-box {
  padding: 16px 20px 0 20px;
  background: #fff;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  background: #f8f9fa;
  border-radius: 8px;
  padding: 12px 0;
  border: 1px solid #ebeef5;
}
.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  border-right: 1px solid #ebeef5;
}
.stat-item:last-child {
  border-right: none;
}
.stat-item .num {
  font-size: 15px;
  font-weight: 700;
  color: #3370ff;
  font-family: monospace; /* 让数字更整齐 */
}
.stat-item .desc {
  font-size: 11px;
  color: #8f959e;
  margin-top: 4px;
}

/* 1. 左侧文档信息栏 (260px) */
.left-sidebar {
  width: 260px;
  background: #f9fafb;
  border-right: 1px solid #dee0e3;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  padding: 20px;
  overflow-y: auto;
}
.sidebar-section {
  margin-bottom: 30px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2329;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 文本分析微调 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr); /* 变成 2x2 网格更适合左侧窄栏 */
  gap: 12px;
}
.stat-item {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 2px 4px rgba(0,0,0,0.02);
}
.stat-item .num { font-size: 18px; font-weight: bold; color: #3370ff; font-family: monospace;}
.stat-item .desc { font-size: 11px; color: #8f959e; margin-top: 4px;}

/* 摘要区域 */
.summary-text {
  font-size: 13px;
  color: #646a73;
  line-height: 1.6;
  background: #fff;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.keywords-area {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;        /* 确保开启换行 */
  align-items: flex-start; /* 保证行对齐 */
  gap: 8px;
  width: 100%;           /* 确保容器占满父级宽度 */
  overflow: visible;     /* 必须开启可见，防止溢出触发滚动 */
}

/* 确保每个 Tag 不会因为太长而挤压别人 */
.keyword-tag {
  white-space: normal !important; /* 强制 Tag 内部内容可以处理换行 */
  height: auto !important;        /* 让高度随内容自动适配 */
  min-height: 24px;               /* 保持一定高度 */
}

/* 协作卡片 */
.collab-card {
  background: #fff;
  border: 1px solid #e1eaff;
  border-radius: 8px;
  padding: 16px;
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.online { background-color: #52c41a; box-shadow: 0 0 4px #52c41a; }

/* 2. 右侧 AI 助手栏 (拓宽到 380px) */
.expanded-ai {
  width: 380px !important; /* 加宽！让对话框更大 */
}
.chat-area {
  flex-grow: 1;
  padding: 20px;
  overflow-y: auto;
  background: #f4f5f7; /* 纯净灰底，突出气泡 */
}
.chat-bubble {
  max-width: 90%;
  padding: 14px 18px; /* 加大气泡内边距 */
  border-radius: 12px;
  margin-bottom: 20px;
  font-size: 14px;
  line-height: 1.6;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
.selected-context {
  font-size: 12px;
  color: #3370ff;
  background: #e1eaff;
  padding: 6px 12px;
  border-radius: 4px;
  margin-bottom: 12px;
  display: inline-block;
}
</style>
