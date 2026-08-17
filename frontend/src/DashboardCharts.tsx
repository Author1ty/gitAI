import { Area, Column, Pie } from '@ant-design/charts';

type Palette = {
  ai: string;
  human: string;
  mixed: string;
  unknown: string;
};

type TrendDatum = {
  date: string;
  value: number;
  category: string;
};

type DistributionDatum = {
  name: string;
  value: number;
};

type AgentDatum = {
  agent: string;
  aiLines: number;
};

export function AttributionTrendArea({ data, palette }: { data: TrendDatum[]; palette: Palette }) {
  return <Area
    data={data}
    xField="date"
    yField="value"
    colorField="category"
    stack={{ orderBy: 'series' }}
    height={310}
    scale={{ color: { range: [palette.ai, palette.human, palette.mixed, palette.unknown] } }}
    axis={{ x: { title: false }, y: { title: '\u4ee3\u7801\u884c\u6570' } }}
    legend={{ position: 'top' }}
    tooltip={{ title: 'date' }}
  />;
}

export function DepartmentAttributionPie({ data }: { data: DistributionDatum[] }) {
  return <Pie
    data={data}
    angleField="value"
    colorField="name"
    innerRadius={0.63}
    height={270}
    legend={{ position: 'bottom' }}
    label={false}
  />;
}

export function ProjectAttributionColumn({ data, color }: { data: DistributionDatum[]; color: string }) {
  return <Column
    data={data}
    xField="name"
    yField="value"
    color={color}
    height={270}
    axis={{ x: { labelAutoRotate: false }, y: { title: 'AI \u4ee3\u7801\u884c\u6570' } }}
  />;
}

export function AgentContributionColumn({ data, color }: { data: AgentDatum[]; color: string }) {
  return <Column
    data={data}
    xField="agent"
    yField="aiLines"
    color={color}
    height={230}
    axis={{ y: { title: 'AI \u4ee3\u7801\u884c\u6570' }, x: { labelAutoRotate: false } }}
  />;
}
