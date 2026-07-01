import { defineStore } from "pinia";
import { computed, ref } from "vue";

import { authApi } from "@/api/auth";
import { ApiError, AUTH_STORAGE_KEY } from "@/api/client";
import type { AuthSession, LoginPayload, RegisterPayload, UserRole } from "@/types/auth";

function loadSession(): AuthSession | null {
  const raw = localStorage.getItem(AUTH_STORAGE_KEY);
  if (!raw) {
    return null;
  }

  try {
    const session = JSON.parse(raw) as AuthSession;
    if (!session.token || !session.user?.userId) {
      throw new Error("Invalid auth session");
    }
    if (session.expiresAt && new Date(session.expiresAt).getTime() <= Date.now()) {
      localStorage.removeItem(AUTH_STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    return null;
  }
}

export const useAuthStore = defineStore("auth", () => {
  const session = ref<AuthSession | null>(loadSession());
  const initialized = ref(false);
  const busy = ref(false);
  let initialization: Promise<void> | null = null;

  const user = computed(() => session.value?.user ?? null);
  const token = computed(() => session.value?.token ?? "");
  const isAuthenticated = computed(() => Boolean(token.value && user.value));
  const role = computed<UserRole | null>(() => user.value?.role ?? null);
  const homeRoute = computed(() => (role.value === "SOCIAL" ? "/social/events" : "/student/events"));

  function persist(nextSession: AuthSession | null) {
    session.value = nextSession;
    if (nextSession) {
      localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(nextSession));
    } else {
      localStorage.removeItem(AUTH_STORAGE_KEY);
    }
  }

  function clearSession() {
    persist(null);
  }

  async function initialize() {
    if (initialized.value) {
      return;
    }
    if (initialization) {
      return initialization;
    }

    initialization = (async () => {
      if (session.value) {
        try {
          const currentUser = await authApi.me();
          persist({ ...session.value, user: currentUser });
        } catch (error) {
          if (error instanceof ApiError && error.status === 401) {
            clearSession();
          }
        }
      }
      initialized.value = true;
    })().finally(() => {
      initialization = null;
    });

    return initialization;
  }

  async function login(payload: LoginPayload) {
    busy.value = true;
    try {
      const nextSession = await authApi.login(payload);
      persist(nextSession);
      return nextSession;
    } finally {
      busy.value = false;
    }
  }

  async function register(payload: RegisterPayload) {
    busy.value = true;
    try {
      const nextSession = await authApi.register(payload);
      persist(nextSession);
      return nextSession;
    } finally {
      busy.value = false;
    }
  }

  async function logout() {
    busy.value = true;
    try {
      if (session.value) {
        await authApi.logout().catch(() => undefined);
      }
    } finally {
      clearSession();
      busy.value = false;
    }
  }

  return {
    session,
    user,
    token,
    role,
    isAuthenticated,
    initialized,
    busy,
    homeRoute,
    initialize,
    login,
    register,
    logout,
    clearSession
  };
});
