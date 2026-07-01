<script setup lang="ts">
import {
  Bot,
  ChevronLeft,
  ChevronRight,
  Filter,
  LayoutList,
  LoaderCircle,
  MapPin,
  MessageSquareText,
  RefreshCw,
  Route,
  Search,
  Send,
  Sparkles,
  WandSparkles,
  X
} from "@lucide/vue";
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";

import { eventsApi } from "@/api/events";
import EventCard from "@/components/events/EventCard.vue";
import PlanDialog from "@/components/events/PlanDialog.vue";
import { ApiError } from "@/api/client";
import type {
  EventItem,
  RecommendedEvent,
  RecommendedPlan,
  ToolContextRequest
} from "@/types/events";

type AiMode = "events" | "plans";
type MobilePane = "browse" | "assistant";

interface AiMessage {
  id: number;
  role: "user" | "assistant";
  text: string;
}

const router = useRouter();
const categories = ["全部", "公益", "企业", "校内", "线上", "研究", "文化"];
const quickNeeds = ["想从零开始学日语", "想提升 Agent 开发能力", "周末想参加有报酬的活动"];
const pageSize = 6;

const events = ref<EventItem[]>([]);
const keyword = ref("");
const appliedKeyword = ref("");
const category = ref("全部");
const benefitType = ref("");
const location = ref("");
const filtersOpen = ref(false);
const loadingEvents = ref(true);
const eventError = ref("");
const currentPage = ref(1);

const followedOrganizations = ref<string[]>([]);
const reservedEventIds = ref<number[]>([]);
const followingOrganization = ref("");
const reservingEventId = ref<number | null>(null);
const actionNotice = ref("");

const mobilePane = ref<MobilePane>("browse");
const aiMode = ref<AiMode>("events");
const aiNeed = ref("");
const aiLoading = ref(false);
const aiError = ref("");
const aiMessage = ref("");
const aiMessages = ref<AiMessage[]>([
  {
    id: 1,
    role: "assistant",
    text: "告诉我你现在想学什么、可用时间或地点偏好。我会保留同一轮对话中的补充条件。"
  }
]);
const recommendedEvents = ref<RecommendedEvent[]>([]);
const recommendedPlans = ref<RecommendedPlan[]>([]);
const planDialogOpen = ref(false);
const importingPlan = ref(false);
const importMessage = ref("");
let messageSequence = 2;
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const totalPages = computed(() => Math.max(1, Math.ceil(events.value.length / pageSize)));
const pagedEvents = computed(() => {
  const start = (currentPage.value - 1) * pageSize;
  return events.value.slice(start, start + pageSize);
});
const resultRange = computed(() => {
  if (!events.value.length) {
    return "0 个结果";
  }
  const start = (currentPage.value - 1) * pageSize + 1;
  const end = Math.min(currentPage.value * pageSize, events.value.length);
  return `${start}-${end} / ${events.value.length}`;
});
const hasActiveFilters = computed(
  () => Boolean(appliedKeyword.value || category.value !== "全部" || benefitType.value || location.value)
);

onMounted(async () => {
  await Promise.all([loadEvents(), loadRelationshipState()]);
});

async function loadEvents() {
  loadingEvents.value = true;
  eventError.value = "";
  try {
    events.value = await eventsApi.search({
      keyword: appliedKeyword.value,
      category: category.value === "全部" ? "" : category.value,
      benefitType: benefitType.value,
      location: location.value
    });
    currentPage.value = 1;
  } catch (error) {
    eventError.value = errorMessage(error, "事件加载失败");
  } finally {
    loadingEvents.value = false;
  }
}

async function loadRelationshipState() {
  try {
    const [follows, reservations] = await Promise.all([
      eventsApi.follows(),
      eventsApi.reservations()
    ]);
    followedOrganizations.value = follows.map((item) => item.organizationName);
    reservedEventIds.value = reservations.map((item) => item.event.id);
  } catch {
    showNotice("关注与预约状态暂时未能同步");
  }
}

async function applySearch() {
  appliedKeyword.value = keyword.value.trim();
  await loadEvents();
}

async function selectCategory(nextCategory: string) {
  category.value = nextCategory;
  await loadEvents();
}

async function clearFilters() {
  keyword.value = "";
  appliedKeyword.value = "";
  category.value = "全部";
  benefitType.value = "";
  location.value = "";
  await loadEvents();
}

function changePage(nextPage: number) {
  currentPage.value = Math.min(Math.max(nextPage, 1), totalPages.value);
  document.querySelector(".events-search-panel")?.scrollIntoView({ behavior: "smooth", block: "start" });
}

function isFollowed(event: EventItem) {
  return followedOrganizations.value.includes(event.organizationName);
}

function isReserved(event: EventItem) {
  return reservedEventIds.value.includes(event.id);
}

async function toggleFollow(event: EventItem) {
  if (followingOrganization.value) {
    return;
  }
  followingOrganization.value = event.organizationName;
  try {
    if (isFollowed(event)) {
      await eventsApi.unfollow(event.organizationName);
      followedOrganizations.value = followedOrganizations.value.filter(
        (item) => item !== event.organizationName
      );
      showNotice(`已取消关注 ${event.organizationName}`);
    } else {
      await eventsApi.follow(event.organizationName);
      followedOrganizations.value = [...followedOrganizations.value, event.organizationName];
      showNotice(`已关注 ${event.organizationName}`);
    }
  } catch (error) {
    showNotice(errorMessage(error, "关注操作失败"));
  } finally {
    followingOrganization.value = "";
  }
}

async function reserve(event: EventItem) {
  if (isReserved(event) || reservingEventId.value !== null) {
    return;
  }
  reservingEventId.value = event.id;
  try {
    await eventsApi.reserve(event.id);
    reservedEventIds.value = [...reservedEventIds.value, event.id];
    showNotice(`已预约「${event.title}」`);
  } catch (error) {
    showNotice(errorMessage(error, "预约失败"));
  } finally {
    reservingEventId.value = null;
  }
}

function setQuickNeed(text: string) {
  aiNeed.value = text;
}

async function submitAi() {
  const need = aiNeed.value.trim();
  if (!need || aiLoading.value) {
    return;
  }

  aiMessages.value.push({ id: messageSequence++, role: "user", text: need });
  aiNeed.value = "";
  aiLoading.value = true;
  aiError.value = "";
  aiMessage.value = "";
  importMessage.value = "";

  try {
    if (aiMode.value === "events") {
      const response = await eventsApi.recommendEvents({
        need,
        category: category.value === "全部" ? "" : category.value,
        benefitType: benefitType.value,
        location: location.value,
        toolContext: buildToolContext()
      });
      recommendedEvents.value = response.recommendations ?? [];
      aiMessage.value = response.message || `找到 ${recommendedEvents.value.length} 个匹配事件`;
      aiMessages.value.push({
        id: messageSequence++,
        role: "assistant",
        text:
          recommendedEvents.value.length > 0
            ? `我结合当前需求筛出了 ${recommendedEvents.value.length} 个高相关活动，已经按适合程度排序。`
            : "这一轮没有足够匹配的活动。你可以补充时间、地点或技能方向，我会继续沿用当前对话。"
      });
    } else {
      const response = await eventsApi.recommendPlans({
        goal: need,
        horizonDays: 21,
        intensity: "normal",
        location: location.value,
        toolContext: buildToolContext()
      });
      recommendedPlans.value = response.plans ?? [];
      aiMessage.value = response.message || `生成 ${recommendedPlans.value.length} 份计划`;
      aiMessages.value.push({
        id: messageSequence++,
        role: "assistant",
        text:
          recommendedPlans.value.length > 0
            ? `我让规划、日程检查和 Critic 协作生成了 ${recommendedPlans.value.length} 份路线，你可以比较后选择一份写入日程。`
            : "这次没有生成可执行计划，请把目标和计划周期描述得更具体一些。"
      });
      planDialogOpen.value = recommendedPlans.value.length > 0;
    }
  } catch (error) {
    aiError.value = errorMessage(error, "AI 服务暂时不可用");
    aiMessages.value.push({
      id: messageSequence++,
      role: "assistant",
      text: aiError.value
    });
  } finally {
    aiLoading.value = false;
  }
}

async function importPlan(plan: RecommendedPlan) {
  importingPlan.value = true;
  importMessage.value = "";
  try {
    const response = await eventsApi.importPlan(
      plan,
      aiMessages.value.filter((item) => item.role === "user").at(-1)?.text ?? plan.summary
    );
    importMessage.value = `已写入 ${response.importedCount} 个日程${
      response.skippedCount ? `，跳过 ${response.skippedCount} 个` : ""
    }`;
  } catch (error) {
    importMessage.value = errorMessage(error, "写入日程失败");
  } finally {
    importingPlan.value = false;
  }
}

function buildToolContext(): ToolContextRequest {
  return {
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Tokyo",
    clientNow: new Date().toISOString(),
    locationText: location.value.trim()
  };
}

function showNotice(message: string) {
  actionNotice.value = message;
  if (noticeTimer) {
    clearTimeout(noticeTimer);
  }
  noticeTimer = setTimeout(() => {
    actionNotice.value = "";
  }, 2800);
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError || error instanceof Error ? error.message : fallback;
}
</script>

<template>
  <section class="events-workspace">
    <div class="events-mobile-switch" aria-label="事件页面视图">
      <button type="button" :class="{ 'is-active': mobilePane === 'browse' }" @click="mobilePane = 'browse'">
        <LayoutList :size="18" />
        浏览事件
      </button>
      <button
        type="button"
        :class="{ 'is-active': mobilePane === 'assistant' }"
        @click="mobilePane = 'assistant'"
      >
        <Sparkles :size="18" />
        AI 助手
      </button>
    </div>

    <section class="events-search-panel" :class="{ 'mobile-hidden': mobilePane !== 'browse' }">
      <header class="events-section-heading">
        <div>
          <p class="eyebrow">Discover</p>
          <h2>找到下一次值得参加的事</h2>
          <span>数据库中的真实活动，可以直接关注和预约。</span>
        </div>
        <button class="events-refresh-button" type="button" title="刷新事件" @click="loadEvents">
          <RefreshCw :class="{ spin: loadingEvents }" :size="18" />
        </button>
      </header>

      <form class="events-search-bar" @submit.prevent="applySearch">
        <Search :size="20" />
        <input v-model="keyword" type="search" placeholder="搜索活动、组织、地点或技能" />
        <button v-if="keyword" class="search-clear-button" type="button" title="清空" @click="keyword = ''">
          <X :size="17" />
        </button>
        <button class="events-search-submit" type="submit">搜索</button>
      </form>

      <div class="events-filter-row">
        <div class="events-category-tabs">
          <button
            v-for="item in categories"
            :key="item"
            type="button"
            :class="{ 'is-active': category === item }"
            @click="selectCategory(item)"
          >
            {{ item }}
          </button>
        </div>
        <button
          class="events-filter-toggle"
          type="button"
          :class="{ 'is-active': filtersOpen || benefitType || location }"
          @click="filtersOpen = !filtersOpen"
        >
          <Filter :size="17" />
          筛选
        </button>
      </div>

      <Transition name="menu">
        <div v-if="filtersOpen" class="events-extra-filters">
          <label>
            <span>收益类型</span>
            <select v-model="benefitType" @change="loadEvents">
              <option value="">全部收益</option>
              <option value="技能经验">技能经验</option>
              <option value="金钱报酬">金钱报酬</option>
              <option value="两者都有">两者都有</option>
            </select>
          </label>
          <label>
            <span>地点</span>
            <div>
              <MapPin :size="16" />
              <input v-model="location" placeholder="例如：大阪、线上" @keyup.enter="loadEvents" />
            </div>
          </label>
          <button type="button" @click="loadEvents">应用筛选</button>
          <button v-if="hasActiveFilters" class="clear-filter-button" type="button" @click="clearFilters">
            清除
          </button>
        </div>
      </Transition>

      <div class="events-result-summary">
        <span>{{ resultRange }}</span>
        <p v-if="hasActiveFilters">
          当前结果已按你的筛选收窄
          <button type="button" @click="clearFilters">恢复全部</button>
        </p>
      </div>

      <div v-if="loadingEvents" class="events-loading-state">
        <LoaderCircle class="spin" :size="24" />
        <span>正在读取可参与活动</span>
      </div>
      <div v-else-if="eventError" class="events-empty-state">
        <h3>事件暂时没有加载出来</h3>
        <p>{{ eventError }}</p>
        <button type="button" @click="loadEvents">重新加载</button>
      </div>
      <div v-else-if="!events.length" class="events-empty-state">
        <Search :size="28" />
        <h3>没有找到匹配事件</h3>
        <p>换一个关键词，或者放宽分类、收益和地点条件。</p>
        <button type="button" @click="clearFilters">查看全部事件</button>
      </div>
      <div v-else class="events-result-list">
        <EventCard
          v-for="event in pagedEvents"
          :key="event.id"
          :event="event"
          :followed="isFollowed(event)"
          :reserved="isReserved(event)"
          :following="followingOrganization === event.organizationName"
          :reserving="reservingEventId === event.id"
          @follow="toggleFollow"
          @reserve="reserve"
        />
      </div>

      <nav v-if="totalPages > 1" class="events-pagination" aria-label="事件分页">
        <button type="button" :disabled="currentPage === 1" @click="changePage(currentPage - 1)">
          <ChevronLeft :size="17" />
          上一页
        </button>
        <span>{{ currentPage }} / {{ totalPages }}</span>
        <button type="button" :disabled="currentPage === totalPages" @click="changePage(currentPage + 1)">
          下一页
          <ChevronRight :size="17" />
        </button>
      </nav>
    </section>

    <aside class="events-ai-panel" :class="{ 'mobile-hidden': mobilePane !== 'assistant' }">
      <header class="ai-panel-heading">
        <div>
          <span class="ai-heading-icon"><Bot :size="22" /></span>
          <div>
            <p class="eyebrow">Agent</p>
            <h2>AI 助手</h2>
          </div>
        </div>
        <span class="ai-online-state">在线</span>
      </header>

      <div class="ai-mode-switch">
        <button type="button" :class="{ 'is-active': aiMode === 'events' }" @click="aiMode = 'events'">
          <WandSparkles :size="17" />
          推荐事件
        </button>
        <button type="button" :class="{ 'is-active': aiMode === 'plans' }" @click="aiMode = 'plans'">
          <Route :size="17" />
          生成计划
        </button>
      </div>

      <div class="ai-conversation" aria-live="polite">
        <div
          v-for="message in aiMessages"
          :key="message.id"
          class="ai-message"
          :class="`is-${message.role}`"
        >
          <MessageSquareText v-if="message.role === 'assistant'" :size="16" />
          <p>{{ message.text }}</p>
        </div>
        <div v-if="aiLoading" class="ai-message is-assistant is-loading">
          <LoaderCircle class="spin" :size="17" />
          <p>{{ aiMode === "events" ? "正在理解需求并检索活动…" : "多个 Agent 正在协作规划…" }}</p>
        </div>
      </div>

      <div v-if="aiMessages.length === 1" class="ai-quick-needs">
        <button v-for="need in quickNeeds" :key="need" type="button" @click="setQuickNeed(need)">
          {{ need }}
        </button>
      </div>

      <div class="ai-composer">
        <textarea
          v-model="aiNeed"
          rows="3"
          :placeholder="
            aiMode === 'events'
              ? '例如：想从零开始学日语，最好线上'
              : '例如：21 天提升 Java 项目设计能力'
          "
          @keydown.ctrl.enter.prevent="submitAi"
          @keydown.meta.enter.prevent="submitAi"
        ></textarea>
        <button type="button" title="发送需求" :disabled="!aiNeed.trim() || aiLoading" @click="submitAi">
          <Send :size="19" />
        </button>
      </div>

      <div v-if="aiMessage || aiError" class="ai-response-meta" :class="{ 'is-error': aiError }">
        {{ aiError || aiMessage }}
      </div>

      <section v-if="aiMode === 'events' && recommendedEvents.length" class="ai-recommendation-results">
        <header>
          <strong>推荐结果</strong>
          <span>{{ recommendedEvents.length }} 个</span>
        </header>
        <article v-for="recommendation in recommendedEvents" :key="recommendation.event.id">
          <div class="ai-recommendation-title">
            <div>
              <span>{{ recommendation.event.category }}</span>
              <h3>{{ recommendation.event.title }}</h3>
            </div>
            <strong>{{ recommendation.score }}</strong>
          </div>
          <p>{{ recommendation.reason }}</p>
          <details v-if="recommendation.evidence?.length">
            <summary>查看匹配证据</summary>
            <ul>
              <li v-for="evidence in recommendation.evidence" :key="evidence">{{ evidence }}</li>
            </ul>
          </details>
          <div class="ai-recommendation-actions">
            <button type="button" @click="toggleFollow(recommendation.event)">
              {{ isFollowed(recommendation.event) ? "已关注" : "关注组织" }}
            </button>
            <button
              type="button"
              :disabled="isReserved(recommendation.event)"
              @click="reserve(recommendation.event)"
            >
              {{ isReserved(recommendation.event) ? "已预约" : "预约" }}
            </button>
          </div>
        </article>
      </section>

      <section v-if="aiMode === 'plans' && recommendedPlans.length" class="ai-plan-result">
        <div>
          <Route :size="21" />
          <span>
            <strong>{{ recommendedPlans.length }} 份可选计划</strong>
            <small>包含活动顺序、时间安排和质量检查</small>
          </span>
        </div>
        <button type="button" @click="planDialogOpen = true">打开流程图</button>
      </section>
    </aside>

    <Transition name="menu">
      <div v-if="actionNotice" class="events-toast">{{ actionNotice }}</div>
    </Transition>

    <PlanDialog
      :open="planDialogOpen"
      :plans="recommendedPlans"
      :importing="importingPlan"
      :import-message="importMessage"
      @close="planDialogOpen = false"
      @import="importPlan"
      @view-schedule="router.push('/student/schedule')"
    />
  </section>
</template>
