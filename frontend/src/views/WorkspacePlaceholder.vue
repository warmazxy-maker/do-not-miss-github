<script setup lang="ts">
import { ArrowUpRight, Blocks, CircleCheck, Layers3 } from "@lucide/vue";
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const title = computed(() => route.meta.title ?? "Do Not Miss");
const description = computed(() => route.meta.description ?? "这个模块正在迁移到新的 Vue 客户端。");
</script>

<template>
  <section class="workspace-view">
    <header class="view-heading">
      <div>
        <p class="eyebrow">VUE WORKSPACE</p>
        <h1>{{ title }}</h1>
        <p>{{ description }}</p>
      </div>
      <span class="migration-status"><CircleCheck :size="17" /> 基础架构已接入</span>
    </header>

    <div class="migration-board">
      <div class="migration-copy">
        <span class="section-icon"><Layers3 :size="22" /></span>
        <p class="eyebrow">当前阶段</p>
        <h2>页面外壳已经迁移，业务组件将在下一阶段逐项接入。</h2>
        <p>
          路由、登录状态、角色权限与API客户端现在由Vue统一管理。旧页面仍保存在
          <code>legacy-index.html</code>，便于对照迁移。
        </p>
      </div>
      <div class="migration-facts">
        <article>
          <Blocks :size="21" />
          <strong>组件化</strong>
          <span>学生端和社会端拥有独立布局与路由边界。</span>
        </article>
        <article>
          <ArrowUpRight :size="21" />
          <strong>可扩展</strong>
          <span>后续模块可以独立开发，不再继续膨胀单个app.js。</span>
        </article>
      </div>
    </div>
  </section>
</template>
