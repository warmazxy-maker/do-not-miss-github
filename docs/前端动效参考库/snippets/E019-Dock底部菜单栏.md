# Dock 底部菜单栏

## 基本信息

- 效果名称：Dock 底部菜单栏
- 触发词：底部菜单栏、Dock、Mac Dock、悬停放大导航、浮动工具栏
- 来源链接：用户提供代码，原始链接未记录
- 来源类型：网站参考 / 用户收集代码
- 许可证或版权备注：作为交互参考使用；接入产品前需回查原始来源与许可证。
- 适合页面：移动端一级导航、桌面端快捷入口、运行时链路工具的视图切换。
- 推荐优先级：中

## 视觉描述

底部居中悬浮一条深色面板。鼠标靠近任意图标时，该图标及其邻近图标会按距离平滑放大，形成类似 macOS Dock 的透镜效果；悬停图标上方出现文字提示。

## 交互描述

- 鼠标在面板内横向移动：根据指针到图标中心的距离实时计算尺寸。
- 鼠标离开：图标通过弹簧动画恢复基础尺寸，面板高度收回。
- 键盘：Enter 或 Space 触发对应入口。
- 触屏：没有 hover，因此应保持固定尺寸，点击直接导航；不要把关键信息只放在 hover 提示中。

## 技术要点

- 主要技术：`motion/react` 的 `useMotionValue`、`useTransform`、`useSpring`、`AnimatePresence`。
- 关键 CSS：绝对定位底部面板、图标尺寸由 motion style 驱动、tooltip 淡入上浮。
- 关键 JS：读取每个图标的 `getBoundingClientRect()`，把指针 X 坐标映射成图标目标大小。
- 是否依赖第三方库：原实现依赖 `motion/react`；图标示例依赖 `react-icons/vsc`。
- 移动端是否适配：需要单独设计，默认固定图标大小即可。
- 性能风险：图标数量应控制在 4-6 个；指针移动频繁，Vue 改造时用 `requestAnimationFrame` 合帧，避免每次 pointermove 都触发全量响应式更新。

## 参考代码

```tsx
'use client';

import { motion, useMotionValue, useSpring, useTransform, AnimatePresence } from 'motion/react';
import { Children, cloneElement, useEffect, useMemo, useRef, useState } from 'react';

import './Dock.css';

function DockItem({ children, className = '', onClick, mouseX, spring, distance, magnification, baseItemSize, label }) {
  const ref = useRef(null);
  const isHovered = useMotionValue(0);
  const mouseDistance = useTransform(mouseX, val => {
    const rect = ref.current?.getBoundingClientRect() ?? { x: 0, width: baseItemSize };
    return val - rect.x - baseItemSize / 2;
  });
  const targetSize = useTransform(mouseDistance, [-distance, 0, distance], [baseItemSize, magnification, baseItemSize]);
  const size = useSpring(targetSize, spring);

  return (
    <motion.div
      ref={ref}
      style={{ width: size, height: size }}
      onClick={onClick}
      className={`dock-item ${className}`}
      tabIndex={0}
      role="button"
      aria-label={label}
    >
      {Children.map(children, child => cloneElement(child, { isHovered }))}
    </motion.div>
  );
}

function DockLabel({ children, className = '', isHovered }) {
  const [isVisible, setIsVisible] = useState(false);
  useEffect(() => isHovered.on('change', value => setIsVisible(value === 1)), [isHovered]);
  return (
    <AnimatePresence>
      {isVisible && (
        <motion.div
          initial={{ opacity: 0, y: 0 }}
          animate={{ opacity: 1, y: -10 }}
          exit={{ opacity: 0, y: 0 }}
          transition={{ duration: 0.2 }}
          className={`dock-label ${className}`}
          role="tooltip"
          style={{ x: '-50%' }}
        >{children}</motion.div>
      )}
    </AnimatePresence>
  );
}

function DockIcon({ children, className = '' }) {
  return <div className={`dock-icon ${className}`}>{children}</div>;
}

export default function Dock({
  items,
  className = '',
  spring = { mass: 0.1, stiffness: 150, damping: 12 },
  magnification = 70,
  distance = 200,
  panelHeight = 68,
  dockHeight = 256,
  baseItemSize = 50
}) {
  const mouseX = useMotionValue(Infinity);
  const isHovered = useMotionValue(0);
  const maxHeight = useMemo(
    () => Math.max(dockHeight, magnification + magnification / 2 + 4),
    [magnification, dockHeight]
  );
  const heightRow = useTransform(isHovered, [0, 1], [panelHeight, maxHeight]);
  const height = useSpring(heightRow, spring);

  return (
    <motion.div style={{ height, scrollbarWidth: 'none' }} className="dock-outer">
      <motion.div
        onMouseMove={({ pageX }) => { isHovered.set(1); mouseX.set(pageX); }}
        onMouseLeave={() => { isHovered.set(0); mouseX.set(Infinity); }}
        className={`dock-panel ${className}`}
        style={{ height: panelHeight }}
        role="toolbar"
        aria-label="Application dock"
      >
        {items.map((item, index) => (
          <DockItem key={index} {...item} mouseX={mouseX} spring={spring} distance={distance}
            magnification={magnification} baseItemSize={baseItemSize} label={item.label}>
            <DockIcon>{item.icon}</DockIcon>
            <DockLabel>{item.label}</DockLabel>
          </DockItem>
        ))}
      </motion.div>
    </motion.div>
  );
}
```

```css
.dock-outer {
  display: flex;
  max-width: 100%;
  align-items: center;
  margin: 0 0.5rem;
}

.dock-panel {
  position: absolute;
  bottom: 0.5rem;
  left: 50%;
  display: flex;
  align-items: flex-end;
  width: fit-content;
  gap: 1rem;
  padding: 0 0.5rem 0.5rem;
  transform: translateX(-50%);
  border: 1px solid #222;
  border-radius: 1rem;
  background: #120f17;
}

.dock-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #222;
  border-radius: 10px;
  background: #120f17;
  cursor: pointer;
}

.dock-label {
  position: absolute;
  top: -1.5rem;
  left: 50%;
  width: fit-content;
  padding: 0.125rem 0.5rem;
  transform: translateX(-50%);
  border: 1px solid #222;
  border-radius: 0.375rem;
  background: #120f17;
  color: #fff;
  font-size: 0.75rem;
  white-space: pre;
}
```

## 改造成 Do Not Miss 的方案

1. 桌面端可作为开发者运行时链路工具的快捷视图导航，或个人主页的轻量快捷入口；不建议替换信息密集的左侧主导航。
2. Vue 实现不直接引入 React 组件：使用 `pointermove` 记录鼠标 X 坐标，每个按钮根据中心距离计算 `scale`，通过 CSS `transform` 和 `transition` 实现弹性回弹。
3. 导航项复用现有 `lucide-vue-next` 图标和 Vue Router；4-5 个以内，保留文字 tooltip、焦点状态与键盘操作。
4. 手机端固定为紧凑底部导航，不做放大，避免误触和布局跳动。

## 不要直接照搬的地方

- 当前项目是 Vue，不应安装 React 只为使用这一段组件。
- 原版黑紫色主题与 Do Not Miss 的浅色青绿工作台不一致；接入时需要改为浅色浮层、青绿激活态。
- `aria-haspopup="true"` 不适用于普通导航按钮，应依据实际行为改为链接或普通按钮。
- `role="button"` 的 div 需要完整键盘处理；在 Vue 中优先直接使用原生 `button` 或 `RouterLink`。
