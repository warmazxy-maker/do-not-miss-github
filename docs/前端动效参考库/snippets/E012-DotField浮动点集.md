# E012 DotField 浮动点集

## 参考定位

- 效果名称：DotField Canvas 浮动点集
- 自然语言触发词：浮动点集、点阵背景、点阵交互、DotField、鼠标鼓起
- 原始技术栈：React + Canvas 2D + SVG Radial Gradient
- 迁移目标：Vue 3 + Canvas 2D + requestAnimationFrame
- 建议等级：局部空间背景。适合表达“数据、能力、关系网络”，不能替代真实图表。

## 视觉与交互

1. 容器被规则的圆点网格填满，点的颜色沿左上到右下渐变。
2. 鼠标移动得越快，engagement 越高，鼠标周围的点反应越明显。
3. bulgeOnly 模式下，鼠标附近的点向外推开，形成一块柔和鼓起的“力场”。
4. 非 bulgeOnly 模式下，点拥有速度和摩擦，会被鼠标短暂排斥后回到原位。
5. 可选 waveAmplitude 让整个点阵产生连续波动；可选 sparkle 让少量点不规律变大。
6. SVG 覆盖层提供跟随鼠标的径向光晕，并以鼠标速度决定光晕透明度。

## React 用法参考

~~~jsx
<div style={{ width: "100%", height: "600px", position: "relative" }}>
  <DotField
    dotRadius={1.5}
    dotSpacing={14}
    bulgeStrength={67}
    glowRadius={160}
    sparkle={false}
    waveAmplitude={0}
    cursorRadius={500}
    cursorForce={0.1}
    bulgeOnly
    gradientFrom="#A855F7"
    gradientTo="#B497CF"
    glowColor="#120F17"
  />
</div>
~~~

原始容器样式：

~~~css
.dot-field-container {
  position: relative;
  width: 100%;
  height: 100%;
}
~~~

## 参数说明

| 参数 | 含义 | Do Not Miss 建议 |
| --- | --- | --- |
| dotRadius | 点的半径 | 1 到 1.5 px |
| dotSpacing | 相邻点之间的额外间距 | 14 到 22 px；屏幕大时应增大 |
| cursorRadius | 鼠标影响半径 | 180 到 300 px；原示例 500 偏大 |
| cursorForce | 排斥模式下的力量 | 0.03 到 0.08 |
| bulgeOnly | 是否只做稳定鼓起 | 建议 true，视觉更克制、更易控 |
| bulgeStrength | 鼓起强度 | 25 到 50 |
| glowRadius | SVG 光晕半径 | 100 到 180 px |
| sparkle | 随机闪烁 | 默认 false；只用于展示页 |
| waveAmplitude | 波动幅度 | 默认 0；长期页面不要持续波动 |
| gradientFrom / gradientTo | 点阵渐变颜色 | 使用低饱和青绿、蓝绿或中性灰，不照搬紫色 |
| glowColor | 光晕颜色 | 与页面底色协调，保持足够对比 |

## 原实现的关键链路

### 1. 构建点阵

容器 resize 后按 step = dotRadius + dotSpacing 计算行列数。每个点保存：

~~~js
{
  ax, ay,  // 初始锚点
  sx, sy,  // 平滑后的实际绘制位置
  vx, vy,  // 排斥模式下的速度
  x, y     // 物理目标位置
}
~~~

### 2. 鼠标速度与 engagement

每 20ms 计算当前与上一帧鼠标位置的距离，得到平滑速度。engagement 是速度归一化后的缓动值：

~~~js
targetEngagement = min(mouse.speed / 5, 1)
engagement += (targetEngagement - engagement) * 0.06
~~~

鼠标停下时，点阵和光晕自然回落，不会一直强烈反应。

### 3. 鼠标力场

在 cursorRadius 内，鼓起模式按距离平方衰减：

~~~js
t = 1 - distance / cursorRadius
push = t * t * bulgeStrength * engagement
point.sx += (point.ax - cos(angle) * push - point.sx) * 0.15
point.sy += (point.ay - sin(angle) * push - point.sy) * 0.15
~~~

也就是说，越靠近鼠标的点被推得越明显；离开影响范围后，点以缓动回到锚点。

### 4. Canvas 绘制与 SVG 光晕

每个动画帧清空 Canvas，建立线性渐变，再把所有点加入一个 Path 后一次 fill。SVG circle 的 cx、cy 跟随鼠标，opacity 跟随 engagement。这是比逐点设置样式更高效的 Canvas 用法。

## 原实现需要修正的地方

1. 原版即使鼠标静止、waveAmplitude 为 0、sparkle 为 false，也持续 requestAnimationFrame 重绘所有点。长时间停留会浪费 CPU。
2. 每帧都遍历全部点。以 1000 x 600、step 约 15.5 为例，约有 2400 个点；大面积高密度点阵会明显增加主线程压力。
3. 原版在 window 上监听 mousemove；多个 DotField 会同时响应全局鼠标，且用户移出容器仍会触发。
4. Canvas 默认可能截获点击。作为背景必须设置 pointer-events: none，并把 pointermove 绑定在外层容器。
5. svgRef 没有实际使用，可以移除。
6. 仅 window resize 时重建点阵，对由侧栏折叠、容器布局变化导致的尺寸改变反应不够及时；应使用 ResizeObserver。

## Vue 3 迁移方案

组件建议命名为 DotFieldBackdrop.vue。

1. 外层容器 position: relative；Canvas 和 SVG 都 absolute inset: 0、pointer-events: none，内容层位于更高 z-index。
2. pointermove 绑定外层容器，只在指针实际进入背景区域时更新坐标。
3. 用 ResizeObserver 重建点阵，限制 devicePixelRatio 为 min(dpr, 1.5)。
4. 判断是否需要重绘：只有鼠标速度、engagement、点坐标或 wave 状态仍在变化时才保留 requestAnimationFrame；完全静止后停止，下一次 pointermove 再唤醒。
5. IntersectionObserver 不可见时停止动画；document.visibilityState hidden 时也暂停。
6. 容器最大点数设上限，例如 1600 到 2200；超过时自动增大 spacing。
7. prefers-reduced-motion 下只绘制静态点阵，不监听指针也不创建动画循环。

## Do Not Miss 的适合位置

可以用：

1. 能力地图标题区的弱背景，暗示“能力节点与知识网络”，但真实标签与分数仍放在清晰前景。
2. 未来运行时程序分析流图的空画布背景；数据节点、请求高亮可叠加在点阵上。
3. 开源项目的介绍页或架构页，作为静态技术氛围背景。
4. AI 计划已生成、能力评估已完成等一次性结果页面的顶部背景。

不建议：

- 事件检索、预约、挑战、Coach 对话、社会端表单等阅读与操作密集区域。
- 日程周视图或任何需要精准拖拽的网格。
- 需要展示真实统计关系时。点阵只是空间隐喻，不是图表。

## 视觉与无障碍边界

1. 使用浅色背景搭配低对比点阵；正文和操作按钮应放在不透明或半透明的前景区域。
2. 不使用随机闪烁与持续波浪作为默认效果，避免焦虑感和视觉疲劳。
3. 背景不承载任何唯一的信息；关闭动效后页面必须完全可理解。
4. 触屏设备只展示静态点阵或不展示，不模拟鼠标追随。

## 采用时的验收点

- 鼠标移动时，近处点阵自然鼓起，移出区域后回落。
- 静止、离开视口、切换后台标签页后动画循环停止。
- 容器宽高变化后点阵完整铺满，不拉伸、不卡边。
- 点数上限生效，低性能设备不会明显掉帧。
- Canvas 与 SVG 不遮挡页面按钮、输入框、链接和拖拽操作。
