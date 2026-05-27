<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as tenantsApi from '@/api/tenants';
import type { Tenant } from '@/types/common';

const tenants = ref<Tenant[]>([]);
const error = ref('');
const form = reactive<Tenant>({ name: '', code: '', status: 'ENABLED' });

async function load() {
  error.value = '';
  try {
    const page = await tenantsApi.listTenants();
    tenants.value = page.items || [];
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载租户失败';
  }
}

async function create() {
  await tenantsApi.createTenant(form);
  Object.assign(form, { name: '', code: '', status: 'ENABLED' });
  await load();
}

async function disable(tenant: Tenant) {
  if (!tenant.id) return;
  await tenantsApi.deleteTenant(tenant.id);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>租户管理</h1>
        <p class="muted">仅超级管理员可访问，跨租户操作会写审计日志。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="create">
      <input v-model="form.name" placeholder="租户名称" required />
      <input v-model="form.code" placeholder="租户编码" required />
      <select v-model="form.status"><option>ENABLED</option><option>DISABLED</option></select>
      <button type="submit">创建租户</button>
    </form>
    <div class="card">
      <div v-if="tenants.length === 0" class="empty">暂无租户</div>
      <table v-else>
        <thead><tr><th>ID</th><th>名称</th><th>编码</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="tenant in tenants" :key="String(tenant.id)">
            <td>{{ tenant.id }}</td>
            <td>{{ tenant.name }}</td>
            <td>{{ tenant.code }}</td>
            <td>{{ tenant.status }}</td>
            <td><button class="danger" type="button" @click="disable(tenant)">禁用</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
