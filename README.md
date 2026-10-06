# Resilient Finance Ledger (Java)

本地可运行的复式记账与交易事件回放服务。纯内存实现，**不依赖外部消息系统、外部数据库或真实凭据**；
在并发提交、重复请求、事件乱序和版本升级下保证账务结论一致且可解释。

## 账务模型

- **账户**：按 `(tenantId, accountId)` 唯一，绑定单一币种，余额只能由复式记账分录变动。
- **复式分录（LedgerEntry）**：每笔成功转账产生一条分录，借方 = 付款账户，贷方 = 收款账户，
  金额相等，因此任意时刻**总借贷守恒**（`GET /api/tenants/{t}/balanced` 可验证）。
  每条分录还记录**过账后双方余额**（`debitBalanceAfter` / `creditBalanceAfter`），
  查流水即可逐步核对任意账户余额的推演过程。
- **期初余额入账**：开户时先追加 `AccountOpenedEvent`，账户从 0 开始，
  正期初以一条期初分录落账——**借 `SYSTEM_OPENING_EQUITY`（每租户一个的系统期初权益账户），
  贷新账户**。期初不再凭空出现：来源由权益账户解释，借贷双方配平，
  任意账户余额都能只靠流水从 0 逐步推出（贷入加、借出减）。零期初不产生分录（金额必须为正），
  但开户事件照常记录，账户本身也可由事件还原。
- **事件先行**：开户与转账都在临界区内先把事件追加到只追加的 `EventStore`，
  成功后才变动账户状态并写入审计流水。事件存储是已确认事实的唯一来源，
  **重启后仅靠事件即可还原账户与余额**（含开户与期初），无需预先建户。
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
- 回放前**整批**事件先经 `EventUpgrader` 升级到当前版本并校验——任何一条不兼容，
  整批拒绝、账务零变化，绝不部分入账。
- 之后两段式应用：先开户事件（按账户存在性去重），再交易事件（按幂等键去重），
  因此**乱序、重复、处理中途重启均不改变最终余额**，已确认交易不丢失、不重复。
- 回放的是已确认事件，不再做余额检查（提交时已校验），因此乱序回放与顺序回放终态一致
  （借贷求和可交换）。
- 回放后同一 `requestId` 的新提交仍被识别为幂等命中；重复回放同一批事件应用数为 0。

### 对账报告（按租户的一致快照）
- `GET /api/tenants/{t}/reconciliation` 返回该租户的对账报告：
  - `totalDebits` / `totalCredits`：借方与贷方**各自独立汇总**（分录金额双边求和）；
  - `accounts[]`：逐户核对——`derivedBalance`（由流水从 0 推出）与 `systemBalance`
    （系统当前余额）、`difference`（系统 − 推导）与 `matched`；
  - `discrepancies[]`：每处差异定位到**租户、账户、差额与涉及的具体分录**
    （`involvedEntries` 为该账户的全部相关分录，可逐笔排查）；
  - `balanced`：总借贷相等且无账户差异时为 true。
- **一致快照**：报告在租户锁 + 该租户全部账户锁（字典序，与转账/期初分录加锁顺序一致，
  无死锁）的临界区内生成，并发写入下不会读到中间状态而误报不平。
- 报告按租户隔离：只含本租户账户（含本租户的系统期初权益账户），他租户差异不外溢。

### 版本兼容边界
- **TransactionEvent V1**：无 `requestId` 字段，升级时以 `transactionId` 派生确定性幂等键
  （`v1-<txId>`），可正常回放。
- **TransactionEvent V2**：无 `businessRef` 字段，升级时置 null（可选字段，不猜测取值）。
- **TransactionEvent V3**：当前版本，新增**可选**业务参考信息 `businessRef`
  （如订单号，透传保存、不参与账务计算），原样通过。
- **AccountOpenedEvent V1**：当前版本（开户与期初事件）。
- **其他版本（含未来版本）**：明确拒绝，抛出/返回 `UNSUPPORTED_EVENT_VERSION`，
  **绝不猜测语义、绝不产生错误账务**。
- 关键字段缺失/非法（交易金额非正、期初为负、必填标识为空等）：拒绝，
  原因码 `EVENT_VALIDATION_FAILED`。
- JSON 兼容：旧报文没有 `eventType` 属性时按 `TransactionEvent` 反序列化；
  开户事件报文带 `"eventType": "ACCOUNT_OPENED"`。

### 租户隔离
- 所有查询（余额、审计流水、拒绝审计、对账报告）按 `tenantId` 过滤，任何租户只能看到自身数据；
  跨租户访问他方账户返回 `TENANT_MISMATCH`（转账）或 404（查询）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/accounts` | 建户 `{tenantId, accountId, currency, initialBalance, businessRef?}`；期初按复式记账落账 |
| POST | `/api/transfers` | 转账 `{..., businessRef?}`；200=ACCEPTED/DUPLICATE，422=REJECTED+原因码 |
| GET | `/api/tenants/{t}/accounts/{a}/balance` | 余额查询（租户隔离） |
| GET | `/api/tenants/{t}/audit` | 审计流水（租户隔离），每条分录带过账后双方余额 |
| GET | `/api/tenants/{t}/rejections` | 拒绝审计（租户隔离） |
| GET | `/api/tenants/{t}/balanced` | 校验总借贷守恒 |
| GET | `/api/tenants/{t}/reconciliation` | 对账报告：借贷独立汇总、逐户核对、差异定位（一致快照） |
| POST | `/api/replay` | 回放事件数组（开户/交易、各受支持版本）；不兼容事件 422 + 原因码，整批零变化 |

## 本地验证

```bash
# 运行全部测试（并发转账 / 幂等 / 拒绝原因与租户隔离 / 乱序回放 / 版本兼容 /
# 期初入账与余额推导 / 对账报告与并发快照 / 纯事件恢复 / HTTP 端到端）
mvn test

# 只跑某一组，例如对账报告：
mvn test -Dtest=ReconciliationReportTest

# 本地启动服务（随机端口，见启动日志）
mvn spring-boot:run
```

手工验证对账报告与差异定位（服务启动后）：

```bash
# 建户（期初 100，复式落账）并转账 30
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","accountId":"A","currency":"CNY","initialBalance":100}'
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","accountId":"B","currency":"CNY","initialBalance":0}'
curl -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"requestId":"r1","tenantId":"t1","fromAccountId":"A","toAccountId":"B","amount":30,"currency":"CNY"}'

# 查流水：每条分录带过账后双方余额，可逐步对账
curl localhost:8080/api/tenants/t1/audit

# 对账报告：balanced=true，逐户 derivedBalance == systemBalance
curl localhost:8080/api/tenants/t1/reconciliation
```

测试日志会打印每个场景的**输入、关键判定与最终结果**（`[输入]/[判定]/[结果]` 前缀），例如：

```
[输入] A=1000, B=0, 200 线程并发 A->B, 每笔=10（超额竞争）
[判定] accepted=100, INSUFFICIENT_FUNDS=100
[结果] A=0, B=1000
```

## 代码结构

```
domain/    账户、分录（带过账后余额）、请求/结果、拒绝原因码、拒绝审计、对账报告
event/     LedgerEvent（密封接口）、TransactionEvent（V3）、AccountOpenedEvent（V1）、
           EventUpgrader（V1→V2→V3 逐级升级与兼容拒绝）
service/   EventStore（只追加事件存储）、LedgerService（记账核心：幂等/锁/期初分录/回放/对账快照）
api/       REST 控制器与 DTO
tests/     并发、幂等、拒绝原因与租户隔离、回放、版本兼容、期初入账、对账报告、
           纯事件恢复、HTTP 端到端测试
```
