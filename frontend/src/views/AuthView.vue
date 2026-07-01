<script setup lang="ts">
import { ArrowRight, Building2, GraduationCap, KeyRound, Mail, UserRound } from "@lucide/vue";
import { computed, reactive, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";

import { ApiError } from "@/api/client";
import { useAuthStore } from "@/stores/auth";
import type { UserRole } from "@/types/auth";

const props = defineProps<{
  mode: "login" | "register";
}>();

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const errorMessage = ref("");
const form = reactive({
  account: "",
  username: "",
  email: "",
  password: "",
  role: "STUDENT" as UserRole
});

const isLogin = computed(() => props.mode === "login");
const title = computed(() => (isLogin.value ? "欢迎回来" : "创建你的成长档案"));
const supportingText = computed(() =>
  isLogin.value
    ? "继续管理活动、挑战、日程和能力证据。"
    : "选择身份后，系统会为你准备对应的工作空间。"
);

watch(
  () => props.mode,
  () => {
    errorMessage.value = "";
  }
);

async function submit() {
  errorMessage.value = "";
  try {
    if (isLogin.value) {
      await authStore.login({
        account: form.account.trim(),
        password: form.password
      });
    } else {
      await authStore.register({
        username: form.username.trim(),
        email: form.email.trim(),
        password: form.password,
        role: form.role
      });
    }

    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : authStore.homeRoute;
    await router.replace(redirect);
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "暂时无法完成认证，请稍后重试。";
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-brand-panel">
      <RouterLink class="brand auth-brand" to="/">
        <span class="brand-mark">DNM</span>
        <span>
          <strong>do not miss</strong>
          <small>student growth platform</small>
        </span>
      </RouterLink>
      <div class="auth-statement">
        <p class="eyebrow">真实经历，而不是空白的四年</p>
        <h1>把每一次行动，沉淀成看得见的成长。</h1>
        <p>发现实践机会、规划行动、完成挑战，并让真实证据形成属于你的能力档案。</p>
      </div>
      <div class="auth-feature-strip">
        <span>活动发现</span>
        <span>AI计划</span>
        <span>能力评估</span>
        <span>成长教练</span>
      </div>
    </section>

    <section class="auth-form-panel">
      <form class="auth-card" @submit.prevent="submit">
        <div class="auth-card-heading">
          <p class="eyebrow">{{ isLogin ? "SIGN IN" : "GET STARTED" }}</p>
          <h2>{{ title }}</h2>
          <p>{{ supportingText }}</p>
        </div>

        <div v-if="!isLogin" class="role-selector" aria-label="注册身份">
          <button
            type="button"
            :class="{ 'is-active': form.role === 'STUDENT' }"
            @click="form.role = 'STUDENT'"
          >
            <GraduationCap :size="20" />
            <span><strong>学生端</strong><small>发现机会并记录成长</small></span>
          </button>
          <button
            type="button"
            :class="{ 'is-active': form.role === 'SOCIAL' }"
            @click="form.role = 'SOCIAL'"
          >
            <Building2 :size="20" />
            <span><strong>社会端</strong><small>发布实践与组织活动</small></span>
          </button>
        </div>

        <label v-if="isLogin" class="form-field">
          <span>用户名或邮箱</span>
          <div class="input-shell">
            <UserRound :size="18" />
            <input v-model="form.account" autocomplete="username" required placeholder="输入用户名或邮箱" />
          </div>
        </label>

        <label v-else class="form-field">
          <span>用户名</span>
          <div class="input-shell">
            <UserRound :size="18" />
            <input
              v-model="form.username"
              autocomplete="username"
              minlength="2"
              maxlength="60"
              required
              placeholder="设置用户名"
            />
          </div>
        </label>

        <label v-if="!isLogin" class="form-field">
          <span>邮箱</span>
          <div class="input-shell">
            <Mail :size="18" />
            <input v-model="form.email" type="email" autocomplete="email" required placeholder="name@example.com" />
          </div>
        </label>

        <label class="form-field">
          <span>密码</span>
          <div class="input-shell">
            <KeyRound :size="18" />
            <input
              v-model="form.password"
              type="password"
              :autocomplete="isLogin ? 'current-password' : 'new-password'"
              minlength="6"
              maxlength="72"
              required
              placeholder="至少 6 位"
            />
          </div>
        </label>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

        <button class="primary-action" type="submit" :disabled="authStore.busy">
          <span>{{ authStore.busy ? "处理中..." : isLogin ? "登录" : "注册并进入" }}</span>
          <ArrowRight :size="19" />
        </button>

        <p class="auth-switch">
          {{ isLogin ? "还没有账号？" : "已经有账号？" }}
          <RouterLink :to="isLogin ? '/register' : '/login'">
            {{ isLogin ? "创建账号" : "直接登录" }}
          </RouterLink>
        </p>
      </form>
    </section>
  </main>
</template>
