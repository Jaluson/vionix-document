<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { usePermissionStore } from '@/stores/permission';

const auth = useAuthStore();
const permission = usePermissionStore();
const router = useRouter();

const menus = computed(() => permission.visibleMenus);

async function logout() {
  await auth.logout();
  router.push('/login');
}
</script>

<template>
  <div class="app-shell">
    <aside v-if="auth.isAuthenticated" class="sidebar">
      <div class="brand">
        <strong>Vionix</strong>
        <span>IoT Monitor</span>
      </div>
      <nav>
        <RouterLink v-for="item in menus" :key="item.path" :to="item.path">
          {{ item.label }}
        </RouterLink>
      </nav>
      <div class="user-card">
        <span>{{ auth.user?.nickname || auth.user?.username }}</span>
        <small>Tenant {{ auth.user?.tenantId }}</small>
        <button class="ghost" type="button" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="main-panel">
      <RouterView />
    </main>
  </div>
</template>
