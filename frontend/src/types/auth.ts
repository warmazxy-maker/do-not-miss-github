/** Built-in roles; roles are data on the backend, so an unknown code is still a valid role. */
export type UserRole = "STUDENT" | "SOCIAL" | "OPERATOR" | "ADMIN" | "ADMIN_ZERO" | (string & {});

/** The only roles the public sign-up form may request. */
export type RegistrableRole = "STUDENT" | "SOCIAL";

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
  role: RegistrableRole;
}
