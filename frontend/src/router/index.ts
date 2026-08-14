import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    {
      path: '/dashboard',
      name: 'dashboard',
      component: () => import('../views/dashboard/index.vue'),
      meta: { title: '全局总览' },
    },
    {
      path: '/projects',
      name: 'project-list',
      component: () => import('../views/project-list/index.vue'),
      meta: { title: '项目列表' },
    },
    {
      path: '/projects/create',
      name: 'project-create',
      component: () => import('../views/project-create/index.vue'),
      meta: { title: '新建项目' },
    },
    {
      path: '/projects/:id',
      name: 'project-detail',
      component: () => import('../views/project-detail/index.vue'),
      meta: { title: '项目详情' },
    },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · EvoCode` : 'EvoCode'
})

export default router
