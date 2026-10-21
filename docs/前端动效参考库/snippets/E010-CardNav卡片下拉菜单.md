# E010 CardNav 卡片下拉菜单

## 参考定位

- 效果名称：CardNav 卡片下拉导航
- 自然语言触发词：下拉菜单、卡片导航、展开导航、CardNav、顶部折叠菜单
- 原始技术栈：React + GSAP + CSS
- 迁移目标：Vue 3 Transition + ResizeObserver；需要弹性高度时可加 GSAP
- 建议等级：展示型顶部导航或少量二级视图导航。不能替换复杂业务应用的主侧栏。

## 视觉与交互

1. 默认是一个 60px 高的悬浮顶栏：左侧汉堡按钮、中间 Logo、右侧 CTA。
2. 点击汉堡后，顶栏高度向下展开；内部的 1 到 3 张分类卡片从下方淡入上移。
3. 桌面端卡片横向三列，移动端转为竖向堆叠。
4. 每张分类卡下包含少量文本链接，适合把导航按主题分组。
5. 关闭时，卡片先收回，顶栏再恢复为紧凑状态。

它是“顶栏展开成为导航面板”，不是传统 hover 下拉菜单。

## React 用法参考

~~~jsx
<CardNav
  logo={logo}
  logoAlt="Company Logo"
  items={items}
  baseColor="#fff"
  menuColor="#000"
  buttonBgColor="#111"
  buttonTextColor="#fff"
  ease="power3.out"
  theme="light"
/>
~~~

数据结构：

~~~js
{
  label: "Projects",
  bgColor: "#2F293A",
  textColor: "#fff",
  links: [
    { label: "Featured", ariaLabel: "Featured Projects", href: "/featured" },
    { label: "Case Studies", ariaLabel: "Project Case Studies", href: "/cases" }
  ]
}
~~~

## 原实现的关键链路

1. 初始状态把 nav 高度设为 60px，内容区隐藏，卡片设置为 y 50、opacity 0。
2. createTimeline 创建暂停的 GSAP timeline：
   - 顶栏高度补间到 calculateHeight；
   - 三张卡片以 0.08 秒 stagger 上移并淡入。
3. 打开时 play(0)，同时设置 isExpanded 和汉堡按钮状态。
4. 关闭时 reverse，等 onReverseComplete 后再将 isExpanded 设为 false。
5. 窗口 resize 时，重新计算移动端内容高度，并重建 timeline；展开状态下直接恢复到 100% 进度。
6. 桌面高度固定为约 260px；移动端临时把内容区改为 static、可见后读取 scrollHeight，再恢复原样。

## 原实现需要修正的地方

1. 汉堡由 div 加 role="button" 模拟。迁移时必须直接使用 button，获得原生键盘、disabled 和焦点行为。
2. 缺少 Escape 关闭、打开后焦点进入菜单、关闭后焦点回到触发按钮的逻辑。
3. 仅设置 aria-hidden 不会阻止内容里的链接通过 Tab 被聚焦。关闭时应使用 v-if，或 inert 加 tabindex="-1"。
4. CTA 是一个没有事件和语义目标的按钮，只适合原型；业务项目必须有真实命令或不显示。
5. calculateHeight 通过反复改内联样式测量，频繁 resize 时可能触发布局抖动。Vue 中用 ResizeObserver 和内容自然高度更稳。
6. 原版只取 items 的前三项，扩展性受限。超过三个分类时应该改为普通导航、抽屉或分页，而不是继续挤卡片。

## Vue 3 迁移方案

建议组件名为 CardDropdownNav.vue。

~~~vue
<CardDropdownNav
  v-model:open="navOpen"
  :groups="navigationGroups"
  @navigate="router.push($event)"
/>
~~~

实现原则：

1. 顶栏由原生 button 控制，绑定 aria-expanded 和 aria-controls。
2. 内容区优先使用 CSS Grid 和 Transition，max-height 或 grid-template-rows 负责展开；只有确实需要物理弹簧感时引入 GSAP。
3. 用 ResizeObserver 观察内容高度；动画目标值来自实际内容，而不是写死 260px。
4. 使用 v-if 在关闭动画完成后移除链接，或给关闭内容加 inert。
5. 打开时 focus 第一个链接，Escape 或点击遮罩关闭，关闭时 focus 触发按钮。
6. 使用 router-link 而不是 a href，避免 SPA 路由整页刷新。
7. 小屏幕下取消居中的绝对定位 Logo，使用正常 flex 顺序，保证菜单按钮和 Logo 不重叠。

## Do Not Miss 的适合位置

可以用：

1. GitHub 项目展示页或未来独立的产品介绍页：介绍产品、功能地图、架构、Demo、部署指南。
2. 未来运行时程序分析工具：将 Trace、数据流、消息队列、Agent 运行、Bad Case 分为不超过三组。
3. 移动端学生视图的二级导航：当前模块下只有 2 到 3 个子页时，例如“成就 / 能力 / 简历评估”。
4. 社会端活动详情的轻量跳转入口，但只能包含少量相关页面。

不建议：

- 学生端桌面主导航。事件、预约、日程、成就、挑战、Coach 等模块应固定可见，侧栏更高效。
- AI 推荐、计划和 Coach 的工作区顶部。用户正在执行任务时不应被展示式菜单打断。
- 大于三类、每类又有很多链接的后台系统。

## 视觉边界

1. 保持业务工具的浅色背景、8px 以下圆角和清晰层级；不必复制原版大卡片与巨大留白。
2. 使用 Lucide Menu、X 和 ArrowUpRight 图标；不能手绘汉堡和箭头。
3. 卡片展开动画建议 180 到 280 ms。0.5 秒适合展示页，日常业务使用会显慢。
4. 每张导航卡的文字应在移动端自然换行，不能依赖 line-height: 0 一类技巧强行压布局。
5. reduced-motion 下只做淡入、淡出，不做高度大幅弹动。

## 采用时的验收点

- 打开、关闭、Escape、点击背景与路由跳转都正确工作。
- 关闭状态时，内容链接无法被 Tab 获取焦点。
- 文字、Logo、按钮在 320px 宽度到桌面宽度都不重叠。
- 内容高度随菜单项数量变化，窗口 resize 后无截断和闪烁。
- 不影响主侧栏、搜索框和页面业务操作。
