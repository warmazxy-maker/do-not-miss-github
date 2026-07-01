<script setup lang="ts">
import {
  CalendarPlus,
  ChevronDown,
  ChevronRight,
  CirclePlus,
  Compass,
  Flag,
  LogOut,
  Menu,
  Search,
  X
} from "@lucide/vue";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";

import {
  studentMobileMoreItems,
  studentMobilePrimaryItems,
  studentNavigationGroups,
  studentNavigationItems,
  type StudentNavigationItem
} from "@/navigation/student";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const commandOpen = ref(false);
const commandQuery = ref("");
const commandInput = ref<HTMLInputElement | null>(null);
const quickMenuOpen = ref(false);
const userMenuOpen = ref(false);
const mobileMoreOpen = ref(false);

const initial = computed(() => authStore.user?.username.slice(0, 1).toUpperCase() ?? "D");
const currentTitle = computed(() => String(route.meta.title ?? "学生端"));
const currentSection = computed(() => String(route.meta.section ?? "成长工作台"));
const mobileMoreActive = computed(() =>
  studentMobileMoreItems.some((item) => route.path.startsWith(item.to))
);
const filteredNavigation = computed(() => {
  const query = commandQuery.value.trim().toLowerCase();
  if (!query) {
    return studentNavigationItems;
  }
  return studentNavigationItems.filter((item) => item.label.toLowerCase().includes(query));
});
const todayLabel = new Intl.DateTimeFormat("zh-CN", {
  month: "long",
  day: "numeric",
  weekday: "short"
}).format(new Date());

const quickActions = [
  { label: "查找事件", to: "/student/events", icon: Compass },
  { label: "创建挑战", to: "/student/challenges?action=create", icon: Flag },
  { label: "添加日程", to: "/student/schedule?action=create", icon: CalendarPlus }
];

watch(
  () => route.fullPath,
  () => {
    quickMenuOpen.value = false;
    userMenuOpen.value = false;
    mobileMoreOpen.value = false;
    commandOpen.value = false;
  }
);

watch(commandOpen, async (open) => {
  if (open) {
    commandQuery.value = "";
    await nextTick();
    commandInput.value?.focus();
  }
});

function isActive(item: StudentNavigationItem) {
  return route.path.startsWith(item.to);
}

function toggleQuickMenu() {
  quickMenuOpen.value = !quickMenuOpen.value;
  userMenuOpen.value = false;
}

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value;
  quickMenuOpen.value = false;
}

async function navigate(to: string) {
  commandOpen.value = false;
  quickMenuOpen.value = false;
  userMenuOpen.value = false;
  mobileMoreOpen.value = false;
  await router.push(to);
}

function closeFloatingMenus() {
  quickMenuOpen.value = false;
  userMenuOpen.value = false;
}

async function logout() {
  await authStore.logout();
  await router.replace("/login");
}

function handleGlobalKeydown(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    commandOpen.value = true;
  }
  if (event.key === "Escape") {
    commandOpen.value = false;
    quickMenuOpen.value = false;
    userMenuOpen.value = false;
    mobileMoreOpen.value = false;
  }
}

onMounted(() => window.addEventListener("keydown", handleGlobalKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", handleGlobalKeydown));
</script>

<template>
  <div class="student-shell" @click="closeFloatingMenus">
    <aside class="student-sidebar">
      <RouterLink class="student-brand" to="/student/events">
        <span class="brand-mark">DNM</span>
        <span>
          <strong>do not miss</strong>
          <small>学生成长工作台</small>
        </span>
      </RouterLink>

      <div class="student-profile-strip">
        <span class="student-profile-avatar">{{ initial }}</span>
        <span>
          <strong>{{ authStore.user?.username }}</strong>
          <small>学生账号</small>
        </span>
      </div>

      <nav class="student-navigation" aria-label="学生端功能导航">
        <section v-for="group in studentNavigationGroups" :key="group.label" class="student-nav-group">
          <p>{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            :class="{ 'is-active': isActive(item) }"
          >
            <component :is="item.icon" :size="19" />
            <span>{{ item.label }}</span>
            <ChevronRight :size="15" />
          </RouterLink>
        </section>
      </nav>

      <RouterLink class="student-today-link" to="/student/schedule">
        <CalendarPlus :size="19" />
        <span>
          <small>今天</small>
          <strong>{{ todayLabel }}</strong>
        </span>
        <ChevronRight :size="15" />
      </RouterLink>
    </aside>

    <section class="student-workspace">
      <header class="student-topbar">
        <RouterLink class="student-mobile-brand" to="/student/events">
          <span class="brand-mark">DNM</span>
          <strong>do not miss</strong>
        </RouterLink>

        <div class="student-route-title">
          <span>{{ currentSection }}</span>
          <h1>{{ currentTitle }}</h1>
        </div>

        <div class="student-topbar-actions">
          <button class="student-search-button" type="button" @click="commandOpen = true">
            <Search :size="18" />
            <span>跳转模块</span>
          </button>

          <div class="student-action-menu" @click.stop>
            <button class="student-create-button" type="button" @click="toggleQuickMenu">
              <CirclePlus :size="18" />
              <span>新建</span>
              <ChevronDown :size="15" />
            </button>
            <Transition name="menu">
              <div v-if="quickMenuOpen" class="student-menu-panel quick-action-panel">
                <button
                  v-for="action in quickActions"
                  :key="action.to"
                  type="button"
                  @click="navigate(action.to)"
                >
                  <component :is="action.icon" :size="18" />
                  <span>{{ action.label }}</span>
                </button>
              </div>
            </Transition>
          </div>

          <div class="student-action-menu user-action-menu" @click.stop>
            <button class="student-user-button" type="button" title="账户菜单" @click="toggleUserMenu">
              <span>{{ initial }}</span>
              <ChevronDown :size="15" />
            </button>
            <Transition name="menu">
              <div v-if="userMenuOpen" class="student-menu-panel student-user-menu">
                <div>
                  <strong>{{ authStore.user?.username }}</strong>
                  <small>{{ authStore.user?.email }}</small>
                </div>
                <button type="button" @click="logout">
                  <LogOut :size="17" />
                  <span>退出登录</span>
                </button>
              </div>
            </Transition>
          </div>
        </div>
      </header>

      <main class="student-main">
        <RouterView v-slot="{ Component, route: activeRoute }">
          <Transition name="student-page" mode="out-in">
            <component :is="Component" :key="activeRoute.fullPath" />
          </Transition>
        </RouterView>
      </main>
    </section>

    <nav class="student-mobile-navigation" aria-label="学生端移动导航">
      <RouterLink
        v-for="item in studentMobilePrimaryItems"
        :key="item.to"
        :to="item.to"
        :class="{ 'is-active': isActive(item) }"
      >
        <component :is="item.icon" :size="20" />
        <span>{{ item.shortLabel ?? item.label }}</span>
      </RouterLink>
      <button
        type="button"
        :class="{ 'is-active': mobileMoreActive || mobileMoreOpen }"
        @click="mobileMoreOpen = true"
      >
        <Menu :size="20" />
        <span>更多</span>
      </button>
    </nav>

    <Teleport to="body">
      <Transition name="overlay">
        <div v-if="commandOpen" class="student-overlay" @click.self="commandOpen = false">
          <section class="student-command-dialog" role="dialog" aria-modal="true" aria-label="跳转模块">
            <div class="student-command-search">
              <Search :size="19" />
              <input ref="commandInput" v-model="commandQuery" placeholder="输入模块名称" />
              <button type="button" title="关闭" @click="commandOpen = false">
                <X :size="18" />
              </button>
            </div>
            <div class="student-command-results">
              <button
                v-for="item in filteredNavigation"
                :key="item.to"
                type="button"
                @click="navigate(item.to)"
              >
                <component :is="item.icon" :size="19" />
                <span>{{ item.label }}</span>
                <ChevronRight :size="16" />
              </button>
              <p v-if="filteredNavigation.length === 0">没有匹配的模块</p>
            </div>
          </section>
        </div>
      </Transition>

      <Transition name="mobile-sheet">
        <div v-if="mobileMoreOpen" class="student-mobile-sheet-layer" @click.self="mobileMoreOpen = false">
          <section class="student-mobile-sheet">
            <header>
              <strong>更多</strong>
              <button type="button" title="关闭" @click="mobileMoreOpen = false">
                <X :size="20" />
              </button>
            </header>
            <div class="student-mobile-quick-actions">
              <button
                v-for="action in quickActions"
                :key="action.to"
                type="button"
                @click="navigate(action.to)"
              >
                <component :is="action.icon" :size="20" />
                <span>{{ action.label }}</span>
              </button>
            </div>
            <nav>
              <RouterLink v-for="item in studentMobileMoreItems" :key="item.to" :to="item.to">
                <component :is="item.icon" :size="20" />
                <span>{{ item.label }}</span>
                <ChevronRight :size="16" />
              </RouterLink>
            </nav>
            <button class="student-mobile-logout" type="button" @click="logout">
              <LogOut :size="19" />
              <span>退出登录</span>
            </button>
          </section>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
