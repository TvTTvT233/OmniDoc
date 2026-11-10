/**
 * @description 页面路由配置
 */
import { createRouter, createWebHistory } from 'vue-router'
import { STORAGE_KEYS } from '../constants'
import { getAuthenticatedUserId } from '../utils/jwt.js'

const routes = [
    { path: '/', redirect: '/login' },
    { path: '/index.html', redirect: '/login' },
    { path: '/login', component: () => import('../views/auth/LoginView.vue') },
    { path: '/register', component: () => import('../views/auth/RegisterView.vue') },
    { path: '/evaluations', component: () => import('../views/evals/EvaluationCenter.vue') },
    { path: '/dashboard', component: () => import('../views/dashboard/IndexView.vue') },
    { path: '/ppt-runtime', name: 'pptRuntime', component: () => import('../views/dashboard/PptRuntime.vue')},
    { path: '/aiops', component: () => import('../views/aiops/AIOpsView.vue') },
    { path: '/editor/:id', component: () => import('../views/editor/EditorView.vue') },
    { path: '/agent-workbench', component: () => import('../views/agent/AgentWorkbench.vue') },
    { path: '/review-m0', component: () => import('../views/review/ReviewM0List.vue') },
    { path: '/review-m0/:caseId', component: () => import('../views/review/ReviewM0Case.vue') }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
    const isPublicRoute = to.path === '/login' || to.path === '/register' || to.path === '/evaluations'
    const authenticatedUserId = getAuthenticatedUserId()
    if (!isPublicRoute && !authenticatedUserId) {
        localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN)
        localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
        localStorage.removeItem(STORAGE_KEYS.USER_INFO)
        localStorage.removeItem('userId')
        next('/login')
    } else {
        next()
    }
})

export default router
