<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as rbacApi from '@/api/rbac';
import type { ManagedUser, Role } from '@/types/common';

const users = ref<ManagedUser[]>([]);
const roles = ref<Role[]>([]);
const error = ref('');
const form = reactive({ username: '', nickname: '', password: '', roles: [] as string[] });

async function load() {
  error.value = '';
  try {
    const [userPage, rolePage] = await Promise.all([rbacApi.listUsers(), rbacApi.listRoles()]);
    users.value = userPage.items || [];
    roles.value = rolePage.items || [];
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载用户失败';
  }
}

async function create() {
  await rbacApi.createUser({ ...form, status: 'ENABLED' });
  Object.assign(form, { username: '', nickname: '', password: '', roles: [] });
  await load();
}

async function toggle(user: ManagedUser) {
  if (!user.id) return;
  await rbacApi.updateUserStatus(user.id, user.status === 'ENABLED' ? 'DISABLED' : 'ENABLED');
  await load();
}

async function remove(user: ManagedUser) {
  if (!user.id) return;
  await rbacApi.deleteUser(user.id);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>用户管理</h1>
        <p class="muted">用户数据自动限制在当前租户。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="create">
      <input v-model="form.username" placeholder="用户名" required />
      <input v-model="form.nickname" placeholder="昵称" />
      <input v-model="form.password" placeholder="初始密码" required type="password" />
      <select v-model="form.roles" multiple>
        <option v-for="role in roles" :key="String(role.id)" :value="role.code || String(role.id)">{{ role.name }}</option>
      </select>
      <button type="submit">创建用户</button>
    </form>
    <div class="card">
      <div v-if="users.length === 0" class="empty">暂无用户</div>
      <table v-else>
        <thead><tr><th>用户名</th><th>昵称</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="user in users" :key="String(user.id)">
            <td>{{ user.username }}</td>
            <td>{{ user.nickname || '-' }}</td>
            <td>{{ user.roles?.join(', ') || '-' }}</td>
            <td>{{ user.status }}</td>
            <td class="actions">
              <button class="ghost" type="button" @click="toggle(user)">切换状态</button>
              <button class="danger" type="button" @click="remove(user)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
