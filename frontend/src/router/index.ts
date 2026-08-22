import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
  { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },
  {
    path: '/',
    component: () => import('@/views/ChatLayout.vue'),
    children: [
      { path: '', name: 'home', component: () => import('@/views/ChatView.vue') },
      { path: 'session/:id', name: 'session', component: () => import('@/views/ChatView.vue') },
    ],
  },
  {
    path: '/admin/providers',
    name: 'admin-providers',
    component: () => import('@/views/admin/ProviderConfigView.vue'),
  },
  {
    path: '/admin/usage',
    name: 'admin-usage',
    component: () => import('@/views/admin/UsageStatsView.vue'),
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('@/views/admin/UserManageView.vue'),
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  const publicPages = ['login', 'register']
  if (!publicPages.includes(String(to.name)) && !auth.isLoggedIn) {
    return { name: 'login' }
  }
  if (publicPages.includes(String(to.name)) && auth.isLoggedIn) {
    return { name: 'home' }
  }
})

export default router
