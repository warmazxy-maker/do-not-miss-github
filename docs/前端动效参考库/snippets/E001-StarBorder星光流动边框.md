# E001 - StarBorder 星光流动边框

## 基本信息

- 效果名称：StarBorder 星光流动边框
- 触发词：星光边框、流动边框、发光边框、cyan 边框、个人标签高级边框、能力标签发光
- 来源链接：https://reactbits.dev/animations/star-borderutm_source=chatgpt.com&color=cyan
- 规范化来源备注：https://reactbits.dev/animations/star-border
- 来源类型：前端动效组件参考
- 许可证或版权备注：来源页面和许可证需要在正式复用前确认；当前仅作为视觉和交互参考，不直接整体照搬。
- 适合页面：个人成就、能力标签、Judge 验证按钮、重要能力卡片、AI 推荐结果重点卡片
- 推荐优先级：高

## 视觉描述

一个圆角容器外侧有两团发光的径向渐变，分别从顶部和底部沿边缘缓慢流动。视觉上像星光、能量条或扫描光从边框经过，适合用来强调“重要”“已验证”“高价值”的标签或按钮。

这个效果不适合大面积铺满页面，更适合少量关键元素点缀。用于个人能力标签时，可以让高分标签、已验证标签、核心能力标签拥有轻微流光边框，普通标签保持静态。

## 交互描述

- 默认状态：边框有两段 cyan/白色光点缓慢移动。
- Hover 状态：可以提高透明度、稍微加快速度，或让内层背景变亮。
- Selected 状态：用于当前选中的能力标签，边框持续流动。
- Disabled / 普通状态：关闭动画，只保留静态边框，避免页面过吵。

## 技术要点

- 主要技术：React Component + CSS Animation
- 可迁移到 Vue：可以，核心逻辑只是动态 class、style 和 slot。
- 关键 CSS：`radial-gradient`、`absolute`、`overflow: hidden`、`@keyframes`
- 是否依赖第三方库：不依赖
- 移动端是否适配：可以，但要降低动画数量
- 性能风险：如果几十个标签同时开动画，会增加重绘成本；建议只给 Top 标签或 hover 标签开启。

## 原始使用方式

```jsx
import StarBorder from './StarBorder'

<StarBorder
  as="button"
  className="custom-class"
  color="cyan"
  speed="5s"
>
  // content
</StarBorder>
```

## 原始 React 组件

```jsx
import './StarBorder.css';

const StarBorder = ({
  as: Component = 'button',
  className = '',
  color = 'white',
  speed = '6s',
  thickness = 1,
  children,
  ...rest
}) => {
  return (
    <Component
      className={`star-border-container ${className}`}
      style={{
        padding: `${thickness}px 0`,
        ...rest.style
      }}
      {...rest}
    >
      <div
        className="border-gradient-bottom"
        style={{
          background: `radial-gradient(circle, ${color}, transparent 10%)`,
          animationDuration: speed
        }}
      ></div>
      <div
        className="border-gradient-top"
        style={{
          background: `radial-gradient(circle, ${color}, transparent 10%)`,
          animationDuration: speed
        }}
      ></div>
      <div className="inner-content">{children}</div>
    </Component>
  );
};

export default StarBorder;
```

## 原始 CSS

```css
.star-border-container {
  display: inline-block;
  position: relative;
  border-radius: 20px;
  overflow: hidden;
}

.border-gradient-bottom {
  position: absolute;
  width: 300%;
  height: 50%;
  opacity: 0.7;
  bottom: -12px;
  right: -250%;
  border-radius: 50%;
  animation: star-movement-bottom linear infinite alternate;
  z-index: 0;
}

.border-gradient-top {
  position: absolute;
  opacity: 0.7;
  width: 300%;
  height: 50%;
  top: -12px;
  left: -250%;
  border-radius: 50%;
  animation: star-movement-top linear infinite alternate;
  z-index: 0;
}

.inner-content {
  position: relative;
  border: 1px solid #222;
  background: #000;
  color: white;
  font-size: 16px;
  text-align: center;
  padding: 16px 26px;
  border-radius: 20px;
  z-index: 1;
}

@keyframes star-movement-bottom {
  0% {
    transform: translate(0%, 0%);
    opacity: 1;
  }
  100% {
    transform: translate(-100%, 0%);
    opacity: 0;
  }
}

@keyframes star-movement-top {
  0% {
    transform: translate(0%, 0%);
    opacity: 1;
  }
  100% {
    transform: translate(100%, 0%);
    opacity: 0;
  }
}
```

## 改造成 Do Not Miss 的方案

Do Not Miss 现在是 Vue 前端，所以不要直接复制 React 组件。推荐改造成一个通用 Vue 组件：

- `StarBorder.vue`
- props：`as`、`color`、`speed`、`thickness`、`active`
- slot：内部放能力标签、按钮或卡片内容
- CSS 改成 scoped class 或全局 utility class

用于能力标签时的建议规则：

- 已验证标签：启用 cyan 或 teal 流光。
- 高分标签：启用更明显的流光，但数量限制在 Top 3 到 Top 5。
- 待 Judge 标签：使用 amber 流光，表示需要确认。
- 普通标签：不启用动画，只保留静态卡片。

## Vue 伪代码参考

```vue
<template>
  <component
    :is="as"
    class="star-border-container"
    :class="{ 'is-active': active }"
    :style="{ padding: `${thickness}px 0` }"
  >
    <span
      v-if="active"
      class="border-gradient-bottom"
      :style="gradientStyle"
    />
    <span
      v-if="active"
      class="border-gradient-top"
      :style="gradientStyle"
    />
    <span class="inner-content">
      <slot />
    </span>
  </component>
</template>
```

## 不要直接照搬的地方

- 原始内部背景是纯黑，和 Do Not Miss 现在的浅色、纸感、绿色系风格不完全一致，需要改成浅色内层或半透明内层。
- `border-radius: 20px` 对当前项目卡片来说可能太圆，标签可保留，卡片建议降到 10px 到 14px。
- 不要给所有能力标签都开动画，否则页面会显得廉价且费性能。
- 来源许可证正式使用前需要确认。

