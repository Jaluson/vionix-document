import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { usePermissionStore } from '@/stores/permission';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    public?: boolean;
    permission?: string | string[];
    superAdminOnly?: boolean;
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: () => {
      const auth = useAuthStore();
      return auth.isAuthenticated ? usePermissionStore().firstAccessiblePath : '/login';
    }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/monitor',
    name: 'monitor',
    component: () => import('@/views/monitor/MonitorView.vue'),
    meta: { title: '实时监控', permission: 'menu:monitor' }
  },
  {
    path: '/devices',
    name: 'devices',
    component: () => import('@/views/devices/DeviceDirectoryView.vue'),
    meta: { title: '设备目录', permission: 'menu:device' }
  },
  {
    path: '/alerts',
    name: 'alerts',
    component: () => import('@/views/alerts/AlertCenterView.vue'),
    meta: { title: '告警中心', permission: 'menu:alert' }
  },
  {
    path: '/rules',
    name: 'rules',
    component: () => import('@/views/rules/RulesView.vue'),
    meta: { title: '规则管理', permission: 'menu:rule' }
  },
  {
    path: '/device-groups',
    name: 'device-groups',
    component: () => import('@/views/groups/DeviceGroupsView.vue'),
    meta: { title: '设备分组', permission: 'menu:device-group' }
  },
  {
    path: '/dashboards',
    name: 'dashboards',
    component: () => import('@/views/dashboards/DashboardListView.vue'),
    meta: { title: '仪表盘', permission: 'menu:dashboard' }
  },
  {
    path: '/dashboards/:id',
    name: 'dashboard-view',
    component: () => import('@/views/dashboards/DashboardView.vue'),
    meta: { title: '查看仪表盘', permission: ['menu:dashboard', 'api:dashboard:view'] }
  },
  {
    path: '/dashboards/:id/edit',
    name: 'dashboard-edit',
    component: () => import('@/views/dashboards/DashboardEditView.vue'),
    meta: { title: '编辑仪表盘', permission: ['api:dashboard:manage', 'menu:dashboard'] }
  },
  {
    path: '/rbac/users',
    name: 'rbac-users',
    component: () => import('@/views/rbac/UsersView.vue'),
    meta: { title: '用户管理', permission: 'menu:user' }
  },
  {
    path: '/rbac/roles',
    name: 'rbac-roles',
    component: () => import('@/views/rbac/RolesView.vue'),
    meta: { title: '角色管理', permission: 'menu:role' }
  },
  {
    path: '/tenants',
    name: 'tenants',
    component: () => import('@/views/tenants/TenantsView.vue'),
    meta: { title: '租户管理', superAdminOnly: true }
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/errors/ForbiddenView.vue'),
    meta: { title: '403' }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/403'
  }
];

export const router = createRouter({
  history: createWebHistory(),
  routes
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  const permission = usePermissionStore();

  if (to.meta.public) {
    if (to.path === '/login' && auth.isAuthenticated) return permission.firstAccessiblePath;
    return true;
  }

  const ok = await auth.ensureSession();
  if (!ok) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }

  if (to.meta.superAdminOnly && !auth.isSuperAdmin) {
    return '/403';
  }

  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return '/403';
  }

  return true;
});

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - Vionix` : 'Vionix';
});
