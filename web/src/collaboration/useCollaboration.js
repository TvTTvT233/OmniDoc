import { ref, nextTick } from 'vue'
import { docApi } from '../api/document'

const USER_COLORS = [
  '#3370ff', '#f56c6c', '#67c23a', '#e6a23c', '#909399',
  '#b37feb', '#36cfc9', '#ff85c0', '#597ef7', '#ffc53d'
]

let colorIndex = 0
const userColorMap = {}

function getUserColor(userId) {
  if (!userColorMap[userId]) {
    userColorMap[userId] = USER_COLORS[colorIndex % USER_COLORS.length]
    colorIndex++
  }
  return userColorMap[userId]
}

export function useCollaboration(documentId, userId, userName, documentState = {}) {
  const onlineUsers = ref([])
  const remoteCursors = ref([])
  const isConnected = ref(false)
  const isCollaborating = ref(false)
  const connectionError = ref('')
  const lastSyncTime = ref(0)

  let paperElement = null
  let isRemoteChange = false
  let pollTimer = null
  let autoSaveTimer = null
  let lastKnownContent = ''
  let lastSavedContent = ''
  let lastKnownVersion = null
  let inputHandler = null
  let compositionHandler = null
  let isComposing = false
  let composeData = ''
  let isDisposed = false

  async function autoSave() {
    if (!paperElement || !isCollaborating.value || isDisposed) return
    const content = paperElement.innerHTML
    if (content === lastSavedContent) return

    try {
      // The editor owns the saved version and CAS guard for both manual and automatic saves.
      const saved = await documentState.saveDocument?.(content)
      if (saved !== true) throw new Error('自动保存未完成，草稿已保留；请检查编辑器保存状态。')
      lastSavedContent = content
      lastSyncTime.value = Date.now()
      connectionError.value = ''
    } catch (e) {
      connectionError.value = e.message || '自动保存失败，草稿已保留。'
    }
  }

  function scheduleAutoSave() {
    if (autoSaveTimer) clearTimeout(autoSaveTimer)
    autoSaveTimer = setTimeout(autoSave, 1500)
  }

  async function pollChanges() {
    if (!isCollaborating.value || isDisposed) return
    try {
      const response = await docApi.getDocDetail(documentId)
      if (isDisposed) return
      const docData = response?.data
      if (!Number.isInteger(docData?.version) || typeof docData.content !== 'string') throw new Error('远端文档版本未知，未替换正文。')
      const remoteContent = docData.content

      if (remoteContent !== lastKnownContent || docData.version !== lastKnownVersion) {
        if (documentState.acceptRemoteDocument?.(docData) !== true) {
          connectionError.value = '远端同步暂停，本地草稿已保留；请检查文档版本。'
          return
        }
        lastKnownVersion = docData.version
        // The editor applies the declared content format and updates its version/draft together.
        // Never independently inject a raw PLAIN_TEXT response into innerHTML here.
        isRemoteChange = true
        await nextTick()
        lastKnownContent = remoteContent
        lastSavedContent = paperElement?.innerHTML || ''
        lastSyncTime.value = Date.now()
        isRemoteChange = false
        connectionError.value = ''
      }
    } catch (e) {
      connectionError.value = '读取协作文档失败，现有正文未替换。'
    }
  }

  function setupPaperListeners() {
    paperElement = document.querySelector('.paper')
    if (!paperElement) {
      setTimeout(setupPaperListeners, 500)
      return
    }

    lastKnownContent = paperElement.innerHTML || ''
    lastSavedContent = lastKnownContent

    inputHandler = () => {
      if (isRemoteChange || isComposing || isDisposed) return
      scheduleAutoSave()
    }

    compositionHandler = {
      start: () => { isComposing = true },
      end: (e) => {
        isComposing = false
        composeData = e.data || ''
        if (composeData && paperElement && !isDisposed) {
          scheduleAutoSave()
        }
      }
    }

    paperElement.addEventListener('input', inputHandler)
    paperElement.addEventListener('compositionstart', compositionHandler.start)
    paperElement.addEventListener('compositionend', compositionHandler.end)
  }

  function startPolling() {
    pollTimer = setInterval(pollChanges, 2000)
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
    if (autoSaveTimer) {
      clearTimeout(autoSaveTimer)
      autoSaveTimer = null
    }
  }

  function disconnect() {
    isDisposed = true
    stopPolling()

    if (paperElement) {
      if (inputHandler) {
        paperElement.removeEventListener('input', inputHandler)
      }
      if (compositionHandler) {
        paperElement.removeEventListener('compositionstart', compositionHandler.start)
        paperElement.removeEventListener('compositionend', compositionHandler.end)
      }
    }

    isConnected.value = false
    isCollaborating.value = false
    onlineUsers.value = []
    remoteCursors.value = []
  }

  async function startCollaboration() {
    if (isCollaborating.value) return
    isDisposed = false
    try {
      setupPaperListeners()
      startPolling()
      isConnected.value = true
      isCollaborating.value = true
      connectionError.value = ''
      lastSyncTime.value = Date.now()
    } catch (e) {
      connectionError.value = e.message || '启动失败'
      console.error('[Collaboration] 启动失败', e)
    }
  }

  function stopCollaboration() {
    disconnect()
  }

  return {
    onlineUsers,
    remoteCursors,
    isConnected,
    isCollaborating,
    connectionError,
    lastSyncTime,
    startCollaboration,
    stopCollaboration,
    getUserColor
  }
}
