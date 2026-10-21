# E013 SnapDragSchedule 吸附拖拽日程

## 基本信息

- 效果名称：SnapDragSchedule 吸附拖拽日程
- 触发词：高级拖拽日程、时间块吸附、拖拽改时间、日程 resize、网格吸附
- 来源链接：
  - https://interactjs.io/docs/snapping/
  - https://github.com/taye/interact.js
- 来源类型：开源项目 / 官方文档
- 许可证或版权备注：interact.js 为 MIT License；本片段是按 Do Not Miss 日程结构重写的 Vue 适配草案，不直接复制完整示例。
- 适合页面：`StudentScheduleView.vue`、AI 计划写入后的时间块调整、未来周视图日程编辑器
- 推荐优先级：高

## 视觉描述

日程块像日历软件一样贴在时间网格上。拖动时卡片半透明浮起，顶部和底部显示吸附线，松手后自动落到最近的 15 分钟或 30 分钟刻度。调整高度时只改变开始/结束时间，不让卡片自由漂移。

## 交互描述

1. 用户按住时间块拖动，块体跟随手指或鼠标移动。
2. 移动过程中按网格单位吸附，旁边显示新的时间范围。
3. 拖到容器边缘时不越界，最小高度对应 15 分钟。
4. 松手时只提交一次变更，失败则回弹到旧位置。
5. 键盘用户可以用上下方向键微调时间，用 `Shift + 上下` 扩展或缩短时长。

## 技术要点

- 主要技术：interact.js draggable / resizable / snap / restrictRect
- 关键 CSS：固定网格高度、拖动中 `transform`、提交中禁用 pointer
- 关键 JS：像素与分钟互转、乐观更新、失败回滚
- 是否依赖第三方库：需要 `interactjs`
- 移动端是否适配：适配，但触屏上应加长按进入编辑或提供显式拖拽把手
- 性能风险：不要给每个日程块重复注册全局监听；组件卸载必须 `unset()`

## 参考代码

```vue
<script setup lang="ts">
import interact from "interactjs";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";

interface ScheduleBlock {
  id: number;
  title: string;
  startMinute: number;
  endMinute: number;
}

const props = defineProps<{
  item: ScheduleBlock;
  dayStartMinute?: number;
  pxPerMinute?: number;
}>();

const emit = defineEmits<{
  change: [value: ScheduleBlock];
}>();

const blockRef = ref<HTMLElement | null>(null);
const draft = ref({ startMinute: props.item.startMinute, endMinute: props.item.endMinute });

const dayStart = computed(() => props.dayStartMinute ?? 8 * 60);
const pxPerMinute = computed(() => props.pxPerMinute ?? 1.4);
const snapMinutes = 15;

const blockStyle = computed(() => {
  const top = (draft.value.startMinute - dayStart.value) * pxPerMinute.value;
  const height = Math.max(32, (draft.value.endMinute - draft.value.startMinute) * pxPerMinute.value);
  return {
    top: `${top}px`,
    height: `${height}px`
  };
});

onMounted(() => {
  if (!blockRef.value) return;

  interact(blockRef.value)
    .draggable({
      inertia: false,
      modifiers: [
        interact.modifiers.snap({
          targets: [
            interact.snappers.grid({
              x: 1,
              y: snapMinutes * pxPerMinute.value
            })
          ],
          range: 12
        }),
        interact.modifiers.restrictRect({
          restriction: "parent",
          endOnly: false
        })
      ],
      listeners: {
        move(event) {
          const deltaMinutes = Math.round(event.dy / pxPerMinute.value / snapMinutes) * snapMinutes;
          const duration = props.item.endMinute - props.item.startMinute;
          const nextStart = clampToDay(props.item.startMinute + deltaMinutes, duration);

          draft.value = {
            startMinute: nextStart,
            endMinute: nextStart + duration
          };
        },
        end() {
          emit("change", { ...props.item, ...draft.value });
        }
      }
    })
    .resizable({
      edges: { top: true, bottom: true, left: false, right: false },
      modifiers: [
        interact.modifiers.snapSize({
          targets: [
            interact.snappers.grid({
              width: 1,
              height: snapMinutes * pxPerMinute.value
            })
          ],
          range: 12
        }),
        interact.modifiers.restrictSize({
          min: { width: 0, height: snapMinutes * pxPerMinute.value }
        })
      ],
      listeners: {
        move(event) {
          const topDelta = Math.round(event.deltaRect.top / pxPerMinute.value / snapMinutes) * snapMinutes;
          const heightDelta = Math.round(event.deltaRect.height / pxPerMinute.value / snapMinutes) * snapMinutes;
          const nextStart = props.item.startMinute + topDelta;
          const nextEnd = props.item.endMinute + topDelta + heightDelta;

          draft.value = normalizeRange(nextStart, nextEnd);
        },
        end() {
          emit("change", { ...props.item, ...draft.value });
        }
      }
    });
});

onBeforeUnmount(() => {
  if (blockRef.value) interact(blockRef.value).unset();
});

function clampToDay(start: number, duration: number) {
  const minStart = dayStart.value;
  const maxStart = 23 * 60 - duration;
  return Math.min(Math.max(start, minStart), maxStart);
}

function normalizeRange(start: number, end: number) {
  const safeStart = Math.max(dayStart.value, Math.min(start, end - snapMinutes));
  const safeEnd = Math.min(23 * 60, Math.max(end, safeStart + snapMinutes));
  return { startMinute: safeStart, endMinute: safeEnd };
}

function formatMinute(value: number) {
  return `${String(Math.floor(value / 60)).padStart(2, "0")}:${String(value % 60).padStart(2, "0")}`;
}
</script>

<template>
  <article ref="blockRef" class="schedule-drag-block" :style="blockStyle">
    <span class="schedule-drag-handle"></span>
    <strong>{{ item.title }}</strong>
    <small>{{ formatMinute(draft.startMinute) }} - {{ formatMinute(draft.endMinute) }}</small>
  </article>
</template>
```

```css
.schedule-day-column {
  position: relative;
  min-height: 1260px;
  background-image: linear-gradient(to bottom, rgba(31, 41, 55, 0.08) 1px, transparent 1px);
  background-size: 100% 21px;
}

.schedule-drag-block {
  position: absolute;
  left: 8px;
  right: 8px;
  display: grid;
  gap: 4px;
  min-height: 32px;
  padding: 8px 10px 8px 14px;
  border: 1px solid rgba(20, 184, 166, 0.35);
  border-radius: 8px;
  background: rgba(240, 253, 250, 0.96);
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  touch-action: none;
  user-select: none;
}

.schedule-drag-block:hover,
.schedule-drag-block:focus-visible {
  border-color: rgba(13, 148, 136, 0.75);
  box-shadow: 0 12px 30px rgba(15, 118, 110, 0.14);
}

.schedule-drag-handle {
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 5px;
  width: 3px;
  border-radius: 999px;
  background: #14b8a6;
}

@media (prefers-reduced-motion: no-preference) {
  .schedule-drag-block {
    transition: border-color 160ms ease, box-shadow 160ms ease, opacity 160ms ease;
  }
}
```

## 改造成 Do Not Miss 的方案

1. 先把 `StudentScheduleView.vue` 的月视图 / 列表视图拆出 `ScheduleDayColumn.vue` 和 `ScheduleTimeBlock.vue`。
2. 后端仍只接收明确的 `startTime / endTime`；前端拖拽只负责把像素位置转成分钟，再转 ISO 时间。
3. 只在“编辑模式”启用 interact.js，默认查看模式仍是普通按钮或卡片。
4. 拖拽结束后做乐观更新；接口失败时恢复旧时间并弹 toast。
5. 日程块上保留显式编辑按钮，避免触屏用户误拖。

## 不要直接照搬的地方

- 官方 snapping 示例偏通用，不处理业务时间、接口失败回滚、跨日期限制；这些必须按 Do Not Miss 重写。
- 不要把 `transform` 留在最终状态里，否则和顶部定位会叠加出错。
- 不建议日程页面使用强烈弹性或惯性，时间管理工具需要可控、可预期。
- 周视图如果一次渲染大量日程块，应只给可编辑块注册交互。

