import { get } from '@/api/http';
import type { MetricSeries } from '@/types/common';

export interface MetricQuery {
  level?: 'raw' | 'min' | 'hour' | 'day' | string;
  measurement?: string;
  fields: string;
  start: string;
  end?: string;
  agg?: string;
  deviceId?: string;
  source?: string;
}

export function queryMetrics(params: MetricQuery) {
  return get<MetricSeries | MetricSeries[]>('/metrics', { params });
}
