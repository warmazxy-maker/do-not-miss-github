export type UserRole = "STUDENT" | "SOCIAL";

export interface User {
  userId: string;
  username: string;
  email: string;
  role: UserRole;
  createdAt: string;
}

export interface AuthSession {
  token: string;
  expiresAt: string;
  user: User;
}

export interface LoginPayload {
  account: string;
  password: string;
}

export interface RegisterPayload {
  username: string;
  email: string;
  password: string;
  role: UserRole;
}
