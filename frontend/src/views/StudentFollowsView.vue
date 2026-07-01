<script setup lang="ts">
import { Building2, HeartOff, LoaderCircle, Search } from "@lucide/vue";
import { computed, onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { organizationsApi } from "@/api/workspace";
import type { Organization } from "@/types/workspace";

const organizations = ref<Organization[]>([]);
const followed = ref<string[]>([]);
const query = ref("");
const loading = ref(true);
const busyName = ref("");
const error = ref("");
const visible = computed(() =>
  organizations.value.filter(
    (item) =>
      followed.value.includes(item.name) &&
      `${item.name} ${item.type} ${item.summary}`.toLowerCase().includes(query.value.toLowerCase())
  )
);

onMounted(async () => {
  try {
    const [all, follows] = await Promise.all([organizationsApi.list(), organizationsApi.follows()]);
    organizations.value = all;
    followed.value = follows.map((item) => item.organizationName);
  } catch (cause) {
    error.value = cause instanceof ApiError || cause instanceof Error ? cause.message : "关注加载失败";
  } finally { loading.value = false; }
});

async function unfollow(name: string) {
  busyName.value = name;
  try { await organizationsApi.unfollow(name); followed.value = followed.value.filter((item) => item !== name); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "取消关注失败"; }
  finally { busyName.value = ""; }
}
</script>

<template>
  <section class="follows-workspace-vue">
    <header class="module-page-heading">
      <div><p class="eyebrow">Following</p><h2>持续关注值得信任的组织</h2><p>你关注的公益组织、公司、学校和研究团体会集中在这里。</p></div>
      <label class="module-search"><Search :size="18" /><input v-model="query" placeholder="搜索已关注组织" /></label>
    </header>
    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" />正在读取关注</div>
    <div v-else-if="!visible.length" class="module-empty"><Building2 :size="30" /><h3>没有匹配的关注组织</h3><p>可以在事件列表或 AI 推荐中关注组织。</p></div>
    <div v-else class="organization-card-grid">
      <article v-for="organization in visible" :key="organization.id">
        <span><Building2 :size="22" /></span><div><small>{{ organization.type }}</small><h3>{{ organization.name }}</h3><p>{{ organization.summary || "该组织暂未补充介绍。" }}</p></div>
        <button type="button" :disabled="busyName === organization.name" @click="unfollow(organization.name)">
          <LoaderCircle v-if="busyName === organization.name" class="spin" :size="16" /><HeartOff v-else :size="16" />取消关注
        </button>
      </article>
    </div>
    <p v-if="error" class="module-error">{{ error }}</p>
  </section>
</template>
