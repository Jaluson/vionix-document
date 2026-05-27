import { defineStore } from 'pinia';
import * as authApi from '@/api/auth';
import type { LoginPayload, LoginResponse, UserInfo } from '@/types/common';
import { clearAccessToken, setAccessToken } from '@/utils/token';

function permissionsFromUser(user: UserInfo | null) {
  return Array.isArray(user?.permissions) ? user.permissions : [];
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null as string | null,
    user: null as UserInfo | null,
    permissions: [] as string[],
    bootstrapped: false,
    loading: false
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.accessToken && state.user),
    isSuperAdmin: (state) => state.user?.roles?.includes('SUPER_ADMIN') || false,
    isTenantAdmin: (state) => state.user?.roles?.includes('TENANT_ADMIN') || false
  },
  actions: {
    setSession(session: LoginResponse) {
      this.accessToken = session.accessToken;
      this.user = session.user;
      this.permissions = permissionsFromUser(session.user);
      this.bootstrapped = true;
      setAccessToken(session.accessToken);
    },
    clearSession() {
      this.accessToken = null;
      this.user = null;
      this.permissions = [];
      this.bootstrapped = true;
      clearAccessToken();
    },
    hasPermission(permission?: string | string[]) {
      if (!permission) return true;
      if (this.isSuperAdmin) return true;
      const permissions = Array.isArray(permission) ? permission : [permission];
      if (this.isTenantAdmin && permissions.some((item) => item.startsWith('menu:') || item.startsWith('api:'))) {
        return true;
      }
      return permissions.some((item) => this.permissions.includes(item));
    },
    async login(payload: LoginPayload) {
      this.loading = true;
      try {
        const session = await authApi.login(payload);
        this.setSession(session);
      } finally {
        this.loading = false;
      }
    },
    async refreshSession() {
      const session = await authApi.refresh();
      this.setSession(session);
    },
    async ensureSession() {
      if (this.isAuthenticated) return true;
      if (this.bootstrapped) return false;
      try {
        await this.refreshSession();
        return true;
      } catch {
        this.clearSession();
        return false;
      }
    },
    async logout() {
      try {
        await authApi.logout();
      } finally {
        this.clearSession();
      }
    }
  }
});
