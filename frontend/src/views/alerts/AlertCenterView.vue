<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as alertsApi from '@/api/alerts';
import type { AlertRecord, AlertStats } from '@/types/common';
import { formatDateTime, formatNumber } from '@/utils/format';

const loading = ref(false);
const error = ref('');
const alerts = ref<AlertRecord[]>([]);
const firing = ref<AlertRecord[]>([]);
const stats = ref<AlertStats>({});
const filter = reactive({ severity: '', status: '', deviceId: '' });

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [page, firingAlerts, summary] = await Promise.all([
      alertsApi.listAlerts(filter),
      alertsApi.listFiringAlerts(),
      alertsApi.getAlertStats(filter)
    ]);
    alerts.value = page.items || [];
    firing.value = firingAlerts;
    stats.value = summary;
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载告警失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>告警中心</h1>
        <p class="muted">查看触发中和历史告警。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>

    <div class="grid three">
      <div class="card"><strong>{{ formatNumber(stats.total) }}</strong><p class="muted">总告警</p></div>
      <div class="card"><strong>{{ formatNumber(stats.firing) }}</strong><p class="muted">触发中</p></div>
      <div class="card"><strong>{{ formatNumber(stats.critical) }}</strong><p class="muted">严重告警</p></div>
    </div>

    <div class="card toolbar">
      <select v-model="filter.severity" @change="load">
        <option value="">全部级别</option>
        <option>INFO</option>
        <option>WARNING</option>
        <option>CRITICAL</option>
      </select>
      <select v-model="filter.status" @change="load">
        <option value="">全部状态</option>
        <option>FIRING</option>
        <option>RESOLVED</option>
        <option>SUPPRESSED</option>
        <option>ESCALATED</option>
      </select>
      <input v-model="filter.deviceId" placeholder="deviceId" @keyup.enter="load" />
      <button type="button" @click="load">查询</button>
    </div>

    <div class="card">
      <h2>当前触发中</h2>
      <div v-if="firing.length === 0" class="empty">暂无触发中告警</div>
      <ul v-else>
        <li v-for="alert in firing" :key="String(alert.id)">
          {{ alert.severity }} - {{ alert.ruleName || alert.deviceId }} - {{ formatDateTime(alert.firedAt) }}
        </li>
      </ul>
    </div>

    <div class="card">
      <p v-if="loading" class="muted">加载中...</p>
      <div v-else-if="alerts.length === 0" class="empty">暂无历史告警</div>
      <table v-else>
        <thead><tr><th>规则</th><th>设备</th><th>级别</th><th>状态</th><th>触发值</th><th>触发时间</th></tr></thead>
        <tbody>
          <tr v-for="alert in alerts" :key="String(alert.id)">
            <td>{{ alert.ruleName || '-' }}</td>
            <td>{{ alert.deviceId }}</td>
            <td>{{ alert.severity }}</td>
            <td>{{ alert.status }}</td>
            <td>{{ formatNumber(alert.triggerValue) }}</td>
            <td>{{ formatDateTime(alert.firedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
