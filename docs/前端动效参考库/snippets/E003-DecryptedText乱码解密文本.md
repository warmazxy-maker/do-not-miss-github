# E003 - DecryptedText 乱码解密文本

## 基本信息

- 效果名称：DecryptedText 乱码解密文本
- 编号：三号效果
- 触发词：乱码文本、解密文字、能力解锁、字符揭示、赛博文字、文字加密
- 来源类型：用户提供的 React 动效组件代码
- 来源组件：DecryptedText
- 许可证或版权备注：当前仅作为交互与实现参考；正式直接复用第三方代码前，仍需确认原始来源和许可证。
- 适合页面：能力分变化、完成活动后的结果提示、Judge 通过、重要成就、AI 处理完成
- 推荐优先级：中

## 视觉与交互描述

文本先显示为随机字符，再逐渐还原成真实文本，像信息被破解或能力被解锁。它可以从开头、结尾或中心依次揭示；也可以先随机刷新若干轮，最后一次性变为真实文字。

它比打字机更强调“结果已被确认”，更适合一次性、短促、稀有的奖励反馈，例如：

- 完成活动后，新能力标签出现时。
- Judge 通过后，“已验证”状态揭示时。
- 能力分发生较大变化时。
- AI 规划、RAG 或评分任务完成时的短标题。

不适合列表正文、活动标题或每一张能力卡片。大量并行随机字符会让页面嘈杂，也会持续制造重绘。

## 支持的模式

| 配置 | 含义 | Do Not Miss 推荐用途 |
| --- | --- | --- |
| animateOn=hover | 鼠标经过时解密 | 少量重点能力标签的 hover 反馈 |
| animateOn=click | 点击后解密 | Judge 结果、可展开的能力证明 |
| animateOn=view | 进入可视区域时只播放一次 | 成就页的首个能力摘要 |
| animateOn=inViewHover | 进入视区后再允许 hover | 展示型页面，谨慎使用 |
| sequential=true | 逐个字符按方向还原 | 很短的“已验证”“Java”等标题 |
| sequential=false | 多轮随机字符后整体还原 | “评分完成”“能力解锁”等状态语 |
| revealDirection=start/end/center | 从不同方向揭示 | center 适合徽章，start 适合普通文字 |
| clickMode=once/toggle | 点击一次或可正反切换 | 业务结果默认 once，少用 toggle |

## 技术要点

- 原始技术：React State、setInterval、IntersectionObserver、Motion React。
- 数据结构：revealedIndices 是一个字符下标集合，用于记录哪些字符已被还原。
- 随机字符集：默认字母加符号；可以指定 characters，或只从原文字符中随机。
- 可访问性：原组件用 sr-only 放置可读全文，并将视觉乱码层标记为 aria-hidden。Vue 版本必须保留这一点。
- Vue 迁移建议：不需要引入 Motion React 的等价库；普通 span + 鼠标事件 + CSS 即可。只在需要元素进出场时再考虑 Vue Transition。
- 定时器：每个组件只能维护一个 interval；触发新动画和组件卸载时必须 clearInterval。

## 用户提供的使用方式

~~~jsx
import DecryptedText from './DecryptedText';

<DecryptedText text="Hover me!" />

<DecryptedText
  text="Customize me"
  speed={60}
  maxIterations={10}
  characters="ABCD1234!?"
  className="revealed"
  parentClassName="all-letters"
  encryptedClassName="encrypted"
/>

<DecryptedText
  text="Click to decrypt"
  animateOn="view"
  clickMode="once"
/>

<div style={{ marginTop: '4rem' }}>
  <DecryptedText
    text="This text animates when in view"
    revealDirection="start"
    sequential
    useOriginalCharsOnly={false}
  />
</div>
~~~

备注：使用示例的注释与第三段属性含义存在轻微不一致。animateOn=view 是进入视区播放，而非点击播放；clickMode 只有 animateOn=click 时才生效。Vue 组件应把这些模式明确校验，避免无效配置。

## 核心算法摘要

~~~js
// 1. 每次刷新时，已经揭示的下标保留原字符，其他位置随机取字符。
function shuffleText(originalText, revealedIndices, availableChars) {
  return originalText
    .split('')
    .map((char, index) => {
      if (char === ' ') return ' ';
      if (revealedIndices.has(index)) return originalText[index];
      return availableChars[Math.floor(Math.random() * availableChars.length)];
    })
    .join('');
}

// 2. 顺序模式按 start / end / center 得到下一个需揭示的下标。
// 3. 每个 interval tick 将一个下标加入 revealedIndices，并刷新 displayText。
// 4. 非顺序模式只刷新 maxIterations 次乱码，最后直接显示原文。
// 5. 反向模式从完整 revealedIndices 中逐步移除下标，恢复为乱码。
~~~

## Vue 改造成 Do Not Miss 的方案

建议组件名：DecryptReveal.vue。

- props：text、speed、maxIterations、sequential、revealDirection、characters、animateOn、clickMode、playOnce、reducedMotion。
- emits：complete、reverse-complete。
- slots：不需要。该组件应专注于单段短文本，不要承担卡片布局。
- 状态：displayText、isAnimating、isRevealed、revealedIndices、hasPlayed。
- 默认策略：animateOn=view、playOnce=true、sequential=false、speed 约 45 到 70 毫秒、maxIterations 约 8。
- 用户开启系统减少动效偏好时，直接渲染原文，不创建 interval。

建议接入位置：

1. 能力档案中，新标签第一次出现的标题。
2. Judge 完成后的 PASS 或 MANUAL REVIEW 状态文字。
3. 完成活动后的“评分已入账”短提示。
4. Guide Agent 返回计划后的一个极短状态标题。

## 不要直接照搬的地方

- 原组件引入 Motion React，但这里只使用 motion.span。Vue 版本无须为了这个效果引入大型动画库。
- hover 模式会在鼠标反复进出时不断重播，能力列表不要批量使用。
- 不要把随机字符作为唯一文本；必须保留屏幕阅读器可读原文和减少动效降级。
- 不要在请求尚未完成时使用“已解密”的视觉暗示。它只能表达真实完成的结果，不能伪造进度。
