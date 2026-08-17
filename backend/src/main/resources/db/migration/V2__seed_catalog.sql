INSERT INTO departments (id, name, description) VALUES
  (1, '客户产品部', '面向客户交付的核心产品线'),
  (2, '平台研发部', '基础平台、工程效率与公共能力');

INSERT INTO projects (id, department_id, name, description) VALUES
  (1, 1, 'ESOP Central', '股权与员工服务平台'),
  (2, 1, '客户门户', '面向客户的自助服务门户'),
  (3, 2, '研发效能平台', '内部工程效率与开发工具');

INSERT INTO repository_groups (id, project_id, name) VALUES
  (1, 1, '前端应用'),
  (2, 1, '服务端服务'),
  (3, 2, '门户应用'),
  (4, 3, '基础设施');

INSERT INTO repositories (id, project_id, group_id, name, git_url, default_branch) VALUES
  (1, 1, 1, 'esop-web', 'ssh://git.example.local/cloudcrm/esop-web.git', 'master'),
  (2, 1, 2, 'esop-api', 'ssh://git.example.local/cloudcrm/esop-api.git', 'master'),
  (3, 1, 1, 'esop-central-app-v2', 'ssh://git.example.local/cloudcrm/esop-central-app-v2.git', 'master'),
  (4, 2, 3, 'self-service-portal', 'ssh://git.example.local/cloudcrm/self-service-portal.git', 'main'),
  (5, 3, 4, 'git-ai-dashboard', 'ssh://git.example.local/platform/git-ai-dashboard.git', 'main'),
  (6, 3, NULL, 'shared-components', 'ssh://git.example.local/platform/shared-components.git', 'main');
