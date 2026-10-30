<script setup lang="ts">
import { Activity, ArrowLeft, Bot, ClipboardList, Database, GitBranch, LogOut, MessagesSquare, ScrollText, ThumbsUp, UserPlus } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRouter } from "vue-router";

import { opsApi } from "@/api/ops";
import OpsAgentRuns from "@/components/ops/OpsAgentRuns.vue";
import OpsAuditLogs from "@/components/ops/OpsAuditLogs.vue";
import OpsOperatorAccounts from "@/components/ops/OpsOperatorAccounts.vue";
import OpsJourneys from "@/components/ops/OpsJourneys.vue";
import OpsMessaging from "@/components/ops/OpsMessaging.vue";
import OpsOverview from "@/components/ops/OpsOverview.vue";
import OpsRecommendations from "@/components/ops/OpsRecommendations.vue";
import OpsStorage from "@/components/ops/OpsStorage.vue";
import { useAuthStore } from "@/stores/auth";
import "@/styles/ops.css";

type TabKey = "overview" | "journeys" | "agents" | "recommendations" | "messaging" | "storage" | "audit" | "operators";

const authStore = useAuthStore();
const router = useRouter();

async function logout() {
  await authStore.logout();
  await router.replace("/login");
}
const allTabs: { key: TabKey; label: string; hint: string; icon: unknown }[] = [
  { key: "overview", label: "总览", hint: "依赖健康与关键计数", icon: Activity },
  { key: "journeys", label: "链路追踪", hint: "一条成果从 SQL → MQ → Agent 的全过程", icon: GitBranch },
  { key: "agents", label: "Agent 运行", hint: "所有用户的 Agent 运行、步骤耗时与产物", icon: Bot },
  { key: "recommendations", label: "推荐", hint: "推荐效果：零结果率、反馈漏斗、不相关原因、最近的推荐", icon: ThumbsUp },
  { key: "messaging", label: "消息队列", hint: "Outbox 待发消息与 RabbitMQ 队列", icon: MessagesSquare },
  { key: "storage", label: "Redis / 数据库", hint: "键空间、连接池、迁移与表大小", icon: Database },
  { key: "audit", label: "审计日志", hint: "敏感操作和被拒绝的越权尝试（D1）", icon: ScrollText },
  { key: "operators", label: "运营账号", hint: "只有超级管理员能创建运营账号（D3）", icon: UserPlus }
];
// Creating operator accounts needs ROLE_ASSIGN, which only admin_zero has.
const tabs = computed(() => allTabs.filter((tab) => tab.key !== "operators" || authStore.user?.role === "ADMIN_ZERO"));

const active = ref<TabKey>("overview");
const allowed = ref<boolean | null>(null);
const focusRunId = ref<number | null>(null);
const homeRoute = computed(() => authStore.homeRoute);

onMounted(async () => {
  try {
    allowed.value = (await opsApi.access()).allowed;
  } catch {
    allowed.value = false;
  }
  const saved = sessionStorage.getItem("ops-tab");
  if (saved && tabs.value.some((tab) => tab.key === saved)) {
    active.value = saved as TabKey;
  }
});

function select(key: TabKey) {
  active.value = key;
  sessionStorage.setItem("ops-tab", key);
}

function openRun(runId: number) {
  focusRunId.value = runId;
  select("agents");
}
</script>

<template>
  <div class="ops-shell">
    <header class="ops-header">
      <RouterLink v-if="homeRoute !== '/ops'" class="ops-back" :to="homeRoute">
        <ArrowLeft :size="17" />
        <span>返回工作台</span>
      </RouterLink>
      <button v-else type="button" class="ops-back" @click="logout">
        <LogOut :size="17" />
        <span>退出登录</span>
      </button>
      <div>
        <p class="eyebrow">Operations</p>
        <h1>运维观测面板</h1>
      </div>
      <RouterLink class="ops-back" to="/operator">
        <ClipboardList :size="17" />
        <span>运营后台</span>
      </RouterLink>
    </header>

    <section v-if="allowed === null" class="ops-empty">正在确认权限…</section>
    <section v-else-if="!allowed" class="ops-empty">
      <strong>没有访问权限</strong>
      <p>运维面板需要管理员角色，并且后端总开关 OPS_CONSOLE_ENABLED=true。</p>
    </section>

    <template v-else>
      <nav class="ops-tabs" aria-label="面板分区">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: active === tab.key }"
          :title="tab.hint"
          @click="select(tab.key)"
        >
          <component :is="tab.icon" :size="17" />
          <span>{{ tab.label }}</span>
        </button>
      </nav>
      <p class="ops-tab-hint">{{ allTabs.find((tab) => tab.key === active)?.hint }}</p>

      <main class="ops-main">
        <OpsOverview v-if="active === 'overview'" @navigate="select" />
        <OpsJourneys v-else-if="active === 'journeys'" @open-run="openRun" />
        <OpsAgentRuns v-else-if="active === 'agents'" :focus-run-id="focusRunId" @consumed="focusRunId = null" />
        <OpsRecommendations v-else-if="active === 'recommendations'" @open-run="openRun" />
        <OpsMessaging v-else-if="active === 'messaging'" />
        <OpsAuditLogs v-else-if="active === 'audit'" />
        <OpsOperatorAccounts v-else-if="active === 'operators'" />
        <OpsStorage v-else />
      </main>
    </template>
  </div>
</template>
