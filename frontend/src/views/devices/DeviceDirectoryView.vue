<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as devicesApi from '@/api/devices';
import type { Device, DeviceStatus } from '@/types/common';
import { formatDateTime } from '@/utils/format';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const loading = ref(false);
const error = ref('');
const devices = ref<Device[]>([]);
const filter = reactive({ keyword: '', status: '' as DeviceStatus | '', source: '' });
const form = reactive<Device>({
  deviceId: '',
  name: '',
  source: 'mqtt',
  location: '',
  metadata: {}
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const page = await devicesApi.listDevices(filter);
    devices.value = page.items || [];
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载设备失败';
  } finally {
    loading.value = false;
  }
}

async function save() {
  try {
    await devicesApi.createDevice(form);
    Object.assign(form, { deviceId: '', name: '', source: 'mqtt', location: '', metadata: {} });
    await load();
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '保存设备失败';
  }
}

async function setStatus(device: Device, status: DeviceStatus) {
  if (!device.deviceId) return;
  await devicesApi.updateDeviceStatus(device.deviceId, status);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>设备目录</h1>
        <p class="muted">维护租户内最小设备元数据，作为监控、规则和仪表盘变量的权限来源。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <div class="card">
      <div class="toolbar">
        <input v-model="filter.keyword" placeholder="设备 ID / 名称" @keyup.enter="load" />
        <select v-model="filter.status" @change="load">
          <option value="">全部状态</option>
          <option value="ENABLED">启用</option>
          <option value="DISABLED">禁用</option>
          <option value="ARCHIVED">归档</option>
        </select>
        <input v-model="filter.source" placeholder="source" @keyup.enter="load" />
        <button type="button" @click="load">查询</button>
      </div>
    </div>

    <form v-if="auth.hasPermission('api:device:manage')" class="card grid three" @submit.prevent="save">
      <input v-model="form.deviceId" placeholder="deviceId，如 sensor-001" required />
      <input v-model="form.name" placeholder="设备名称" required />
      <input v-model="form.source" placeholder="source" />
      <input v-model="form.location" placeholder="位置" />
      <button type="submit">新增设备</button>
    </form>

    <div class="card">
      <p v-if="loading" class="muted">加载中...</p>
      <div v-else-if="devices.length === 0" class="empty">暂无设备</div>
      <table v-else>
        <thead>
          <tr>
            <th>设备</th>
            <th>来源</th>
            <th>位置</th>
            <th>状态</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="device in devices" :key="device.deviceId">
            <td>
              <strong>{{ device.name }}</strong>
              <div class="muted">{{ device.deviceId }}</div>
            </td>
            <td>{{ device.source }}</td>
            <td>{{ device.location || '-' }}</td>
            <td>
              <span class="status" :class="device.status === 'ENABLED' ? 'ok' : device.status === 'DISABLED' ? 'warn' : 'bad'">
                {{ device.status }}
              </span>
            </td>
            <td>{{ formatDateTime(device.updatedAt) }}</td>
            <td class="actions">
              <button class="ghost" type="button" @click="setStatus(device, 'ENABLED')">启用</button>
              <button class="secondary" type="button" @click="setStatus(device, 'DISABLED')">禁用</button>
              <button class="danger" type="button" @click="setStatus(device, 'ARCHIVED')">归档</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
