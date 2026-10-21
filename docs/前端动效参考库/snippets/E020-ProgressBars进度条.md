# ProgressBars 进度条

## 基本信息

- 效果名称：ProgressBars 进度条组件集
- 触发词：进度条、线性进度、圆形进度、半圆进度、多段进度、波浪进度
- 来源链接：[Codocraft Solutions](https://codocraft.com)（用户提供代码页页脚标注）
- 来源类型：网站参考 / 用户收集代码
- 许可证或版权备注：作为视觉与交互参考使用；投入产品前需核查原始页面的许可证。
- 适合页面：能力分、活动完成度、AI 规划步骤、文件上传、异步任务状态。
- 推荐优先级：高

## 视觉描述

一套深蓝底、金色强调色的进度效果：基础线性进度、条纹、发光、流光；以及 SVG 圆环、CSS 圆环、半圆、多层圆环、脉冲、阶梯、渐变和波浪圆环。所有示例都从 0 过渡到 85%。

## 交互描述

- 数据变化时，线性条从旧值平滑过渡到新值。
- 圆环用 SVG 路径长度或 `conic-gradient` 反映百分比。
- 发光、流光、脉冲适合“正在处理”；完成后的真实分数不宜永久循环，以免误导用户它仍在计算。
- 无障碍：业务组件应有可读文本，并使用 `role="progressbar"`、`aria-valuenow`、`aria-valuemin`、`aria-valuemax`。

## 技术要点

- 主要技术：CSS `transition` / `@keyframes`、SVG `stroke-dasharray` 与 `stroke-dashoffset`、`conic-gradient`。
- 是否依赖第三方库：否。
- 移动端是否适配：是，圆环尺寸应缩小；线性条保持至少 6px 高度。
- 性能风险：条纹/流光/脉冲仅应用于少量进行中任务；能力档案不应让几十个标签同时无限动画。

## 参考代码

```html
<!-- 线性进度 -->
<div class="progress-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="85">
  <div class="progress-fill" style="--progress: 85%"></div>
</div>

<!-- SVG 圆环 -->
<div class="circular-progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="85">
  <svg viewBox="0 0 150 150" aria-hidden="true">
    <circle class="circle-bg" cx="75" cy="75" r="45"></circle>
    <circle class="circle-progress" cx="75" cy="75" r="45" style="--offset: 42.45"></circle>
  </svg>
  <div class="progress-text">85%</div>
</div>

<!-- 半圆 -->
<div class="semi-circular-progress">
  <svg viewBox="0 0 150 150" aria-hidden="true">
    <circle class="circle-bg" cx="75" cy="75" r="45"></circle>
    <circle class="circle-progress" cx="75" cy="75" r="45"></circle>
  </svg>
  <div class="progress-text">85%</div>
</div>

<!-- 多段圆环：可表达“分数 / 可信度”两个维度 -->
<div class="circular-progress-multi">
  <svg viewBox="0 0 150 150" aria-hidden="true">
    <circle class="circle-bg-multi" cx="75" cy="75" r="45"></circle>
    <circle class="circle-progress-multi-1" cx="75" cy="75" r="45"></circle>
    <circle class="circle-progress-multi-2" cx="75" cy="75" r="35"></circle>
  </svg>
  <div class="progress-text">85%</div>
</div>
```

```css
/* 原始参考以 85% 为终点；实际接入时改为由 --progress 或 --offset 驱动。 */
.progress-bar {
  width: 100%;
  height: 20px;
  overflow: hidden;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.2);
  box-shadow: 0 0 10px rgba(237, 190, 0, 0.3);
}

.progress-fill {
  width: var(--progress, 0%);
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #edbe00, #ffed4e);
  transition: width 700ms cubic-bezier(0.22, 1, 0.36, 1);
}

/* 条纹进行态 */
.progress-bar-striped .progress-fill {
  background: repeating-linear-gradient(45deg, #edbe00, #edbe00 10px, #ffed4e 10px, #ffed4e 20px);
  background-size: 28px 28px;
  animation: stripe-move 1s linear infinite;
}

@keyframes stripe-move {
  to { background-position: 28px 0; }
}

/* 发光进行态 */
.progress-bar-glow .progress-fill {
  box-shadow: 0 0 10px #edbe00, 0 0 20px #edbe00, 0 0 30px #edbe00;
  animation: glow-pulse 2s ease-in-out infinite alternate;
}

@keyframes glow-pulse {
  to { box-shadow: 0 0 15px #edbe00, 0 0 30px #edbe00, 0 0 45px #edbe00; }
}

/* 流光进行态 */
.progress-bar-wave .progress-fill {
  position: relative;
  overflow: hidden;
}

.progress-bar-wave .progress-fill::after {
  position: absolute;
  inset: 0;
  content: '';
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.4), transparent);
  animation: progress-wave 2s ease-in-out infinite;
}

@keyframes progress-wave {
  from { transform: translateX(-100%); }
  to { transform: translateX(100%); }
}

.circular-progress,
.circular-progress-multi {
  position: relative;
  width: 150px;
  height: 150px;
}

.circular-progress svg,
.circular-progress-multi svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.circle-bg,
.circle-bg-multi {
  fill: none;
  stroke: rgba(255, 255, 255, 0.2);
  stroke-width: 10;
}

.circle-progress {
  fill: none;
  stroke: #edbe00;
  stroke-width: 10;
  stroke-linecap: round;
  stroke-dasharray: 283;
  stroke-dashoffset: var(--offset, 283);
  transition: stroke-dashoffset 900ms cubic-bezier(0.22, 1, 0.36, 1);
}

.circle-progress-multi-1,
.circle-progress-multi-2 {
  fill: none;
  stroke-linecap: round;
  transition: stroke-dashoffset 900ms cubic-bezier(0.22, 1, 0.36, 1);
}

.circle-progress-multi-1 {
  stroke: #edbe00;
  stroke-width: 8;
  stroke-dasharray: 283;
  stroke-dashoffset: 42.45;
}

.circle-progress-multi-2 {
  stroke: #ffed4e;
  stroke-width: 6;
  stroke-dasharray: 220;
  stroke-dashoffset: 44;
}

.progress-text {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
  font-size: 1.5em;
  font-weight: 700;
}

/* CSS 圆环替代方案 */
.circular-progress-conic {
  position: relative;
  width: 150px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: conic-gradient(#edbe00 calc(var(--progress) * 1%), rgba(255, 255, 255, 0.2) 0);
}

.circular-progress-conic::before {
  position: absolute;
  inset: 10px;
  content: '';
  border-radius: inherit;
  background: #151450;
}

@media (prefers-reduced-motion: reduce) {
  .progress-fill,
  .circle-progress,
  .circle-progress-multi-1,
  .circle-progress-multi-2 { transition: none; }
  .progress-bar-striped .progress-fill,
  .progress-bar-glow .progress-fill,
  .progress-bar-wave .progress-fill::after { animation: none; }
}
```

## 原参考中包含的变体

1. 基础线性条：最适合活动完成度、上传进度。
2. 条纹线性条：适合“正在同步/处理中”，不表示精确百分比时也可使用。
3. 发光与流光线性条：适合 AI 正在生成计划、Outbox 正在投递等短时状态。
4. SVG 圆环：最适合单一能力分、活动完成比例。
5. `conic-gradient` 圆环：不需要 SVG，适合轻量统计卡。
6. 半圆：适合卡片顶部的紧凑概览。
7. 多层圆环：适合同时表达能力分和可信度，但必须配文字说明两个环分别代表什么。
8. 脉冲、阶梯、螺旋、波浪：更偏演示或“进行中”氛围，不适合长期静态展示真实能力分。

## 改造成 Do Not Miss 的方案

1. 现有能力档案继续以圆环或小型线条展示能力值，使用青绿、金色和中性色，不能沿用原参考的深蓝金色主题。
2. Agent Trace、异步任务、文件上传使用线性条：仅任务状态为 `PROCESSING` 时开启条纹或流光，完成后停在静态准确百分比。
3. AI 规划生成页使用步骤型进度：展示“需求理解 → 检索 → 规划 → 校验”的明确状态，避免伪造连续百分比。
4. Vue 组件接受 `value`、`max`、`variant`、`animated` 四个参数；值变化触发一次过渡，不用写死 `85%`。

## 不要直接照搬的地方

- 原代码将百分比、SVG 路径偏移和动画终点全部写死为 85%，只能当演示，不能直接接业务数据。
- 以 `background` 在 `conic-gradient` 之间做关键帧插值兼容性不稳定；生产中优先按 CSS 变量更新角度或使用 SVG。
- 无限脉冲/波浪会让用户以为数据仍未稳定，能力分和可信度必须在计算完成后停下。
