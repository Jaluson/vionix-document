<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as rbacApi from '@/api/rbac';
import type { PermissionItem, Role } from '@/types/common';

const roles = ref<Role[]>([]);
const permissions = ref<PermissionItem[]>([]);
const error = ref('');
const form = reactive({ name: '', code: '', dataScope: 'TENANT', permissions: [] as string[] });

async function load() {
  error.value = '';
  try {
    const [rolePage, permissionItems] = await Promise.all([rbacApi.listRoles(), rbacApi.listPermissions()]);
    roles.value = rolePage.items || [];
    permissions.value = permissionItems;
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载角色失败';
  }
}

async function create() {
  await rbacApi.createRole({ ...form });
  Object.assign(form, { name: '', code: '', dataScope: 'TENANT', permissions: [] });
  await load();
}

async function remove(role: Role) {
  if (!role.id) return;
  await rbacApi.deleteRole(role.id);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>角色管理</h1>
        <p class="muted">权限码与后端 API 注解保持同一命名基线。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="create">
      <input v-model="form.name" placeholder="角色名称" required />
      <input v-model="form.code" placeholder="角色编码" required />
      <select v-model="form.dataScope">
        <option>SELF</option><option>GROUP</option><option>TENANT</option><option>ALL</option>
      </select>
      <select v-model="form.permissions" multiple>
        <option v-for="permission in permissions" :key="permission.code" :value="permission.code">
          {{ permission.code }}
        </option>
      </select>
      <button type="submit">创建角色</button>
    </form>
    <div class="card">
      <div v-if="roles.length === 0" class="empty">暂无角色</div>
      <table v-else>
        <thead><tr><th>名称</th><th>编码</th><th>数据范围</th><th>权限</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="role in roles" :key="String(role.id)">
            <td>{{ role.name }}</td>
            <td>{{ role.code }}</td>
            <td>{{ role.dataScope || '-' }}</td>
            <td>{{ role.permissions?.length || 0 }}</td>
            <td><button class="danger" type="button" @click="remove(role)">删除</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
