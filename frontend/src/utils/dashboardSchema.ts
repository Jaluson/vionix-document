import type { DashboardLayout, DashboardWidget } from '@/types/common';

const allowedWidgetTypes = new Set(['line-chart', 'stat', 'alert-list', 'text']);

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function validateWidget(value: unknown, index: number): DashboardWidget {
  if (!isRecord(value)) throw new Error(`组件 ${index + 1} 必须是对象`);
  const id = String(value.id || `w_${index + 1}`);
  const type = String(value.type || '');
  const title = String(value.title || '未命名组件');
  if (!allowedWidgetTypes.has(type)) {
    throw new Error(`组件 ${title} 使用了不支持的类型：${type}`);
  }
  if (!isRecord(value.grid)) {
    throw new Error(`组件 ${title} 缺少 grid 配置`);
  }
  const grid = value.grid;
  const widget: DashboardWidget = {
    id,
    type: type as DashboardWidget['type'],
    title,
    grid: {
      x: Number(grid.x ?? 0),
      y: Number(grid.y ?? index * 3),
      w: Number(grid.w ?? 6),
      h: Number(grid.h ?? 3)
    },
    datasource: isRecord(value.datasource)
      ? {
          metric: value.datasource.metric ? String(value.datasource.metric) : undefined,
          device: value.datasource.device ? String(value.datasource.device) : undefined,
          level: value.datasource.level ? String(value.datasource.level) : undefined,
          agg: value.datasource.agg ? String(value.datasource.agg) : undefined
        }
      : undefined,
    config: isRecord(value.config) ? value.config : undefined
  };

  if (widget.grid.w < 1 || widget.grid.h < 1) {
    throw new Error(`组件 ${title} 的宽高必须大于 0`);
  }

  return widget;
}

export function normalizeDashboardLayout(input?: DashboardLayout | string | null): DashboardLayout {
  if (!input) return { widgets: [] };
  const parsed = typeof input === 'string' ? (JSON.parse(input) as unknown) : input;
  if (!isRecord(parsed)) throw new Error('仪表盘布局必须是 JSON 对象');
  const rawWidgets = Array.isArray(parsed.widgets) ? parsed.widgets : [];
  return {
    variables: isRecord(parsed.variables) ? parsed.variables : {},
    widgets: rawWidgets.map(validateWidget)
  };
}

export function defaultDashboardLayout(): DashboardLayout {
  return {
    variables: {
      device: '$device',
      timeRange: '24h'
    },
    widgets: [
      {
        id: 'temperature_line',
        type: 'line-chart',
        title: '温度趋势',
        grid: { x: 0, y: 0, w: 8, h: 3 },
        datasource: {
          metric: 'temperature',
          device: '$device',
          level: 'min',
          agg: 'mean'
        },
        config: {
          maxPoints: 120
        }
      },
      {
        id: 'alert_list',
        type: 'alert-list',
        title: '当前告警',
        grid: { x: 8, y: 0, w: 4, h: 3 }
      }
    ]
  };
}
