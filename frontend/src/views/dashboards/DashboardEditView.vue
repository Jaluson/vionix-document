<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import * as dashboardsApi from '@/api/dashboards';
import type { Dashboard } from '@/types/common';
import { defaultDashboardLayout, normalizeDashboardLayout } from '@/utils/dashboardSchema';

const route = useRoute();
const router = useRouter();
const dashboard = ref<Dashboard | null>(null);
const title = ref('');
const description = ref('');
const layoutJson = ref('');
const error = ref('');

async function load() {
  try {
    dashboard.value = await dashboardsApi.getDashboard(String(route.params.id));
    title.value = dashboard.value.title || dashboard.value.name || '';
    description.value = dashboard.value.description || '';
    layoutJson.value = JSON.stringify(normalizeDashboardLayout(dashboard.value.layout), null, 2);
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载仪表盘失败';
  }
}

async function save() {
  error.value = '';
  try {
    const layout = normalizeDashboardLayout(layoutJson.value || JSON.stringify(defaultDashboardLayout()));
    await dashboardsApi.updateDashboard(String(route.params.id), {
      title: title.value,
      description: description.value,
      layout
    });
    router.push(`/dashboards/${route.params.id}`);
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '保存失败';
  }
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>编辑仪表盘</h1>
        <p class="muted">JSON 会做基本 schema 校验，且不得包含 tenant_id。</p>
      </div>
      <button type="button" @click="save">保存</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card grid">
      <input v-model="title" placeholder="标题" />
      <input v-model="description" placeholder="描述" />
      <textarea v-model="layoutJson" spellcheck="false"></textarea>
    </div>
  </section>
</template>
