<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import * as dashboardsApi from '@/api/dashboards';
import type { Dashboard, DashboardLayout } from '@/types/common';
import { normalizeDashboardLayout } from '@/utils/dashboardSchema';

const route = useRoute();
const dashboard = ref<Dashboard | null>(null);
const layout = ref<DashboardLayout>({ widgets: [] });
const error = ref('');

async function load() {
  try {
    dashboard.value = await dashboardsApi.getDashboard(String(route.params.id));
    layout.value = normalizeDashboardLayout(dashboard.value.layout);
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载仪表盘失败';
  }
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>{{ dashboard?.title || dashboard?.name || '仪表盘' }}</h1>
        <p class="muted">{{ dashboard?.description || '低代码组件预览' }}</p>
      </div>
      <RouterLink :to="`/dashboards/${route.params.id}/edit`">编辑</RouterLink>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <div v-if="layout.widgets.length === 0" class="card empty">暂无组件</div>
    <div v-else class="dashboard-grid">
      <article v-for="widget in layout.widgets" :key="widget.id" class="card widget">
        <h2>{{ widget.title }}</h2>
        <p class="muted">类型：{{ widget.type }}</p>
        <pre>{{ widget.datasource || widget.config || {} }}</pre>
      </article>
    </div>
  </section>
</template>
