<script setup lang="ts">
import { Building2, CalendarDays, LoaderCircle } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";

import { organizationsApi, socialApi } from "@/api/workspace";
import type { EventItem } from "@/types/events";
import type { Organization } from "@/types/workspace";

const organizations = ref<Organization[]>([]);
const events = ref<EventItem[]>([]);
const loading = ref(true);
const names = computed(() => [...new Set(events.value.map((event) => event.organizationName))]);

onMounted(async () => {
  try { [organizations.value, events.value] = await Promise.all([organizationsApi.list(), socialApi.mine()]); }
  finally { loading.value = false; }
});

function profile(name: string) {
  return organizations.value.find((item) => item.name === name);
}
</script>

<template>
  <section class="social-module-view">
    <header class="module-page-heading"><div><p class="eyebrow">Organization</p><h2>组织主页</h2><p>根据已经发布的活动汇总组织在平台上的公开信息。</p></div></header>
    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" />正在读取组织</div>
    <div v-else-if="!names.length" class="module-empty"><Building2 :size="30" /><h3>还没有组织资料</h3><p>发布第一场活动后会自动建立组织记录。</p></div>
    <div v-else class="organization-profile-grid">
      <article v-for="name in names" :key="name">
        <span><Building2 :size="26" /></span><div><small>{{ profile(name)?.type || "活动组织" }}</small><h3>{{ name }}</h3><p>{{ profile(name)?.summary || "该组织通过实践活动帮助学生积累真实经历。" }}</p>
          <strong><CalendarDays :size="15" />已发布 {{ events.filter((event) => event.organizationName === name).length }} 场活动</strong>
        </div>
      </article>
    </div>
  </section>
</template>
