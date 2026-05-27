import { get } from '@/api/http';
import type { AlertRecord, AlertStats, PageQuery, PageResult } from '@/types/common';

export interface AlertQuery extends PageQuery {
  severity?: string;
  status?: string;
  deviceId?: string;
  startTime?: string;
  endTime?: string;
}

export function listAlerts(params: AlertQuery = {}) {
  return get<PageResult<AlertRecord>>('/alerts', { params });
}

export function listFiringAlerts() {
  return get<AlertRecord[]>('/alerts/firing');
}

export function getAlertStats(params: Omit<AlertQuery, 'pageNum' | 'pageSize'> = {}) {
  return get<AlertStats>('/alerts/stats', { params });
}
