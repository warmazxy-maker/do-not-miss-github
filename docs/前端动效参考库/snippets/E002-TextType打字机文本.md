# E002 - TextType 打字机文本

## 基本信息

- 效果名称：TextType 打字机文本
- 编号：二号效果
- 触发词：打字机、逐字出现、AI 正在说话、引导文字轮播、文字删除重写
- 来源类型：用户提供的 React 动效组件代码
- 来源组件：TextType
- 许可证或版权备注：当前作为交互与实现参考；正式直接复用第三方代码前，仍需确认原始来源和许可证。
- 适合页面：Guide Agent 对话、Coach 开场语、登录欢迎语、AI 推荐加载反馈、空状态
- 推荐优先级：中

## 视觉与交互描述

文字像正在输入一样逐字出现，末尾带一个闪烁光标。它支持一条或多条文本：一段结束后暂停、逐字删除，再输入下一段；也可以只播放一次。

它适合表达“系统正在组织语言”或“AI 有自己的节奏”。Do Not Miss 中，应该只让很短的引导句使用，例如 Coach 第一次提问、Guide Agent 等待结果时的状态语。长段聊天内容和活动正文仍应一次性渲染，避免拖慢阅读。

- 基础模式：逐字输入一段文本，光标持续闪烁。
- 轮播模式：输入完成 -> 暂停 -> 逐字删除 -> 输入下一段。
- 可见性模式：元素进入屏幕视区后才开始播放。
- 随机速度模式：每个字使用小幅随机间隔，模拟自然输入节奏。
- 完成回调：一段文本结束后触发事件，例如切换下一步引导、显示操作按钮。

## 技术要点

- 原始技术：React Hooks + setTimeout + GSAP 光标透明度动画。
- 核心状态：displayedText、当前字符索引、当前句索引、是否删除、是否开始播放。
- 可迁移到 Vue：可以。用 ref、watchEffect 或生命周期钩子维持相同状态机。
- Vue 实现建议：光标闪烁直接使用 CSS keyframes，逐字和删除仍用单个可清理的 setTimeout；不为一个光标额外引入 GSAP。
- 可见性判断：使用 IntersectionObserver，进入页面后开始；组件卸载时必须断开 observer 并清理 timeout。
- 性能边界：同一屏最多同时运行 1 到 2 个打字机。聊天列表滚动时，不要让历史气泡重复播放。

## 原始使用方式

~~~jsx
import TextType from './TextType';

<TextType
  text={["Text typing effect", "for your websites", "Happy coding!"]}
  typingSpeed={75}
  pauseDuration={1500}
  showCursor
  cursorCharacter="_"
  deletingSpeed={50}
  cursorBlinkDuration={0.5}
/>
~~~

注：用户给出的示例中 texts、variableSpeedEnabled、variableSpeedMin、variableSpeedMax 与原组件实际 props 不完全一致。原组件使用 text 和 variableSpeed；迁移时应统一成 Vue 的明确 props，避免兼容两套命名。

## 原始 React 逻辑

下面保留用户提供的逻辑。两处 JSX 模板字符串在文档中改成了等价字符串拼接，避免 Markdown 和归档工具转义；其余状态机与原代码一致。

~~~jsx
'use client';

import { useEffect, useRef, useState, createElement, useMemo, useCallback } from 'react';
import { gsap } from 'gsap';
import './TextType.css';

const TextType = ({
  text,
  as: Component = 'div',
  typingSpeed = 50,
  initialDelay = 0,
  pauseDuration = 2000,
  deletingSpeed = 30,
  loop = true,
  className = '',
  showCursor = true,
  hideCursorWhileTyping = false,
  cursorCharacter = '|',
  cursorClassName = '',
  cursorBlinkDuration = 0.5,
  textColors = [],
  variableSpeed,
  onSentenceComplete,
  startOnVisible = false,
  reverseMode = false,
  ...props
}) => {
  const [displayedText, setDisplayedText] = useState('');
  const [currentCharIndex, setCurrentCharIndex] = useState(0);
  const [isDeleting, setIsDeleting] = useState(false);
  const [currentTextIndex, setCurrentTextIndex] = useState(0);
  const [isVisible, setIsVisible] = useState(!startOnVisible);
  const cursorRef = useRef(null);
  const containerRef = useRef(null);

  const textArray = useMemo(() => (Array.isArray(text) ? text : [text]), [text]);

  const getRandomSpeed = useCallback(() => {
    if (!variableSpeed) return typingSpeed;
    const { min, max } = variableSpeed;
    return Math.random() * (max - min) + min;
  }, [variableSpeed, typingSpeed]);

  const getCurrentTextColor = () => {
    if (textColors.length === 0) return 'inherit';
    return textColors[currentTextIndex % textColors.length];
  };

  useEffect(() => {
    if (!startOnVisible || !containerRef.current) return;
    const observer = new IntersectionObserver(
      entries => entries.forEach(entry => {
        if (entry.isIntersecting) setIsVisible(true);
      }),
      { threshold: 0.1 }
    );
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, [startOnVisible]);

  useEffect(() => {
    if (showCursor && cursorRef.current) {
      gsap.set(cursorRef.current, { opacity: 1 });
      gsap.to(cursorRef.current, {
        opacity: 0,
        duration: cursorBlinkDuration,
        repeat: -1,
        yoyo: true,
        ease: 'power2.inOut'
      });
    }
  }, [showCursor, cursorBlinkDuration]);

  useEffect(() => {
    if (!isVisible) return;

    let timeout;
    const currentText = textArray[currentTextIndex];
    const processedText = reverseMode ? currentText.split('').reverse().join('') : currentText;

    const executeTypingAnimation = () => {
      if (isDeleting) {
        if (displayedText === '') {
          setIsDeleting(false);
          if (currentTextIndex === textArray.length - 1 && !loop) return;
          if (onSentenceComplete) onSentenceComplete(textArray[currentTextIndex], currentTextIndex);
          setCurrentTextIndex(prev => (prev + 1) % textArray.length);
          setCurrentCharIndex(0);
          timeout = setTimeout(() => {}, pauseDuration);
        } else {
          timeout = setTimeout(() => setDisplayedText(prev => prev.slice(0, -1)), deletingSpeed);
        }
      } else if (currentCharIndex < processedText.length) {
        timeout = setTimeout(
          () => {
            setDisplayedText(prev => prev + processedText[currentCharIndex]);
            setCurrentCharIndex(prev => prev + 1);
          },
          variableSpeed ? getRandomSpeed() : typingSpeed
        );
      } else if (textArray.length >= 1) {
        if (!loop && currentTextIndex === textArray.length - 1) return;
        timeout = setTimeout(() => setIsDeleting(true), pauseDuration);
      }
    };

    if (currentCharIndex === 0 && !isDeleting && displayedText === '') {
      timeout = setTimeout(executeTypingAnimation, initialDelay);
    } else {
      executeTypingAnimation();
    }

    return () => clearTimeout(timeout);
  }, [
    currentCharIndex, displayedText, isDeleting, typingSpeed, deletingSpeed,
    pauseDuration, textArray, currentTextIndex, loop, initialDelay,
    isVisible, reverseMode, variableSpeed, onSentenceComplete
  ]);

  const shouldHideCursor =
    hideCursorWhileTyping && (currentCharIndex < textArray[currentTextIndex].length || isDeleting);

  return createElement(
    Component,
    {
      ref: containerRef,
      className: 'text-type ' + className,
      ...props
    },
    <span className="text-type__content" style={{ color: getCurrentTextColor() || 'inherit' }}>
      {displayedText}
    </span>,
    showCursor && (
      <span
        ref={cursorRef}
        className={
          'text-type__cursor ' + cursorClassName +
          (shouldHideCursor ? ' text-type__cursor--hidden' : '')
        }
      >
        {cursorCharacter}
      </span>
    )
  );
};

export default TextType;
~~~

## 原始 CSS

~~~css
.text-type {
  display: inline-block;
  white-space: pre-wrap;
}

.text-type__cursor {
  margin-left: 0.25rem;
  display: inline-block;
  opacity: 1;
}

.text-type__cursor--hidden {
  display: none;
}
~~~

## 改造成 Do Not Miss 的方案

建议制作一个通用 TypewriterText.vue，并保持业务内容与动效分离。

- props：text、typingSpeed、deletingSpeed、pauseDuration、loop、startOnVisible、showCursor、cursorCharacter、playOnce。
- events：complete，用于在一段引导文字播完后显示后续控件。
- 一次业务请求只播放一次：Guide Agent 得到推荐后，推荐结果本身直接出现；仅顶部一句引导说明可使用打字机。
- Coach 场景默认关闭“删除重写”，保持聊天阅读稳定性；首页或登录欢迎语可开启轮播。
- 使用 prefers-reduced-motion：用户系统偏好减少动效时，直接完整显示文本和静态光标。

## 不要直接照搬的地方

- 现有项目是 Vue，不应引入 React 或把 GSAP 作为一个光标闪烁的必要依赖。
- 不应对已存在的聊天历史逐字重放；只对新生成、很短的引导文字使用。
- 原始代码在删除结束后使用了一个空的 setTimeout，Vue 版本应把“下一句开始前的暂停”明确建模，避免因为响应式更新导致停顿不稳定。
- 长文本、错误信息、表单说明不要用打字机，否则可用性会变差。
