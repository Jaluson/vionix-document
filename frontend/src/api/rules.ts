import { del, get, post, put } from '@/api/http';
import type { PageQuery, PageResult, Rule } from '@/types/common';

export interface RuleQuery extends PageQuery {
  keyword?: string;
  enabled?: boolean | '';
  scope?: string;
}

export function listRules(params: RuleQuery = {}) {
  return get<PageResult<Rule>>('/rules', { params });
}

export function getRule(id: string | number) {
  return get<Rule>(`/rules/${id}`);
}

export function createRule(payload: Rule) {
  return post<Rule>('/rules', payload);
}

export function updateRule(id: string | number, payload: Rule) {
  return put<Rule>(`/rules/${id}`, payload);
}

export function deleteRule(id: string | number) {
  return del<void>(`/rules/${id}`);
}

export function toggleRule(id: string | number, enabled: boolean) {
  return put<Rule>(`/rules/${id}/toggle`, { enabled });
}
