# E011 PillNav 异色导航

## 参考定位

- 效果名称：PillNav 异色胶囊导航
- 自然语言触发词：异色导航、胶囊导航、圆形遮罩导航、PillNav、悬停换色
- 原始技术栈：React Router + GSAP + CSS
- 迁移目标：Vue Router + CSS Clip Path 或 GSAP + 响应式抽屉
- 建议等级：少量一级导航。视觉简洁但不适合模块非常多的业务系统。

## 视觉与交互

1. 左侧独立圆形 Logo，右侧是一个深色容器包着多枚浅色胶囊导航项。
2. 每个导航项有两层同文案：默认文字与悬停文字。
3. 鼠标进入时，一个同色圆形从胶囊底部扩大，完整覆盖整个胶囊背景；默认文字上移离场，浅色文字从下方进入。
4. 当前路由通过胶囊下方的小圆点标记。
5. 移动端隐藏横向胶囊，改为一个圆形菜单按钮和下拉链接列表。

效果的重点是“圆形遮罩覆盖胶囊”。圆的直径由胶囊宽高计算，保证无论导航项多宽，圆扩张后都能铺满四角。

## React 用法参考

~~~jsx
<PillNav
  logo={logo}
  logoAlt="Company Logo"
  items={[
    { label: "Home", href: "/" },
    { label: "About", href: "/about" },
    { label: "Services", href: "/services" },
    { label: "Contact", href: "/contact" }
  ]}
  activeHref="/"
  baseColor="#000000"
  pillColor="#ffffff"
  hoveredPillTextColor="#ffffff"
  pillTextColor="#000000"
  initialLoadAnimation={false}
/>
~~~

## 原实现的关键链路

1. layout 阶段读取每个胶囊宽 w 与高 h，构造覆盖圆的半径：

~~~js
radius = ((w * w) / 4 + h * h) / (2 * h)
diameter = ceil(2 * radius) + 2
~~~

2. 将圆放在胶囊下方，scale 初始为 0，transform origin 设置到合适的圆心位置。
3. 每个导航项维护一个暂停的 GSAP timeline：
   - 圆形遮罩 scale 到 1.2；
   - 默认文字向上移出；
   - 悬停文字从下方移入并淡入。
4. mouseenter 使用 tweenTo(timeline.duration)，mouseleave tweenTo(0)，因此反复进出时能从当前进度平滑反向。
5. 初始加载可让 Logo 从 scale 0 弹出，同时横向导航宽度从 0 展开。
6. 移动端用独立汉堡按钮展开一个短链接列表。

关键的样式结构：

~~~html
<a class="pill">
  <span class="hover-circle"></span>
  <span class="label-stack">
    <span class="pill-label">Events</span>
    <span class="pill-label-hover">Events</span>
  </span>
</a>
~~~

~~~css
.pill {
  position: relative;
  overflow: hidden;
  border-radius: 999px;
}

.hover-circle {
  position: absolute;
  left: 50%;
  bottom: 0;
  border-radius: 50%;
  background: var(--base);
  transform: translateX(-50%) scale(0);
}
~~~

## 原实现需要修正的地方

1. 原数据中的第一个 Home 既被 Logo 复用，又在导航列表再次渲染，会产生重复首页入口。迁移时 Logo 应单独配置 homeHref。
2. isExternalLink 直接对 href 调 startsWith；若数据缺少 href 会抛异常。必须先校验字符串。
3. ul 使用 role="menubar"、链接使用 role="menuitem"，但没有实现 menubar 所要求的左右方向键、Home、End 导航。最简单的修正是移除这些 role，保留普通 nav + ul + a。
4. 移动端菜单缺少 aria-expanded、aria-controls、Escape 关闭与点击外部关闭。
5. 初始动画将 width 从 0 动到 auto，某些浏览器和动态文案下容易闪烁；更稳的是 clip-path、scaleX 或 CSS grid-template-columns。
6. 原 CSS 使用 line-height: 0 和 text-transform: uppercase，不利于中英文混排；Do Not Miss 应使用正常行高、中文原文和 letter-spacing: 0。
7. 原版只有 pointer hover 反馈；键盘 focus-visible 也需要展示相同的边框或填充状态。

## Vue 3 迁移方案

建议组件名为 PillNavigation.vue。

~~~vue
<PillNavigation
  :items="primaryLinks"
  :active-path="route.path"
  home-href="/student/events"
/>
~~~

实现原则：

1. 数据里单独传 homeHref 和 logo，避免依赖 items[0]。
2. 内部链接使用 RouterLink；外部链接使用 a，并给 target="_blank" 的链接加入安全 rel。
3. 普通导航使用 nav > ul > li > RouterLink，默认浏览器 Tab 导航已经正确，不强加 menu role。
4. 桌面 hover 圆形遮罩可通过 CSS transform 或 clip-path 做；不一定要引入 GSAP。
5. 圆形大小可由 ResizeObserver 计算后写入 CSS 变量，窗口变化时更新。
6. 移动端可复用现有的导航抽屉；不要在同一个页面同时放多个独立的菜单系统。
7. reduced-motion 下，圆形遮罩直接切换为背景色，文字不做上下滑动。

## Do Not Miss 的适合位置

可以用：

1. GitHub 开源项目的产品展示页：产品、功能、架构、部署、Demo。
2. 未来运行时程序分析工具的少量顶层视图入口。
3. 简历定段或能力评估完成后的临时结果导航，入口数不超过四个。
4. 面向外部访客的介绍站，而不是学生日常操作区。

不建议：

- 当前学生端桌面主导航：模块数量和访问频率都更适合固定侧栏。
- 社会端活动编辑与审核。
- 事件搜索、AI 推荐、预约、日程等执行任务页面。
- 将它与 BubbleMenu、CardNav 同时放在同一界面。

## 视觉边界

1. 原 demo 的黑白异色可以保留为基础，但业务产品中建议使用浅底、深青绿主色，而不是大面积纯黑。
2. 导航本身不使用动态的多色背景，选中态只保留一个稳定强调信号。
3. 胶囊适合导航，普通业务按钮和卡片仍使用现有较小圆角。
4. 每个导航项必须有稳定宽度或自然内容宽度，长中文文案可换成短模块名，避免挤压。

## 采用时的验收点

- 任意宽度导航项的圆形遮罩都能完整覆盖，不露出角落。
- 当前路由、hover、focus-visible 三种状态都清晰可辨。
- 320px 移动端下，横向导航正确切换为菜单按钮。
- Escape、点击外部、选择链接后均能收起移动菜单。
- 页面不使用错误的 menubar 语义，也不会重复渲染首页入口。
