<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const error = ref('');
const form = reactive({
  tenantCode: 'default',
  username: 'admin',
  password: ''
});

async function submit() {
  error.value = '';
  try {
    await auth.login(form);
    router.push(String(route.query.redirect || '/monitor'));
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : '登录失败';
  }
}
</script>

<template>
  <section class="login-page">
    <form class="card login-card grid" @submit.prevent="submit">
      <div>
        <h1>登录 Vionix</h1>
        <p class="muted">使用租户账号进入设备监控平台。</p>
      </div>
      <label>
        租户编码
        <input v-model="form.tenantCode" autocomplete="organization" />
      </label>
      <label>
        用户名
        <input v-model="form.username" autocomplete="username" required />
      </label>
      <label>
        密码
        <input v-model="form.password" autocomplete="current-password" required type="password" />
      </label>
      <p v-if="error" class="error">{{ error }}</p>
      <button :disabled="auth.loading" type="submit">{{ auth.loading ? '登录中...' : '登录' }}</button>
      <p class="muted">refreshToken 由 HttpOnly Cookie 保存，前端不会读取或持久化。</p>
    </form>
  </section>
</template>
