import { del, get, post, put } from '@/api/http';
import type { DeviceGroup, PageQuery, PageResult } from '@/types/common';

export function listDeviceGroups(params: PageQuery = {}) {
  return get<PageResult<DeviceGroup> | DeviceGroup[]>('/device-groups', { params });
}

export function createDeviceGroup(payload: Partial<DeviceGroup>) {
  return post<DeviceGroup>('/device-groups', payload);
}

export function updateDeviceGroup(id: string | number, payload: Partial<DeviceGroup>) {
  return put<DeviceGroup>(`/device-groups/${id}`, payload);
}

export function deleteDeviceGroup(id: string | number) {
  return del<void>(`/device-groups/${id}`);
}

export function bindDevice(id: string | number, deviceId: string) {
  return post<void>(`/device-groups/${id}/devices`, { deviceId });
}

export function removeDevice(id: string | number, deviceId: string) {
  return del<void>(`/device-groups/${id}/devices`, { data: { deviceId } });
}
