import { defineStore } from 'pinia';
import { useAuthStore } from '@/stores/auth';

export interface MenuItem {
  label: string;
  path: string;
  permission?: string;
  superAdminOnly?: boolean;
}

export const menuItems: MenuItem[] = [
  { label: '实时监控', path: '/monitor', permission: 'menu:monitor' },
  { label: '设备目录', path: '/devices', permission: 'menu:device' },
  { label: '告警中心', path: '/alerts', permission: 'menu:alert' },
  { label: '规则管理', path: '/rules', permission: 'menu:rule' },
  { label: '设备分组', path: '/device-groups', permission: 'menu:device-group' },
  { label: '仪表盘', path: '/dashboards', permission: 'menu:dashboard' },
  { label: '用户管理', path: '/rbac/users', permission: 'menu:user' },
  { label: '角色管理', path: '/rbac/roles', permission: 'menu:role' },
  { label: '租户管理', path: '/tenants', superAdminOnly: true }
];

export const usePermissionStore = defineStore('permission', {
  getters: {
    visibleMenus(): MenuItem[] {
      const auth = useAuthStore();
      return menuItems.filter((item) => {
        if (item.superAdminOnly) return auth.isSuperAdmin;
        return auth.hasPermission(item.permission);
      });
    },
    firstAccessiblePath(): string {
      return this.visibleMenus[0]?.path || '/403';
    }
  }
});
