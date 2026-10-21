# ContributionGraph 热力图

## 基本信息

- 效果名称：ContributionGraph 热力图
- 触发词：热力图、贡献图、学习打卡图、年度格子图、GitHub 绿格图、连续学习
- 来源链接：用户提供组件代码，原始页面链接未记录
- 来源类型：网站参考 / 用户收集代码
- 许可证或版权备注：作为交互与布局参考使用；投入产品前应回查原始来源与许可证。
- 适合页面：个人主页、成长记录、学习复盘、年度总结。
- 推荐优先级：高

## 视觉描述

按周为列、按星期为行排列全年日期，每天是一个小方格；颜色深浅代表当天的学习或完成活动数量。悬停方格展示日期与完成次数，下方用 Less / More 图例解释颜色层级。视觉语言接近 GitHub Contributions，但数据语义是“真实学习与实践”。

## 交互描述

- Hover / 键盘聚焦：展示日期、完成次数、当天完成的活动摘要。
- 年份切换：重新按目标年份生成 53 周 x 7 天网格。
- Legend：说明 0-4 级颜色强度。
- 窄屏：保留横向滚动，不能强行压缩方格到不可点击。

## 技术要点

- 主要技术：日期边界计算、53 周网格、按日聚合、CSS Grid 或 table、tooltip。
- 是否依赖第三方库：原实现使用 `motion/react` 让 tooltip 进出场；网格本身不需要第三方依赖。
- 移动端是否适配：可以，使用横向滚动容器并保留 tooltip 的点击/长按替代方案。
- 性能风险：一年只有 371 个格子，渲染开销很低；不要让每个格子都持续动画。

## 原始数据契约

```ts
export interface ContributionData {
  count: number;  // 当天有效学习/完成记录数
  date: string;   // YYYY-MM-DD
  level: number;  // 0-4，颜色深浅
}

export interface ContributionGraphProps {
  className?: string;
  data?: ContributionData[];
  showLegend?: boolean;
  showTooltips?: boolean;
  year?: number;
}
```

## 关键参考实现

```tsx
"use client";

import { motion, useReducedMotion } from "motion/react";
import type React from "react";
import { useMemo, useState } from "react";

const WEEKS_IN_YEAR = 53;
const DAYS_IN_WEEK = 7;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

const createDayData = (currentDate: Date, source: ContributionData[]) => {
  const [date] = currentDate.toISOString().split("T");
  const existing = source.find((item) => item.date === date);
  return { count: existing?.count ?? 0, date, level: existing?.level ?? 0 };
};

export function ContributionGraph({ data = [], year = new Date().getFullYear(), showLegend = true, showTooltips = true }: ContributionGraphProps) {
  const [hoveredDay, setHoveredDay] = useState<ContributionData | null>(null);
  const [tooltipPosition, setTooltipPosition] = useState({ x: 0, y: 0 });
  const reduceMotion = useReducedMotion();

  const yearData = useMemo(() => {
    const start = new Date(year, 0, 1);
    const end = new Date(year, 11, 31);
    const firstSunday = new Date(start);
    firstSunday.setDate(start.getDate() - start.getDay());
    const result: ContributionData[] = [];

    for (let week = 0; week < WEEKS_IN_YEAR; week += 1) {
      for (let day = 0; day < DAYS_IN_WEEK; day += 1) {
        const current = new Date(firstSunday);
        current.setDate(firstSunday.getDate() + week * DAYS_IN_WEEK + day);
        const valid = current >= start && current <= end;
        result.push(valid ? createDayData(current, data) : { count: 0, date: "", level: 0 });
      }
    }
    return result;
  }, [data, year]);

  return (
    <div className="contribution-graph">
      <div className="overflow-x-auto">
        <table className="border-separate border-spacing-1 text-xs">
          <caption className="sr-only">Contribution Graph for {year}</caption>
          <tbody>
            {Array.from({ length: DAYS_IN_WEEK }, (_, dayIndex) => (
              <tr key={DAYS[dayIndex]}>
                <td>{dayIndex % 2 === 0 ? DAYS[dayIndex] : ""}</td>
                {Array.from({ length: WEEKS_IN_YEAR }, (_week, weekIndex) => {
                  const day = yearData[weekIndex * DAYS_IN_WEEK + dayIndex];
                  return <td key={`${day.date}-${weekIndex}`} onMouseEnter={(event) => {
                    if (showTooltips && day.date) {
                      setHoveredDay(day);
                      setTooltipPosition({ x: event.clientX, y: event.clientY });
                    }
                  }} onMouseLeave={() => setHoveredDay(null)}>
                    <div className={`heat-cell heat-level-${day.level}`} />
                  </td>;
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {showTooltips && hoveredDay && (
        <motion.div className="heat-tooltip" initial={reduceMotion ? false : { opacity: 0, scale: 0.8 }} animate={{ opacity: 1, scale: 1 }}
          style={{ left: tooltipPosition.x + 10, top: tooltipPosition.y - 40 }}>
          <strong>{hoveredDay.count} 条有效学习记录</strong><span>{hoveredDay.date}</span>
        </motion.div>
      )}
      {showLegend && <div className="heat-legend"><span>少</span>{[0, 1, 2, 3, 4].map(level => <i className={`heat-cell heat-level-${level}`} key={level} />)}<span>多</span></div>}
    </div>
  );
}
```

```css
.contribution-graph { overflow: hidden; }
.heat-cell { width: 10px; height: 10px; border-radius: 2px; background: #e9eeec; }
.heat-level-1 { background: #d0eee6; }
.heat-level-2 { background: #8bd7c7; }
.heat-level-3 { background: #3eaea0; }
.heat-level-4 { background: #117e75; }
.heat-tooltip { position: fixed; z-index: 50; display: grid; gap: 3px; padding: 8px 10px; border: 1px solid #d9dfdc; border-radius: 6px; background: #fff; box-shadow: 0 8px 20px rgba(23, 45, 42, .14); font-size: 12px; pointer-events: none; }
.heat-legend { display: flex; align-items: center; justify-content: flex-end; gap: 4px; margin-top: 10px; color: #677476; font-size: 11px; }
```

## Do Not Miss 的真实数据设计

前端不自行随机 `count`。后端按用户与本地日期聚合有效成长记录，返回：

```json
[
  { "date": "2026-09-03", "count": 2, "level": 2 },
  { "date": "2026-09-04", "count": 1, "level": 1 },
  { "date": "2026-09-05", "count": 4, "level": 4 }
]
```

建议的统计口径：

1. 已完成活动：`achievement_record.completed_at` 按用户当地日期聚合。
2. 已完成挑战：挑战完成事件按完成日期聚合。
3. 复盘补充：只作为当天记录详情，不单独多加一次，防止写长复盘刷热力值。
4. `level` 不直接等于 count：例如 0=0 条，1=1 条，2=2 条，3=3 条，4=4 条及以上；具体阈值可以以后基于真实数据校准。

## 改造成 Do Not Miss 的方案

1. 个人主页在“近期成长证据”下方补一块“学习节奏”，显示当前年热力图与连续学习天数。
2. Tooltip 显示当天完成的活动标题，而不是笼统的 contribution；点击格子可筛选当天成长记录。
3. 当前项目为 Vue：使用 `v-for` 渲染 53 x 7 格，`computed` 预生成日期，tooltip 用固定定位和 Vue Transition；无需安装 React 或 Motion。
4. 使用当前浅色纸感与青绿色谱，不照搬 GitHub 的绿色或原代码的 `bg-primary`，尤其 level 0 必须是低对比空白色。

## 不要直接照搬的地方

- 原 demo 的 `generateSampleData()` 用随机数，只适合演示，接业务会误导用户。
- 原代码每个日期使用 `source.find()`，全年渲染会多次线性查找；Vue 版先转换成 `Map<date, data>`。
- 仅靠 mouse hover 不够；移动端应该提供点击打开当天详情，键盘用户需要能聚焦格子。
- `toISOString()` 会转为 UTC，接入时必须按用户时区生成本地日期，避免凌晨附近日期错位。
