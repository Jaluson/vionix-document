import { get, post, put } from '@/api/http';
import type { Device, DeviceStatus, PageQuery, PageResult } from '@/types/common';

export interface DeviceQuery extends PageQuery {
  keyword?: string;
  status?: DeviceStatus | '';
  source?: string;
}

export function listDevices(params: DeviceQuery = {}) {
  return get<PageResult<Device>>('/devices', { params });
}

export function getDevice(deviceId: string) {
  return get<Device>(`/devices/${encodeURIComponent(deviceId)}`);
}

export function createDevice(payload: Device) {
  return post<Device>('/devices', payload);
}

export function updateDevice(deviceId: string, payload: Partial<Device>) {
  return put<Device>(`/devices/${encodeURIComponent(deviceId)}`, payload);
}

export function updateDeviceStatus(deviceId: string, status: DeviceStatus) {
  return put<Device>(`/devices/${encodeURIComponent(deviceId)}/status`, { status });
}
