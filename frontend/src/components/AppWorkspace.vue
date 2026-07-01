<script setup lang="ts">
import {
  CalendarDays,
  ChevronRight,
  LogOut,
  Menu,
  Sparkles,
  X,
  type LucideIcon
} from "@lucide/vue";
import { computed, ref } from "vue";
import { RouterLink, RouterView, useRouter } from "vue-router";

import { useAuthStore } from "@/stores/auth";

export interface WorkspaceNavigationItem {
  to: string;
  label: string;
  icon: LucideIcon;
}

const props = defineProps<{
  roleLabel: string;
  navigation: WorkspaceNavigationItem[];
}>();

const router = useRouter();
const authStore = useAuthStore();
const mobileNavigationOpen = ref(false);
const initial = computed(() => authStore.user?.username.slice(0, 1).toUpperCase() ?? "D");

async function logout() {
  await authStore.logout();
  await router.replace("/login");
}
</script>

<template>
  <div class="workspace-shell">
    <header class="workspace-header">
      <RouterLink class="brand" :to="authStore.homeRoute">
        <span class="brand-mark">DNM</span>
        <span>
          <strong>do not miss</strong>
          <small>{{ props.roleLabel }}</small>
        </span>
      </RouterLink>

      <div class="header-actions">
        <div class="session-user">
          <span class="session-avatar">{{ initial }}</span>
          <span>
            <strong>{{ authStore.user?.username }}</strong>
            <small>{{ authStore.user?.email }}</small>
          </span>
        </div>
        <button class="icon-button desktop-logout" type="button" title="退出登录" @click="logout">
          <LogOut :size="19" />
        </button>
        <button
          class="icon-button mobile-menu-button"
          type="button"
          :title="mobileNavigationOpen ? '关闭导航' : '打开导航'"
          @click="mobileNavigationOpen = !mobileNavigationOpen"
        >
          <X v-if="mobileNavigationOpen" :size="21" />
          <Menu v-else :size="21" />
        </button>
      </div>
    </header>

    <div class="workspace-body">
      <aside class="workspace-sidebar" :class="{ 'is-open': mobileNavigationOpen }">
        <div class="sidebar-context">
          <Sparkles :size="18" />
          <span>成长工作台</span>
        </div>
        <nav class="workspace-navigation" aria-label="功能导航">
          <RouterLink
            v-for="item in navigation"
            :key="item.to"
            :to="item.to"
            @click="mobileNavigationOpen = false"
          >
            <component :is="item.icon" :size="19" />
            <span>{{ item.label }}</span>
            <ChevronRight class="nav-chevron" :size="16" />
          </RouterLink>
        </nav>
        <div class="sidebar-footer">
          <CalendarDays :size="18" />
          <span>把经历变成能够回看的成长证据。</span>
        </div>
      </aside>

      <main class="workspace-main">
        <RouterView v-slot="{ Component, route }">
          <Transition name="workspace" mode="out-in">
            <component :is="Component" :key="route.fullPath" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>
