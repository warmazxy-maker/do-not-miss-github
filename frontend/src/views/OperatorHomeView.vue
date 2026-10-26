<script setup lang="ts">
import { LogOut } from "@lucide/vue";
import { useRouter } from "vue-router";

import { useAuthStore } from "@/stores/auth";

const authStore = useAuthStore();
const router = useRouter();

async function logout() {
  await authStore.logout();
  await router.replace("/login");
}
</script>

<template>
  <div class="ops-shell">
    <header class="ops-header">
      <div>
        <p class="eyebrow">Operator</p>
        <h1>运营后台</h1>
      </div>
      <button type="button" class="ops-back" @click="logout">
        <LogOut :size="17" />
        <span>退出登录</span>
      </button>
    </header>
    <section class="ops-empty">
      <strong>你好，{{ authStore.user?.username }}</strong>
      <p>运营账号负责审核活动和评测候选。审核后台会在方向 D 的 D3 阶段上线，目前这里只是落地页。</p>
    </section>
  </div>
</template>

<style>
@import "@/styles/ops.css";
</style>
