import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import type { DataNode } from 'antd/es/tree';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import {
  ApartmentOutlined,
  DashboardOutlined,
  DeleteOutlined,
  CloudSyncOutlined,
  BranchesOutlined,
  CodeOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  FolderOpenOutlined,
  GithubOutlined,
  PlusOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  SyncOutlined,
  TeamOutlined,
  UserOutlined,
  UserSwitchOutlined,
  LogoutOutlined,
  MenuOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  ConfigProvider,
  DatePicker,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Progress,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Tree,
  TreeSelect,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import './App.css';

const { Header, Content, Sider } = Layout;
const { RangePicker } = DatePicker;
const { Title, Text } = Typography;

const AttributionTrendArea = lazy(() => import('./DashboardCharts').then(({ AttributionTrendArea: component }) => ({ default: component })));
const DepartmentAttributionPie = lazy(() => import('./DashboardCharts').then(({ DepartmentAttributionPie: component }) => ({ default: component })));
const ProjectAttributionColumn = lazy(() => import('./DashboardCharts').then(({ ProjectAttributionColumn: component }) => ({ default: component })));
const AgentContributionColumn = lazy(() => import('./DashboardCharts').then(({ AgentContributionColumn: component }) => ({ default: component })));

function ChartLoading({ height }: { height: number }) {
  return <div className="chart-loading" style={{ minHeight: height }}><Spin description={'\u6b63\u5728\u52a0\u8f7d\u56fe\u8868'} /></div>;
}

type Option = { id: number; name: string; parentId: number | null; type: string };
type FilterOptions = { departments: Option[]; projects: Option[]; groups: Option[]; repositories: Option[] };
type Summary = { aiLines: number; humanLines: number; mixedLines: number; unknownLines: number; commits: number; repositories: number };
type TrendPoint = { date: string; aiLines: number; humanLines: number; mixedLines: number; unknownLines: number };
type Distribution = { id: string; name: string; aiLines: number; humanLines: number; mixedLines: number; unknownLines: number };
type RepositoryMetric = {
  repositoryId: number; name: string; projectName: string; groupName: string | null;
  aiLines: number; humanLines: number; mixedLines: number; unknownLines: number; syncedAt: string;
  syncStatus: string; historyComplete: boolean; historyOffset: number; syncError: string | null;
};
type AgentMetric = { agent: string; model: string; aiLines: number; sessions: number };
type AttributionValues = { aiLines: number; humanLines: number; mixedLines: number; unknownLines: number };
type ScopeMetric = AttributionValues & { id: string; name: string; parentName: string; repositories: number; commits: number };
type PersonMetric = AttributionValues & { author: string; repositories: number; commits: number };
type PersonRankings = { byAiLines: PersonMetric[]; byAiRate: PersonMetric[] };
type GroupNode = { id: number; name: string; type: string; children: GroupNode[] };
type Dashboard = {
  summary: Summary; trend: TrendPoint[]; departments: Distribution[]; projects: Distribution[];
  repositories: RepositoryMetric[]; agents: AgentMetric[]; projectPanorama: ScopeMetric[]; groupPanorama: ScopeMetric[];
  personRankings: PersonRankings; hierarchy: GroupNode[];
};
type DashboardParams = { departmentId?: number; projectId?: number; repositoryId?: number; groupId?: number; from?: string; to?: string };
type SyncJob = {
  id: number; repositoryId: number; repositoryName: string; status: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED';
  phase: string; phaseUpdatedAt: string | null; requestedAt: string | null; startedAt: string | null; finishedAt: string | null;
  processedCommits: number; batchCommitCount: number; historyComplete: boolean | null; historyOffset: number;
  message: string | null; error: string | null; reused: boolean;
};
type SyncJobBatch = { jobs: SyncJob[] };
type SyncQueueStatus = { workerCount: number; runningJobs: number; queuedJobs: number };
type SyncSchedule = { enabled: boolean; intervalMinutes: number; lastTriggeredAt: string | null; nextRunAt: string | null; due: boolean };

type AuthUser = { id: number; username: string; displayName: string; role: 'SUPER_ADMIN' | 'DEPARTMENT_ADMIN' | 'VIEWER'; departmentId: number | null };
type AuthSession = { token: string; user: AuthUser };
type LoginResponse = { token: string; expiresAt: string; user: AuthUser };
type OperationsOverview = { totalUsers: number; enabledUsers: number; repositories: number };
type UserAccount = { id: number; username: string; displayName: string; role: AuthUser['role']; departmentId: number | null; departmentName: string | null; enabled: boolean; createdAt: string; updatedAt: string };
type AuditLog = { id: number; username: string | null; departmentId: number | null; action: string; targetType: string | null; targetId: string | null; detail: string | null; createdAt: string };
type AppSection = 'overview' | 'repositories' | 'catalog' | 'operations';

const authStorageKey = 'git-ai-insight-session';
function getStoredSession(): AuthSession | null {
  try { const raw = window.localStorage.getItem(authStorageKey); return raw ? JSON.parse(raw) as AuthSession : null; } catch { return null; }
}
function getAuthHeaders(): Record<string, string> {
  const session = getStoredSession();
  return session?.token ? { Authorization: `Bearer ${session.token}` } : {};
}
function handleUnauthorized(response: Response) {
  if (response.status === 401) {
    window.localStorage.removeItem(authStorageKey);
    window.dispatchEvent(new Event('git-ai-auth-required'));
  }
}

const palette = { ai: '#6750e8', human: '#18a874', mixed: '#f1a03b', unknown: '#91a0b8', navy: '#17213a' };

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: getAuthHeaders() });
  handleUnauthorized(response);
  if (!response.ok) throw new Error(`\u8bf7\u6c42\u5931\u8d25 (${response.status})`);
  return response.json() as Promise<T>;
}

async function postJson<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { ...getAuthHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  handleUnauthorized(response);
  if (!response.ok) { const detail = await response.text(); throw new Error(detail || `\u8bf7\u6c42\u5931\u8d25 (${response.status})`); }
  return response.json() as Promise<T>;
}

async function deleteJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { method: 'DELETE', headers: getAuthHeaders() });
  handleUnauthorized(response);
  if (!response.ok) { const detail = await response.text(); throw new Error(detail || '\u8bf7\u6c42\u5931\u8d25 (' + response.status + ')'); }
  return response.json() as Promise<T>;
}

async function putJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'PUT', headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  handleUnauthorized(response);
  if (!response.ok) { const detail = await response.text(); throw new Error(detail || `\u8bf7\u6c42\u5931\u8d25 (${response.status})`); }
  return response.json() as Promise<T>;
}

function queryString(params: DashboardParams) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  });
  return search.toString();
}

function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN').format(value); }
function attributionTotal(value: AttributionValues) { return value.aiLines + value.humanLines + value.mixedLines + value.unknownLines; }
function attributionRate(value: AttributionValues) { const total = attributionTotal(value); return total ? Math.round((value.aiLines / total) * 100) : 0; }

function nodeIcon(type: string) {
  if (type === 'department') return <TeamOutlined />;
  if (type === 'project') return <DeploymentUnitOutlined />;
  if (type === 'group') return <FolderOpenOutlined />;
  return <GithubOutlined />;
}

function toTreeNodes(nodes: GroupNode[]): DataNode[] {
  return nodes.map((node) => ({ key: `${node.type}-${node.id}`, icon: nodeIcon(node.type), title: node.name, children: toTreeNodes(node.children) }));
}

type ScopeTreeNode = { key: string; value: string; title: string; children?: ScopeTreeNode[] };

const scopeTypeLabels: Record<string, string> = {
  department: '\u90e8\u95e8',
  project: '\u9879\u76ee',
  group: '\u4ed3\u5e93\u5206\u7ec4',
  repository: '\u4ed3\u5e93',
};

function toScopeTreeData(nodes: GroupNode[]): ScopeTreeNode[] {
  return nodes.map((node) => ({
    key: `${node.type}-${node.id}`,
    value: `${node.type}-${node.id}`,
    title: `${scopeTypeLabels[node.type] ?? '\u8282\u70b9'}\uFF1A${node.name}`,
    children: node.children?.length ? toScopeTreeData(node.children) : undefined,
  }));
}

function filterHierarchy(nodes: GroupNode[], keyword: string): GroupNode[] {
  const normalized = keyword.trim().toLocaleLowerCase('zh-CN');
  if (!normalized) return nodes;
  return nodes.flatMap((node) => {
    const children = filterHierarchy(node.children ?? [], keyword);
    return node.name.toLocaleLowerCase('zh-CN').includes(normalized) || children.length ? [{ ...node, children }] : [];
  });
}

function collectExpandableTreeKeys(nodes: GroupNode[]): React.Key[] {
  return nodes.flatMap((node) => node.children?.length
    ? [`${node.type}-${node.id}`, ...collectExpandableTreeKeys(node.children)]
    : []);
}

function findHierarchyPath(nodes: GroupNode[], type: string, id: number, ancestors: GroupNode[] = []): GroupNode[] | null {
  for (const node of nodes) {
    const path = [...ancestors, node];
    if (node.type === type && node.id === id) return path;
    const nested = findHierarchyPath(node.children ?? [], type, id, path);
    if (nested) return nested;
  }
  return null;
}

function findRepositoryIdsUnderGroup(nodes: GroupNode[], groupId: number): Set<number> {
  const result = new Set<number>();
  const visit = (node: GroupNode, insideGroup: boolean) => {
    const nowInside = insideGroup || (node.type === 'group' && node.id === groupId);
    if (nowInside && node.type === 'repository') result.add(node.id);
    (node.children ?? []).forEach((child) => visit(child, nowInside));
  };
  nodes.forEach((node) => visit(node, false));
  return result;
}

function StatCard({ title, value, icon, color, caption }: { title: string; value: number; icon: React.ReactNode; color: string; caption: string }) {
  return <Card className="stat-card" variant="borderless">
    <div className="stat-card__top"><span className="stat-card__icon" style={{ color, background: `${color}18` }}>{icon}</span><Text type="secondary">{title}</Text></div>
    <Statistic value={value} formatter={(number) => formatNumber(Number(number))} styles={{ content: { color: palette.navy, fontWeight: 700 } }} />
    <Text className="stat-card__caption">{caption}</Text>
  </Card>;
}

function CatalogDrawer({ filters, canCreateDepartment }: { filters?: FilterOptions; canCreateDepartment: boolean }) {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [departmentForm] = Form.useForm();
  const [projectForm] = Form.useForm();
  const [groupForm] = Form.useForm();
  const [repositoryForm] = Form.useForm();
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();

  async function create(path: string, form: ReturnType<typeof Form.useForm>[0]) {
    try {
      const values = await form.validateFields();
      setSaving(true);
      await postJson<{ id: number }>(path, values);
      form.resetFields();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['filters'] }),
        queryClient.invalidateQueries({ queryKey: ['hierarchy'] }),
      ]);
      message.success('\u5df2\u4fdd\u5b58\uff0c\u53ef\u7ee7\u7eed\u6dfb\u52a0\u4e0b\u4e00\u7ea7\u6570\u636e');
    } catch (error) {
      if (error instanceof Error && error.message) message.error(error.message);
    } finally { setSaving(false); }
  }

  async function removeRepository(repositoryId: number, repositoryName: string) {
    try {
      setDeletingId(repositoryId);
      await deleteJson<{ id: number }>('/api/catalog/repositories/' + repositoryId);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['filters'] }),
        queryClient.invalidateQueries({ queryKey: ['hierarchy'] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['sync-jobs'] }),
      ]);
      message.success('\u5df2\u5220\u9664\u6570\u636e\u6e90\u201c' + repositoryName + '\u201d\uff0c\u76f8\u5173\u5f52\u56e0\u7edf\u8ba1\u4e0e\u540c\u6b65\u4efb\u52a1\u5df2\u6e05\u7406');
    } catch (error) {
      if (error instanceof Error && error.message) message.error(error.message);
    } finally { setDeletingId(null); }
  }

  const departments = filters?.departments ?? [];
  const projects = filters?.projects ?? [];
  const groups = filters?.groups ?? [];
  const repositories = filters?.repositories ?? [];
  const departmentById = new Map(departments.map((item) => [item.id, item.name]));
  const projectById = new Map(projects.map((item) => [item.id, item]));
  const repositoryFormProjectId = Form.useWatch('projectId', repositoryForm) as number | undefined;
  const projectOption = (item: Option) => ({ value: item.id, label: `${departmentById.get(item.parentId ?? -1) ?? '\u672a\u5206\u914d'} / ${item.name}` });
  const groupOption = (item: Option) => ({ value: item.id, label: `${projectById.get(item.parentId ?? -1)?.name ?? '\u672a\u5206\u914d'} / ${item.name}` });
  const repositoryFormGroupOptions = groups.filter((item) => !repositoryFormProjectId || item.parentId === repositoryFormProjectId).map(groupOption);
  return <>
    <Button icon={<SettingOutlined />} onClick={() => setOpen(true)}>{'\u7ba1\u7406\u6570\u636e\u6e90'}</Button>
    <Drawer title={'\u90e8\u95e8\u3001\u9879\u76ee\u4e0e\u4ed3\u5e93\u7ba1\u7406'} open={open} size={520} onClose={() => setOpen(false)} destroyOnHidden>
      <Alert type="info" showIcon title={'\u5148\u5efa\u7acb\u7ec4\u7ec7\u5c42\u7ea7\uff0c\u518d\u540c\u6b65\u4ee3\u7801'} description={'\u63a8\u8350\u987a\u5e8f\uff1a\u90e8\u95e8 \u2192 \u9879\u76ee \u2192\uff08\u53ef\u9009\uff09\u4ed3\u5e93\u5206\u7ec4 \u2192 \u4ed3\u5e93\u3002\u65b0\u589e\u4ed3\u5e93\u540e\uff0c\u8bf7\u524d\u5f80\u201c\u4ed3\u5e93\u4e0e\u540c\u6b65\u201d\u53d1\u8d77\u521d\u59cb\u540c\u6b65\u3002'} />
      <Tabs className="catalog-tabs" items={[
        { key: 'department', label: '\u90e8\u95e8', children: <Form form={departmentForm} layout="vertical" onFinish={() => create('/api/catalog/departments', departmentForm)}>
          <Form.Item name="name" label={'\u90e8\u95e8\u540d\u79f0'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u90e8\u95e8\u540d\u79f0' }]}><Input placeholder={'\u4f8b\u5982\uff1a\u5e73\u53f0\u7814\u53d1\u90e8'} /></Form.Item>
          <Form.Item name="description" label={'\u8bf4\u660e\uff08\u53ef\u9009\uff09'}><Input.TextArea rows={2} placeholder={'\u90e8\u95e8\u804c\u8d23\u6216\u8bf4\u660e'} /></Form.Item>
          <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={saving}>{'\u65b0\u589e\u90e8\u95e8'}</Button>
        </Form> },
        { key: 'project', label: '\u9879\u76ee', children: <Form form={projectForm} layout="vertical" onFinish={() => create('/api/catalog/projects', projectForm)}>
          <Form.Item name="departmentId" label={'\u6240\u5c5e\u90e8\u95e8'} rules={[{ required: true, message: '\u8bf7\u9009\u62e9\u90e8\u95e8' }]}><Select showSearch optionFilterProp="label" placeholder={'\u9009\u62e9\u90e8\u95e8'} options={departments.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item>
          <Form.Item name="name" label={'\u9879\u76ee\u540d\u79f0'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u9879\u76ee\u540d\u79f0' }]}><Input placeholder={'\u4f8b\u5982\uff1a\u7edf\u4e00\u95e8\u6237'} /></Form.Item>
          <Form.Item name="description" label={'\u8bf4\u660e\uff08\u53ef\u9009\uff09'}><Input.TextArea rows={2} /></Form.Item>
          <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={saving}>{'\u65b0\u589e\u9879\u76ee'}</Button>
        </Form> },
        { key: 'group', label: '\u4ed3\u5e93\u5206\u7ec4', children: <Form form={groupForm} layout="vertical" onFinish={() => create('/api/catalog/groups', groupForm)}>
          <Form.Item name="projectId" label={'\u6240\u5c5e\u9879\u76ee'} rules={[{ required: true, message: '\u8bf7\u9009\u62e9\u9879\u76ee' }]}><Select showSearch optionFilterProp="label" placeholder={'\u9009\u62e9\u9879\u76ee'} options={projects.map(projectOption)} /></Form.Item>
          <Form.Item name="name" label={'\u5206\u7ec4\u540d\u79f0'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u5206\u7ec4\u540d\u79f0' }]}><Input placeholder={'\u4f8b\u5982\uff1a\u540e\u7aef\u670d\u52a1'} /></Form.Item>
          <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={saving}>{'\u65b0\u589e\u4ed3\u5e93\u5206\u7ec4'}</Button>
        </Form> },
        { key: 'repository', label: '\u4ed3\u5e93', children: <>
          <Form form={repositoryForm} layout="vertical" onFinish={() => create('/api/catalog/repositories', repositoryForm)}>
            <Form.Item name="projectId" label={'\u6240\u5c5e\u9879\u76ee'} rules={[{ required: true, message: '\u8bf7\u9009\u62e9\u9879\u76ee' }]}><Select showSearch optionFilterProp="label" placeholder={'\u9009\u62e9\u9879\u76ee'} options={projects.map(projectOption)} /></Form.Item>
          <Form.Item name="groupId" label={'\u6240\u5c5e\u4ed3\u5e93\u5206\u7ec4'}><Select allowClear placeholder={'\u53ef\u9009\u4ed3\u5e93\u5206\u7ec4'} showSearch optionFilterProp="label" options={repositoryFormGroupOptions} /></Form.Item>
            <Form.Item name="name" label={'\u6570\u636e\u6e90\u540d\u79f0'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u6570\u636e\u6e90\u540d\u79f0' }]}><Input placeholder={'\u4f8b\u5982\uff1apayment-api'} /></Form.Item>
            <Form.Item name="gitUrl" label="Git URL" rules={[{ required: true, message: 'Git URL \u4e0d\u80fd\u4e3a\u7a7a' }]}><Input placeholder="ssh://git.example.com/team/payment-api.git" /></Form.Item>
            <Form.Item name="defaultBranch" label={'\u9ed8\u8ba4\u5206\u652f'} initialValue="main"><Input placeholder="main" /></Form.Item>
            <Form.Item name="mirrorPath" label={'Git mirror \u76ee\u5f55\uff08\u53ef\u9009\uff09'}><Input placeholder={'\u7559\u7a7a\u5219\u4f7f\u7528\u7cfb\u7edf\u9ed8\u8ba4\u76ee\u5f55'} /></Form.Item>
            <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={saving}>{'\u65b0\u589e\u6570\u636e\u6e90'}</Button>
          </Form>
          <div className="catalog-repository-list">
            <Text strong>{'\u5df2\u914d\u7f6e\u7684\u6570\u636e\u6e90'}</Text>
            <Text type="secondary">{'\u5220\u9664\u6570\u636e\u6e90\u4f1a\u540c\u65f6\u6e05\u7406\u5f52\u56e0\u7edf\u8ba1\u548c\u540c\u6b65\u4efb\u52a1\uff0cGit mirror \u76ee\u5f55\u4f1a\u4fdd\u7559'}</Text>
            {repositories.length ? <div className="catalog-repository-items">
              {repositories.map((repository) => <div className="catalog-repository-item" key={repository.id}>
                <div className="catalog-repository-name"><GithubOutlined /><span><b>{repository.name}</b><small>{projectById.get(repository.parentId ?? -1)?.name ?? '\u672a\u5206\u914d\u9879\u76ee'}</small></span></div>
                <Popconfirm
                  title={'\u786e\u5b9a\u5220\u9664\u8be5\u6570\u636e\u6e90\uff1f'}
                  description={'\u5220\u9664\u540e\u5f52\u56e0\u7edf\u8ba1\u548c\u540c\u6b65\u4efb\u52a1\u5c06\u88ab\u6e05\u7406\uff0c\u4e14\u4e0d\u53ef\u64a4\u9500\u3002'}
                  okText={'\u5220\u9664'}
                  cancelText={'\u53d6\u6d88'}
                  okButtonProps={{ danger: true, loading: deletingId === repository.id }}
                  onConfirm={() => removeRepository(repository.id, repository.name)}
                >
                  <Button danger type="text" size="small" icon={<DeleteOutlined />} loading={deletingId === repository.id}>{'\u5220\u9664'}</Button>
                </Popconfirm>
              </div>)}
            </div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u6682\u65e0\u5df2\u914d\u7f6e\u6570\u636e\u6e90'} />}
          </div>
        </> },
      ].filter((item) => canCreateDepartment || item.key !== 'department')} />
    </Drawer>
  </>;
}
function roleLabel(role: AuthUser['role']) {
  return role === 'SUPER_ADMIN' ? '\u6700\u9ad8\u6743\u9650' : role === 'DEPARTMENT_ADMIN' ? '\u90e8\u95e8\u6743\u9650' : '\u67e5\u770b\u6743\u9650';
}

function LoginPage({ onLoggedIn }: { onLoggedIn: (session: AuthSession) => void }) {
  const [submitting, setSubmitting] = useState(false);
  const { message } = AntApp.useApp();
  const [form] = Form.useForm();
  async function submit() {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      const response = await fetch('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(values) });
      if (!response.ok) throw new Error((await response.text()) || '\u767b\u5f55\u5931\u8d25');
      const login = await response.json() as LoginResponse;
      const session = { token: login.token, user: login.user };
      window.localStorage.setItem(authStorageKey, JSON.stringify(session));
      onLoggedIn(session);
    } catch (error) {
      if (error instanceof Error) message.error(error.message);
    } finally { setSubmitting(false); }
  }
  return <div className="login-page"><Card className="login-card" variant="borderless">
    <div className="login-brand"><span className="brand-mark"><RobotOutlined /></span><div><Title level={2}>Git AI Insight</Title><Text type="secondary">{'\u4ee3\u7801\u5f52\u56e0\u4e0e\u8fd0\u8425\u7ba1\u7406\u5e73\u53f0'}</Text></div></div>
    <Alert type="info" showIcon title={'\u4e09\u65b9\u8ba4\u8bc1\u5360\u4f4d\u6a21\u5f0f'} description={'\u5f53\u524d\u53ea\u8f6c\u53d1\u7528\u6237\u540d\u548c\u5bc6\u7801\u7ed9\u8ba4\u8bc1\u9002\u914d\u5668\uff0c\u7cfb\u7edf\u4e0d\u4fdd\u5b58\u5bc6\u7801\u3002\u672c\u5730\u6f14\u793a\u53ef\u4f7f\u7528 superadmin / deptadmin / viewer \u4e0e\u4efb\u610f\u975e\u7a7a\u5bc6\u7801\u3002'} />
    <Form form={form} layout="vertical" onFinish={submit} className="login-form">
      <Form.Item name="username" label={'\u7528\u6237\u540d'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u7528\u6237\u540d' }]}><Input autoFocus placeholder="superadmin" /></Form.Item>
      <Form.Item name="password" label={'\u5bc6\u7801'} rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u5bc6\u7801' }]}><Input.Password placeholder={'\u7531\u4e09\u65b9\u8ba4\u8bc1\u63a5\u53e3\u6821\u9a8c'} /></Form.Item>
      <Button type="primary" htmlType="submit" block size="large" loading={submitting}>{'\u767b\u5f55\u8fdb\u5165\u770b\u677f'}</Button>
    </Form>
  </Card></div>;
}

function OperationsDrawer({ filters }: { filters?: FilterOptions }) {
  const [open, setOpen] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [form] = Form.useForm();
  const { message } = AntApp.useApp();
  const queryClient = useQueryClient();
  const overviewQuery = useQuery({ queryKey: ['operations-overview'], queryFn: () => getJson<OperationsOverview>('/api/operations/overview'), enabled: open });
  const usersQuery = useQuery({ queryKey: ['operations-users'], queryFn: () => getJson<UserAccount[]>('/api/operations/users'), enabled: open });
  const auditQuery = useQuery({ queryKey: ['operations-audit'], queryFn: () => getJson<AuditLog[]>('/api/operations/audit-logs?limit=100'), enabled: open });
  const departments = filters?.departments ?? [];
  async function saveUser() {
    try {
      const values = await form.validateFields();
      const path = editingId === null ? '/api/operations/users' : `/api/operations/users/${editingId}`;
      if (editingId === null) await postJson<UserAccount>(path, values); else await putJson<UserAccount>(path, values);
      message.success(editingId === null ? '\u5e10\u53f7\u5df2\u521b\u5efa' : '\u8d26\u53f7\u6743\u9650\u5df2\u4fdd\u5b58');
      setEditingId(null); form.resetFields();
      await Promise.all(['operations-users', 'operations-overview', 'operations-audit'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })));
    } catch (error) { if (error instanceof Error) message.error(error.message); }
  }
  const userColumns: ColumnsType<UserAccount> = [
    { title: '\u8d26\u53f7', dataIndex: 'username', key: 'username', render: (value, record) => <div><b>{value}</b><div className="table-subtitle">{record.displayName}</div></div> },
    { title: '\u89d2\u8272', dataIndex: 'role', key: 'role', render: (value: AuthUser['role']) => <Tag color={value === 'SUPER_ADMIN' ? 'purple' : value === 'DEPARTMENT_ADMIN' ? 'blue' : 'default'}>{roleLabel(value)}</Tag> },
    { title: '\u90e8\u95e8', dataIndex: 'departmentName', key: 'departmentName', render: (value: string | null) => value || '\u5168\u5c40' },
    { title: '\u72b6\u6001', dataIndex: 'enabled', key: 'enabled', render: (value: boolean) => <Tag color={value ? 'success' : 'default'}>{value ? '\u5df2\u542f\u7528' : '\u5df2\u505c\u7528'}</Tag> },
    { title: '\u64cd\u4f5c', key: 'action', render: (_, record) => <Button type="link" onClick={() => { setEditingId(record.id); form.setFieldsValue(record); }}>{'\u7f16\u8f91'}</Button> },
  ];
  const auditColumns: ColumnsType<AuditLog> = [
    { title: '\u65f6\u95f4', dataIndex: 'createdAt', key: 'createdAt', width: 165, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') },
    { title: '\u7528\u6237', dataIndex: 'username', key: 'username', width: 130, render: (value: string | null) => value || '\u7cfb\u7edf' },
    { title: '\u64cd\u4f5c', dataIndex: 'action', key: 'action', width: 190 },
    { title: '\u5bf9\u8c61', key: 'target', render: (_, record) => record.targetType ? `${record.targetType}${record.targetId ? ` #${record.targetId}` : ''}` : '\u2014' },
    { title: '\u8be6\u60c5', dataIndex: 'detail', key: 'detail' },
  ];
  return <><Button icon={<UserSwitchOutlined />} onClick={() => setOpen(true)}>{'\u8fd0\u8425\u7ba1\u7406'}</Button>
    <Drawer title={'\u8fd0\u8425\u7ba1\u7406'} open={open} size={940} onClose={() => setOpen(false)} destroyOnHidden>
      <Alert type="info" showIcon title={'\u8d26\u53f7\u3001\u6743\u9650\u4e0e\u5ba1\u8ba1'} description={'\u5bc6\u7801\u4e0d\u4f1a\u5199\u5165\u672c\u7cfb\u7edf\uff1b\u89d2\u8272\u548c\u90e8\u95e8\u8303\u56f4\u5728\u670d\u52a1\u7aef\u5f3a\u5236\u6821\u9a8c\u3002\u4ed3\u5e93\u3001\u540c\u6b65\u4efb\u52a1\u4e0e\u81ea\u52a8\u540c\u6b65\u8bbe\u7f6e\u5747\u96c6\u4e2d\u5728\u201c\u4ed3\u5e93\u4e0e\u540c\u6b65\u201d\u7ba1\u7406\u3002'} />
      <Row gutter={[12, 12]} className="operations-stats">
        <Col span={8}><Statistic title={'\u8d26\u53f7\u603b\u6570'} value={overviewQuery.data?.totalUsers ?? 0} /></Col><Col span={8}><Statistic title={'\u542f\u7528\u8d26\u53f7'} value={overviewQuery.data?.enabledUsers ?? 0} /></Col><Col span={8}><Statistic title={'\u5df2\u7eb3\u7ba1\u4ed3\u5e93'} value={overviewQuery.data?.repositories ?? 0} /></Col>
      </Row>
      <Tabs items={[
        { key: 'accounts', label: '\u8d26\u53f7\u4e0e\u6388\u6743', children: <><Card size="small" className="operations-form" title={editingId === null ? '\u65b0\u5efa\u8d26\u53f7' : '\u7f16\u8f91\u8d26\u53f7'} extra={editingId !== null ? <Button type="link" onClick={() => { setEditingId(null); form.resetFields(); }}>{'\u53d6\u6d88\u7f16\u8f91'}</Button> : null}><Form form={form} layout="inline" onFinish={saveUser} initialValues={{ role: 'VIEWER', enabled: true }}><Form.Item name="username" rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u8d26\u53f7' }]}><Input placeholder={'\u8d26\u53f7'} /></Form.Item><Form.Item name="displayName" rules={[{ required: true, message: '\u8bf7\u8f93\u5165\u663e\u793a\u540d' }]}><Input placeholder={'\u663e\u793a\u540d'} /></Form.Item><Form.Item name="role"><Select style={{ width: 140 }} options={[{ value: 'SUPER_ADMIN', label: '\u6700\u9ad8\u6743\u9650' }, { value: 'DEPARTMENT_ADMIN', label: '\u90e8\u95e8\u6743\u9650' }, { value: 'VIEWER', label: '\u67e5\u770b\u6743\u9650' }]} /></Form.Item><Form.Item name="departmentId"><Select allowClear placeholder={'\u6240\u5c5e\u90e8\u95e8'} style={{ width: 150 }} options={departments.map((item) => ({ value: item.id, label: item.name }))} /></Form.Item><Form.Item name="enabled" valuePropName="checked"><Switch checkedChildren={'\u542f\u7528'} unCheckedChildren={'\u505c\u7528'} /></Form.Item><Button type="primary" htmlType="submit">{'\u4fdd\u5b58'}</Button></Form></Card><Table columns={userColumns} dataSource={usersQuery.data ?? []} rowKey="id" loading={usersQuery.isLoading} size="small" pagination={false} scroll={{ x: 820 }} /></> },
        { key: 'audit', label: '\u64cd\u4f5c\u5ba1\u8ba1', children: <Table columns={auditColumns} dataSource={auditQuery.data ?? []} rowKey="id" loading={auditQuery.isLoading} size="small" pagination={{ pageSize: 10 }} scroll={{ x: 800 }} /> },
      ]} />
    </Drawer>
  </>;
}

function DashboardPage({ session, onLogout }: { session: AuthSession; onLogout: () => void }) {
  // Default to the complete imported history. A rolling 30-day default makes older repositories appear empty immediately after sync.
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [departmentId, setDepartmentId] = useState<number>();
  const [projectId, setProjectId] = useState<number>();
  const [groupId, setGroupId] = useState<number>();
  const [repositoryId, setRepositoryId] = useState<number>();
  const [activeSection, setActiveSection] = useState<AppSection>('overview');
  const [mobileNavigationOpen, setMobileNavigationOpen] = useState(false);
  const [treeKeyword, setTreeKeyword] = useState('');
  const [catalogSelectedTreeKey, setCatalogSelectedTreeKey] = useState<string>();
  const [catalogExpandedKeys, setCatalogExpandedKeys] = useState<React.Key[]>([]);
  const queryClient = useQueryClient();
  const { message } = AntApp.useApp();
  const canManage = session.user.role !== 'VIEWER';
  const isSuperAdmin = session.user.role === 'SUPER_ADMIN';

  const filtersQuery = useQuery({ queryKey: ['filters'], queryFn: () => getJson<FilterOptions>('/api/filters') });
  // Keep the organization tree independent from the selected dashboard scope. Otherwise,
  // selecting a department shrinks the tree to that department and hides its siblings.
  const hierarchyQuery = useQuery({ queryKey: ['hierarchy'], queryFn: () => getJson<GroupNode[]>('/api/hierarchy') });
  // The hierarchy states keep the visible breadcrumb, but the API must receive only
  // the node the user actually selected. Sending all ancestor IDs together makes a
  // group/project selection look like an invalid intersection to the backend.
  const selectedScopeParams = useMemo<Pick<DashboardParams, 'departmentId' | 'projectId' | 'groupId' | 'repositoryId'>>(() => {
    if (repositoryId !== undefined) return { repositoryId };
    if (groupId !== undefined) return { groupId };
    if (projectId !== undefined) return { projectId };
    if (departmentId !== undefined) return { departmentId };
    return {};
  }, [departmentId, projectId, groupId, repositoryId]);
  const params = useMemo<DashboardParams>(() => ({
    ...selectedScopeParams,
    ...(range ? { from: range[0].format('YYYY-MM-DD'), to: range[1].format('YYYY-MM-DD') } : {}),
  }), [selectedScopeParams, range]);
  const dashboardQuery = useQuery({ queryKey: ['dashboard', params], queryFn: () => getJson<Dashboard>(`/api/dashboard?${queryString(params)}`) });
  const [jobsOpen, setJobsOpen] = useState(false);
  const [scheduleOpen, setScheduleOpen] = useState(false);
  const [scheduleEnabled, setScheduleEnabled] = useState(false);
  const [scheduleInterval, setScheduleInterval] = useState(60);
  const [waitingForSync, setWaitingForSync] = useState(false);
  const syncScheduleQuery = useQuery({
    queryKey: ['sync-schedule'],
    queryFn: () => getJson<SyncSchedule>('/api/sync-schedule'),
    refetchInterval: scheduleOpen ? 30_000 : false,
  });
  const syncJobsQuery = useQuery({
    queryKey: ['sync-jobs'],
    queryFn: () => getJson<SyncJob[]>('/api/sync-jobs?limit=30'),
    refetchInterval: waitingForSync || syncScheduleQuery.data?.enabled ? 5000 : false,
  });
  const syncQueueStatusQuery = useQuery({
    queryKey: ['sync-queue-status'],
    queryFn: () => getJson<SyncQueueStatus>('/api/sync-jobs/status'),
    refetchInterval: waitingForSync || syncScheduleQuery.data?.enabled ? 5000 : false,
  });
  const activeJobs = (syncJobsQuery.data ?? []).filter((job) => job.status === 'QUEUED' || job.status === 'RUNNING');
  const syncMutation = useMutation({
    mutationFn: () => postJson<SyncJobBatch>('/api/sync-jobs', selectedScopeParams),
    onSuccess: async (result) => {
      setWaitingForSync(result.jobs.some((job) => job.status === 'QUEUED' || job.status === 'RUNNING'));
      setJobsOpen(true);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sync-jobs'] }),
        queryClient.invalidateQueries({ queryKey: ['sync-queue-status'] }),
      ]);
      const reused = result.jobs.filter((job) => job.reused).length;
      const scope = repositoryId ? '\u5f53\u524d\u4ed3\u5e93' : `${result.jobs.length} \u4e2a\u4ed3\u5e93`;
      message.success(reused ? `${scope} \u5df2\u6709\u540c\u6b65\u4efb\u52a1\uff0c\u5df2\u7ee7\u7eed\u8ddf\u8e2a\u3002` : `${scope} \u5df2\u52a0\u5165\u540c\u6b65\u961f\u5217\u3002`);
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '\u521b\u5efa\u540c\u6b65\u4efb\u52a1\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u540e\u7aef\u65e5\u5fd7\u3002'),
  });
  const cancelJobMutation = useMutation({
    mutationFn: (jobId: number) => postJson<SyncJob>(`/api/sync-jobs/${jobId}/cancel`),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['sync-jobs'] }),
        queryClient.invalidateQueries({ queryKey: ['sync-queue-status'] }),
      ]);
      message.success('\u5df2\u53d6\u6d88\u5c1a\u672a\u5f00\u59cb\u7684\u540c\u6b65\u4efb\u52a1\u3002');
    },
    onError: (error) => message.warning(error instanceof Error ? error.message : '\u4efb\u52a1\u65e0\u6cd5\u53d6\u6d88\u3002'),
  });

  const scheduleMutation = useMutation({
    mutationFn: (payload: { enabled: boolean; intervalMinutes: number }) => putJson<SyncSchedule>('/api/sync-schedule', payload),
    onSuccess: async (result) => {
      setScheduleEnabled(result.enabled);
      setScheduleInterval(result.intervalMinutes);
      await queryClient.invalidateQueries({ queryKey: ['sync-schedule'] });
      message.success(result.enabled ? `\u5df2\u542f\u7528\u81ea\u52a8\u540c\u6b65\uff1a\u6bcf ${result.intervalMinutes} \u5206\u949f\u68c0\u67e5\u4e00\u6b21\u3002` : '\u5df2\u5173\u95ed\u81ea\u52a8\u540c\u6b65\u3002');
    },
    onError: (error) => message.error(error instanceof Error ? error.message : '\u4fdd\u5b58\u81ea\u52a8\u540c\u6b65\u8bbe\u7f6e\u5931\u8d25\u3002'),
  });

  useEffect(() => {
    if (!syncScheduleQuery.data) return;
    setScheduleEnabled(syncScheduleQuery.data.enabled);
    setScheduleInterval(syncScheduleQuery.data.intervalMinutes);
  }, [syncScheduleQuery.data]);

  useEffect(() => {
    if (waitingForSync && syncJobsQuery.isSuccess && activeJobs.length === 0) {
      setWaitingForSync(false);
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({ queryKey: ['filters'] }),
      ]);
    }
  }, [activeJobs.length, queryClient, syncJobsQuery.isSuccess, waitingForSync]);

  const filters = filtersQuery.data;
  const dashboard = dashboardQuery.data;
  const hierarchy = hierarchyQuery.data ?? [];
  // In a single-department deployment the department is selected by default. In a multi-department
  // deployment the default remains the organization-wide view so users can compare departments first.
  const defaultDepartmentId = filters?.departments.length === 1 ? filters.departments[0].id : undefined;

  useEffect(() => {
    if (defaultDepartmentId !== undefined && departmentId === undefined) setDepartmentId(defaultDepartmentId);
  }, [defaultDepartmentId, departmentId]);

  const departmentById = new Map((filters?.departments ?? []).map((item) => [item.id, item.name]));
  const projectById = new Map((filters?.projects ?? []).map((item) => [item.id, item]));
  const selectedDepartmentProjectIds = new Set(
    (filters?.projects ?? []).filter((item) => !departmentId || item.parentId === departmentId).map((item) => item.id),
  );
  const projects = (filters?.projects ?? []).filter((item) => !departmentId || item.parentId === departmentId);
  const groups = (filters?.groups ?? []).filter((item) => !projectId
    ? !departmentId || selectedDepartmentProjectIds.has(item.parentId ?? -1)
    : item.parentId === projectId);
  const repositoriesInSelectedGroup = groupId === undefined ? null : findRepositoryIdsUnderGroup(hierarchy, groupId);
  const repositories = (filters?.repositories ?? []).filter((item) => {
    const matchesProject = !projectId
      ? !departmentId || selectedDepartmentProjectIds.has(item.parentId ?? -1)
      : item.parentId === projectId;
    return matchesProject && (repositoriesInSelectedGroup === null || repositoriesInSelectedGroup.has(item.id));
  });

  const totalLines = dashboard ? dashboard.summary.aiLines + dashboard.summary.humanLines + dashboard.summary.mixedLines + dashboard.summary.unknownLines : 0;
  const aiRate = totalLines ? Math.round((dashboard!.summary.aiLines / totalLines) * 100) : 0;
  const trendData = dashboard?.trend.flatMap((item) => [
    { date: item.date, category: '\u4eba\u5de5\u786e\u8ba4', value: item.humanLines },
    { date: item.date, category: 'AI \u5f52\u56e0', value: item.aiLines },
    { date: item.date, category: '\u6df7\u5408\u5f52\u56e0', value: item.mixedLines },
    { date: item.date, category: '\u672a\u77e5 / \u672a\u8ffd\u8e2a', value: item.unknownLines },
  ]) ?? [];
  const departmentData = dashboard?.departments.map((item) => ({ name: item.name, value: item.aiLines })) ?? [];
  const projectData = dashboard?.projects.map((item) => ({ name: item.name, value: item.aiLines })) ?? [];
  const agentData = dashboard?.agents.map((item) => ({ agent: item.agent, aiLines: item.aiLines })) ?? [];
  const projectPanorama = dashboard?.projectPanorama ?? [];
  const groupPanorama = dashboard?.groupPanorama ?? [];
  const personRankings = dashboard?.personRankings ?? { byAiLines: [], byAiRate: [] };
  const visibleHierarchy = filterHierarchy(hierarchy, treeKeyword);
  const catalogSearchExpandedKeys = useMemo(() => collectExpandableTreeKeys(visibleHierarchy), [visibleHierarchy]);
  const displayedCatalogExpandedKeys = treeKeyword.trim() ? catalogSearchExpandedKeys : catalogExpandedKeys;
  const selectedDepartment = (filters?.departments ?? []).find((item) => item.id === departmentId)?.name;
  const selectedProject = (filters?.projects ?? []).find((item) => item.id === projectId)?.name;
  const selectedGroup = (filters?.groups ?? []).find((item) => item.id === groupId)?.name;
  const selectedRepository = (filters?.repositories ?? []).find((item) => item.id === repositoryId)?.name;
  const selectedPath = repositoryId
    ? findHierarchyPath(hierarchy, 'repository', repositoryId)
    : groupId
      ? findHierarchyPath(hierarchy, 'group', groupId)
      : projectId
        ? findHierarchyPath(hierarchy, 'project', projectId)
        : departmentId
          ? findHierarchyPath(hierarchy, 'department', departmentId)
          : null;
  const scopePath = selectedPath?.map((node) => node.name).join(' / ')
    || [selectedDepartment, selectedProject, selectedGroup, selectedRepository].filter(Boolean).join(' / ')
    || '\u5168\u90e8\u7ec4\u7ec7';
  const selectedTreeKey = selectedPath ? `${selectedPath.at(-1)!.type}-${selectedPath.at(-1)!.id}` : undefined;
  const activeSectionMeta: Record<AppSection, { title: string; description: string }> = {
    overview: { title: '\u4ee3\u7801\u5f52\u56e0\u603b\u89c8', description: '\u4ece\u7ec4\u7ec7\u3001\u9879\u76ee\u3001\u4ed3\u5e93\u4e0e\u63d0\u4ea4\u8005\u89c6\u89d2\u89c2\u5bdf AI \u4ee3\u7801\u8d21\u732e' },
    repositories: { title: '\u4ed3\u5e93\u4e0e\u540c\u6b65', description: '\u5b9a\u4f4d\u6570\u636e\u6e90\u3001\u89c2\u5bdf\u540c\u6b65\u8fdb\u5ea6\u5e76\u53d1\u8d77\u5b89\u5168\u540c\u6b65' },
    catalog: { title: '\u7ec4\u7ec7\u4e0e\u6570\u636e\u6e90', description: '\u7ef4\u62a4\u90e8\u95e8\u3001\u9879\u76ee\u3001\u4ed3\u5e93\u5206\u7ec4\u4e0e\u4ee3\u7801\u4ed3\u5c42\u7ea7' },
    operations: { title: '\u8fd0\u8425\u7ba1\u7406', description: '\u96c6\u4e2d\u7ba1\u7406\u8d26\u53f7\u6743\u9650\u4e0e\u8fd0\u8425\u5ba1\u8ba1\u8bb0\u5f55' },
  };
  const navigationItems = [
    { key: 'overview', icon: <DashboardOutlined />, label: '\u6982\u89c8\u770b\u677f' },
    { key: 'repositories', icon: <CloudSyncOutlined />, label: '\u4ed3\u5e93\u4e0e\u540c\u6b65' },
    ...(canManage ? [{ key: 'catalog', icon: <DatabaseOutlined />, label: '\u7ec4\u7ec7\u4e0e\u6570\u636e\u6e90' }] : []),
    ...(isSuperAdmin ? [{ key: 'operations', icon: <SafetyCertificateOutlined />, label: '\u8fd0\u8425\u7ba1\u7406' }] : []),
  ];
  function hierarchyPathFromKey(key: React.Key | undefined) {
    const [type, rawId] = String(key ?? '').split('-');
    const id = Number(rawId);
    if (!Number.isInteger(id)) return null;
    return findHierarchyPath(hierarchy, type, id);
  }

  function selectHierarchyNode(selectedKeys: React.Key[]) {
    const path = hierarchyPathFromKey(selectedKeys[0]);
    if (!path) return;
    const department = path.find((node) => node.type === 'department');
    const project = path.find((node) => node.type === 'project');
    const group = path.find((node) => node.type === 'group');
    const repository = path.find((node) => node.type === 'repository');
    setDepartmentId(department?.id);
    setProjectId(project?.id);
    setGroupId(group?.id ?? undefined);
    setRepositoryId(repository?.id);
  }

  function selectCatalogHierarchyNode(selectedKeys: React.Key[]) {
    const key = selectedKeys[0];
    if (!key) {
      setCatalogSelectedTreeKey(undefined);
      return;
    }
    if (!hierarchyPathFromKey(key)) return;
    setCatalogSelectedTreeKey(String(key));
  }

  function openCatalogSelectionInRepositories() {
    if (!catalogSelectedTreeKey) return;
    selectHierarchyNode([catalogSelectedTreeKey]);
    setActiveSection('repositories');
  }

  function selectDepartment(value: number | undefined) {
    setDepartmentId(value ?? undefined);
    setProjectId(undefined);
    setGroupId(undefined);
    setRepositoryId(undefined);
  }

  function selectProject(value: number | undefined) {
    if (value === undefined) {
      setProjectId(undefined);
      setGroupId(undefined);
      setRepositoryId(undefined);
      return;
    }
    const project = projectById.get(value);
    setDepartmentId(project?.parentId ?? undefined);
    setProjectId(value);
    setGroupId(undefined);
    setRepositoryId(undefined);
  }

  function selectGroup(value: number | undefined) {
    if (value === undefined) {
      setGroupId(undefined);
      setRepositoryId(undefined);
      return;
    }
    const projectIdForGroup = filters?.groups.find((item) => item.id === value)?.parentId;
    const project = projectById.get(projectIdForGroup ?? -1);
    setDepartmentId(project?.parentId ?? undefined);
    setProjectId(projectIdForGroup ?? undefined);
    setGroupId(value);
    setRepositoryId(undefined);
  }

  function selectRepository(value: number | undefined) {
    if (value === undefined) {
      setRepositoryId(undefined);
      return;
    }
    const path = findHierarchyPath(hierarchy, 'repository', value);
    const project = path?.find((node) => node.type === 'project');
    const group = path?.find((node) => node.type === 'group');
    setDepartmentId(path?.find((node) => node.type === 'department')?.id ?? undefined);
    setProjectId(project?.id ?? filters?.repositories.find((item) => item.id === value)?.parentId ?? undefined);
    setGroupId(group?.id ?? undefined);
    setRepositoryId(value);
  }

  function resetScope() {
    setRange(null);
    setDepartmentId(defaultDepartmentId);
    setProjectId(undefined);
    setGroupId(undefined);
    setRepositoryId(undefined);
  }
  const catalogSelectedPath = hierarchyPathFromKey(catalogSelectedTreeKey);
  const catalogSelectedScopePath = catalogSelectedPath?.map((node) => node.name).join(' / ');
  const hasScopeChanges = Boolean(range || projectId || groupId || repositoryId || departmentId !== defaultDepartmentId);

  const repositoryColumns: ColumnsType<RepositoryMetric> = [
    { title: '\u4ed3\u5e93', dataIndex: 'name', key: 'name', width: 250, render: (name: string, record) => <div><Space size={6}><GithubOutlined className="purple-icon" /><b>{name}</b></Space><div className="table-subtitle">{record.projectName}{record.groupName ? ` / ${record.groupName}` : ''}</div></div> },
    { title: 'AI \u5360\u6bd4', key: 'rate', width: 165, render: (_, record) => { const sum = record.aiLines + record.humanLines + record.mixedLines + record.unknownLines; const value = sum ? Math.round(record.aiLines / sum * 100) : 0; return <Progress percent={value} size="small" strokeColor={palette.ai} />; } },
    { title: 'AI \u884c', dataIndex: 'aiLines', key: 'aiLines', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u4eba\u5de5\u786e\u8ba4', dataIndex: 'humanLines', key: 'humanLines', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u540c\u6b65\u72b6\u6001', key: 'syncStatus', width: 220, render: (_, record) => record.syncStatus === 'FAILED' ? <Tooltip title={record.syncError || '\u540c\u6b65\u5931\u8d25\uff0c\u8bf7\u6253\u5f00\u540c\u6b65\u4efb\u52a1\u67e5\u770b\u8be6\u60c5'}><Tag color="error">{'\u540c\u6b65\u5931\u8d25'}</Tag></Tooltip> : record.syncStatus === 'SYNCING' ? <Tag color="processing" icon={<SyncOutlined spin />}>{'\u540c\u6b65\u4e2d\uff0c\u5df2\u56de\u6eaf'} {formatNumber(record.historyOffset)} {'\u6761'}</Tag> : !record.syncedAt && (!record.syncStatus || record.syncStatus === 'NOT_SYNCED') ? <Tag>{'\u672a\u540c\u6b65'}</Tag> : !record.historyComplete ? <Tag color="processing" icon={<SyncOutlined spin />}>{'\u5386\u53f2\u540c\u6b65\u4e2d\uff0c\u5df2\u56de\u6eaf'} {formatNumber(record.historyOffset)} {'\u6761'}</Tag> : record.syncedAt ? <Tag color="success" icon={<SyncOutlined />}>{'\u5df2\u540c\u6b65'}</Tag> : <Tag>{'\u672a\u540c\u6b65'}</Tag> },
  ];

  const scopeColumns: ColumnsType<ScopeMetric> = [
    { title: '\u540d\u79f0', dataIndex: 'name', key: 'name', width: 220, render: (name: string, record) => <div><Space size={6}><DeploymentUnitOutlined className="purple-icon" /><b>{name}</b></Space><div className="table-subtitle">{record.parentName}</div></div> },
    { title: 'AI \u5360\u6bd4', key: 'rate', width: 160, render: (_, record) => <Progress percent={attributionRate(record)} size="small" strokeColor={palette.ai} /> },
    { title: 'AI \u884c', dataIndex: 'aiLines', key: 'aiLines', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u4eba\u5de5\u786e\u8ba4', dataIndex: 'humanLines', key: 'humanLines', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u4ed3\u5e93\u6570', dataIndex: 'repositories', key: 'repositories', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u63d0\u4ea4\u6570', dataIndex: 'commits', key: 'commits', align: 'right', render: (value: number) => formatNumber(value) },
  ];

  const personColumns: ColumnsType<PersonMetric> = [
    { title: '\u6392\u540d', key: 'rank', width: 66, render: (_, __, index) => <Tag color={index < 3 ? 'purple' : 'default'}>#{index + 1}</Tag> },
    { title: '\u63d0\u4ea4\u8005', dataIndex: 'author', key: 'author', width: 180, render: (author: string) => <Space size={6}><UserOutlined className="purple-icon" /><b>{author}</b></Space> },
    { title: 'AI \u884c', dataIndex: 'aiLines', key: 'aiLines', align: 'right', render: (value: number) => formatNumber(value) },
    { title: 'AI \u5360\u6bd4', key: 'rate', width: 150, render: (_, record) => <Progress percent={attributionRate(record)} size="small" strokeColor={palette.ai} /> },
    { title: '\u63d0\u4ea4\u6570', dataIndex: 'commits', key: 'commits', align: 'right', render: (value: number) => formatNumber(value) },
    { title: '\u4ed3\u5e93\u6570', dataIndex: 'repositories', key: 'repositories', align: 'right', render: (value: number) => formatNumber(value) },
  ];

  const syncPhaseConfig: Record<string, [string, string]> = {
    QUEUED: ['warning', '\u7b49\u5f85 worker'],
    PREPARING_MIRROR: ['processing', '\u51c6\u5907 mirror'],
    READING_COMMITS: ['processing', '\u8bfb\u53d6\u63d0\u4ea4'],
    READING_ATTRIBUTION: ['processing', '\u89e3\u6790 Git AI'],
    WRITING_STATS: ['processing', '\u5199\u5165\u7edf\u8ba1'],
    FINALIZING: ['processing', '\u4fdd\u5b58\u8fdb\u5ea6'],
    COMPLETED: ['success', '\u5df2\u5b8c\u6210'],
    FAILED: ['error', '\u5931\u8d25'],
    CANCELLED: ['default', '\u5df2\u53d6\u6d88'],
  };

  function effectiveSyncPhase(job: SyncJob) {
    if (job.status === 'SUCCESS') return 'COMPLETED';
    if (job.status === 'FAILED') return 'FAILED';
    if (job.status === 'CANCELLED') return 'CANCELLED';
    return job.phase;
  }

  function renderBatchProgress(job: SyncJob) {
    if (job.batchCommitCount > 0) return <Text>{formatNumber(job.processedCommits)} / {formatNumber(job.batchCommitCount)}</Text>;
    if (job.status === 'SUCCESS') return <Text type="secondary">{'\u672c\u6279\u5df2\u5b8c\u6210'}</Text>;
    if (job.status === 'FAILED') return <Text type="secondary">{'\u672c\u6279\u672a\u5b8c\u6210'}</Text>;
    return <Text type="secondary">{'\u5f85\u83b7\u53d6'}</Text>;
  }

  const syncJobColumns: ColumnsType<SyncJob> = [
    { title: '\u4ed3\u5e93', dataIndex: 'repositoryName', key: 'repositoryName', render: (value: string) => <Space size={6}><GithubOutlined className="purple-icon" />{value}</Space> },
    { title: '\u72b6\u6001', dataIndex: 'status', key: 'status', width: 100, render: (status: SyncJob['status']) => {
      const config = status === 'SUCCESS' ? ['success', '\u5b8c\u6210'] : status === 'FAILED' ? ['error', '\u5931\u8d25'] : status === 'CANCELLED' ? ['default', '\u5df2\u53d6\u6d88'] : status === 'RUNNING' ? ['processing', '\u540c\u6b65\u4e2d'] : ['warning', '\u6392\u961f\u4e2d'];
      return <Tag color={config[0]} icon={status === 'RUNNING' ? <SyncOutlined spin /> : undefined}>{config[1]}</Tag>;
    } },
    { title: '\u5f53\u524d\u9636\u6bb5', key: 'phase', width: 135, render: (_, job) => {
      const phase = effectiveSyncPhase(job);
      const config = syncPhaseConfig[phase] ?? ['default', phase || '\u2014'];
      return <Tag color={config[0]}>{config[1]}</Tag>;
    } },
    { title: '\u672c\u6279\u8fdb\u5ea6', key: 'processedCommits', align: 'right', width: 120, render: (_, job) => renderBatchProgress(job) },
    { title: '\u5386\u53f2\u8fdb\u5ea6', key: 'history', width: 155, render: (_, job) => job.historyComplete ? <Tag color="success">{'\u5386\u53f2\u5df2\u5b8c\u6210'}</Tag> : job.historyOffset ? <Tag color="processing">{'\u5df2\u56de\u6eaf'} {formatNumber(job.historyOffset)} {'\u6761'}</Tag> : <Text type="secondary">{'\u7b49\u5f85\u7ed3\u679c'}</Text> },
    { title: '\u8bf4\u660e', key: 'message', render: (_, job) => <div className="sync-job-message">{job.error || job.message || '\u2014'}<small>{job.finishedAt ? `\u5b8c\u6210\u4e8e ${dayjs(job.finishedAt).format('MM-DD HH:mm:ss')}` : job.phaseUpdatedAt ? `\u9636\u6bb5\u66f4\u65b0\u4e8e ${dayjs(job.phaseUpdatedAt).format('MM-DD HH:mm:ss')}` : job.startedAt ? `\u5f00\u59cb\u4e8e ${dayjs(job.startedAt).format('MM-DD HH:mm:ss')}` : job.requestedAt ? `\u63d0\u4ea4\u4e8e ${dayjs(job.requestedAt).format('MM-DD HH:mm:ss')}` : ''}</small></div> },
    { title: '\u64cd\u4f5c', key: 'action', width: 80, render: (_, job) => canManage && job.status === 'QUEUED' ? <Button type="link" size="small" loading={cancelJobMutation.isPending} onClick={() => cancelJobMutation.mutate(job.id)}>{'\u53d6\u6d88'}</Button> : null },
  ];

  function selectAppSection(key: AppSection) {
    setActiveSection(key);
    setMobileNavigationOpen(false);
  }

  return <>
    <Drawer
      className="mobile-navigation-drawer"
      title={<Space size={9}><span className="brand-mark brand-mark--compact"><RobotOutlined /></span><span>Git AI Insight</span></Space>}
      placement="left"
      open={mobileNavigationOpen}
      onClose={() => setMobileNavigationOpen(false)}
      destroyOnHidden
    >
      <div className="mobile-navigation-title">{'\u5de5\u4f5c\u53f0'}</div>
      <Menu className="app-navigation" mode="inline" selectedKeys={[activeSection]} items={navigationItems} onClick={({ key }) => selectAppSection(key as AppSection)} />
      <div className="sider-status mobile-navigation-status">
        <span className="sider-status__dot" />
        <div><b>{activeJobs.length ? '\u540c\u6b65\u4efb\u52a1\u5904\u7406\u4e2d' : '\u6570\u636e\u670d\u52a1\u6b63\u5e38'}</b><small>{activeJobs.length ? `${activeJobs.length} \u4e2a\u4efb\u52a1\u6b63\u5728\u961f\u5217\u4e2d` : '\u5f52\u56e0\u6570\u636e\u7531 Git AI Notes \u63d0\u4f9b'}</small></div>
      </div>
    </Drawer>
    <Drawer title={'\u81ea\u52a8\u540c\u6b65\u8bbe\u7f6e'} open={scheduleOpen} size={420} onClose={() => setScheduleOpen(false)} destroyOnHidden>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Alert type="info" showIcon title={'\u540e\u53f0\u5b9a\u65f6\u540c\u6b65'} description={'\u542f\u7528\u540e\uff0c\u670d\u52a1\u4f1a\u6309\u8bbe\u5b9a\u95f4\u9694\u4e3a\u5168\u90e8\u4ed3\u5e93\u521b\u5efa\u540c\u6b65\u4efb\u52a1\uff1b\u5df2\u6709\u6392\u961f\u6216\u8fd0\u884c\u4e2d\u7684\u4ed3\u5e93\u4efb\u52a1\u4f1a\u590d\u7528\uff0c\u4e0d\u4f1a\u91cd\u590d clone\u3002'} />
        <Card size="small" className="schedule-card">
          <div className="schedule-row">
            <div><Text strong>{'\u542f\u7528\u81ea\u52a8\u540c\u6b65'}</Text><small>{'\u9996\u6b21\u542f\u7528\u6216\u4fdd\u5b58\u8bbe\u7f6e\u540e\uff0c\u4f1a\u5728\u4e0b\u4e00\u6b21\u68c0\u67e5\uff08\u9ed8\u8ba4 1 \u5206\u949f\u5185\uff09\u89e6\u53d1\u3002'}</small></div>
            <Switch checked={scheduleEnabled} onChange={setScheduleEnabled} />
          </div>
          <div className="schedule-interval">
            <Text strong>{'\u6267\u884c\u95f4\u9694'}</Text>
            <InputNumber min={5} max={10_080} value={scheduleInterval} addonAfter={'\u5206\u949f'} style={{ width: 180 }} onChange={(value) => setScheduleInterval(typeof value === 'number' ? value : 60)} />
            <Text type="secondary">{'\u53ef\u8bbe\u7f6e 5 \u5206\u949f\u81f3 7 \u5929\u3002\u6bcf\u6b21\u53ea\u4f1a\u8fdb\u5165\u73b0\u6709\u7684\u53d7\u9650\u540c\u6b65\u961f\u5217\u3002'}</Text>
          </div>
          {syncScheduleQuery.data?.lastTriggeredAt
            ? <div className="schedule-time">{'\u4e0a\u6b21\u89e6\u53d1\uff1a'}{dayjs(syncScheduleQuery.data.lastTriggeredAt).format('YYYY-MM-DD HH:mm:ss')}<br />{'\u9884\u8ba1\u4e0b\u6b21\uff1a'}{syncScheduleQuery.data.nextRunAt ? dayjs(syncScheduleQuery.data.nextRunAt).format('YYYY-MM-DD HH:mm:ss') : '\u2014'}</div>
            : <div className="schedule-time">{'\u5c1a\u672a\u81ea\u52a8\u89e6\u53d1\uff1b\u4fdd\u5b58\u5e76\u542f\u7528\u540e\u5c06\u7531\u540e\u53f0\u8c03\u5ea6\u5668\u5f00\u59cb\u6267\u884c\u3002'}</div>}
          <Button type="primary" block loading={scheduleMutation.isPending} onClick={() => scheduleMutation.mutate({ enabled: scheduleEnabled, intervalMinutes: scheduleInterval })}>{'\u4fdd\u5b58\u81ea\u52a8\u540c\u6b65\u8bbe\u7f6e'}</Button>
        </Card>
      </Space>
    </Drawer>
    <Drawer title={'\u540c\u6b65\u4efb\u52a1'} open={jobsOpen} size={760} onClose={() => setJobsOpen(false)} destroyOnHidden>
      <Alert type="info" showIcon className="sync-job-alert"
        title={syncQueueStatusQuery.data
          ? `\u540c\u6b65\u5bb9\u91cf\uff1a${syncQueueStatusQuery.data.runningJobs} / ${syncQueueStatusQuery.data.workerCount} \u4e2a Git worker \u6b63\u5728\u8fd0\u884c\uff0c${syncQueueStatusQuery.data.queuedJobs} \u4e2a\u4ed3\u5e93\u6392\u961f`
          : '\u6bcf\u4e2a\u4ed3\u5e93\u6bcf\u6b21\u53ea\u4f1a\u8fd0\u884c\u4e00\u4e2a\u4efb\u52a1'}
        description={'\u4e3a\u4e86\u4fdd\u62a4\u591a\u5e74\u5386\u53f2\u548c\u8d85\u5927\u4ed3\u5e93\uff0c\u53ea\u6709\u771f\u6b63\u83b7\u5f97 Git worker \u7684\u4efb\u52a1\u624d\u4f1a\u663e\u793a\u4e3a\u201c\u540c\u6b65\u4e2d\u201d\u3002\u5176\u4ed6\u4efb\u52a1\u4fdd\u6301\u201c\u6392\u961f\u4e2d\u201d\u5e76\u53ef\u5b89\u5168\u53d6\u6d88\u3002'} />
      <Table className="sync-job-table" columns={syncJobColumns} dataSource={syncJobsQuery.data ?? []} rowKey="id" size="small" loading={syncJobsQuery.isLoading} pagination={{ pageSize: 8, hideOnSinglePage: true }} scroll={{ x: 920 }} locale={{ emptyText: '\u6682\u65f6\u6ca1\u6709\u540c\u6b65\u4efb\u52a1' }} />
    </Drawer>
    <Layout className="app-layout">
      <Sider className="sider" width={244} theme="light" breakpoint="lg" collapsedWidth={0}>
<div className="brand"><span className="brand-mark"><RobotOutlined /></span><div><b>Git AI Insight</b><small>{'\u4ee3\u7801\u5f52\u56e0\u4e0e\u8fd0\u8425\u5e73\u53f0'}</small></div></div>
        <div className="sider-section-title">{'\u5de5\u4f5c\u53f0'}</div>
        <Menu className="app-navigation" mode="inline" selectedKeys={[activeSection]} items={navigationItems} onClick={({ key }) => selectAppSection(key as AppSection)} />
        <div className="sider-status">
          <span className="sider-status__dot" />
          <div><b>{activeJobs.length ? '\u540c\u6b65\u4efb\u52a1\u5904\u7406\u4e2d' : '\u6570\u636e\u670d\u52a1\u6b63\u5e38'}</b><small>{activeJobs.length ? `${activeJobs.length} \u4e2a\u4efb\u52a1\u6b63\u5728\u961f\u5217\u4e2d` : '\u5f52\u56e0\u6570\u636e\u7531 Git AI Notes \u63d0\u4f9b'}</small></div>
        </div>
      </Sider>
      <Layout>
        <Header className="topbar">
          <div className="page-heading"><Button className="mobile-navigation-trigger" type="text" icon={<MenuOutlined />} aria-label={'\u6253\u5f00\u5de5\u4f5c\u53f0\u5bfc\u822a'} onClick={() => setMobileNavigationOpen(true)}>{'\u83dc\u5355'}</Button><div><Title level={3}>{activeSectionMeta[activeSection].title}</Title><Text type="secondary">{activeSectionMeta[activeSection].description}</Text></div></div>
          <Space className="topbar-actions" wrap size={10}>
            <Button icon={<UnorderedListOutlined />} onClick={() => setJobsOpen(true)}>{'\u540c\u6b65\u4efb\u52a1'}{activeJobs.length ? ` (${activeJobs.length})` : ''}</Button>
            {activeJobs.length ? <Tag color="processing" icon={<SyncOutlined />}>{activeJobs.some((job) => job.status === 'RUNNING') ? '\u6b63\u5728\u540c\u6b65' : '\u540c\u6b65\u6392\u961f\u4e2d'}</Tag> : <Tag color="success">{'\u6570\u636e\u5df2\u5c31\u7eea'}</Tag>}
            <div className="user-summary"><UserOutlined /><span>{session.user.displayName}</span><Tag color={isSuperAdmin ? 'purple' : canManage ? 'blue' : 'default'}>{roleLabel(session.user.role)}</Tag><Button type="text" size="small" icon={<LogoutOutlined />} onClick={() => { void fetch('/api/auth/logout', { method: 'POST', headers: getAuthHeaders() }); onLogout(); }}>{'\u9000\u51fa'}</Button></div>
          </Space>
        </Header>
        <Content className="content">
          {activeSection === 'overview' && <>
            <Card className="filter-card" variant="borderless">
              <div className="filter-card__content">
                <div className="filter-card__title"><span className="filter-label">{'\u7edf\u8ba1\u8303\u56f4'}</span><Text type="secondary">{'\u7edf\u4e00\u8c03\u6574\u672c\u9875\u5168\u90e8\u56fe\u8868\u3001\u6392\u540d\u548c\u660e\u7ec6'}</Text></div>
                <Space wrap size={[12, 12]}>
                  <RangePicker value={range} allowClear placeholder={['\u5168\u90e8\u5386\u53f2\u8d77\u70b9', '\u5168\u90e8\u5386\u53f2\u7ec8\u70b9']} onChange={(value) => setRange(value?.[0] && value[1] ? value as [Dayjs, Dayjs] : null)} />
                  <Select allowClear placeholder={'\u5168\u90e8\u90e8\u95e8'} value={departmentId} onChange={selectDepartment} showSearch optionFilterProp="label" options={(filters?.departments ?? []).map((item) => ({ value: item.id, label: item.name }))} style={{ width: 150 }} />
                  <Select allowClear placeholder={'\u5168\u90e8\u9879\u76ee'} value={projectId} onChange={selectProject} showSearch optionFilterProp="label" options={projects.map((item) => ({ value: item.id, label: `${departmentById.get(item.parentId ?? -1) ?? '\u672a\u5206\u914d'} / ${item.name}` }))} style={{ width: 165 }} />
                  <Select allowClear placeholder={'\u5168\u90e8\u4ed3\u5e93\u5206\u7ec4'} value={groupId} onChange={selectGroup} showSearch optionFilterProp="label" options={groups.map((item) => ({ value: item.id, label: `${projectById.get(item.parentId ?? -1)?.name ?? '\u672a\u5206\u914d'} / ${item.name}` }))} style={{ width: 165 }} />
                  <Select allowClear placeholder={'\u5168\u90e8\u4ed3\u5e93'} value={repositoryId} onChange={selectRepository} showSearch optionFilterProp="label" options={repositories.map((item) => ({ value: item.id, label: item.name }))} style={{ width: 190 }} />
                  <Button onClick={resetScope} disabled={!hasScopeChanges}>{'\u91cd\u7f6e'}</Button>
                </Space>
                <div className="scope-context"><Text type="secondary">{'\u5f53\u524d\u8303\u56f4'}</Text><b>{scopePath}</b></div>
              </div>
            </Card>
            {dashboardQuery.isError && <Alert className="load-error" type="error" showIcon title={'\u65e0\u6cd5\u52a0\u8f7d\u770b\u677f\u6570\u636e'} description={'\u8bf7\u786e\u8ba4 Spring Boot \u540e\u7aef\u5df2\u5728 8080 \u7aef\u53e3\u542f\u52a8'} />}
            {dashboardQuery.isLoading ? <div className="page-loading"><Spin size="large" description={'\u6b63\u5728\u6c47\u603b\u4ee3\u7801\u5f52\u56e0\u6570\u636e'} /></div> : dashboard && <>
              <Row gutter={[16, 16]} className="stat-grid">
                <Col xs={24} sm={12} xl={4}><StatCard title={'AI \u751f\u6210\u4ee3\u7801\u884c'} value={dashboard.summary.aiLines} color={palette.ai} icon={<RobotOutlined />} caption={`\u5360\u5f53\u524d\u7edf\u8ba1\u8303\u56f4 ${aiRate}%`} /></Col>
                <Col xs={24} sm={12} xl={4}><StatCard title={'\u4eba\u5de5\u786e\u8ba4\u4ee3\u7801\u884c'} value={dashboard.summary.humanLines} color={palette.human} icon={<UserOutlined />} caption={'\u660e\u786e\u7531\u4eba\u5de5\u63d0\u4ea4 / \u786e\u8ba4'} /></Col>
                <Col xs={24} sm={12} xl={4}><StatCard title={'\u6df7\u5408\u5f52\u56e0\u4ee3\u7801\u884c'} value={dashboard.summary.mixedLines} color={palette.mixed} icon={<ApartmentOutlined />} caption={'\u540c\u4e00\u884c\u540c\u65f6\u5305\u542b AI \u4e0e\u4eba\u5de5\u6765\u6e90'} /></Col>
                <Col xs={24} sm={12} xl={4}><StatCard title={'\u672a\u77e5 / \u672a\u8ffd\u8e2a\u4ee3\u7801\u884c'} value={dashboard.summary.unknownLines} color={palette.unknown} icon={<CodeOutlined />} caption={'\u6ca1\u6709\u76f4\u63a5\u8bc1\u636e\u5f52\u4e3a\u4eba\u5de5\u4fee\u6539'} /></Col>
                <Col xs={24} sm={12} xl={4}><StatCard title={'\u63d0\u4ea4\u6b21\u6570'} value={dashboard.summary.commits} color="#3e7dcd" icon={<BranchesOutlined />} caption={'\u5f53\u524d\u7b5b\u9009\u8303\u56f4\u5185'} /></Col>
                <Col xs={24} sm={12} xl={4}><StatCard title={'\u7eb3\u5165\u7edf\u8ba1\u4ed3\u5e93'} value={dashboard.summary.repositories} color="#c14d94" icon={<GithubOutlined />} caption={'\u5f53\u524d\u53ef\u89c1\u4e14\u53ef\u7ba1\u7406'} /></Col>
              </Row>
              <Row gutter={[16, 16]} className="dashboard-row">
                <Col xs={24} xl={16}><Card className="panel-card" title={<><span className="panel-title-icon"><SyncOutlined /></span>{'\u6bcf\u65e5\u5f52\u56e0\u8d8b\u52bf'}</>} extra={<Text type="secondary">{'\u6309\u63d0\u4ea4\u65e5\u671f\u7edf\u8ba1'}</Text>}><Suspense fallback={<ChartLoading height={310} />}><AttributionTrendArea data={trendData} palette={palette} /></Suspense></Card></Col>
                <Col xs={24} xl={8}><Card className="panel-card ai-rate-card" title={<><span className="panel-title-icon"><RobotOutlined /></span>{'AI \u4ee3\u7801\u5360\u6bd4'}</>}><div className="rate-ring"><Progress type="circle" percent={aiRate} strokeColor={palette.ai} railColor="#eeeefd" size={140} format={(percent) => <><b>{percent}%</b><small>{'AI \u4ee3\u7801'}</small></>} /></div><div className="legend-list"><span><i style={{ background: palette.ai }} />{'AI \u4ee3\u7801'} <b>{formatNumber(dashboard.summary.aiLines)}</b></span><span><i style={{ background: palette.human }} />{'\u4eba\u5de5\u786e\u8ba4'} <b>{formatNumber(dashboard.summary.humanLines)}</b></span><span><i style={{ background: palette.mixed }} />{'\u6df7\u5408\u5f52\u56e0'} <b>{formatNumber(dashboard.summary.mixedLines)}</b></span><span><i style={{ background: palette.unknown }} />{'\u672a\u77e5 / \u672a\u8ffd\u8e2a'} <b>{formatNumber(dashboard.summary.unknownLines)}</b></span></div></Card></Col>
                <Col xs={24} xl={12}><Card className="panel-card" title={<><span className="panel-title-icon"><TeamOutlined /></span>{'\u90e8\u95e8 AI \u5f52\u56e0\u5206\u5e03'}</>}>{departmentData.length ? <Suspense fallback={<ChartLoading height={270} />}><DepartmentAttributionPie data={departmentData} /></Suspense> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Card></Col>
                <Col xs={24} xl={12}><Card className="panel-card" title={<><span className="panel-title-icon"><DeploymentUnitOutlined /></span>{'\u9879\u76ee AI \u5f52\u56e0\u5bf9\u6bd4'}</>}>{projectData.length ? <Suspense fallback={<ChartLoading height={270} />}><ProjectAttributionColumn data={projectData} color={palette.ai} /></Suspense> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />}</Card></Col>
              </Row>
              <Row gutter={[16, 16]} className="dashboard-row">
                <Col xs={24} xl={16}><Card className="panel-card" title={<><span className="panel-title-icon"><GithubOutlined /></span>{'\u4ed3\u5e93\u5f52\u56e0\u660e\u7ec6'}</>} extra={<Text type="secondary">{'\u6309 AI \u5f52\u56e0\u884c\u6570\u6392\u5e8f'}</Text>}><Table columns={repositoryColumns} dataSource={dashboard.repositories} rowKey="repositoryId" size="middle" pagination={false} scroll={{ x: 800 }} /></Card></Col>
                <Col xs={24} xl={8}><Card className="panel-card" title={<><span className="panel-title-icon"><RobotOutlined /></span>{'AI \u5de5\u5177\u8d21\u732e'}</>}><Suspense fallback={<ChartLoading height={230} />}><AgentContributionColumn data={agentData} color={palette.mixed} /></Suspense><div className="agent-list">{dashboard.agents.map((item) => <div key={`${item.agent}-${item.model}`}><span><RobotOutlined /> {item.agent} <small>{item.model || 'unknown'}</small></span><b>{formatNumber(item.aiLines)} {'\u884c'}</b><Text type="secondary">{item.sessions} sessions</Text></div>)}</div></Card></Col>
              </Row>
          <Row gutter={[16, 16]} className="dashboard-row">
            <Col xs={24} xl={12}>
              <Card className="panel-card" title={<><span className="panel-title-icon"><UserOutlined /></span>{'\u4e2a\u4eba AI \u4ee3\u7801\u6392\u540d'}</>} extra={<Text type="secondary">{'\u6309 Git \u63d0\u4ea4\u8005\u6c47\u603b'}</Text>}>
                <Tabs items={[
                  { key: 'ai-lines', label: `AI \u751f\u6210\u4ee3\u7801\u884c (${personRankings.byAiLines.length})`, children: personRankings.byAiLines.length ? <Table columns={personColumns} dataSource={personRankings.byAiLines} rowKey="author" size="small" pagination={false} scroll={{ x: 680, y: 360 }} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u6682\u65e0\u5e26\u63d0\u4ea4\u8005\u7684\u5f52\u56e0\u6570\u636e'} /> },
                  { key: 'ai-rate', label: `AI \u4ee3\u7801\u5360\u6bd4 (${personRankings.byAiRate.length})`, children: personRankings.byAiRate.length ? <Table columns={personColumns} dataSource={personRankings.byAiRate} rowKey="author" size="small" pagination={false} scroll={{ x: 680, y: 360 }} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u6682\u65e0\u5e26\u63d0\u4ea4\u8005\u7684\u5f52\u56e0\u6570\u636e'} /> },
                ]} />
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card className="panel-card" title={<><span className="panel-title-icon"><FolderOpenOutlined /></span>{'\u9879\u76ee\u4e0e\u4ed3\u5e93\u5206\u7ec4\u5168\u666f'}</>} extra={<Text type="secondary">{'\u5f53\u524d\u7b5b\u9009\u8303\u56f4\u5185\u7684\u6c47\u603b'}</Text>}>
                <Tabs items={[
                  { key: 'project-panorama', label: `\u9879\u76ee (${projectPanorama.length})`, children: projectPanorama.length ? <Table columns={scopeColumns} dataSource={projectPanorama} rowKey="id" size="small" pagination={false} scroll={{ x: 760, y: 360 }} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u6682\u65e0\u9879\u76ee\u7edf\u8ba1\u6570\u636e'} /> },
                  { key: 'group-panorama', label: `\u4ed3\u5e93\u5206\u7ec4 (${groupPanorama.length})`, children: groupPanorama.length ? <Table columns={scopeColumns} dataSource={groupPanorama} rowKey={(item) => `${item.parentName}-${item.id}`} size="small" pagination={false} scroll={{ x: 760, y: 360 }} /> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u6682\u65e0\u4ed3\u5e93\u5206\u7ec4\u7edf\u8ba1\u6570\u636e'} /> },
                ]} />
              </Card>
            </Col>
          </Row>
          <Alert className="quality-note" type="info" showIcon title={'\u5f52\u56e0\u53e3\u5f84\u8bf4\u660e'} description={'AI \u4ee3\u7801\u884c\u6765\u81ea Git AI Note \u4e2d\u53ef\u786e\u8ba4\u7684 AI \u5f52\u56e0\uff1b\u672a\u77e5 / \u672a\u8ffd\u8e2a\u8868\u793a Git Note \u6ca1\u6709\u63d0\u4f9b\u8db3\u591f\u8bc1\u636e\uff0c\u4e0d\u80fd\u76f4\u63a5\u7b49\u540c\u4e8e\u4eba\u5de5\u4fee\u6539\uff1b\u6df7\u5408\u5f52\u56e0\u8868\u793a\u540c\u4e00\u63d0\u4ea4\u540c\u65f6\u5b58\u5728 AI \u4e0e\u4eba\u5de5\u6765\u6e90\u3002'} />
        </>}

          </>}

          {activeSection === 'repositories' && <>
            <Card className="workspace-card workspace-scope-card" title={<><span className="panel-title-icon"><ApartmentOutlined /></span>{'\u540c\u6b65\u8303\u56f4'}</>} extra={<Text type="secondary">{'\u90e8\u95e8 \u2192 \u9879\u76ee \u2192 \u4ed3\u5e93\u5206\u7ec4 \u2192 \u4ed3\u5e93'}</Text>}>
              <div className="scope-quick-select scope-quick-select--primary">
                <div className="scope-quick-select__copy"><Text strong>{'\u9009\u62e9\u7ec4\u7ec7\u8303\u56f4'}</Text><Text type="secondary">{'\u53ef\u9009\u62e9\u90e8\u95e8\u3001\u9879\u76ee\u3001\u4ed3\u5e93\u5206\u7ec4\u6216\u5355\u4e2a\u4ed3\u5e93'}</Text></div>
                <TreeSelect
                  allowClear
                  showSearch
                  treeDefaultExpandAll
                  treeNodeFilterProp="title"
                  value={selectedTreeKey}
                  treeData={toScopeTreeData(hierarchy)}
                  placeholder={'\u8bf7\u9009\u62e9\u90e8\u95e8 / \u9879\u76ee / \u4ed3\u5e93\u5206\u7ec4 / \u4ed3\u5e93'}
                  onChange={(value) => {
                    if (!value) { resetScope(); return; }
                    selectHierarchyNode([String(value)]);
                  }}
                  style={{ minWidth: 360, flex: 1 }}
                />
                <Button className="scope-reset-button" onClick={resetScope} disabled={!hasScopeChanges}>{'\u91cd\u7f6e\u8303\u56f4'}</Button>
              </div>
              <div className="scope-context workspace-scope"><Text type="secondary">{'\u5f53\u524d\u8303\u56f4\uff1a'}</Text><Tag color="purple">{scopePath}</Tag><Text type="secondary">{'\u9009\u62e9\u4e0a\u7ea7\u8282\u70b9\u4f1a\u5305\u542b\u5176\u4e0b\u5168\u90e8\u4ed3\u5e93\uff0c\u9009\u62e9\u5355\u4e2a\u4ed3\u5e93\u53ea\u4f1a\u64cd\u4f5c\u8be5\u4ed3\u5e93\u3002'}</Text></div>
            </Card>
            <Row gutter={[16, 16]} className="workspace-row">
              <Col xs={24}>
                <Card className="workspace-card" title={<><span className="panel-title-icon"><CloudSyncOutlined /></span>{'\u4ed3\u5e93\u540c\u6b65'}</>} extra={<Space wrap size={8}><Tag>{`${dashboard?.repositories.length ?? 0} \u4e2a\u4ed3\u5e93`}</Tag><Button icon={<UnorderedListOutlined />} onClick={() => setJobsOpen(true)}>{'\u540c\u6b65\u4efb\u52a1'}</Button>{isSuperAdmin && <Button icon={<SettingOutlined />} onClick={() => setScheduleOpen(true)}>{'\u81ea\u52a8\u540c\u6b65'}{syncScheduleQuery.data?.enabled ? ` / ${syncScheduleQuery.data.intervalMinutes} \u5206\u949f` : ''}</Button>}{canManage && <Button type="primary" icon={<SyncOutlined />} loading={syncMutation.isPending} onClick={() => syncMutation.mutate()}>{repositoryId ? '\u540c\u6b65\u5f53\u524d\u4ed3\u5e93' : '\u540c\u6b65\u5f53\u524d\u8303\u56f4'}</Button>}</Space>}>
                  <Alert className="sync-job-alert" type={activeJobs.length ? 'info' : 'success'} showIcon title={activeJobs.length ? `\u5f53\u524d\u6709 ${activeJobs.length} \u4e2a\u540c\u6b65\u4efb\u52a1\u6b63\u5728\u5904\u7406` : '\u5f53\u524d\u6ca1\u6709\u6b63\u5728\u5904\u7406\u7684\u540c\u6b65\u4efb\u52a1'} description={repositoryId ? `\u5f53\u524d\u9009\u62e9\u7684\u662f\u5355\u4e2a\u4ed3\u5e93\uff1a${scopePath}` : `\u540c\u6b65\u6309\u94ae\u5c06\u5904\u7406\u201c${scopePath}\u201d\u8303\u56f4\u5185\u7684\u5168\u90e8\u4ed3\u5e93`} />
                  <div className="scope-context workspace-scope"><Text type="secondary">{'\u4ed3\u5e93\u5217\u8868\u8303\u56f4\uff1a'}</Text><b>{scopePath}</b></div>
                  <Table className="repository-table" columns={repositoryColumns} dataSource={dashboard?.repositories ?? []} rowKey="repositoryId" size="middle" loading={dashboardQuery.isLoading} pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }} scroll={{ x: 900 }} locale={{ emptyText: '\u5f53\u524d\u8303\u56f4\u6ca1\u6709\u5df2\u914d\u7f6e\u7684\u4ed3\u5e93' }} />
                </Card>
              </Col>
            </Row>
          </>}

          {activeSection === 'catalog' && canManage && <Row gutter={[16, 16]} className="workspace-row">
            <Col xs={24} xl={12}>
               <Card className="workspace-card repo-navigator" title={<><span className="panel-title-icon"><DatabaseOutlined /></span>{'\u7ec4\u7ec7\u7ed3\u6784'}</>} extra={<Text type="secondary">{'\u90e8\u95e8 \u2192 \u9879\u76ee \u2192 \u5206\u7ec4 \u2192 \u4ed3\u5e93'}</Text>}>
                 <Input allowClear value={treeKeyword} onChange={(event) => setTreeKeyword(event.target.value)} placeholder={'\u641c\u7d22\u7ec4\u7ec7\u6216\u4ed3\u5e93'} />
                 {hierarchyQuery.isLoading ? <div className="tree-loading"><Spin /></div> : visibleHierarchy.length ? <><Tree className="repo-tree" showIcon showLine blockNode autoExpandParent={Boolean(treeKeyword.trim())} treeData={toTreeNodes(visibleHierarchy)} selectedKeys={catalogSelectedTreeKey ? [catalogSelectedTreeKey] : []} expandedKeys={displayedCatalogExpandedKeys} onExpand={(keys) => { if (!treeKeyword.trim()) setCatalogExpandedKeys(keys); }} onSelect={selectCatalogHierarchyNode} /><div className="catalog-tree-context">{catalogSelectedScopePath ? <><div><Text type="secondary">{'\u5f53\u524d\u9009\u4e2d\uff1a'}</Text><b>{catalogSelectedScopePath}</b></div><Text type="secondary">{'\u4ec5\u7528\u4e8e\u5b9a\u4f4d\u7ec4\u7ec7\u548c\u6570\u636e\u6e90\uff0c\u4e0d\u4f1a\u81ea\u52a8\u6539\u53d8\u770b\u677f\u7edf\u8ba1\u6216\u540c\u6b65\u8303\u56f4\u3002'}</Text><Button type="link" onClick={openCatalogSelectionInRepositories}>{'\u4ee5\u6b64\u8303\u56f4\u524d\u5f80\u4ed3\u5e93\u4e0e\u540c\u6b65'}</Button></> : <Text type="secondary">{'\u9009\u62e9\u7ec4\u7ec7\u6216\u4ed3\u5e93\u8282\u70b9\u53ef\u67e5\u770b\u5b9a\u4f4d\u4fe1\u606f\u3002'}</Text>}</div></> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={'\u672a\u627e\u5230\u5339\u914d\u7684\u7ec4\u7ec7\u6216\u4ed3\u5e93'} />}
              </Card>
            </Col>
            <Col xs={24} xl={12}>
               <Card className="workspace-card" title={<><span className="panel-title-icon"><SettingOutlined /></span>{'\u6570\u636e\u6e90\u7ef4\u62a4'}</>}>
                  <Alert type="info" showIcon title={'\u6309\u7ec4\u7ec7\u5c42\u7ea7\u7ef4\u62a4\u6570\u636e\u6e90'} description={'\u5148\u5efa\u7acb\u90e8\u95e8\u548c\u9879\u76ee\uff0c\u518d\u6309\u9700\u8981\u521b\u5efa\u4ed3\u5e93\u5206\u7ec4\u548c\u4ed3\u5e93\u3002\u4ed3\u5e93\u4fdd\u5b58\u540e\uff0c\u53ef\u5728\u201c\u4ed3\u5e93\u4e0e\u540c\u6b65\u201d\u4e2d\u53d1\u8d77\u521d\u59cb\u540c\u6b65\u3002'} />
                  <div className="management-actions"><CatalogDrawer filters={filters} canCreateDepartment={isSuperAdmin} /><Button icon={<CloudSyncOutlined />} onClick={() => setActiveSection('repositories')}>{'\u524d\u5f80\u4ed3\u5e93\u4e0e\u540c\u6b65'}</Button></div>
                  <div className="management-guide"><b>{'\u5f53\u524d\u6743\u9650\uff1a'}{roleLabel(session.user.role)}</b><Text type="secondary">{'\u90e8\u95e8\u7ba1\u7406\u5458\u53ea\u80fd\u5728\u6388\u6743\u90e8\u95e8\u8303\u56f4\u5185\u7ef4\u62a4\u9879\u76ee\u3001\u5206\u7ec4\u548c\u4ed3\u5e93\uff1b\u6700\u9ad8\u6743\u9650\u53ef\u521b\u5efa\u90e8\u95e8\u3002'}</Text></div>
              </Card>
            </Col>
          </Row>}

          {activeSection === 'operations' && isSuperAdmin && <Row gutter={[16, 16]} className="workspace-row">
            <Col xs={24}>
              <Card className="workspace-card operations-workspace-card" title={<><span className="panel-title-icon"><SafetyCertificateOutlined /></span>{'\u8d26\u53f7\u3001\u6743\u9650\u4e0e\u5ba1\u8ba1'}</>}>
                  <Alert type="info" showIcon title={'\u6309\u7ec4\u7ec7\u5c42\u7ea7\u7ef4\u62a4\u6570\u636e\u6e90'} description={'\u5148\u5efa\u7acb\u90e8\u95e8\u548c\u9879\u76ee\uff0c\u518d\u6309\u9700\u8981\u521b\u5efa\u4ed3\u5e93\u5206\u7ec4\u548c\u4ed3\u5e93\u3002\u4ed3\u5e93\u4fdd\u5b58\u540e\uff0c\u53ef\u5728\u201c\u4ed3\u5e93\u4e0e\u540c\u6b65\u201d\u4e2d\u53d1\u8d77\u521d\u59cb\u540c\u6b65\u3002'} />
                <div className="management-actions"><OperationsDrawer filters={filters} /></div>
                <div className="management-guide"><b>{'\u6743\u9650\u5206\u7ea7'}</b><Text type="secondary">{'\u6700\u9ad8\u6743\u9650\u53ef\u7ba1\u7406\u5168\u5c40\u914d\u7f6e\uff1b\u90e8\u95e8\u6743\u9650\u53ea\u80fd\u7ef4\u62a4\u6388\u6743\u90e8\u95e8\uff1b\u67e5\u770b\u6743\u9650\u53ea\u80fd\u6d4f\u89c8\u7edf\u8ba1\u4e0e\u540c\u6b65\u72b6\u6001\uff0c\u4e0d\u80fd\u521b\u5efa\u914d\u7f6e\u6216\u53d1\u8d77\u66f4\u65b0\u3002'}</Text></div>
              </Card>
            </Col>
          </Row>}
        </Content>
      </Layout>
    </Layout>
  </>;
}

export default function App() {
  const [session, setSession] = useState<AuthSession | null>(() => getStoredSession());
  useEffect(() => {
    const clear = () => setSession(null);
    window.addEventListener('git-ai-auth-required', clear);
    return () => window.removeEventListener('git-ai-auth-required', clear);
  }, []);
  function loggedIn(next: AuthSession) { setSession(next); }
  function loggedOut() { window.localStorage.removeItem(authStorageKey); setSession(null); }
  return <ConfigProvider theme={{ token: { colorPrimary: palette.ai, borderRadius: 12, fontFamily: 'Inter, "Microsoft YaHei", sans-serif' } }}><AntApp>{session ? <DashboardPage session={session} onLogout={loggedOut} /> : <LoginPage onLoggedIn={loggedIn} />}</AntApp></ConfigProvider>;
}






