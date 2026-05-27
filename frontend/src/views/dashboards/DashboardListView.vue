<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as dashboardsApi from '@/api/dashboards';
import type { Dashboard } from '@/types/common';
import { defaultDashboardLayout } from '@/utils/dashboardSchema';
import { formatDateTime } from '@/utils/format';

const dashboards = ref<Dashboard[]>([]);
const error = ref('');
const form = reactive({ title: '', description: '' });

async function load() {
  error.value = '';
  try {
    const page = await dashboardsApi.listDashboards();
    dashboards.value = page.items || [];
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载仪表盘失败';
  }
}

async function create() {
  await dashboardsApi.createDashboard({
    title: form.title,
    description: form.description,
    layout: defaultDashboardLayout()
  });
  Object.assign(form, { title: '', description: '' });
  await load();
}

async function publish(dashboard: Dashboard) {
  if (!dashboard.id) return;
  await dashboardsApi.publishDashboard(dashboard.id, !(dashboard.published || dashboard.public));
  await load();
}

async function remove(dashboard: Dashboard) {
  if (!dashboard.id) return;
  await dashboardsApi.deleteDashboard(dashboard.id);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>仪表盘</h1>
        <p class="muted">组件数据源复用指标 API 和 WebSocket。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="create">
      <input v-model="form.title" placeholder="仪表盘标题" required />
      <input v-model="form.description" placeholder="描述" />
      <button type="submit">创建仪表盘</button>
    </form>
    <div class="card">
      <div v-if="dashboards.length === 0" class="empty">暂无仪表盘</div>
      <table v-else>
        <thead><tr><th>标题</th><th>描述</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="dashboard in dashboards" :key="String(dashboard.id)">
            <td>{{ dashboard.title || dashboard.name }}</td>
            <td>{{ dashboard.description || '-' }}</td>
            <td>{{ dashboard.published || dashboard.public ? '公开' : '私有' }}</td>
            <td>{{ formatDateTime(dashboard.updatedAt) }}</td>
            <td class="actions">
              <RouterLink :to="`/dashboards/${dashboard.id}`">查看</RouterLink>
              <RouterLink :to="`/dashboards/${dashboard.id}/edit`">编辑</RouterLink>
              <button class="ghost" type="button" @click="publish(dashboard)">切换公开</button>
              <button class="danger" type="button" @click="remove(dashboard)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
