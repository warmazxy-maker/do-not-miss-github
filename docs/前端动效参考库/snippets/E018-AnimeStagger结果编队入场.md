# E018 AnimeStagger 结果编队入场

## 基本信息

- 效果名称：AnimeStagger 结果编队入场
- 触发词：交错出现、结果流入、卡片编队、stagger、推荐结果揭示、步骤依次点亮
- 来源链接：
  - https://animejs.com/documentation/utilities/stagger/
  - https://github.com/juliangarnier/anime
- 来源类型：开源项目 / 官方文档
- 许可证或版权备注：Anime.js 为 MIT License；本片段用其 stagger 思路重写为 Do Not Miss 的结果列表动画。
- 适合页面：AI 推荐结果、Agent Trace 步骤、简历面试问题生成、能力详情证据卡、Bad Case 处理流
- 推荐优先级：中

## 视觉描述

一组结果不是同时出现，而是按业务顺序轻微错峰进入：第一张先清晰，后续卡片每隔几十毫秒跟上。Agent Trace 可以按步骤依次点亮，AI 推荐结果可以像系统刚整理完一样有秩序地落位。

## 交互描述

1. 数据加载完成后，对当前批次结果播放一次入场。
2. 每个子项使用相同位移和透明度变化，只改变 delay。
3. 用户重新筛选时可以重播，但翻页时不要把旧卡片也重播。
4. reduced-motion 下所有项目立即显示。

## 技术要点

- 主要技术：Anime.js `animate` + `stagger`，或 CSS 自定义 delay
- 关键 CSS：初始 opacity、transform、完成态类名
- 关键 JS：只选择当前容器内的元素；卸载时取消动画
- 是否依赖第三方库：可选 `animejs`
- 移动端是否适配：适配，delay 总时长要短
- 性能风险：不要对 layout 属性做动画，只动 opacity / transform

## 参考代码

```vue
<script setup lang="ts">
import { animate, stagger } from "animejs";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

interface ResultItem {
  id: number;
  title: string;
  reason: string;
}

const props = defineProps<{ items: ResultItem[]; batchKey: string }>();
const listRef = ref<HTMLElement | null>(null);
let animation: ReturnType<typeof animate> | undefined;

watch(
  () => props.batchKey,
  async () => {
    await nextTick();
    play();
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  animation?.pause();
});

function play() {
  if (!listRef.value) return;
  const cards = listRef.value.querySelectorAll<HTMLElement>("[data-stagger-card]");
  cards.forEach((card) => card.classList.remove("is-ready"));

  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    cards.forEach((card) => card.classList.add("is-ready"));
    return;
  }

  animation?.pause();
  animation = animate(cards, {
    opacity: [0, 1],
    y: [12, 0],
    scale: [0.985, 1],
    delay: stagger(58),
    duration: 420,
    ease: "outCubic",
    onComplete: () => cards.forEach((card) => card.classList.add("is-ready"))
  });
}
</script>

<template>
  <div ref="listRef" class="stagger-result-list">
    <article v-for="item in items" :key="item.id" data-stagger-card class="stagger-result-card">
      <strong>{{ item.title }}</strong>
      <p>{{ item.reason }}</p>
    </article>
  </div>
</template>
```

```css
.stagger-result-list {
  display: grid;
  gap: 10px;
}

.stagger-result-card {
  min-height: 74px;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #fff;
  opacity: 0;
  transform: translateY(12px);
}

.stagger-result-card.is-ready {
  opacity: 1;
  transform: none;
}
```

无依赖 CSS 版本：

```vue
<template>
  <div class="css-stagger-list">
    <article
      v-for="(item, index) in items"
      :key="item.id"
      class="css-stagger-card"
      :style="{ '--delay': `${Math.min(index, 8) * 54}ms` }"
    >
      <strong>{{ item.title }}</strong>
      <p>{{ item.reason }}</p>
    </article>
  </div>
</template>
```

```css
.css-stagger-card {
  animation: result-card-in 360ms cubic-bezier(.2,.8,.2,1) both;
  animation-delay: var(--delay);
}

@keyframes result-card-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .css-stagger-card {
    animation: none;
  }
}
```

## 改造成 Do Not Miss 的方案

1. AI 推荐结果每次拿到新 `requestId` 或 `runId` 后，对本批次卡片播放一次。
2. Agent Trace 中可按 step 顺序点亮，但失败步骤要立即可见，不要等动画排队。
3. 简历面试问题生成后，问题卡可以错峰进入，给用户“系统已整理好问题”的反馈。
4. 证据时间线如果已经使用 E016，不需要再叠加 Stagger，避免重复动效。

## 不要直接照搬的地方

- 不要在每次响应式小更新时重播全部卡片；用 `batchKey` 控制播放时机。
- 不要让总 delay 超过 500ms，业务工具不能让用户等动画。
- 不要用旋转、弹跳或大幅缩放，Do Not Miss 的结果列表需要稳定可信。

