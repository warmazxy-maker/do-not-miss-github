import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";

import { useAuthStore } from "@/stores/auth";
import type { UserRole } from "@/types/auth";
import AuthView from "@/views/AuthView.vue";
import SocialLayout from "@/views/SocialLayout.vue";
import SocialEventsView from "@/views/SocialEventsView.vue";
import SocialOrganizationView from "@/views/SocialOrganizationView.vue";
import SocialPublishView from "@/views/SocialPublishView.vue";
import StudentAchievementsView from "@/views/StudentAchievementsView.vue";
import StudentChallengesView from "@/views/StudentChallengesView.vue";
import StudentCoachView from "@/views/StudentCoachView.vue";
import StudentEventsView from "@/views/StudentEventsView.vue";
import StudentFollowsView from "@/views/StudentFollowsView.vue";
import StudentLayout from "@/views/StudentLayout.vue";
import StudentModulePlaceholder from "@/views/StudentModulePlaceholder.vue";
import StudentReservationsView from "@/views/StudentReservationsView.vue";
import StudentScheduleView from "@/views/StudentScheduleView.vue";
import WorkspacePlaceholder from "@/views/WorkspacePlaceholder.vue";

declare module "vue-router" {
  interface RouteMeta {
    requiresAuth?: boolean;
    guestOnly?: boolean;
    roles?: UserRole[];
    title?: string;
    description?: string;
    section?: string;
  }
}

const studentChildren: RouteRecordRaw[] = [
  ["events", "student-events", "事件", "发现机会"],
  ["reservations", "student-reservations", "我的预约", "行动管理"],
  ["schedule", "student-schedule", "日程", "行动管理"],
  ["challenges", "student-challenges", "挑战", "行动管理"],
  ["coach", "student-coach", "教练", "成长复盘"],
  ["follows", "student-follows", "关注", "发现机会"],
  ["achievements", "student-achievements", "个人成就", "成长档案"]
].map(([path, name, title, section]) => ({
  path,
  name,
  component:
    path === "events"
      ? StudentEventsView
      : path === "achievements"
        ? StudentAchievementsView
        : path === "reservations"
          ? StudentReservationsView
        : path === "schedule"
          ? StudentScheduleView
          : path === "challenges"
            ? StudentChallengesView
            : path === "coach"
              ? StudentCoachView
              : path === "follows"
                ? StudentFollowsView
        : StudentModulePlaceholder,
  meta: { title, section }
}));

const socialChildren: RouteRecordRaw[] = [
  ["events", "social-events", "我的事件", "查看组织发布的事件及审核状态。"],
  ["publish", "social-publish", "发布事件", "提交活动信息并进入质量预处理与审核流程。"],
  ["organization", "social-organization", "组织主页", "维护组织资料与学生可见信息。"]
].map(([path, name, title, description]) => ({
  path,
  name,
  component:
    path === "events"
      ? SocialEventsView
      : path === "publish"
        ? SocialPublishView
        : SocialOrganizationView,
  meta: { title, description }
}));

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/",
      name: "root",
      component: WorkspacePlaceholder,
      meta: { title: "Do Not Miss" }
    },
    {
      path: "/login",
      name: "login",
      component: AuthView,
      props: { mode: "login" },
      meta: { guestOnly: true, title: "登录" }
    },
    {
      path: "/register",
      name: "register",
      component: AuthView,
      props: { mode: "register" },
      meta: { guestOnly: true, title: "注册" }
    },
    {
      path: "/student",
      component: StudentLayout,
      redirect: "/student/events",
      meta: { requiresAuth: true, roles: ["STUDENT"] },
      children: studentChildren
    },
    {
      path: "/social",
      component: SocialLayout,
      redirect: "/social/events",
      meta: { requiresAuth: true, roles: ["SOCIAL"] },
      children: socialChildren
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: WorkspacePlaceholder,
      meta: {
        title: "页面不存在",
        description: "这个地址暂时没有对应的功能页面。"
      }
    }
  ]
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  await authStore.initialize();

  if (to.name === "root") {
    return authStore.isAuthenticated ? authStore.homeRoute : "/login";
  }
  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return authStore.homeRoute;
  }
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta.roles?.length && authStore.role && !to.meta.roles.includes(authStore.role)) {
    return authStore.homeRoute;
  }

  document.title = to.meta.title ? `${to.meta.title} | Do Not Miss` : "Do Not Miss";
  return true;
});

export default router;
