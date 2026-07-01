import { api } from "@/api/client";
import type { AuthSession, LoginPayload, RegisterPayload, User } from "@/types/auth";

export const authApi = {
  login(payload: LoginPayload) {
    return api.post<AuthSession>("/api/auth/login", payload, { auth: false });
  },
  register(payload: RegisterPayload) {
    return api.post<AuthSession>("/api/auth/register", payload, { auth: false });
  },
  me() {
    return api.get<User>("/api/auth/me");
  },
  logout() {
    return api.post<void>("/api/auth/logout");
  }
};
