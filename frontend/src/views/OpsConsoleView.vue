<script setup lang="ts">
import { Activity, ArrowLeft, Bot, Database, GitBranch, MessagesSquare, ThumbsUp } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";

import { opsApi } from "@/api/ops";
import OpsAgentRuns from "@/components/ops/OpsAgentRuns.vue";
import OpsJourneys from "@/components/ops/OpsJourneys.vue";
import OpsMessaging from "@/components/ops/OpsMessaging.vue";
import OpsOverview from "@/components/ops/OpsOverview.vue";
import OpsRecommendations from "@/components/ops/OpsRecommendations.vue";
import OpsStorage from "@/components/ops/OpsStorage.vue";
import { useAuthStore } from "@/stores/auth";
import "@/styles/ops.css";

type TabKey = "overview" | "journeys" | "agents" | "recommendations" | "messaging" | "storage";

const authStore = useAuthStore();
const tabs: { key: TabKey; label: string; hint: string; icon: unknown }[] = [
  { key: "overview", label: "总览", hint: "依赖健康与关键计数", icon: Activity },
  { key: "journeys", label: "链路追踪", hint: "一条成果从 SQL → MQ → Agent 的全过程", icon: GitBranch },
  { key: "agents", label: "Agent 运行", hint: "所有用户的 Agent 运行、步骤耗时与产物", icon: Bot },
  { key: "recommendations", label: "推荐", hint: "推荐效果：零结果率、反馈漏斗、不相关原因、最近的推荐", icon: ThumbsUp },
  { key: "messaging", label: "消息队列", hint: "Outbox 待发消息与 RabbitMQ 队列", icon: MessagesSquare },
  { key: "storage", label: "Redis / 数据库", hint: "键空间、连接池、迁移与表大小", icon: Database }
];

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
  if (saved && tabs.some((tab) => tab.key === saved)) {
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
      <RouterLink class="ops-back" :to="homeRoute">
        <ArrowLeft :size="17" />
        <span>返回工作台</span>
      </RouterLink>
      <div>
        <p class="eyebrow">Operations</p>
        <h1>运维观测面板</h1>
      </div>
    </header>

    <section v-if="allowed === null" class="ops-empty">正在确认权限…</section>
    <section v-else-if="!allowed" class="ops-empty">
      <strong>没有访问权限</strong>
      <p>运维面板默认关闭。后端需设置 OPS_CONSOLE_ENABLED=true，并把你的用户 ID 加入 OPS_ADMIN_USERS。</p>
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
      <p class="ops-tab-hint">{{ tabs.find((tab) => tab.key === active)?.hint }}</p>

      <main class="ops-main">
        <OpsOverview v-if="active === 'overview'" @navigate="select" />
        <OpsJourneys v-else-if="active === 'journeys'" @open-run="openRun" />
        <OpsAgentRuns v-else-if="active === 'agents'" :focus-run-id="focusRunId" @consumed="focusRunId = null" />
        <OpsRecommendations v-else-if="active === 'recommendations'" @open-run="openRun" />
        <OpsMessaging v-else-if="active === 'messaging'" />
        <OpsStorage v-else />
      </main>
    </template>
  </div>
</template>
