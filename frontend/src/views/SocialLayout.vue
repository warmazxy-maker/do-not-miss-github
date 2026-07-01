<script setup lang="ts">
import { Building2, CalendarRange, SquarePen } from "@lucide/vue";

import AppWorkspace, { type WorkspaceNavigationItem } from "@/components/AppWorkspace.vue";

const navigation: WorkspaceNavigationItem[] = [
  { to: "/social/events", label: "我的事件", icon: CalendarRange },
  { to: "/social/publish", label: "发布事件", icon: SquarePen },
  { to: "/social/organization", label: "组织主页", icon: Building2 }
];
</script>

<template>
  <AppWorkspace role-label="社会端" :navigation="navigation" />
</template>
