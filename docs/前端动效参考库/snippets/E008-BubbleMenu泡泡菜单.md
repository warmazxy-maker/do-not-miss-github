# E008 BubbleMenu 泡泡菜单

## 参考定位

- 效果名称：BubbleMenu 胶囊展开菜单
- 自然语言触发词：泡泡菜单、胶囊菜单、展开导航、Bubble Menu、移动端快捷菜单
- 原始技术栈：React + GSAP + CSS
- 迁移目标：Vue 3 Transition + CSS，复杂弹性展开时可引入 GSAP
- 建议等级：次级导航或展示型菜单。不可取代信息密集业务系统的主导航。

## 视觉与交互

1. 页面顶部左侧是 Logo 胶囊，右侧是圆形菜单按钮。
2. 点击菜单后，中间区域出现多枚大尺寸胶囊链接。
3. 胶囊按顺序缩放弹出，文字稍后上移淡入，形成有弹性的“泡泡菜单”效果。
4. 桌面端每个胶囊可以带很轻的旋转；悬停时放大并变成自己的强调色。
5. 小屏幕下改为垂直单列，避免旋转与多列造成触控困难。

它的“泡泡”来自圆角 999px 的胶囊链接和缩放动画，不是自由漂浮的圆形元素。

## React 用法参考

~~~jsx
<BubbleMenu
  logo={<span style={{ fontWeight: 700 }}>RB</span>}
  items={items}
  menuAriaLabel="Toggle navigation"
  menuBg="#ffffff"
  menuContentColor="#111111"
  useFixedPosition={false}
  animationEase="back.out(1.5)"
  animationDuration={0.5}
  staggerDelay={0.12}
/>
~~~

单个菜单项的结构：

~~~js
{
  label: "projects",
  href: "#",
  ariaLabel: "Projects",
  rotation: 8,
  hoverStyles: {
    bgColor: "#f59e0b",
    textColor: "#ffffff"
  }
}
~~~

## 参数说明

| 参数 | 含义 | 建议 |
| --- | --- | --- |
| logo | Logo 图片或节点 | Do Not Miss 可显示 DNM 标识，不要塞复杂信息 |
| items | 菜单项 | 建议最多 4 到 6 项；过多就应使用普通抽屉 |
| menuAriaLabel | 菜单开关的无障碍名称 | 例如“打开快捷导航” |
| menuBg | 胶囊背景色 | 优先高对比的中性色 |
| menuContentColor | 图标、文字的颜色 | 与背景保持足够对比 |
| useFixedPosition | 是否固定在视口上方 | 移动端浮动菜单可开；页内展示一般关闭 |
| animationEase | 打开时的缓动 | back.out 可用，但回弹不能太大 |
| animationDuration | 单项展开时长 | 0.25 到 0.45 秒 |
| staggerDelay | 菜单项之间的错峰 | 0.05 到 0.10 秒 |

## 原实现的关键链路

1. isMenuOpen 记录开关状态，showOverlay 保证关闭动画结束前，覆盖层仍留在 DOM。
2. 打开时：
   - overlay display 改为 flex；
   - 胶囊链接 scale 从 0 弹到 1；
   - 标签从 y 24、透明，过渡到 y 0、可见；
   - 每个项目按 staggerDelay 错峰。
3. 关闭时先让文字下移淡出，再把胶囊缩到 0，动画完成后隐藏 overlay。
4. resize 时，桌面端使用每项 rotation，小屏幕转角归零。
5. CSS 用三列布局放大胶囊；若最后一行只有 1 或 2 个项目，用 nth-child 规则做视觉居中。

## 原实现需要修正的地方

1. 缺少 Escape 关闭。菜单本质是覆盖层，键盘用户必须能按 Escape 返回。
2. 缺少焦点管理。打开后焦点应移动到第一个菜单项，关闭后返回开关按钮。
3. 只设置 aria-hidden 并不能阻止隐藏中的链接获得焦点。关闭动画期间应使用 inert 或让链接 tabindex 为 -1。
4. 缺少点击遮罩空白处关闭的行为；如果菜单覆盖主要内容，应提供。
5. 没有 current route 状态，用户无法知道自己正在何处。
6. 大尺寸胶囊在后台系统很占空间，不能用它承载事件、预约、能力标签等高频操作。

## Vue 3 迁移方案

组件建议命名为 BubbleQuickMenu.vue。

~~~vue
<BubbleQuickMenu
  :items="quickLinks"
  :open="quickMenuOpen"
  @update:open="quickMenuOpen = $event"
/>
~~~

实现原则：

1. 用 Teleport to="body" 渲染 fixed 覆盖层，避免被父容器 overflow 或 transform 截断。
2. Vue TransitionGroup 控制链接依次进入；只有想要明显弹簧曲线时才引入 GSAP。
3. 打开时记录 document.activeElement，把第一个链接 focus；关闭时恢复到菜单按钮。
4. overlay 监听 keydown Escape、点击自身关闭；点击菜单内容时 stopPropagation。
5. 开放时给背景应用 inert，或使用可控 Focus Trap；不能让焦点穿透到被覆盖的页面。
6. current route 项加 aria-current="page"，并使用稳定的激活色。
7. 手机端保持单列、每个入口最小 44 px 点击面积；桌面端旋转应很小，默认不超过 4 度。

## Do Not Miss 的适合位置

可以用：

1. 手机端“跳转模块”的快速入口：事件、预约、日程、成就、挑战、Coach。
2. 项目展示页或个人作品集中的实验性导航。
3. 未来运行时程序分析工具的视图切换，例如 Trace、拓扑、指标、Bad Case。
4. 能力评估完成后的“接下来去哪里”快捷入口，项目数不超过四个。

不建议用：

- 桌面端学生主侧栏。模块多、访问频繁时，固定侧栏更适合扫描与重复操作。
- 社会端审核、活动编辑、复杂表单。
- 事件检索和 AI 对话中的操作按钮。
- 一页里再套一个泡泡菜单，避免导航层级变得不明。

## 视觉边界

1. Do Not Miss 不要将每个菜单项设成不同彩虹色。保留一个主色与 1 个状态强调色，避免产品显得像作品集。
2. 胶囊圆角可大于普通卡片，因为它们明确是临时菜单；业务卡片仍保持较小圆角。
3. 展开菜单必须有遮罩或明确层级，防止文字与底下内容重叠。
4. 遵从 prefers-reduced-motion，改为淡入、淡出，取消 scale 弹跳和旋转。

## 采用时的验收点

- 鼠标、触屏、键盘都能打开、选择并关闭菜单。
- Escape 和点击背景能关闭；关闭后焦点回到菜单按钮。
- Tab 不会跑到被遮挡页面；隐藏的链接无法获得焦点。
- 小屏幕单列布局无文字溢出，所有入口可点击。
- 当前路由清晰可见，主侧栏和泡泡菜单不会同时抢夺导航职责。
