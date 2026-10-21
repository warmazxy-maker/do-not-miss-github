# E005 PixelTrail 像素拖尾

## 参考定位

- 效果名称：PixelTrail WebGL 像素拖尾
- 自然语言触发词：像素拖尾、粒子拖尾、鼠标拖尾、Pixel Trail、液态像素
- 原始技术栈：React Three Fiber + Drei + Three.js Shader + SVG Gooey Filter
- 迁移目标：Vue 3 + TresJS 或原生 Three.js
- 建议等级：高成本局部动效。只在视觉需要被强调的局部容器中启用。

## 视觉与交互

1. 容器内有一层透明 WebGL Canvas，鼠标移动后留下由方格像素构成的尾迹。
2. 尾迹通过贴图随时间衰减，不是每个像素单独创建 DOM 节点。
3. 片元着色器根据屏幕坐标与 gridSize 取格子中心的轨迹强度，输出统一颜色与不同透明度。
4. 可选的 SVG Gooey Filter 会模糊并重新阈值化透明区域，让分散像素呈现“液态融合”的感觉。

## React 用法参考

~~~jsx
<div style={{ height: "500px", position: "relative", overflow: "hidden" }}>
  <PixelTrail
    gridSize={90}
    trailSize={0.1}
    maxAge={200}
    interpolate={5}
    color="#5227FF"
    gooeyFilter={{ id: "custom-goo-filter", strength: 2 }}
    gooeyEnabled
    gooStrength={1}
  />
</div>
~~~

关键参数：

| 参数 | 含义 | 建议范围 |
| --- | --- | --- |
| gridSize | 网格密度。越大，像素格越细 | 40 到 100 |
| trailSize | 轨迹笔刷半径 | 0.05 到 0.15 |
| maxAge | 轨迹保留时间 | 150 到 350 ms |
| interpolate | 鼠标两点之间补点数量 | 2 到 8 |
| color | 像素主色 | 使用一个品牌强调色即可 |
| gooeyFilter.strength | 液态融合的模糊强度 | 1 到 3；过高会糊成一团 |

## 原组件结构

~~~jsx
function PixelTrail(props) {
  return (
    <>
      <GooeyFilter id={props.gooeyFilter.id} strength={props.gooeyFilter.strength} />
      <Canvas className="pixel-canvas" gl={glProps}>
        <Scene
          gridSize={props.gridSize}
          trailSize={props.trailSize}
          maxAge={props.maxAge}
          interpolate={props.interpolate}
          pixelColor={props.color}
        />
      </Canvas>
    </>
  );
}
~~~

Scene 内部使用 useTrailTexture 生成一张随鼠标移动更新的纹理。纹理的采样值作为 fragment shader 的 alpha：

~~~glsl
vec2 gridUv = fract(uv * gridSize);
vec2 gridUvCenter = (floor(uv * gridSize) + 0.5) / gridSize;
float trail = texture2D(mouseTrail, gridUvCenter).r;
gl_FragColor = vec4(pixelColor, trail);
~~~

这段逻辑的重点是：每一个屏幕像素都映射到它所在网格的中心点，因此同一格里的像素共用一个轨迹强度，最终看起来才是规整的像素块，而非柔和的普通笔刷。

原始 CSS：

~~~css
.goo-filter-container {
  position: absolute;
  overflow: hidden;
  z-index: 1;
}

.pixel-canvas {
  position: absolute;
  z-index: 1;
}
~~~

## Vue 迁移方案

优先方案：使用 TresJS 封装 Three.js，组件名建议为 PixelTrailBackdrop.vue。

1. 组件只接受一个相对定位的父容器，并将 Canvas 绝对定位铺满。
2. 使用 pointermove 而非全局 mousemove，只在用户位于该容器时更新 trail texture。
3. Scene 只保留一个全屏 plane 和一个 ShaderMaterial；调整参数只更新 uniform，不重建 WebGL renderer。
4. 用 ResizeObserver 更新 resolution，组件卸载时 dispose geometry、material、texture 和 renderer。
5. gooey 可用 SVG filter 实现，但要允许关闭。透明叠加和 filter 会增加合成成本。

如果项目不想引入 Three.js，降级方案是 Canvas 2D：按固定网格记录最近经过的格子和时间戳，在 requestAnimationFrame 中根据 age 绘制半透明方块。视觉会稍弱，但足够做低成本像素拖尾。

## Do Not Miss 的适合位置

可以用：

1. 登录或项目展示页的局部视觉背景，鼠标在英雄区域移动时才生效。
2. 能力评估完成后的结果页顶部，作为一次性的“能力图谱已生成”反馈。
3. 未来运行时程序分析流图的画布背景，用不同颜色区分数据流或当前高亮链路。
4. 空的能力地图、初次完成 Challenge 等低频、有仪式感的页面。

不建议用：

- 事件检索、AI 推荐列表、预约、关注、社会端活动管理等信息密集页面。
- Schedule 的完整周视图背景，会干扰时间块阅读与拖拽。
- 所有移动端和低性能设备。
- 无用户互动时持续播放的纯装饰背景。

## 性能与无障碍边界

1. Canvas 要 pointer-events: none，不能遮挡按钮、拖拽和文本选择。
2. 使用 IntersectionObserver：离开视口或页面隐藏时暂停渲染。
3. 使用 prefers-reduced-motion：命中后不创建 WebGL Canvas，或只展示静态背景。
4. 检测 WebGL 支持；创建失败时静默降级，不能影响页面业务功能。
5. 限制像素比，例如 min(devicePixelRatio, 1.5)，避免 4K 屏幕大量填充率消耗。
6. 一个页面最多保留一个 PixelTrail Canvas，路由卸载必须释放 GPU 资源。
7. 必须把 canvasProps 和 glProps 作为可配置项，开发环境先使用低网格密度验收性能。

## 采用时的验收点

- 鼠标快速移动时，尾迹连续且不会穿帮断裂。
- 父容器滚动、缩放和窗口 resize 后，轨迹位置仍准确。
- 页面离开视口后 GPU 绘制停止，切换路由后没有残留 Canvas。
- 关闭动效、移动端或 WebGL 不可用时，业务页面仍完整可操作。
- 文本与关键按钮对比度没有被紫色拖尾干扰。
