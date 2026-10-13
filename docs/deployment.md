# DbAgent 部署文档（T8）

## 1. 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 17（`java -version` 校验） |
| 引擎主机 | Windows / Linux 均可；内存 ≥ 1G，磁盘 ≥ 500M |
| 数据库 | 试点期仅需引擎本机 SQLite（内嵌，免安装）；MySQL/TXSQL 仅 Schema 缓存拉取时网络可达 |
| 插件端 | IDEA 2024.1+ Community/Ultimate |

## 2. 部署包

- 引擎：`engine/build/distributions/dbagent-engine.zip`（`gradlew :engine:distZip` 产出）
- 插件：`plugin/build/distributions/plugin-<version>.zip`（`gradlew :plugin:buildPlugin` 产出）

引擎解压后目录布局：

```
dbagent-engine/
  bin/       start.sh / start.bat（封装主密钥参数传递与 JAVA_OPTS）
  config/    engine.yaml（监听/日志/Token 清单/数据源）+ rules/（五个规则 YAML）+ dict/（字典快照目录）
  lib/       engine.jar（fat jar，全部依赖内嵌）
  data/      SQLite engine.db（首次启动自动初始化）
  logs/      audit.log（审计，append-only）/ engine.log（运行）
```

## 3. 首次部署步骤

1. **放置部署包**：解压 `dbagent-engine.zip` 到目标目录（如 `/opt/dbagent` 或 `D:\dbagent`）。
2. **配置系统与数据源**：编辑 `config/engine.yaml`
   - `datasources:` 按归属系统配置 JDBC 连接（refresh-schema 空参回退用，口令仅存引擎侧）；
   - `http.host` 见第 4 节内网绑定。
3. **签发 Token**：在 `tokens:` 清单追加条目（`token`/`notesId`/`owningSystem`/`issuedAt`）：
   - token 值可明文，或 `hmac:<hex>`（HMAC-SHA256(主密钥, 明文 Token)，推荐）；
   - 同 NotesID 仅最新签发有效（一对一）；签发日起 90 天有效，过期与无效不区分统一 401；
   - hmac 计算：可用任意 HMAC-SHA256 工具，或引擎 `--master-key` 启动后按相同算法生成。
4. **放置字典快照**：将贯标字典放入 `config/dict/`（field-abbrev.yaml / table-abbrev.yaml / standard-sample.json 等，引擎启动时导入并记录快照标记）。
5. **启动**：
   - Windows：`set DBAGENT_MASTER_KEY=...`（可选，仅 hmac Token 需要）后运行 `bin\start.bat`；
   - Linux：`DBAGENT_MASTER_KEY=... ./bin/start.sh`（主密钥不入命令行历史可用 `read -s` 后 export）；
   - 堆参数经 `JAVA_OPTS` 调整（默认 `-Xms256m -Xmx1g`）。
6. **就绪校验**：`curl http://<host>:9090/health` → `{"status":"UP","rulesLoaded":true,"dictLoaded":true,...}`。
7. **插件安装**：IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选插件 zip → 重启；首次打开「新建数据库表（向导）→ 连接设置」填引擎地址/NotesID/Token。

## 4. 内网绑定与防火墙白名单

- 默认 `http.host: 127.0.0.1`（仅回环，最安全，适合引擎与 IDEA 同机试点）。
- 团队共享引擎时改为内网网卡地址（如 `10.x.x.x`），**不要绑定 0.0.0.0 暴露公网**。
- 防火墙白名单：仅放行插件使用方网段 → 引擎主机 TCP 9090（或 `http.port` 配置值）；其余端口不放行。
- 引擎出站仅需：MySQL/TXSQL 3306（Schema 缓存拉取，按需）、无公网依赖（LLM 桩默认关闭）。

## 5. 试点验证清单（对齐验收 5.1–5.19 抽验核心项）

| 验收 | 抽验项 | 通过标准 |
| --- | --- | --- |
| 5.1 | 向导全流程 | 表单→生成→预览→复制/插入 SQL 走通 |
| 5.10 | 插件端零 JDBC | 刷新 Schema 缓存全程不填连接参数，返回表数/字段数 |
| 5.11 | 引擎不可达 | 停引擎后插件操作友好提示，不崩溃 |
| 5.15 | 全流程耗时 | 熟练用户 ≤ 3 分钟 |
| 5.16 | 缓存命中响应 | generate-ddl ≤ 2 秒 |
| 5.18 | 规则重启生效 | 改 `rules/naming.yaml` 前缀重启后表名变化（如 t_ → tb_） |
| 5.19 | 干净环境全流程 | 全新目录解压启动引擎 + 插件 zip 安装 + 建表全流程 |

插件 UI 细项见 `docs/t7-plugin-manual-acceptance.md`（手动验收清单）。

## 6. 双平台启动验证记录

- Windows：`bin\start.bat` 干净目录实测启动（health UP、generate-ddl 全流程、审计日志落盘）。
- Linux：`bin/start.sh` 为 POSIX sh 最小语法（无 bashism），经 `bash -n` 语法校验；
  试点机（JDK 17）按第 3 节执行即等同验证。

## 7. 运维要点

- **重启轮换**：Token 清单变更（签发新/作废旧）改 `engine.yaml` 后重启生效；规则与字典变更同样重启生效（无热加载）。
- **日志**：审计四元组在 `logs/audit.log`（时间|NotesID|动作|目标系统|结果），运行日志在 `logs/engine.log`；两日志均不含密码/连接串/Token/主密钥。
- **数据备份**：`data/engine.db` 单文件，停机复制即备份（含确认缓存、规范化 COMMENT、Schema 缓存）。
