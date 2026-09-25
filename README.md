# Resilient Finance Ledger (Java)

本地可运行的复式记账与交易事件回放服务。纯内存实现，**不依赖外部消息系统、外部数据库或真实凭据**；
在并发提交、重复请求、事件乱序和版本升级下保证账务结论一致且可解释。

## 账务模型

- **账户**：按 `(tenantId, accountId)` 唯一，绑定单一币种，余额只能由复式记账分录变动。
- **复式分录（LedgerEntry）**：每笔成功转账产生一条分录，借方 = 付款账户，贷方 = 收款账户，
  金额相等，因此任意时刻**总借贷守恒**（`GET /api/tenants/{t}/balanced` 可验证）。
- **事件先行**：转账在临界区内先把 `TransactionEvent` 追加到只追加的 `EventStore`，
  成功后才变动余额并写入审计流水。事件存储是已确认交易的唯一事实来源，重启后回放即可完整还原。
- **审计**：成功分录（`LedgerEntry`）与被拒请求（`RejectionAudit`，含关键输入与稳定原因码）
  均为只追加记录，可还原影响结论的关键状态与原因。

## 一致性规则

### 并发
- 转账按两个账户键的**字典序加锁**（防死锁），余额检查与扣款在同一临界区：
  不会透支、不会丢失更新、不会重复入账。

### 幂等
- 以 `(tenantId, requestId)` 为幂等键。重复提交（串行或并发）返回首次结果：
  首次成功 → 后续返回 `DUPLICATE` + 原交易号；首次被拒 → 后续返回**相同的稳定原因码**。
- 幂等命中不重复生成流水、不变动余额。

### 拒绝原因码（稳定、可区分）
| 原因码 | 含义 |
|---|---|
| `INSUFFICIENT_FUNDS` | 余额不足 |
| `CURRENCY_MISMATCH` | 币种与账户币种不匹配 |
| `TENANT_MISMATCH` | 账户存在但不属于该租户 |
| `INVALID_AMOUNT` | 金额为空或非正数 |
| `ACCOUNT_NOT_FOUND` | 账户不存在 |
| `UNSUPPORTED_EVENT_VERSION` | 事件版本无法兼容 |
| `EVENT_VALIDATION_FAILED` | 事件关键字段缺失/非法 |

### 回放
- 回放前每条事件先经 `EventUpgrader` 升级到当前版本并校验；
  再按幂等键去重应用：**乱序、重复、处理中途重启均不改变最终余额**，已确认交易不丢失、不重复。
- 回放的是已确认事件，不再做余额检查（提交时已校验），因此乱序回放与顺序回放终态一致
  （借贷求和可交换）。
- 回放后同一 `requestId` 的新提交仍被识别为幂等命中。

### 版本兼容边界
- **V1**：无 `requestId` 字段，升级时以 `transactionId` 派生确定性幂等键（`v1-<txId>`），可正常回放。
- **V2**：当前版本，原样通过。
- **其他版本（含未来版本）**：明确拒绝，抛出/返回 `UNSUPPORTED_EVENT_VERSION`，
  **绝不猜测语义、绝不产生错误账务**。
- 关键字段缺失或金额非正：拒绝，原因码 `EVENT_VALIDATION_FAILED`。

### 租户隔离
- 所有查询（余额、审计流水、拒绝审计）按 `tenantId` 过滤，任何租户只能看到自身数据；
  跨租户访问他方账户返回 `TENANT_MISMATCH`（转账）或 404（查询）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/accounts` | 建户 `{tenantId, accountId, currency, initialBalance}` |
| POST | `/api/transfers` | 转账；200=ACCEPTED/DUPLICATE，422=REJECTED+原因码 |
| GET | `/api/tenants/{t}/accounts/{a}/balance` | 余额查询（租户隔离） |
| GET | `/api/tenants/{t}/audit` | 审计流水（租户隔离） |
| GET | `/api/tenants/{t}/rejections` | 拒绝审计（租户隔离） |
| GET | `/api/tenants/{t}/balanced` | 校验总借贷守恒 |
| POST | `/api/replay` | 回放事件数组；不兼容事件 422 + 原因码 |

## 本地验证

```bash
# 运行全部测试（并发转账 / 幂等 / 拒绝原因 / 乱序回放 / 版本兼容 / HTTP 端到端）
mvn test

# 本地启动服务（随机端口，见启动日志）
mvn spring-boot:run
```

测试日志会打印每个场景的**输入、关键判定与最终结果**（`[输入]/[判定]/[结果]` 前缀），例如：

```
[输入] A=1000, B=0, 200 线程并发 A->B, 每笔=10（超额竞争）
[判定] accepted=100, INSUFFICIENT_FUNDS=100
[结果] A=0, B=1000
```

## 代码结构

```
domain/    账户、分录、请求/结果、拒绝原因码、拒绝审计
event/     TransactionEvent（V2）、EventUpgrader（V1→V2 升级与兼容拒绝）
service/   EventStore（只追加事件存储）、LedgerService（记账核心：幂等/锁/回放/守恒校验）
api/       REST 控制器与 DTO
tests/     并发、幂等、拒绝原因与租户隔离、回放、版本兼容、HTTP 端到端测试
```
