import {
  Award,
  CalendarDays,
  Compass,
  Flag,
  Heart,
  MessagesSquare,
  TicketCheck,
  type LucideIcon
} from "@lucide/vue";

export interface StudentNavigationItem {
  to: string;
  label: string;
  shortLabel?: string;
  icon: LucideIcon;
}

export interface StudentNavigationGroup {
  label: string;
  items: StudentNavigationItem[];
}

export const studentNavigationGroups: StudentNavigationGroup[] = [
  {
    label: "发现",
    items: [
      { to: "/student/events", label: "事件", icon: Compass },
      { to: "/student/follows", label: "关注", icon: Heart }
    ]
  },
  {
    label: "行动",
    items: [
      { to: "/student/reservations", label: "我的预约", shortLabel: "预约", icon: TicketCheck },
      { to: "/student/schedule", label: "日程", icon: CalendarDays },
      { to: "/student/challenges", label: "挑战", icon: Flag }
    ]
  },
  {
    label: "成长",
    items: [
      { to: "/student/coach", label: "教练", icon: MessagesSquare },
      { to: "/student/achievements", label: "个人成就", shortLabel: "成就", icon: Award }
    ]
  }
];

export const studentNavigationItems = studentNavigationGroups.flatMap((group) => group.items);

export const studentMobilePrimaryItems = [
  studentNavigationItems.find((item) => item.to === "/student/events"),
  studentNavigationItems.find((item) => item.to === "/student/schedule"),
  studentNavigationItems.find((item) => item.to === "/student/challenges"),
  studentNavigationItems.find((item) => item.to === "/student/achievements")
].filter((item): item is StudentNavigationItem => Boolean(item));

export const studentMobileMoreItems = studentNavigationItems.filter(
  (item) => !studentMobilePrimaryItems.some((primary) => primary.to === item.to)
);
