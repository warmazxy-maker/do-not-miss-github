<script setup lang="ts">
import { Building2, CalendarRange, SquarePen } from "@lucide/vue";

import { computed, watch } from "vue";
import { useRoute } from "vue-router";

import AppWorkspace, { type WorkspaceNavigationItem } from "@/components/AppWorkspace.vue";
import { useOrganizerAttention } from "@/stores/organizerAttention";

const route = useRoute();
const attention = useOrganizerAttention();
// Re-checked on every page change inside the organisation workspace; no polling.
watch(() => route.fullPath, attention.refresh, { immediate: true });

const navigation = computed<WorkspaceNavigationItem[]>(() => [
  { to: "/social/events", label: "我的事件", icon: CalendarRange, badge: attention.count.value },
  { to: "/social/publish", label: "发布事件", icon: SquarePen },
  { to: "/social/organization", label: "组织主页", icon: Building2 }
]);
</script>

<template>
  <AppWorkspace role-label="社会端" :navigation="navigation" />
</template>
