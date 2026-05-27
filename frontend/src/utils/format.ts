export function formatDateTime(value?: string | number | Date) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}

export function formatNumber(value?: number, digits = 2) {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return Number.isInteger(value) ? String(value) : value.toFixed(digits);
}

export function readJsonObject(text: string, fallback: Record<string, unknown> = {}) {
  if (!text.trim()) return fallback;
  const value = JSON.parse(text) as unknown;
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('JSON 必须是对象');
  }
  return value as Record<string, unknown>;
}

export function toRelativeStart(hours: number) {
  return new Date(Date.now() - hours * 60 * 60 * 1000).toISOString();
}
