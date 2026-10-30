<script setup lang="ts">
import { ArrowLeft, ClipboardList, FlaskConical, LayoutList, LogOut } from "@lucide/vue";
import { computed, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";

import OpsEvalCandidates from "@/components/ops/OpsEvalCandidates.vue";
import OperatorEvents from "@/components/operator/OperatorEvents.vue";
import OperatorQueue from "@/components/operator/OperatorQueue.vue";
import { useAuthStore } from "@/stores/auth";
import "@/styles/ops.css";

/** Operator console (D3): review queue, all events, evaluation candidates. Admins can open it too. */
type TabKey = "queue" | "events" | "eval";

const authStore = useAuthStore();
const router = useRouter();
const tabs: { key: TabKey; label: string; icon: unknown }[] = [
  { key: "queue", label: "审核队列", icon: ClipboardList },
  { key: "events", label: "活动管理", icon: LayoutList },
  { key: "eval", label: "评测候选", icon: FlaskConical }
];
const active = ref<TabKey>(readTab());
const isAdmin = computed(() => ["ADMIN", "ADMIN_ZERO"].includes(authStore.user?.role ?? ""));

function readTab(): TabKey {
  try {
    const saved = sessionStorage.getItem("operator-tab");
    if (saved && tabs.some((tab) => tab.key === saved)) return saved as TabKey;
  } catch { /* storage unavailable */ }
  return "queue";
}

function select(key: TabKey) {
  active.value = key;
  try { sessionStorage.setItem("operator-tab", key); } catch { /* storage unavailable */ }
}

async function logout() {
  await authStore.logout();
  await router.replace("/login");
}
</script>

<template>
  <div class="ops-shell">
    <header class="ops-header">
      <RouterLink v-if="isAdmin" class="ops-back" to="/ops">
        <ArrowLeft :size="17" />
        <span>返回运维面板</span>
      </RouterLink>
      <button v-else type="button" class="ops-back" @click="logout">
        <LogOut :size="17" />
        <span>退出登录</span>
      </button>
      <div>
        <p class="eyebrow">Operator</p>
        <h1>运营后台</h1>
      </div>
    </header>

    <nav class="ops-tabs" aria-label="运营后台分区">
      <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ active: active === tab.key }" @click="select(tab.key)">
        <component :is="tab.icon" :size="17" />
        <span>{{ tab.label }}</span>
      </button>
    </nav>

    <main class="ops-main">
      <OperatorQueue v-if="active === 'queue'" />
      <OperatorEvents v-else-if="active === 'events'" />
      <OpsEvalCandidates v-else :show-run-links="false" />
    </main>
  </div>
</template>
