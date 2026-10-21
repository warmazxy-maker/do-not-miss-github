# E016 ScrollTimeline 证据时间线

## 基本信息

- 效果名称：ScrollTimeline 证据时间线
- 触发词：证据时间线、滚动进度、里程碑揭示、时间轴高亮、滚动驱动动画
- 来源链接：
  - https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scroll-driven_animations
  - https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Scroll-driven_animations/Timelines
- 来源类型：浏览器 CSS 能力 / 技术文档
- 许可证或版权备注：本片段为自写 CSS/Vue 示例，只借鉴 Scroll-driven Animations 的 API 思路。
- 适合页面：Stage 15 标签详情、能力证据时间线、评分审计、Agent Trace 步骤流、简历面试问答过程
- 推荐优先级：高

## 视觉描述

详情页左侧是一条纵向证据线。用户向下滚动时，线条从上到下逐段填充；进入视口的证据卡轻微上浮、变清晰，里程碑节点显示更醒目的圆点。它不是装饰背景，而是帮助用户理解“这个能力为什么涨到这里”。

## 交互描述

1. 打开标签详情时显示证据时间线。
2. 滚动到某条证据附近，节点和卡片变亮。
3. 里程碑节点使用独立样式，但不使用闪烁。
4. 浏览器不支持 scroll timeline 时，回退到 IntersectionObserver 添加 `is-visible` 类。

## 技术要点

- 主要技术：CSS `animation-timeline: view()` / `scroll()`、IntersectionObserver fallback
- 关键 CSS：时间线伪元素、卡片 reveal、进度线 scale
- 关键 JS：只做兼容 fallback，不监听高频 scroll
- 是否依赖第三方库：不需要
- 移动端是否适配：适配，移动端应减少左右偏移
- 性能风险：避免每帧 JS scroll 计算；不要让 box-shadow 大面积变化

## 参考代码

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

interface EvidenceItem {
  id: number;
  title: string;
  summary: string;
  occurredAt: string;
  scoreDelta: number;
  milestone: boolean;
}

defineProps<{ items: EvidenceItem[] }>();

const timelineRef = ref<HTMLElement | null>(null);
let observer: IntersectionObserver | undefined;

onMounted(() => {
  if (CSS.supports("animation-timeline: view()")) return;
  if (!timelineRef.value) return;

  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        entry.target.classList.toggle("is-visible", entry.isIntersecting);
      });
    },
    { threshold: 0.24 }
  );

  timelineRef.value.querySelectorAll(".evidence-step").forEach((element) => observer?.observe(element));
});

onBeforeUnmount(() => {
  observer?.disconnect();
});
</script>

<template>
  <ol ref="timelineRef" class="evidence-timeline">
    <li
      v-for="item in items"
      :key="item.id"
      class="evidence-step"
      :class="{ 'is-milestone': item.milestone }"
    >
      <time>{{ item.occurredAt }}</time>
      <article>
        <header>
          <strong>{{ item.title }}</strong>
          <span>+{{ item.scoreDelta }}</span>
        </header>
        <p>{{ item.summary }}</p>
      </article>
    </li>
  </ol>
</template>
```

```css
.evidence-timeline {
  position: relative;
  display: grid;
  gap: 18px;
  margin: 0;
  padding: 4px 0 4px 28px;
  list-style: none;
}

.evidence-timeline::before,
.evidence-timeline::after {
  content: "";
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 9px;
  width: 2px;
  border-radius: 999px;
}

.evidence-timeline::before {
  background: rgba(15, 23, 42, 0.1);
}

.evidence-timeline::after {
  transform-origin: top;
  background: linear-gradient(#14b8a6, #2563eb);
}

.evidence-step {
  position: relative;
  display: grid;
  gap: 7px;
  opacity: 0.58;
  transform: translateY(12px);
}

.evidence-step::before {
  content: "";
  position: absolute;
  top: 7px;
  left: -25px;
  width: 12px;
  height: 12px;
  border: 2px solid #fff;
  border-radius: 999px;
  background: #94a3b8;
  box-shadow: 0 0 0 3px rgba(148, 163, 184, 0.18);
}

.evidence-step.is-milestone::before {
  background: #0f766e;
  box-shadow: 0 0 0 5px rgba(20, 184, 166, 0.18);
}

.evidence-step article {
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #fff;
}

@supports (animation-timeline: view()) {
  .evidence-timeline::after {
    animation: timeline-fill linear both;
    animation-timeline: view(block 12% 18%);
  }

  .evidence-step {
    animation: evidence-reveal linear both;
    animation-timeline: view(block 82% 14%);
  }
}

@supports not (animation-timeline: view()) {
  .evidence-step.is-visible {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes timeline-fill {
  from {
    transform: scaleY(0);
  }
  to {
    transform: scaleY(1);
  }
}

@keyframes evidence-reveal {
  0% {
    opacity: 0.42;
    transform: translateY(12px);
  }
  38%,
  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .evidence-timeline::after,
  .evidence-step {
    animation: none;
    opacity: 1;
    transform: none;
  }
}
```

## 改造成 Do Not Miss 的方案

1. Stage 15 后端详情接口可以返回 `evidenceLinks + scoreAudits + milestones`，前端合并为时间线。
2. 证据卡显示“来源、发生时间、事实点、分数增量、Judge 状态”，不要只做漂亮节点。
3. 时间线只在详情抽屉或详情页中使用；个人成就首页继续保持密度和可扫描性。
4. 如果证据超过 30 条，默认只展开最近 10 条，其余用“查看更多”分页加载。

## 不要直接照搬的地方

- 不要用滚动动画隐藏重要信息；关闭动效后所有证据仍应可读。
- 不要对每条证据加复杂 3D 或模糊效果，时间线是解释工具。
- 不要用持续闪烁表示里程碑；用颜色、图标、粗细即可。

