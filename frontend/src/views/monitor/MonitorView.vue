<script setup lang="ts">
import * as echarts from 'echarts';
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { listFiringAlerts } from '@/api/alerts';
import { listDashboardDevices } from '@/api/dashboards';
import { queryMetrics } from '@/api/metrics';
import { useAuthStore } from '@/stores/auth';
import { useWebSocketStore } from '@/stores/websocket';
import type { AlertRecord, MetricPoint, MetricSeries } from '@/types/common';
import { formatDateTime, formatNumber, toRelativeStart } from '@/utils/format';

const auth = useAuthStore();
const ws = useWebSocketStore();
const devices = ref<Array<{ deviceId: string; name: string; source?: string }>>([]);
const selectedDevice = ref('');
const metric = ref('temperature');
const points = ref<MetricPoint[]>([]);
const realtime = ref<MetricPoint[]>([]);
const alerts = ref<AlertRecord[]>([]);
const error = ref('');
const loading = ref(false);
const chartEl = ref<HTMLDivElement | null>(null);
let chart: echarts.ECharts | null = null;

const topic = computed(() => {
  if (!auth.user?.tenantId || !selectedDevice.value) return '';
  return `/topic/tenant/${auth.user.tenantId}/device/${selectedDevice.value}/metrics`;
});

function normalizeSeries(response: unknown): MetricSeries | null {
  if (Array.isArray(response)) return response[0] || null;
  if (response && typeof response === 'object' && 'series' in response) {
    const series = (response as { series?: MetricSeries[] }).series;
    return series?.[0] || null;
  }
  return response as MetricSeries;
}

function renderChart() {
  if (!chartEl.value) return;
  chart ||= echarts.init(chartEl.value);
  const merged = [...points.value, ...realtime.value].slice(-160);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: merged.map((point) => formatDateTime(point.time)) },
    yAxis: { type: 'value' },
    series: [{ type: 'line', smooth: true, data: merged.map((point) => point.value) }]
  });
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    if (devices.value.length === 0) {
      devices.value = await listDashboardDevices();
      selectedDevice.value ||= devices.value[0]?.deviceId || '';
    }
    alerts.value = await listFiringAlerts();
    if (selectedDevice.value) {
      const response = await queryMetrics({
        level: 'min',
        fields: metric.value,
        start: toRelativeStart(2),
        agg: 'mean',
        deviceId: selectedDevice.value
      });
      points.value = normalizeSeries(response)?.data || [];
    }
    await nextTick();
    renderChart();
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载监控数据失败';
  } finally {
    loading.value = false;
  }
}

async function subscribe() {
  if (!topic.value) return;
  ws.unsubscribeWhere((item) => item.includes('/metrics'));
  await ws.subscribe<{ metrics: Record<string, number>; time: string }>(topic.value, (message) => {
    const value = message.metrics?.[metric.value];
    if (typeof value === 'number') {
      realtime.value = [...realtime.value, { time: message.time, value }].slice(-120);
      renderChart();
    }
  });
}

watch([selectedDevice, metric], async () => {
  realtime.value = [];
  await load();
  await subscribe();
});

onMounted(async () => {
  await load();
  await subscribe();
});

onBeforeUnmount(() => {
  ws.unsubscribeWhere((item) => item.includes('/metrics'));
  chart?.dispose();
});
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>实时监控</h1>
        <p class="muted">历史趋势来自 `/api/metrics`，秒级点位来自 WebSocket 订阅。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="card toolbar">
      <select v-model="selectedDevice">
        <option value="">请选择设备</option>
        <option v-for="device in devices" :key="device.deviceId" :value="device.deviceId">
          {{ device.name }} ({{ device.deviceId }})
        </option>
      </select>
      <select v-model="metric">
        <option value="temperature">temperature</option>
        <option value="humidity">humidity</option>
        <option value="light">light</option>
        <option value="voltage">voltage</option>
      </select>
      <span class="status" :class="ws.connected ? 'ok' : 'warn'">{{ ws.connected ? 'WS 已连接' : 'WS 未连接' }}</span>
    </div>
    <div class="grid three">
      <div class="card"><strong>{{ selectedDevice || '-' }}</strong><p class="muted">当前设备</p></div>
      <div class="card"><strong>{{ formatNumber(realtime.at(-1)?.value) }}</strong><p class="muted">最新 {{ metric }}</p></div>
      <div class="card"><strong>{{ alerts.length }}</strong><p class="muted">触发中告警</p></div>
    </div>
    <div class="card">
      <p v-if="loading" class="muted">加载中...</p>
      <div v-if="!selectedDevice" class="empty">请先选择一个启用设备</div>
      <div ref="chartEl" class="chart"></div>
    </div>
  </section>
</template>
