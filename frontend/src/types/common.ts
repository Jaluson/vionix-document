export type ApiCode =
  | 'OK'
  | 'BAD_REQUEST'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'DEVICE_DISABLED'
  | 'TENANT_MISMATCH'
  | 'INTERNAL_ERROR'
  | string;

export interface ApiEnvelope<T> {
  code: ApiCode;
  message?: string;
  data: T;
  traceId?: string;
}

export interface PageResult<T> {
  items: T[];
  pageNum: number;
  pageSize: number;
  total: number;
}

export interface PageQuery {
  pageNum?: number;
  pageSize?: number;
  [key: string]: unknown;
}

export interface UserInfo {
  id: number | string;
  username: string;
  nickname?: string;
  tenantId?: number | string;
  roles: string[];
  permissions: string[];
}

export interface LoginPayload {
  tenantCode?: string;
  username: string;
  password: string;
  captcha?: string;
  deviceInfo?: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType?: string;
  expiresIn?: number;
  user: UserInfo;
}

export type DeviceStatus = 'ENABLED' | 'DISABLED' | 'ARCHIVED';

export interface Device {
  id?: number | string;
  deviceId: string;
  name: string;
  source?: string;
  location?: string;
  status?: DeviceStatus;
  metadata?: Record<string, unknown>;
  createdAt?: string;
  updatedAt?: string;
}

export type RuleScope = 'DEVICE' | 'GROUP';
export type Severity = 'INFO' | 'WARNING' | 'CRITICAL';

export interface RuleCondition {
  groupIndex: number;
  conditionType: string;
  metric: string;
  operator: string;
  threshold: number;
  durationSeconds: number;
}

export interface RuleAction {
  actionType: string;
  config: Record<string, unknown>;
}

export interface Rule {
  id?: number | string;
  name: string;
  scope: RuleScope;
  targetId: string | number;
  severity: Severity;
  enabled?: boolean;
  conditions: RuleCondition[];
  actions: RuleAction[];
  createdAt?: string;
  updatedAt?: string;
}

export interface AlertRecord {
  id?: number | string;
  alertId?: number | string;
  ruleName?: string;
  severity?: Severity;
  status?: string;
  deviceId?: string;
  triggerValue?: number;
  firedAt?: string;
  resolvedAt?: string;
  message?: string;
}

export interface AlertStats {
  total?: number;
  firing?: number;
  critical?: number;
  warning?: number;
  resolved?: number;
}

export interface DeviceGroup {
  id: number | string;
  name: string;
  description?: string;
  deviceIds?: string[];
  createdAt?: string;
  updatedAt?: string;
}

export interface MetricPoint {
  time: string;
  value: number;
}

export interface MetricSeries {
  level?: string;
  field?: string;
  agg?: string;
  deviceId?: string;
  data: MetricPoint[];
}

export interface DashboardWidget {
  id: string;
  type: 'line-chart' | 'stat' | 'alert-list' | 'text';
  title: string;
  grid: {
    x: number;
    y: number;
    w: number;
    h: number;
  };
  datasource?: {
    metric?: string;
    device?: string;
    level?: string;
    agg?: string;
  };
  config?: Record<string, unknown>;
}

export interface DashboardLayout {
  variables?: Record<string, unknown>;
  widgets: DashboardWidget[];
}

export interface Dashboard {
  id?: number | string;
  name?: string;
  title?: string;
  description?: string;
  public?: boolean;
  published?: boolean;
  creatorId?: number | string;
  layout?: DashboardLayout | string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PermissionItem {
  id?: number | string;
  code: string;
  name?: string;
  type?: string;
  children?: PermissionItem[];
}

export interface Role {
  id?: number | string;
  name: string;
  code?: string;
  dataScope?: string;
  status?: string;
  description?: string;
  permissions?: string[];
}

export interface ManagedUser {
  id?: number | string;
  username: string;
  nickname?: string;
  tenantId?: number | string;
  status?: string;
  roles?: string[];
}

export interface Tenant {
  id?: number | string;
  name: string;
  code?: string;
  status?: string;
  contactName?: string;
  contactEmail?: string;
}
