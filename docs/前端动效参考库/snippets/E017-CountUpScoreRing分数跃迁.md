# E017 CountUpScoreRing 分数跃迁

## 基本信息

- 效果名称：CountUpScoreRing 分数跃迁
- 触发词：分数滚动、数字跳动、能力分增长、经验值计数、圆环进度
- 来源链接：
  - https://github.com/inorganik/countUp.js
  - https://inorganik.github.io/countUp.js/
- 来源类型：开源项目 / 官方 Demo
- 许可证或版权备注：CountUp.js 为 MIT License；本片段给出轻量 Vue 封装，也提供不引入依赖的 WAAPI 版本。
- 适合页面：能力桶详情、能力评分完成、简历面试定段结果、首页统计卡
- 推荐优先级：中高

## 视觉描述

分数从旧值平滑跳到新值，圆环同步推进。用户能看到“刚刚涨了多少”，但不会像游戏结算页那样夸张。适合展示能力分、经验值、证据数、平均可信度。

## 交互描述

1. 组件首次进入视口时数字从 0 或旧值滚到当前值。
2. 接口刷新后，如果数值变化，数字和圆环同步过渡。
3. 变化幅度较大时显示 `+12.4` 的 delta 标签，几秒后淡出。
4. reduced-motion 下直接显示最终值，不做滚动。

## 技术要点

- 主要技术：CountUp.js 或 `requestAnimationFrame`
- 关键 CSS：conic-gradient 圆环、数字等宽、delta 淡出
- 关键 JS：监听 prop 变化，避免重复播放
- 是否依赖第三方库：可选 `countup.js`
- 移动端是否适配：适配
- 性能风险：不要同时播放几十个数字；列表中只给可见项播放

## 参考代码

```vue
<script setup lang="ts">
import { CountUp } from "countup.js";
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    value: number;
    suffix?: string;
    duration?: number;
  }>(),
  {
    suffix: "",
    duration: 0.8
  }
);

const numberRef = ref<HTMLElement | null>(null);
const previousValue = ref(props.value);
const delta = ref(0);
let countUp: CountUp | undefined;
let deltaTimer: ReturnType<typeof setTimeout> | undefined;

watch(
  () => props.value,
  async (next, previous) => {
    delta.value = next - (previous ?? 0);
    previousValue.value = previous ?? 0;
    await nextTick();
    play(previousValue.value, next);
    if (deltaTimer) clearTimeout(deltaTimer);
    deltaTimer = setTimeout(() => (delta.value = 0), 1800);
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  countUp?.reset();
  if (deltaTimer) clearTimeout(deltaTimer);
});

function play(start: number, end: number) {
  if (!numberRef.value) return;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    numberRef.value.textContent = format(end);
    return;
  }

  countUp = new CountUp(numberRef.value, end, {
    startVal: start,
    duration: props.duration,
    decimalPlaces: Number.isInteger(end) ? 0 : 1,
    suffix: props.suffix,
    useGrouping: false
  });
  countUp.start();
}

function format(value: number) {
  return `${Number.isInteger(value) ? value : value.toFixed(1)}${props.suffix}`;
}
</script>

<template>
  <div class="score-ring" :style="{ '--score': Math.max(0, Math.min(value, 100)) + '%' }">
    <div class="score-ring-orb">
      <strong ref="numberRef">{{ format(value) }}</strong>
      <small>能力分</small>
    </div>
    <Transition name="score-delta">
      <span v-if="delta" class="score-delta">{{ delta > 0 ? "+" : "" }}{{ delta.toFixed(1) }}</span>
    </Transition>
  </div>
</template>
```

```css
.score-ring {
  position: relative;
  display: grid;
  place-items: center;
  width: 116px;
  aspect-ratio: 1;
  border-radius: 50%;
  background:
    radial-gradient(circle at center, #fff 0 58%, transparent 59%),
    conic-gradient(#0f766e var(--score), rgba(15, 23, 42, 0.1) 0);
}

.score-ring-orb {
  display: grid;
  place-items: center;
  width: 82px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: #fff;
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.08);
}

.score-ring strong {
  font-variant-numeric: tabular-nums;
  font-size: 24px;
  line-height: 1;
}

.score-ring small {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
}

.score-delta {
  position: absolute;
  right: -4px;
  top: 4px;
  padding: 3px 7px;
  border-radius: 999px;
  background: #dcfce7;
  color: #166534;
  font-size: 12px;
  font-weight: 700;
}

.score-delta-enter-active,
.score-delta-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.score-delta-enter-from,
.score-delta-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
```

不引入 CountUp.js 时，可以用一个 composable：

```ts
import { onBeforeUnmount, ref, watch, type Ref } from "vue";

export function useAnimatedNumber(source: Ref<number>, duration = 700) {
  const value = ref(source.value);
  let frame = 0;

  watch(source, (next, previous) => {
    cancelAnimationFrame(frame);
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      value.value = next;
      return;
    }

    const from = previous ?? value.value;
    const startedAt = performance.now();

    const tick = (now: number) => {
      const progress = Math.min(1, (now - startedAt) / duration);
      const eased = 1 - Math.pow(1 - progress, 3);
      value.value = from + (next - from) * eased;
      if (progress < 1) frame = requestAnimationFrame(tick);
    };

    frame = requestAnimationFrame(tick);
  });

  onBeforeUnmount(() => cancelAnimationFrame(frame));
  return value;
}
```

## 改造成 Do Not Miss 的方案

1. 个人成就页 `v2-score-ring` 可替换为这个组件，但只对选中的 bucket 播放。
2. 成就统计卡的完成经历数、证据数可以首屏轻量 count-up。
3. 评分完成页可以显示旧分到新分的过渡，并保留公式解释入口。
4. 数字变化必须来自后端返回的真实值，不能在前端模拟“涨分成功”。

## 不要直接照搬的地方

- 不要让所有能力桶卡片同时 count-up，会造成注意力噪音。
- 不要用夸张粒子或长时间滚动，能力分是可信档案，不是抽奖。
- 不要隐藏小数精度规则；分数展示应和后端实际精度一致。

