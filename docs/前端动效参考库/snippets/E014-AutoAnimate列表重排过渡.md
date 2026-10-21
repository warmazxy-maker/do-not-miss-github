# E014 AutoAnimate 列表重排过渡

## 基本信息

- 效果名称：AutoAnimate 列表重排过渡
- 触发词：列表增删动画、筛选重排、卡片平滑移动、自动布局动画、FLIP 列表
- 来源链接：
  - https://github.com/formkit/auto-animate
  - https://auto-animate.formkit.com/
- 来源类型：开源项目 / 官方文档
- 许可证或版权备注：`@formkit/auto-animate` 为 MIT License；本片段保留 API 思路，具体组件按 Do Not Miss 重写。
- 适合页面：事件列表筛选、预约列表、成长记录分页、能力桶排序、Judge 问题列表、Bad Case 列表
- 推荐优先级：高

## 视觉描述

当列表项新增、删除、筛选或排序时，旧卡片不会突然跳到新位置，而是沿着原来的位置平滑移动。新项轻轻淡入并上移，删除项淡出并收缩，整体感觉像数据自己重新排好了队。

## 交互描述

1. 切换筛选条件后，保留的卡片滑到新位置。
2. 新加载的数据从低透明度进入，不打断用户阅读。
3. 删除或取消关注时，卡片收起，后续项补位。
4. reduced-motion 下关闭自动位移动画，只保留即时更新。

## 技术要点

- 主要技术：`@formkit/auto-animate/vue` 或手写 FLIP
- 关键 CSS：容器布局稳定、卡片高度不要依赖异步图片
- 关键 JS：在容器 ref 上启用自动布局动画
- 是否依赖第三方库：推荐使用 `@formkit/auto-animate`
- 移动端是否适配：适配，持续时间要更短
- 性能风险：不适合虚拟长列表；列表项超过 80 个时应谨慎

## 参考代码

```vue
<script setup lang="ts">
import { useAutoAnimate } from "@formkit/auto-animate/vue";
import { computed, ref } from "vue";

interface EventCard {
  id: number;
  title: string;
  category: string;
  status: "OPEN" | "RESERVED" | "DONE";
}

const props = defineProps<{ items: EventCard[] }>();
const statuses = ["ALL", "OPEN", "RESERVED", "DONE"] as const;
const activeStatus = ref<"ALL" | EventCard["status"]>("ALL");

const [listRef] = useAutoAnimate({
  duration: 220,
  easing: "cubic-bezier(.2,.8,.2,1)"
});

const visibleItems = computed(() => {
  if (activeStatus.value === "ALL") return props.items;
  return props.items.filter((item) => item.status === activeStatus.value);
});
</script>

<template>
  <section class="animated-list-panel">
    <nav class="animated-list-filter" aria-label="列表筛选">
      <button
        v-for="status in statuses"
        :key="status"
        type="button"
        :class="{ 'is-active': activeStatus === status }"
        @click="activeStatus = status"
      >
        {{ status }}
      </button>
    </nav>

    <div ref="listRef" class="auto-animate-list">
      <article v-for="item in visibleItems" :key="item.id" class="auto-animate-card">
        <strong>{{ item.title }}</strong>
        <small>{{ item.category }} · {{ item.status }}</small>
      </article>
    </div>
  </section>
</template>
```

```css
.auto-animate-list {
  display: grid;
  gap: 10px;
}

.auto-animate-card {
  min-height: 76px;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.05);
}

.animated-list-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.animated-list-filter button {
  min-height: 34px;
  padding: 0 12px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 999px;
  background: #fff;
}

.animated-list-filter button.is-active {
  border-color: #0f766e;
  background: #ccfbf1;
  color: #115e59;
}
```

如果不想新增依赖，可以先写一个很小的 FLIP 指令：

```ts
import type { Directive } from "vue";

export const vFlipList: Directive<HTMLElement> = {
  beforeUpdate(element) {
    const positions = new Map<Element, DOMRect>();
    element.querySelectorAll("[data-flip-key]").forEach((child) => {
      positions.set(child, child.getBoundingClientRect());
    });
    (element as HTMLElement & { _flipPositions?: Map<Element, DOMRect> })._flipPositions = positions;
  },
  updated(element) {
    const previous = (element as HTMLElement & { _flipPositions?: Map<Element, DOMRect> })._flipPositions;
    if (!previous || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

    element.querySelectorAll("[data-flip-key]").forEach((child) => {
      const first = previous.get(child);
      const last = child.getBoundingClientRect();
      if (!first) return;

      const dx = first.left - last.left;
      const dy = first.top - last.top;
      if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return;

      child.animate(
        [
          { transform: `translate(${dx}px, ${dy}px)` },
          { transform: "translate(0, 0)" }
        ],
        { duration: 220, easing: "cubic-bezier(.2,.8,.2,1)" }
      );
    });
  }
};
```

## 改造成 Do Not Miss 的方案

1. 事件、预约、成长记录这类列表优先用 AutoAnimate，不必为每个页面手写 TransitionGroup。
2. 能力桶卡片排序变化时也可使用，但只在数量较少时启用。
3. 对接口分页结果，不要跨页做飞行动画；只在当前页内部增删重排。
4. 列表容器必须有稳定 gap 和宽度，卡片里图片或异步内容要预留尺寸。

## 不要直接照搬的地方

- 不要给无限滚动列表、虚拟列表或上百条数据启用自动布局动画。
- 不要把布局动画叠加到 `height: auto` 大段文本展开上；详情展开更适合独立 accordion 动画。
- 不要为了动画改变业务排序，动画只反映数据变化。
