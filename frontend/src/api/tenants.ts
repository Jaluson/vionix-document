import { del, get, post, put } from '@/api/http';
import type { PageQuery, PageResult, Tenant } from '@/types/common';

export function listTenants(params: PageQuery = {}) {
  return get<PageResult<Tenant>>('/tenants', { params });
}

export function getTenant(id: string | number) {
  return get<Tenant>(`/tenants/${id}`);
}

export function createTenant(payload: Tenant) {
  return post<Tenant>('/tenants', payload);
}

export function updateTenant(id: string | number, payload: Tenant) {
  return put<Tenant>(`/tenants/${id}`, payload);
}

export function deleteTenant(id: string | number) {
  return del<void>(`/tenants/${id}`);
}
