import { del, get, post, put } from '@/api/http';
import type { Dashboard, PageQuery, PageResult } from '@/types/common';

export function listDashboards(params: PageQuery = {}) {
  return get<PageResult<Dashboard>>('/dashboards', { params });
}

export function getDashboard(id: string | number) {
  return get<Dashboard>(`/dashboards/${id}`);
}

export function createDashboard(payload: Dashboard) {
  return post<Dashboard>('/dashboards', payload);
}

export function updateDashboard(id: string | number, payload: Dashboard) {
  return put<Dashboard>(`/dashboards/${id}`, payload);
}

export function deleteDashboard(id: string | number) {
  return del<void>(`/dashboards/${id}`);
}

export function publishDashboard(id: string | number, published: boolean) {
  return put<Dashboard>(`/dashboards/${id}/publish`, { published });
}

export function listDashboardDevices() {
  return get<Array<{ deviceId: string; name: string; source?: string }>>('/dashboard-vars/devices');
}

export function listDashboardMetrics() {
  return get<Array<{ name?: string; field?: string; label?: string; unit?: string }> | string[]>('/dashboard-vars/metrics');
}
