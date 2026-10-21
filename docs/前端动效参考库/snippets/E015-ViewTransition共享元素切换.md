# E015 ViewTransition 共享元素切换

## 基本信息

- 效果名称：ViewTransition 共享元素切换
- 触发词：共享元素、卡片展开详情、页面丝滑切换、标签详情展开、SPA 视图过渡
- 来源链接：
  - https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API
  - https://developer.chrome.com/docs/web-platform/view-transitions
  - https://view-transitions.chrome.dev/
- 来源类型：浏览器 API 文档 / Chrome DevRel 示例集合
- 许可证或版权备注：Chrome 文档代码示例为 Apache 2.0；本片段使用浏览器 API 自写 Vue Router / 状态切换适配。
- 适合页面：个人成就页能力桶 -> 标签详情、事件卡 -> 事件详情、简历面试列表 -> 会话详情
- 推荐优先级：高

## 视觉描述

用户点击一张能力标签卡片时，卡片标题、分数圆环或主面板不是直接闪切，而是从原位置“长”到详情区域。旧视图淡出，新视图淡入，用户能感到自己仍在同一个对象上深入查看。

## 交互描述

1. 点击能力桶卡片，选中态和详情区域一起过渡。
2. 同一个 bucket 的标题或分数元素设置相同 `view-transition-name`。
3. 浏览器支持时使用原生 View Transition；不支持时直接切换，保持功能完整。
4. 切换速度保持 180 到 260ms，不做夸张缩放。

## 技术要点

- 主要技术：`document.startViewTransition()`、`view-transition-name`、Vue 状态更新
- 关键 CSS：`::view-transition-old()` / `::view-transition-new()`、共享元素命名
- 关键 JS：把状态更新包进 `startViewTransition`
- 是否依赖第三方库：不需要
- 移动端是否适配：支持浏览器才启用；不支持自动降级
- 性能风险：参与共享过渡的元素不能太多；名称必须唯一

## 参考代码

```vue
<script setup lang="ts">
import { computed, ref } from "vue";

interface Bucket {
  bucketId: number;
  displayName: string;
  abilityScore: number;
}

const props = defineProps<{ buckets: Bucket[] }>();
const selectedBucketId = ref<number | null>(props.buckets[0]?.bucketId ?? null);

const selectedBucket = computed(() =>
  props.buckets.find((bucket) => bucket.bucketId === selectedBucketId.value) ?? null
);

function selectBucket(bucketId: number) {
  const update = () => {
    selectedBucketId.value = bucketId;
  };

  if (!document.startViewTransition || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    update();
    return;
  }

  document.startViewTransition(update);
}

function transitionName(bucket: Bucket, part: "title" | "score") {
  return `bucket-${bucket.bucketId}-${part}`;
}
</script>

<template>
  <section class="vt-bucket-workspace">
    <div class="vt-bucket-grid">
      <button
        v-for="bucket in buckets"
        :key="bucket.bucketId"
        type="button"
        class="vt-bucket-card"
        :class="{ 'is-active': selectedBucketId === bucket.bucketId }"
        @click="selectBucket(bucket.bucketId)"
      >
        <strong :style="{ viewTransitionName: transitionName(bucket, 'title') }">
          {{ bucket.displayName }}
        </strong>
        <span :style="{ viewTransitionName: transitionName(bucket, 'score') }">
          {{ bucket.abilityScore }}
        </span>
      </button>
    </div>

    <aside v-if="selectedBucket" class="vt-bucket-detail">
      <h3 :style="{ viewTransitionName: transitionName(selectedBucket, 'title') }">
        {{ selectedBucket.displayName }}
      </h3>
      <strong :style="{ viewTransitionName: transitionName(selectedBucket, 'score') }">
        {{ selectedBucket.abilityScore }}
      </strong>
    </aside>
  </section>
</template>
```

```css
::view-transition-old(root),
::view-transition-new(root) {
  animation-duration: 220ms;
  animation-timing-function: cubic-bezier(.2,.8,.2,1);
}

::view-transition-group(*) {
  animation-duration: 240ms;
  animation-timing-function: cubic-bezier(.2,.8,.2,1);
}

.vt-bucket-card,
.vt-bucket-detail {
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  background: #fff;
}

.vt-bucket-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  min-height: 70px;
  padding: 12px;
  text-align: left;
}

.vt-bucket-card.is-active {
  border-color: #0f766e;
  background: #f0fdfa;
}

.vt-bucket-detail {
  min-height: 220px;
  padding: 20px;
}

@media (prefers-reduced-motion: reduce) {
  ::view-transition-old(root),
  ::view-transition-new(root),
  ::view-transition-group(*) {
    animation-duration: 1ms;
  }
}
```

Vue Router 页面切换也可以包一层：

```ts
import type { Router } from "vue-router";

export function installViewTransitionRouter(router: Router) {
  router.beforeResolve((to, from) => {
    if (to.fullPath === from.fullPath || !document.startViewTransition) return true;

    return new Promise((resolve) => {
      document.startViewTransition(() => resolve(true));
    });
  });
}
```

实际项目里更推荐先用于同页面状态切换，不急着全局接管路由。

## 改造成 Do Not Miss 的方案

1. Stage 15 标签详情可以先在 `StudentAchievementsView.vue` 内做同页面共享元素切换。
2. 只有被点击的 bucket 标题、分数圈使用 `view-transition-name`，不要给整页所有卡片命名。
3. 如果之后拆成 `/student/achievements/:bucketId` 详情路由，再考虑路由级 View Transition。
4. 对 Safari / Firefox 兼容要接受渐进增强，不支持时页面直接切换。

## 不要直接照搬的地方

- 不要让多个元素使用同一个 `view-transition-name`，会导致浏览器无法正确匹配。
- 不要把长列表每个元素都纳入共享过渡；长列表改用 E014 的列表重排。
- 不要在过渡中触发接口请求；先更新本地选中状态，详情数据可以显示骨架再异步加载。

