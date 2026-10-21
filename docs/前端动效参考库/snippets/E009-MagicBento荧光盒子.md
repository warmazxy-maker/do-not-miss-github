# E009 MagicBento 荧光盒子

## 参考定位

- 效果名称：MagicBento 聚光灯卡片矩阵
- 自然语言触发词：荧光盒子、Bento、聚光灯卡片、发光边框、Magic Bento
- 原始技术栈：React + GSAP + CSS Variables
- 迁移目标：Vue 3 + CSS Variables + requestAnimationFrame；粒子和磁吸按需启用
- 建议等级：重点信息总览。用于少量可交互卡片，不可把每个页面都做成发光卡片墙。

## 视觉与交互

1. 卡片组成不规则 Bento 网格，桌面端有重点卡跨越两列或两行，小屏幕收敛为单列。
2. 鼠标进入区域时，一层跟随鼠标的全局聚光灯照亮附近卡片。
3. 每张卡根据鼠标在卡片中的相对位置更新 CSS 变量，边框显示局部径向光晕。
4. 可选粒子在悬停时从卡片内出现、轻微漂移；点击时出现从点击点扩散的涟漪。
5. 可选倾斜和磁吸让卡片向鼠标轻微偏移，但它们不是默认必需效果。

## React 用法参考

~~~jsx
<MagicBento
  textAutoHide={true}
  enableStars
  enableSpotlight
  enableBorderGlow={true}
  enableTilt={false}
  enableMagnetism={false}
  clickEffect
  spotlightRadius={400}
  particleCount={12}
  glowColor="132, 0, 255"
  disableAnimations={false}
/>
~~~

核心参数：

| 参数 | 含义 | Do Not Miss 建议 |
| --- | --- | --- |
| textAutoHide | 标题和描述多行截断 | 开启，但关键业务信息不能只靠截断隐藏 |
| enableStars | 悬停粒子 | 默认关闭；只用于庆祝性结果卡 |
| enableSpotlight | 全局跟随聚光灯 | 能力概览可开，常规列表关闭 |
| enableBorderGlow | 鼠标附近的边框光晕 | 可以作为默认的轻动效 |
| enableTilt | 卡片 3D 倾斜 | 默认关闭，读数据时会干扰对齐 |
| enableMagnetism | 卡片向鼠标偏移 | 默认关闭，避免布局显得不稳 |
| clickEffect | 点击涟漪 | 仅对明确可点击的卡片或按钮启用 |
| spotlightRadius | 聚光灯作用半径 | 240 到 360 px |
| particleCount | 悬停粒子数量 | 4 到 8；原始 12 已偏多 |
| glowColor | RGB 形式的发光色 | 迁移为品牌青绿或中性强调色，不沿用紫光主题 |
| disableAnimations | 动效总开关 | 必须受 prefers-reduced-motion 控制 |

## 原实现的五层机制

### 1. Bento Grid

CSS Grid 在宽屏时使用四列，并让第 3、4 张卡跨行跨列；窄屏收敛为单列。原版卡片为深色、大圆角、4:3 比例。

Do Not Miss 迁移时：

- 只让真正的重点卡占大格，例如“当前能力摘要”或“本周计划”。
- 业务工具卡圆角控制在 8px 左右；不照搬 20px。
- 不把普通页面区块都包成卡片，Bento 只用于一组独立、可比较的摘要项目。

### 2. 全局 Spotlight

原组件在 document mousemove 中计算鼠标到每张卡中心的距离：

1. 距离低于 proximity，卡片 glowIntensity 为 1。
2. 位于 proximity 和 fadeDistance 之间，按线性比例衰减。
3. 把鼠标相对卡片坐标写入 --glow-x、--glow-y，并写入 --glow-intensity。
4. 卡片伪元素使用 radial-gradient 只绘制在边框区域，形成“鼠标扫过点亮边缘”。

边框光晕的核心样式：

~~~css
.magic-bento-card--border-glow::after {
  content: "";
  position: absolute;
  inset: 0;
  padding: 2px;
  background: radial-gradient(
    var(--glow-radius) circle at var(--glow-x) var(--glow-y),
    rgba(var(--glow-rgb), calc(var(--glow-intensity) * 0.55)) 0%,
    transparent 60%
  );
  border-radius: inherit;
  pointer-events: none;
}
~~~

### 3. 悬停粒子

原版首次悬停会预生成若干粒子位置，随后延迟克隆这些粒子到卡片中；每个粒子会漂移、旋转和呼吸，离开时缩小淡出并删除。

这是装饰层，不能影响数据展示。Do Not Miss 默认不启用，只有能力评估完成、Judge 通过等少量结果可用。

### 4. 点击涟漪

卡片点击时，基于点击点到四角的最远距离创建圆形渐变，再放大并淡出。它适合表示“卡片确实已被点开”，不能取代按钮的 loading 或业务成功提示。

### 5. 倾斜与磁吸

鼠标相对卡片中心的位置被映射为 rotateX、rotateY 和轻微的 x、y 位移。原版使用 GSAP 缓动。

这是最容易让信息面板变得花哨的一层。对 Do Not Miss 的能力数据、日程、AI 推荐结果默认关闭。

## 原实现需要修正的地方

1. 原版在 document 上处理 mousemove，每次移动遍历所有卡并连续启动 GSAP tween。卡片较多时会增加主线程工作。
2. enableStars 为 false 的分支把事件监听放在 React ref 回调里；重渲染时可能重复 addEventListener，且没有对应清理。
3. 原版主题是深底紫光、大圆角；直接复制会与 Do Not Miss 当前的理性、浅色业务风格冲突。
4. 原 CSS 使用 transition: all，会不必要地过渡布局和尺寸属性；迁移时只过渡 transform、box-shadow、border-color、opacity。
5. 粒子会创建和移除许多 DOM 节点，连续悬停多个卡片时需要限量与清理。
6. 全局 spotlight 放入 body，若忘记卸载会残留一层 DOM；路由切换时必须移除。

## Vue 3 迁移方案

建议拆为两个组件：

- BentoSummaryGrid.vue：只负责网格、数据插槽与响应式布局。
- SpotlightCard.vue：只负责单卡边框光晕、可选点击涟漪。

实现原则：

1. 父容器用 pointermove 记录最新指针位置；requestAnimationFrame 内统一更新当前附近卡片的 CSS 变量。
2. 可用 data-spotlight-card 标记卡片；只计算容器内的卡片，不监听整个 document。
3. 组件可见时才启用 pointermove；IntersectionObserver 离开视口时暂停。
4. 粒子使用固定 DOM 池，或仅保留 4 到 8 个元素；不每次悬停大量 clone。
5. 倾斜、磁吸和粒子全部是独立 props，默认 false。
6. 在 CSS 中设置 --glow-rgb、--glow-x、--glow-y、--glow-intensity，组件无需为每次鼠标移动创建动画对象。
7. prefers-reduced-motion 时移除 spotlight 跟随，只保留 hover 的静态边框色。

## Do Not Miss 的适合位置

优先使用：

1. 个人成就页顶部的能力摘要：知识与技术、核心职能、已验证能力、待 Judge 项目。
2. 简历面试评估结果：简历事实、面试证据、能力定段、建议补强方向。
3. Guide Agent 的计划选择：最多三份候选计划，让用户比较目标、周期和安排强度。
4. 未来运行时程序分析工具的总览：请求数、Agent 成功率、平均耗时、Bad Case 数。

不建议：

- 事件检索的每一张活动卡。
- 社会端审核队列、预约列表、Coach 长对话。
- 已经存在多层卡片的区域，不能再嵌套 Bento 卡。
- 大量标签墙；标签应该保持轻量、紧凑、可扫描。

## 视觉与无障碍边界

1. 采用浅色底、青绿或蓝绿色光晕；不要把页面改成暗紫色赛博风。
2. 卡片 hover 只能是辅助反馈，所有可点击卡必须有键盘 focus-visible 状态。
3. 卡片若承担导航或操作，应该用 button 或 a，而不是只绑定 div click。
4. 文字与边框光晕始终保持对比度；聚光灯不能降低正文可读性。
5. reduced-motion 下不可出现粒子、倾斜、磁吸或快速涟漪。

## 采用时的验收点

- 单个卡片的光晕跟随流畅，快速扫过多张卡不会明显掉帧。
- 调整浏览器宽度后重点卡布局合理，文字不溢出。
- 鼠标、键盘、触屏都能进入卡片的真实操作。
- 路由切换后没有残留 spotlight、粒子或事件监听。
- 深色、浅色及 reduced-motion 条件下均有可读的回退样式。
