<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import * as devicesApi from '@/api/devices';
import * as groupsApi from '@/api/deviceGroups';
import * as rulesApi from '@/api/rules';
import type { Device, DeviceGroup, Rule } from '@/types/common';

const rules = ref<Rule[]>([]);
const devices = ref<Device[]>([]);
const groups = ref<DeviceGroup[]>([]);
const error = ref('');
const form = reactive<Rule>({
  name: '',
  scope: 'DEVICE',
  targetId: '',
  severity: 'WARNING',
  enabled: true,
  conditions: [{ groupIndex: 0, conditionType: 'THRESHOLD', metric: 'temperature', operator: '>', threshold: 80, durationSeconds: 60 }],
  actions: [{ actionType: 'HTTP', config: {} }]
});

async function load() {
  error.value = '';
  try {
    const [rulePage, devicePage, groupPage] = await Promise.all([
      rulesApi.listRules(),
      devicesApi.listDevices({ status: 'ENABLED' }),
      groupsApi.listDeviceGroups()
    ]);
    rules.value = rulePage.items || [];
    devices.value = devicePage.items || [];
    groups.value = Array.isArray(groupPage) ? groupPage : groupPage.items;
    form.targetId ||= devices.value[0]?.deviceId || '';
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '加载规则失败';
  }
}

async function save() {
  await rulesApi.createRule(form);
  Object.assign(form, {
    name: '',
    scope: 'DEVICE',
    targetId: devices.value[0]?.deviceId || '',
    severity: 'WARNING',
    enabled: true,
    conditions: [{ groupIndex: 0, conditionType: 'THRESHOLD', metric: 'temperature', operator: '>', threshold: 80, durationSeconds: 60 }],
    actions: [{ actionType: 'HTTP', config: {} }]
  });
  await load();
}

async function toggle(rule: Rule) {
  if (!rule.id) return;
  await rulesApi.toggleRule(rule.id, !rule.enabled);
  await load();
}

async function remove(rule: Rule) {
  if (!rule.id) return;
  await rulesApi.deleteRule(rule.id);
  await load();
}

onMounted(load);
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h1>规则管理</h1>
        <p class="muted">当前条件语义为组内 AND、组间 OR。</p>
      </div>
      <button type="button" @click="load">刷新</button>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <form class="card grid three" @submit.prevent="save">
      <input v-model="form.name" placeholder="规则名称" required />
      <select v-model="form.scope">
        <option value="DEVICE">设备</option>
        <option value="GROUP">分组</option>
      </select>
      <select v-if="form.scope === 'DEVICE'" v-model="form.targetId">
        <option v-for="device in devices" :key="device.deviceId" :value="device.deviceId">{{ device.name }}</option>
      </select>
      <select v-else v-model="form.targetId">
        <option v-for="group in groups" :key="String(group.id)" :value="String(group.id)">{{ group.name }}</option>
      </select>
      <select v-model="form.severity">
        <option>INFO</option>
        <option>WARNING</option>
        <option>CRITICAL</option>
      </select>
      <input v-model="form.conditions[0].metric" placeholder="指标" />
      <select v-model="form.conditions[0].operator">
        <option>&gt;</option><option>&gt;=</option><option>&lt;</option><option>&lt;=</option><option>=</option><option>!=</option>
      </select>
      <input v-model.number="form.conditions[0].threshold" placeholder="阈值" type="number" />
      <input v-model.number="form.conditions[0].durationSeconds" placeholder="持续秒数" type="number" />
      <button type="submit">创建规则</button>
    </form>
    <div class="card">
      <div v-if="rules.length === 0" class="empty">暂无规则</div>
      <table v-else>
        <thead><tr><th>名称</th><th>目标</th><th>级别</th><th>条件</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="rule in rules" :key="String(rule.id)">
            <td>{{ rule.name }}</td>
            <td>{{ rule.scope }} / {{ rule.targetId }}</td>
            <td>{{ rule.severity }}</td>
            <td>{{ rule.conditions?.map((item) => `${item.metric} ${item.operator} ${item.threshold}`).join(' OR ') }}</td>
            <td>{{ rule.enabled ? '启用' : '停用' }}</td>
            <td class="actions">
              <button class="ghost" type="button" @click="toggle(rule)">{{ rule.enabled ? '停用' : '启用' }}</button>
              <button class="danger" type="button" @click="remove(rule)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
