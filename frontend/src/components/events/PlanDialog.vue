<script setup lang="ts">
import {
  AlertTriangle,
  ArrowRight,
  CalendarCheck2,
  Check,
  LoaderCircle,
  Route,
  Sparkles,
  X
} from "@lucide/vue";
import { computed, ref, watch } from "vue";

import type { RecommendedPlan } from "@/types/events";

const props = defineProps<{
  open: boolean;
  plans: RecommendedPlan[];
  importing: boolean;
  importMessage: string;
}>();

const emit = defineEmits<{
  close: [];
  import: [plan: RecommendedPlan];
  viewSchedule: [];
}>();

const activeIndex = ref(0);
const activePlan = computed(() => props.plans[activeIndex.value]);

watch(
  () => props.open,
  (open) => {
    if (open) {
      activeIndex.value = 0;
    }
  }
);

function stepAgent(stepIndex: number) {
  const node = activePlan.value?.nodes?.find((item) => item.order === stepIndex + 1);
  return node?.agent ?? "";
}
</script>

<template>
  <Teleport to="body">
    <Transition name="overlay">
      <div v-if="open" class="plan-dialog-layer" @click.self="emit('close')">
        <section class="plan-dialog" role="dialog" aria-modal="true" aria-labelledby="plan-dialog-title">
          <header class="plan-dialog-header">
            <div>
              <p class="eyebrow">Multi-Agent Planner</p>
              <h2 id="plan-dialog-title">选择一条可执行路线</h2>
            </div>
            <button class="icon-button" type="button" title="关闭" @click="emit('close')">
              <X :size="20" />
            </button>
          </header>

          <div class="plan-dialog-layout">
            <aside class="plan-choice-list" aria-label="计划方案">
              <button
                v-for="(plan, index) in plans"
                :key="`${plan.title}-${index}`"
                type="button"
                :class="{ 'is-active': index === activeIndex }"
                @click="activeIndex = index"
              >
                <span>方案 {{ index + 1 }}</span>
                <strong>{{ plan.title }}</strong>
                <small>{{ plan.style }} · {{ plan.steps?.length ?? 0 }} 步</small>
              </button>
            </aside>

            <main v-if="activePlan" class="plan-flow-panel">
              <div class="plan-flow-intro">
                <div>
                  <span class="plan-style-label">{{ activePlan.style }}</span>
                  <h3>{{ activePlan.title }}</h3>
                  <p>{{ activePlan.summary }}</p>
                </div>
                <div v-if="activePlan.qualityScore !== null" class="plan-quality-score">
                  <strong>{{ activePlan.qualityScore }}</strong>
                  <span>质量分</span>
                </div>
              </div>

              <div class="plan-flow-line" aria-label="计划流程">
                <template v-for="(step, index) in activePlan.steps" :key="`${step.order}-${step.title}`">
                  <article class="plan-task-card">
                    <div class="plan-task-order">{{ String(index + 1).padStart(2, "0") }}</div>
                    <div>
                      <p>{{ step.dateLabel || "时间待定" }}</p>
                      <h4>{{ step.title }}</h4>
                      <span>{{ step.scheduleHint || "由日程模块安排具体时段" }}</span>
                      <small>{{ step.reason }}</small>
                      <em v-if="stepAgent(index)">{{ stepAgent(index) }}</em>
                    </div>
                  </article>
                  <ArrowRight v-if="index < activePlan.steps.length - 1" class="plan-flow-arrow" :size="20" />
                </template>
              </div>

              <div v-if="activePlan.warnings?.length" class="plan-warning-list">
                <AlertTriangle :size="18" />
                <span>{{ activePlan.warnings.join("；") }}</span>
              </div>

              <div v-if="activePlan.agentTrace?.length" class="plan-agent-chain">
                <Route :size="17" />
                <span v-for="(agent, index) in activePlan.agentTrace" :key="`${agent}-${index}`">
                  {{ agent }}
                  <ArrowRight v-if="index < activePlan.agentTrace.length - 1" :size="13" />
                </span>
              </div>

              <div v-if="importMessage" class="plan-import-message">
                <Check :size="18" />
                <span>{{ importMessage }}</span>
                <button type="button" @click="emit('viewSchedule')">查看日程</button>
              </div>
            </main>
          </div>

          <footer class="plan-dialog-footer">
            <span><Sparkles :size="16" />活动时间会优先使用数据库中的真实时间</span>
            <button
              class="plan-import-button"
              type="button"
              :disabled="!activePlan || importing"
              @click="activePlan && emit('import', activePlan)"
            >
              <LoaderCircle v-if="importing" class="spin" :size="18" />
              <CalendarCheck2 v-else :size="18" />
              {{ importing ? "正在写入" : "写入日程" }}
            </button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
