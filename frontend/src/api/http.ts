import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
import { getAccessToken } from '@/utils/token';

interface BackendError {
  code?: string;
  message?: string;
  traceId?: string;
}

export class ApiError extends Error {
  code?: string;
  traceId?: string;
  status?: number;

  constructor(message: string, options: { code?: string; traceId?: string; status?: number } = {}) {
    super(message);
    this.name = 'ApiError';
    this.code = options.code;
    this.traceId = options.traceId;
    this.status = options.status;
  }
}

const client = axios.create({
  baseURL: '/api',
  timeout: 15000,
  withCredentials: true
});

let refreshPromise: Promise<void> | null = null;

function isEnvelope(value: unknown): value is { code: string; data: unknown; message?: string; traceId?: string } {
  return !!value && typeof value === 'object' && 'code' in value && 'data' in value;
}

function unwrapResponse(data: unknown) {
  if (isEnvelope(data)) {
    if (data.code !== 'OK') {
      throw new ApiError(data.message || '请求失败', {
        code: data.code,
        traceId: data.traceId
      });
    }
    return data.data;
  }

  return data;
}

function toApiError(error: unknown) {
  if (error instanceof ApiError) return error;
  if (axios.isAxiosError(error)) {
    const axiosError = error as AxiosError<BackendError>;
    const data = axiosError.response?.data;
    return new ApiError(data?.message || axiosError.message || '网络请求失败', {
      code: data?.code,
      traceId: data?.traceId,
      status: axiosError.response?.status
    });
  }
  if (error instanceof Error) return new ApiError(error.message);
  return new ApiError('未知错误');
}

client.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (response) => unwrapResponse(response.data) as any,
  async (error: AxiosError<BackendError>) => {
    const original = error.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined;
    const status = error.response?.status;
    const url = original?.url || '';
    const isAuthEndpoint = url.includes('/auth/login') || url.includes('/auth/refresh');

    if (status === 401 && original && !original._retry && !isAuthEndpoint) {
      original._retry = true;
      try {
        const { useAuthStore } = await import('@/stores/auth');
        const auth = useAuthStore();
        refreshPromise ||= auth.refreshSession().finally(() => {
          refreshPromise = null;
        });
        await refreshPromise;
        return client(original);
      } catch (refreshError) {
        const { useAuthStore } = await import('@/stores/auth');
        const { router } = await import('@/router');
        useAuthStore().clearSession();
        if (router.currentRoute.value.path !== '/login') {
          router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } });
        }
        throw toApiError(refreshError);
      }
    }

    throw toApiError(error);
  }
);

export async function get<T>(url: string, config?: AxiosRequestConfig) {
  return client.get<T, T>(url, config);
}

export async function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
  return client.post<T, T>(url, data, config);
}

export async function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig) {
  return client.put<T, T>(url, data, config);
}

export async function del<T>(url: string, config?: AxiosRequestConfig) {
  return client.delete<T, T>(url, config);
}

export default client;
