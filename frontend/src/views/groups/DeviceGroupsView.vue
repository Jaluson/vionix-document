<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as groupsApi from '@/api/deviceGroups';
import * as devicesApi from '@/api/devices';
import type { Device, DeviceGroup } from '@/types/common';

const groups = ref<DeviceGroup[]>([]);
const devices = ref<Device[]>([]);
const error = ref('');
const form = reactive({ name: '', description: '' });
const selectedDevice = reactive<Record<string, string>>({});

async function load() {
  error.value = '';
  try {
    const [groupResult, devicePage] = await Promise.all([
      groupsApi.listDeviceGroups(),
      devicesApi.listDevices({ status: 'ENABLED' })
    ]);
    groups.value = Array.isArray(groupResult) ? groupResult : groupResult.items;
    devices.value = devicePage.items;
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载分组失败';
  }
}

async function create() {
  await groupsApi.createDeviceGroup(form);
  Object.assign(form, { name: '', description: '' });
  await load();
}

async function bind(group: DeviceGroup) {
  const deviceId = selectedDevice[String(group.id)];
  if (!deviceId) return;
  await groupsApi.bindDevice(group.id, deviceId);
  await load();
}

async function remove(group: DeviceGroup, deviceId: string) {
  await groupsApi.removeDevice(group.id, deviceId);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>设备分组</h1>
        <p class="muted">分组用于规则目标和数据范围校验。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="create">
      <input v-model="form.name" placeholder="分组名称" required />
      <input v-model="form.description" placeholder="描述" />
      <button type="submit">创建分组</button>
    </form>
    <div v-if="groups.length === 0" class="card empty">暂无分组</div>
    <div v-else class="grid two">
      <article v-for="group in groups" :key="String(group.id)" class="card grid">
        <h2>{{ group.name }}</h2>
        <p class="muted">{{ group.description || '无描述' }}</p>
        <div class="toolbar">
          <select v-model="selectedDevice[String(group.id)]">
            <option value="">选择启用设备</option>
            <option v-for="device in devices" :key="device.deviceId" :value="device.deviceId">
              {{ device.name }} ({{ device.deviceId }})
            </option>
          </select>
          <button type="button" @click="bind(group)">绑定</button>
        </div>
        <div v-if="!group.deviceIds?.length" class="empty">暂无设备</div>
        <div v-else class="actions">
          <button v-for="deviceId in group.deviceIds" :key="deviceId" class="ghost" type="button" @click="remove(group, deviceId)">
            {{ deviceId }} ×
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
