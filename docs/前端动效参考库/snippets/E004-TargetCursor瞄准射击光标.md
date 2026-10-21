# E004 TargetCursor 瞄准射击光标

## 参考定位

- 效果名称：TargetCursor 瞄准射击光标
- 自然语言触发词：瞄准射击、瞄准光标、准星光标、Target Cursor、操作反馈
- 原始技术栈：React + GSAP + CSS
- 迁移目标：Vue 3；优先用 Pointer Events、requestAnimationFrame 和 CSS Transform 实现
- 建议等级：局部沉浸式交互。常规业务页面慎用，不作为全站默认光标。

## 视觉与交互

1. 鼠标移动时，中心圆点和四个 L 形角标跟随指针。
2. 非目标区域时，四角围成小准星，并持续缓慢旋转。
3. 鼠标进入带有 cursor-target 标记的元素时，旋转暂停；四个角沿目标边框的四个角展开，形成“瞄准并锁定”。
4. 可选地将准星从默认颜色变为目标色；鼠标按下时中心点与准星轻微缩小，模拟按压反馈。
5. 离开目标后，四角收回为小准星，短暂停顿后恢复旋转。

这不是普通的自定义鼠标图标。它的核心是：将光标四角实时对齐到悬停目标的 viewport 边界，因此用户会感到“当前元素被选中”。

## React 用法参考

~~~jsx
<TargetCursor
  targetSelector=".cursor-target"
  spinDuration={2}
  hideDefaultCursor
  hoverDuration={0.2}
  parallaxOn
  cursorColor="#ffffff"
  cursorColorOnTarget="#B497CF"
/>

<button className="cursor-target">点击我</button>
<div className="cursor-target">可悬停目标</div>
~~~

参数含义：

| 参数 | 作用 | 建议 |
| --- | --- | --- |
| targetSelector | 哪些元素会触发锁定状态 | Vue 中可改成 data-cursor-target 属性，避免样式类与交互类混用 |
| spinDuration | 常态准星转一圈的时长 | 2 到 5 秒；后台业务系统建议更慢或关闭旋转 |
| hideDefaultCursor | 是否隐藏系统光标 | 默认 false；仅桌面沉浸模式可开启 |
| hoverDuration | 锁定目标时的展开动画时长 | 0.15 到 0.25 秒 |
| parallaxOn | 目标滚动或指针移动时是否带一点追随感 | Schedule 或运行时分析工具可开；静态详情页可关 |
| cursorColor | 默认准星颜色 | 跟随页面高对比色 |
| cursorColorOnTarget | 锁定目标后的颜色 | 用品牌强调色，但要保证可见性 |

## DOM 结构与原始 CSS

~~~html
<div class="target-cursor-wrapper">
  <div class="target-cursor-dot"></div>
  <div class="target-cursor-corner corner-tl"></div>
  <div class="target-cursor-corner corner-tr"></div>
  <div class="target-cursor-corner corner-br"></div>
  <div class="target-cursor-corner corner-bl"></div>
</div>
~~~

~~~css
.target-cursor-wrapper {
  position: fixed;
  top: 0;
  left: 0;
  width: 0;
  height: 0;
  pointer-events: none;
  z-index: 9999;
  mix-blend-mode: difference;
  transform: translate(-50%, -50%);
}

.target-cursor-dot {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 4px;
  height: 4px;
  background: #fff;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  will-change: transform;
}

.target-cursor-corner {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 12px;
  height: 12px;
  border: 3px solid #fff;
  will-change: transform;
}

.corner-tl {
  transform: translate(-150%, -150%);
  border-right: none;
  border-bottom: none;
}

.corner-tr {
  transform: translate(50%, -150%);
  border-left: none;
  border-bottom: none;
}

.corner-br {
  transform: translate(50%, 50%);
  border-left: none;
  border-top: none;
}

.corner-bl {
  transform: translate(-150%, 50%);
  border-right: none;
  border-top: none;
}
~~~

## 原组件的关键实现

原 React 组件使用 GSAP。迁移时不必逐行照搬，以下行为必须保留：

1. 初始化：仅在非移动端挂载组件；记录当前 body 光标值，需要时再隐藏。
2. 跟随指针：监听 mousemove，保存指针的 clientX 和 clientY；通过 GSAP tween 平滑更新容器位置。
3. 普通状态：创建无限 rotation 动画，持续旋转整个准星容器。
4. 发现目标：监听 mouseover，从事件元素向上找到最近的 targetSelector 目标；读取 getBoundingClientRect。
5. 计算四角：分别计算目标左上、右上、右下、左下的坐标，并将四个角标动画到这些坐标。
6. 锁定状态：暂停旋转、把角标和中心点切换到 cursorColorOnTarget、用 ticker 持续校正滚动或元素位置变化。
7. 退出目标：移除 ticker，颜色复位，四角回到中心四周；短暂延迟后恢复旋转。
8. 按压反馈：mousedown 时缩小点和整体，mouseup 后复位。
9. 清理：移除全部 window 事件与 tween，恢复 body 的原光标，避免路由切换后残留。

原组件还专门处理了 position: fixed 祖先被 transform、filter、perspective、contain 等属性影响的情况：先向上寻找 containing block，再在计算位置时扣除该容器的 getBoundingClientRect 偏移。Vue 实现同样要保留这一点，或直接把准星 Teleport 到 body。

## Vue 3 实现建议

组件名建议为 TargetCursorOverlay.vue，并以 Teleport 挂载到 body：

~~~vue
<Teleport to="body">
  <div v-if="enabled" ref="cursor" class="target-cursor-wrapper">
    <span ref="dot" class="target-cursor-dot" />
    <span v-for="corner in corners" :key="corner" :class="['target-cursor-corner', corner]" />
  </div>
</Teleport>
~~~

推荐用法：

1. 用 window.matchMedia("(pointer: fine)") 判断桌面精细指针；触屏设备不渲染。
2. 用 window.matchMedia("(prefers-reduced-motion: reduce)") 关闭旋转和缓动。
3. pointermove 只写入最新鼠标坐标；使用一个 requestAnimationFrame 循环批量计算 transform，避免每次鼠标事件都触发多次动画。
4. 用 closest("[data-cursor-target]") 找目标；Schedule 的可拖拽时间块和开发者工具的节点可标记该属性。
5. 目标四角位置放在 CSS 自定义变量中，例如 --corner-tl-x、--corner-tl-y；角标通过 translate3d 渲染，避免 left 和 top 反复触发布局。
6. 普通业务按钮保留浏览器 pointer 光标；只有显式开启沉浸模式时才 hideDefaultCursor。

## Do Not Miss 的适合位置

优先级从高到低：

1. 未来的“运行时程序分析流图”或开发者调试页面：节点、连线、Trace 详情都可以作为可锁定目标。这是最契合的地方。
2. Schedule 周视图：仅在“编辑日程”模式启用，帮助用户感觉自己在精确拖拽时间块。
3. 能力详情：可以只对“开始验证”“查看证据”等少量关键操作触发，不建议整页启用。
4. 互动演示或项目展示页：可以作为用户主动打开的“沉浸演示模式”。

不适合：

- 登录、注册、搜索、AI 对话输入、表单填写、长文本阅读。
- 手机和平板触控设备。
- 社会端管理页面和长期高频使用的列表页。

## 可访问性与性能边界

1. 输入框、textarea、可编辑区域、文本选择状态中必须恢复系统光标；不能让用户失去插入点反馈。
2. 使用键盘 Tab 导航时不要依赖自定义光标；焦点样式仍是主交互反馈。
3. mix-blend-mode: difference 在图片、浅底或深底上会出现不可预测的颜色，需提供没有混合模式的回退样式。
4. Overlay 必须 pointer-events: none，绝不能挡住点击、拖拽或文本选择。
5. 只保留一个全局监听器和一个 requestAnimationFrame。组件卸载时 cancelAnimationFrame 并清理事件。
6. 用户选择减少动态效果时，直接不渲染或只保留静态高亮框。

## 采用时的验收点

- 鼠标移动不造成页面掉帧，滚动时不留残影。
- 进入与离开标记目标后，四角能正确展开和收回。
- 点击、拖拽、文字选择、输入框光标、键盘导航都没有被破坏。
- 触摸屏和 reduced-motion 下自动关闭。
- 路由切换后系统光标恢复正常，页面没有重复监听器。
