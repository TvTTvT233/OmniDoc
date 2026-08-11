import { ref } from 'vue'

// 将状态放在模块作用域，实现全局单例效果
const isAiPanelVisible = ref(false)

try {
    localStorage.removeItem('global_conv_id')
} catch {
    // The panel remains usable when browser storage is unavailable.
}

export function useGlobalAi() {
    // 打开/关闭 AI 面板
    const toggleAiPanel = () => {
        isAiPanelVisible.value = !isAiPanelVisible.value
    }

    return {
        isAiPanelVisible,
        toggleAiPanel,
    }
}
