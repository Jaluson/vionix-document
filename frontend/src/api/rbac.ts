import { del, get, post, put } from '@/api/http';
import type { ManagedUser, PageQuery, PageResult, PermissionItem, Role } from '@/types/common';

export function listUsers(params: PageQuery = {}) {
  return get<PageResult<ManagedUser>>('/users', { params });
}

export function createUser(payload: Partial<ManagedUser> & { password?: string }) {
  return post<ManagedUser>('/users', payload);
}

export function updateUser(id: string | number, payload: Partial<ManagedUser>) {
  return put<ManagedUser>(`/users/${id}`, payload);
}

export function deleteUser(id: string | number) {
  return del<void>(`/users/${id}`);
}

export function updateUserPassword(id: string | number, password: string) {
  return put<void>(`/users/${id}/password`, { password });
}

export function updateUserRoles(id: string | number, roles: string[]) {
  return put<void>(`/users/${id}/roles`, { roles });
}

export function updateUserStatus(id: string | number, status: string) {
  return put<ManagedUser>(`/users/${id}/status`, { status });
}

export function listRoles(params: PageQuery = {}) {
  return get<PageResult<Role>>('/roles', { params });
}

export function createRole(payload: Role) {
  return post<Role>('/roles', payload);
}

export function updateRole(id: string | number, payload: Role) {
  return put<Role>(`/roles/${id}`, payload);
}

export function deleteRole(id: string | number) {
  return del<void>(`/roles/${id}`);
}

export function updateRolePermissions(id: string | number, permissions: string[]) {
  return put<void>(`/roles/${id}/permissions`, { permissions });
}

export function listPermissionTree() {
  return get<PermissionItem[]>('/permissions/tree');
}

export function listPermissions() {
  return get<PermissionItem[]>('/permissions');
}
