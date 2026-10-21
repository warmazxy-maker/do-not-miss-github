# E007 AnimatedList 动态列表

## 参考定位

- 效果名称：AnimatedList 动态可选列表
- 自然语言触发词：动态列表、选择列表、滚动渐隐、键盘选择、Animated List
- 原始技术栈：React + Motion
- 迁移目标：Vue 3 + TransitionGroup + IntersectionObserver + Roving Tabindex
- 建议等级：高实用性业务交互。适合替代静态、无反馈的普通列表。

## 视觉与交互

1. 每个列表项进入可视区时，从较小尺寸和透明状态过渡到正常状态。
2. 容器上、下沿有渐隐蒙层，表示列表还能继续滚动；到顶或到底时对应蒙层淡出。
3. 悬停或点击项目后更新 selectedIndex，选中项目可展示更明确的强调色、边框或内容预览。
4. 键盘选择时，当前选中项会自动滚入可视区，并保留上下留白。
5. 是否显示滚动条、是否显示渐隐、初始选中项均可配置。

## React 用法参考

~~~jsx
<AnimatedList
  items={items}
  onItemSelect={(item, index) => console.log(item, index)}
  showGradients
  enableArrowNavigation
  displayScrollbar
/>
~~~

核心 props：

| 参数 | 含义 | 建议 |
| --- | --- | --- |
| items | 待渲染的数据项 | 实际项目应为对象数组，通过插槽渲染，而非只接字符串 |
| onItemSelect | 点击或确认键选择某项的回调 | 传出完整活动、预约或会话对象 |
| showGradients | 是否展示滚动边缘渐隐 | 长列表建议开启，短列表不必 |
| enableArrowNavigation | 是否支持键盘方向键选择 | 仅容器获得焦点时启用 |
| displayScrollbar | 是否显示滚动条 | 桌面端建议显示精简滚动条，不建议完全隐藏 |
| initialSelectedIndex | 初始选中项 | 推荐结果可默认选中第一项，普通列表默认不选 |

原始 CSS：

~~~css
.scroll-list-container {
  position: relative;
  width: 500px;
}

.scroll-list {
  max-height: 400px;
  overflow-y: auto;
  padding: 16px;
}

.item {
  padding: 16px;
  background-color: #2F293A;
  border-radius: 8px;
  margin-bottom: 1rem;
}

.top-gradient {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 50px;
  background: linear-gradient(to bottom, #120F17, transparent);
  pointer-events: none;
}

.bottom-gradient {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 100px;
  background: linear-gradient(to top, #120F17, transparent);
  pointer-events: none;
}
~~~

## 原实现的关键链路

1. AnimatedItem 用 useInView 监测自己是否在容器视野中；进入时执行 scale 0.7 到 1、opacity 0 到 1 的 Motion 动画。
2. 列表的 onScroll 读取 scrollTop、scrollHeight、clientHeight：
   - 顶部渐隐透明度约等于 scrollTop / 50；
   - 底部渐隐透明度约等于距底部距离 / 50；
   - 列表没有溢出时，底部渐隐直接隐藏。
3. 鼠标进入或点击项目时更新 selectedIndex；点击额外触发 onItemSelect。
4. 原实现用全局 keydown 监听 ArrowUp、ArrowDown、Tab、Enter；选中项变化后读取 data-index 对应 DOM，并使用 scrollTo 平滑滚动到可见位置。

## 原实现需要修正的地方

原样移植不可取：

1. 原代码监听 window 的 keydown 并阻止 Tab 默认行为。这会破坏整个页面的键盘导航，用户可能无法从搜索框跳到按钮。
2. 鼠标悬停就改变 selectedIndex，可能在快速掠过时造成详情区域频繁切换；业务页面更适合 hover 只显示轻量预览，click 或 Enter 才确认选中。
3. 每个项目反复进出可视区都做缩放动画，长列表可能晃动。推荐只在数据首次进入或首次加载时执行。
4. 原 selected 样式与普通项目相同，实际采用时必须有清晰的焦点与选中反馈。

## Vue 3 迁移方案

组件建议命名为 AnimatedSelectableList.vue，采用 slot 渲染真实业务卡片。

~~~vue
<AnimatedSelectableList
  v-model:selected-key="selectedEventId"
  :items="events"
  item-key="id"
  :show-gradients="true"
  @select="openEventDetail"
>
  <template #default="{ item, selected }">
    <EventResultCard :event="item" :selected="selected" />
  </template>
</AnimatedSelectableList>
~~~

实现原则：

1. 用 role="listbox" 或语义化 ul，项目用 role="option" 或 li；只有可选列表才使用 listbox 语义。
2. 容器 tabindex="0"；仅当容器或列表项拥有焦点时响应 ArrowUp、ArrowDown、Home、End 和 Enter。
3. 不拦截 Tab。Tab 必须继续按照网页正常顺序移动焦点。
4. 用 roving tabindex：当前选中项目 tabindex="0"，其他项目 tabindex="-1"，保证键盘用户能定位当前项。
5. 使用 element.scrollIntoView({ block: "nearest", behavior: "smooth" })，不手算复杂滚动位置。
6. 滚动渐隐用 CSS custom property 或 reactive opacity；它们必须 pointer-events: none。
7. 初次加载使用 TransitionGroup 做 80 到 160 ms 的小幅淡入上移；不要在每次滚动时缩放卡片。
8. 超过约 100 项时接入虚拟列表，例如 vue-virtual-scroller，再只对可见数据做渲染和动画。

## Do Not Miss 的适合位置

优先使用：

1. Guide Agent 的 AI 推荐结果：选中一个活动后，在右侧显示推荐理由、匹配依据和预约入口。
2. 学生端事件检索结果：分页内的活动卡片可以依次进入，滚动边缘提示还有更多结果。
3. 我的预约：使用键盘浏览、Enter 打开预约详情或二维码。
4. Coach 历史会话、复习卡片与 Agent Bad Case 列表。
5. 社会端活动审核队列：选中活动后显示质量报告和审核操作。

不建议：

- 日程周视图，时间网格已有自己的导航模型。
- 聊天消息气泡流，聊天更适合自然滚动到底部，不能强塞选择模型。
- 只有 2 到 3 项的短静态设置项。

## 动效与性能边界

1. 业务列表项的进入动效应小于 180 ms，且以淡入或 4 到 8 px 上移为主；不使用大缩放。
2. 翻页、筛选变化时只动画新增或变化项，避免整页列表反复重放。
3. prefers-reduced-motion 下直接展示最终状态，保留选中和键盘交互。
4. 列表状态必须由数据 key 驱动，不能只依赖数组 index；排序、分页后 index 会变化。
5. 事件检索仍需使用服务端分页，AnimatedList 只负责当前页的展示，不能借动效掩盖大数据量问题。

## 采用时的验收点

- 鼠标、触屏和键盘三种方式都能选择项目。
- Tab 能正常离开列表到下一项控件，方向键仅在列表聚焦时生效。
- 选中项滚动进视野但不会突然把整个页面跳走。
- 列表顶部和底部渐隐准确反映可滚动状态。
- 分页、搜索与异步加载过程中，选中状态不会指向错误的项目。
