# E006 Ribbons 流体缎带

## 参考定位

- 效果名称：Ribbons WebGL 流体缎带
- 自然语言触发词：缎带、流体丝带、鼠标丝带、Ribbons、波浪轨迹
- 原始技术栈：React + OGL + WebGL Shader
- 迁移目标：Vue 3 + OGL 或 TresJS / 原生 Three.js
- 建议等级：局部背景动效。用于建立空间感，不用于承载关键信息。

## 视觉与交互

1. 鼠标移动时，缎带头部以弹簧和摩擦力追随鼠标，不会生硬贴住指针。
2. 后续点按顺序延迟追随前一个点，形成有惯性和柔软感的长条轨迹。
3. Polyline 顶点着色器根据路径前后点计算法线，再用 thickness 把一条线扩为有宽度的缎带。
4. 多个 colors 会生成多根略有偏移、弹簧参数略随机的缎带；单色则是干净的单条丝带。
5. 可开启尾部渐隐，也可在顶点着色器叠加正弦波，做出轻微抖动或电流感。

## React 用法参考

~~~jsx
<div style={{ height: "500px", position: "relative", overflow: "hidden" }}>
  <Ribbons
    baseThickness={30}
    colors={["#5227FF"]}
    speedMultiplier={0.5}
    maxAge={500}
    enableFade={false}
    enableShaderEffect={false}
  />
</div>
~~~

原始容器样式：

~~~css
.ribbons-container {
  width: 100%;
  height: 100%;
  position: relative;
}
~~~

## 参数说明

| 参数 | 含义 | 建议 |
| --- | --- | --- |
| colors | 缎带颜色数组 | Do Not Miss 一次使用 1 到 2 种颜色；不要做彩虹背景 |
| baseSpring | 头部追向鼠标的弹簧力度 | 0.02 到 0.05；越大越紧跟 |
| baseFriction | 头部速度保留比例 | 0.85 到 0.94；越高越有滑行感 |
| baseThickness | 缎带基础宽度 | 背景 16 到 32 px，避免压住文字 |
| offsetFactor | 多条缎带之间的偏移 | 单条缎带可接近 0 |
| maxAge | 从头到尾传播一轮的近似时间 | 300 到 700 ms |
| pointCount | 用多少路径点描述缎带 | 30 到 60；过多会增加几何更新成本 |
| speedMultiplier | 轨迹传播速度 | 0.4 到 0.8 |
| enableFade | 是否让尾部逐渐透明 | 背景使用建议开启 |
| enableShaderEffect | 是否叠加正弦波扰动 | 默认关闭；只在展示页使用 |
| effectAmplitude | 着色器扰动幅度 | 1 到 2；大了会显得不稳定 |
| backgroundColor | WebGL 清屏颜色与透明度 | 推荐透明 [0, 0, 0, 0] |

## 原实现的关键链路

1. 初始化 OGL Renderer，创建透明 WebGL canvas 并插入容器。
2. 为每种颜色建立一组 line：各自持有鼠标偏移、速度、弹簧系数、摩擦系数、点列和 Polyline。
3. 每根线预先创建 pointCount 个 Vec3 点，第一点代表缎带头部。
4. 鼠标坐标转为 -1 到 1 的 WebGL 坐标，写入 mouse。
5. 每一帧中：
   - line.points[0] 向 mouse 加上偏移的位置施加弹簧力；
   - mouseVelocity 乘以 friction，产生柔和的惯性；
   - 后续每一个点以 alpha 向前一个点 lerp，制造延迟传播；
   - 更新 uTime 和 Polyline geometry；
   - renderer.render(scene)。
6. 顶点着色器根据 prev 与 next 得到路径切线，旋转成法线，并以 uThickness 向两侧扩开。
7. 片元着色器输出 uColor；开启 fade 后，使用 uv 的纵向位置减弱 alpha。

简化后的运动核心：

~~~js
headVelocity += (mouse - head) * spring;
headVelocity *= friction;
head += headVelocity;

for (let index = 1; index < points.length; index += 1) {
  points[index].lerp(points[index - 1], alpha);
}
~~~

## Vue 3 迁移方案

组件建议命名为 RibbonsBackdrop.vue。

1. onMounted 创建 OGL Renderer 和 Polyline；用 shallowRef 保存 renderer、scene、lines。
2. 用 ResizeObserver 而非全局 window resize，只有容器尺寸变化时重设 canvas 并 resize polyline。
3. 用 pointermove 监听容器，支持鼠标与手写笔；触摸设备默认关闭，或降低 pointCount。
4. 用 requestAnimationFrame 保存循环句柄；onBeforeUnmount 时取消动画、移除监听、从 DOM 移除 canvas，并释放可释放的 WebGL 对象。
5. props 改变时，优先更新 uniform；只有颜色数量或 pointCount 改变时再重建 line。
6. canvas 必须设置 pointer-events: none；监听器挂在外层容器，保证按钮、链接、选择文字仍可正常使用。

推荐修正样式：

~~~css
.ribbons-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
}

.ribbons-content {
  position: relative;
  z-index: 1;
}
~~~

## Do Not Miss 的适合位置

可以用：

1. 项目展示页或登录欢迎区的全宽背景，搭配极少量大文字。
2. 能力评估结果生成后的顶部横幅，表示“成长路径正在展开”。
3. Guide Agent 的首次欢迎或计划生成完成弹窗的背景。
4. 未来的运行时程序分析流图，把不同缎带视作不同请求或消息流的抽象表现。

不建议用：

- 学生端事件检索、预约、关注、挑战列表、社会端管理台。
- Schedule 周视图的主内容层，缎带会影响时间表的扫描效率。
- Coach 对话区，运动背景会干扰长文字阅读。
- 多信息卡片叠加的页面。

## 性能与无障碍边界

1. 这个效果每帧会更新所有 Polyline 顶点并调用 WebGL render；仅在容器可见时运行。
2. IntersectionObserver 进入视口才启动循环，离开视口应 cancelAnimationFrame。
3. document.visibilityState 为 hidden 时暂停，回到页面再继续。
4. 尊重 prefers-reduced-motion；此时显示静态渐变色或直接不渲染。
5. 限制 renderer DPR，例如 min(devicePixelRatio, 1.5)，尤其是大面积背景。
6. 不要在一个页面创建多份 Renderer；一个页面只保留一个缎带 canvas。
7. 保证前景文字有实底或足够遮罩，不能依赖动态背景来承载对比度。

## 采用时的验收点

- 快速移动鼠标时，缎带连续而自然，离开容器后不出现位置跳变。
- 组件所在区域包含按钮或链接时，鼠标点击、Tab 焦点、文字选择均正常。
- 宽屏、窄屏、窗口 resize 时，Canvas 和缎带比例正确。
- 路由切换后没有残留 canvas 或 requestAnimationFrame。
- reduced-motion、后台标签页和移动端都有低成本或静态回退。
