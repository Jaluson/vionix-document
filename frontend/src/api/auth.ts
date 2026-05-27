import { del, get, post } from '@/api/http';
import type { LoginPayload, LoginResponse } from '@/types/common';

export function login(payload: LoginPayload) {
  return post<LoginResponse>('/auth/login', {
    ...payload,
    deviceInfo: payload.deviceInfo || navigator.userAgent
  });
}

export function refresh() {
  return post<LoginResponse>('/auth/refresh');
}

export function logout() {
  return del<void>('/auth/logout');
}

export function getPublicKey() {
  return get<{ publicKey: string }>('/security/public-key');
}
