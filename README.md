# Resilient Finance Ledger (Java)

本地可运行的复式记账与交易事件回放服务。纯内存实现，**不依赖外部消息系统、外部数据库或真实凭据**；
在并发提交、重复请求、事件乱序和版本升级下保证账务结论一致且可解释。

## 账务模型

- **账户**：按 `(tenantId, accountId)` 唯一，绑定单一币种，余额只能由复式记账分录变动。
- **期初余额怎么进账**：开户即追加一条 `AccountOpenedEvent`，并以复式分录落账——
  **借：系统期初权益虚账户 `SYSTEM_OPENING_EQUITY`（非真实账户，不可开户/转账），贷：新账户**。
  期初不再凭空出现：任意账户余额都可以只凭流水逐步推出
  （`余额 = Σ贷入 − Σ借出`），零期初也会落一条分录以记录账户来源。
- **复式分录（LedgerEntry）**：每条分录带 `type`（`OPENING_BALANCE` / `TRANSFER`）、
  借贷双方账户、金额，以及**过账后双方余额**（`debitBalanceAfter` / `creditBalanceAfter`，
  虚账户一侧记 `null`）。查流水时逐笔都能看到“这笔过账之后账户变成了多少”，对账一眼可对。
- **事件先行**：开户/转账都在临界区内先把事件追加到只追加的 `EventStore`，
  成功后才变动余额并写流水。事件存储是唯一事实来源，**重启后仅凭事件即可还原账户与余额**
  （开户事件重建账户与期初，转账事件重放资金变动）。
- **审计**：成功分录（`LedgerEntry`）与被拒请求（`RejectionAudit`，含关键输入与稳定原因码）
  均为只追加记录。

## 一致性规则

### 并发
- 转账按两个账户键的**字典序加锁**（防死锁），余额检查与扣款在同一临界区：
  不会透支、不会丢失更新、不会重复入账。
- 另有一把**快照读写锁**：所有“追加事件 + 变动余额 + 写流水”的临界区持读锁（彼此可并发），
  对账报告持写锁，保证报告读到一致快照（见下）。

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
- 回放为**两阶段**：阶段一整批升级 + 校验（含转账事件引用的账户，含本批开户事件将创建的账户），
  **任何一条不兼容则整批拒绝、账务零变化**；阶段二先应用开户事件（按账户存在性幂等去重），
  再按幂等键去重应用转账事件。
- **乱序、重复、处理中途重启均不改变最终余额**，已确认交易不丢失、不重复；
  开户事件与转账事件可混批、乱序回放（开户与转账无顺序依赖，重排安全）。
- 回放的是已确认事件，不再做余额检查（提交时已校验）。
- 回放后同一 `requestId` 的新提交仍被识别为幂等命中。

### 版本兼容边界
- **TransactionEvent V1**：无 `requestId` 字段，升级时以 `transactionId` 派生确定性幂等键（`v1-<txId>`）。
- **TransactionEvent V2**：有 `requestId`、无业务参考信息，升级时补 `businessReference = null`。
- **TransactionEvent V3（当前版本）**：新增**可选** `businessReference`（业务参考信息，可为 null，
  不参与校验，原样随事件保留）。
- **AccountOpenedEvent V1（当前版本）**：开户事件，`{tenantId, accountId, currency, initialBalance}`，
  `initialBalance` 必须 ≥ 0。
- **其他版本（含未来版本）**：明确拒绝，抛出/返回 `UNSUPPORTED_EVENT_VERSION`，
  **绝不猜测语义、绝不产生错误账务**；同批其他合法事件也一并拒绝（整批零变化）。
- 关键字段缺失或金额非法：拒绝，原因码 `EVENT_VALIDATION_FAILED`。

### 租户隔离
- 所有查询（余额、审计流水、拒绝审计、对账报告）按 `tenantId` 过滤，任何租户只能看到自身数据；
  跨租户访问他方账户返回 `TENANT_MISMATCH`（转账）或 404（查询）。

## 对账报告怎么读

`GET /api/tenants/{t}/reconciliation` 返回按租户的一致快照报告（生成时持快照写锁，
**并发写入期间不会读到一半提交一半未提交的中间状态而误报不平**）：

```json
{
  "tenantId": "t1",
  "entryCount": 3,
  "totalDebits": 820,          // 借方独立汇总（全部流水借侧合计）
  "totalCredits": 820,         // 贷方独立汇总
  "totalsBalanced": true,      // 借方合计 == 贷方合计
  "accounts": [
    {
      "accountId": "A",
      "derivedBalance": 380,   // 仅凭流水推出的余额
      "systemBalance": 380,    // 系统当前余额
      "difference": 0,         // systemBalance - derivedBalance
      "matched": true,
      "entrySequences": [1, 3],       // 该账户涉及的分录序号
      "divergentSequences": []        // 逐笔重算与分录记载的过账后余额不一致的分录
    }
  ],
  "consistent": true           // totalsBalanced 且全部账户 matched
}
```

### 差异怎么定位
1. 看 `consistent` / `totalsBalanced` 得到总体结论；
2. 找 `matched=false` 的账户行——报告直接给出**哪个租户**（`tenantId`）、**哪个账户**
   （`accountId`）、**差多少**（`difference`，正 = 系统余额多于流水可解释部分）；
3. 用 `entrySequences` 拉出该账户全部分录逐笔核对；`divergentSequences` 列出
   “分录记载的过账后余额”与“从头重算值”不一致的具体分录序号——
   若为空而 `difference` 非零，说明差异发生在最后一笔分录之后（余额被绕开流水改动）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/accounts` | 建户 `{tenantId, accountId, currency, initialBalance}`；期初按复式分录落账并追加开户事件 |
| POST | `/api/transfers` | 转账；200=ACCEPTED/DUPLICATE，422=REJECTED+原因码 |
| GET | `/api/tenants/{t}/accounts/{a}/balance` | 余额查询（租户隔离） |
| GET | `/api/tenants/{t}/audit` | 审计流水（含期初分录与每笔过账后余额，租户隔离） |
| GET | `/api/tenants/{t}/rejections` | 拒绝审计（租户隔离） |
| GET | `/api/tenants/{t}/balanced` | 校验总借贷守恒 |
| GET | `/api/tenants/{t}/reconciliation` | 对账报告（一致快照，逐户核对，租户隔离） |
| POST | `/api/replay` | 回放事件数组；不兼容事件 422 + 原因码，整批零变化 |

回放报文中每条事件可带可选 `eventType` 字段：缺省或 `"TRANSFER"` 按交易事件解析
（向后兼容旧客户端），`"ACCOUNT_OPENED"` 按开户事件解析，其余值拒绝。

## 本地验证

```bash
# 运行全部测试（并发转账 / 幂等 / 拒绝原因 / 乱序回放 / 版本兼容 / 期初入账 /
# 事件恢复 / 对账报告与并发一致快照 / HTTP 端到端）
mvn test

# 本地启动服务（随机端口，见启动日志）
mvn spring-boot:run
```

测试日志会打印每个场景的**输入、关键判定与最终结果**（`[输入]/[判定]/[结果]` 前缀），例如：

```
[输入] A=100000, B=100000; 8 线程并发双向转账, 主线程并发对账 200 次
[判定] 并发期间对账 200 次, 不一致报告数=0
[结果] 终态: consistent=true, 借方合计=200400, A=100000, B=100000
```

手动验证对账报告：

```bash
# 建户（期初 100，按复式分录落账）+ 转账 30
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","accountId":"A","currency":"CNY","initialBalance":100}'
curl -X POST localhost:8080/api/accounts -H 'Content-Type: application/json' \
  -d '{"tenantId":"t1","accountId":"B","currency":"CNY","initialBalance":0}'
curl -X POST localhost:8080/api/transfers -H 'Content-Type: application/json' \
  -d '{"requestId":"r1","tenantId":"t1","fromAccountId":"A","toAccountId":"B","amount":30,"currency":"CNY"}'
# 看流水（含期初分录与每笔过账后余额）与对账报告
curl localhost:8080/api/tenants/t1/audit
curl localhost:8080/api/tenants/t1/reconciliation
```

## 代码结构

```
domain/    账户、分录（含类型与过账后余额）、对账报告、请求/结果、拒绝原因码、拒绝审计
event/     LedgerEvent（密封）、TransactionEvent（V3）、AccountOpenedEvent（V1）、
           EventUpgrader（V1→V2→V3 升级链与兼容拒绝）
service/   EventStore（只追加事件存储）、LedgerService（记账核心：期初复式/幂等/锁/回放/对账）
api/       REST 控制器与 DTO
tests/     并发、幂等、拒绝原因与租户隔离、回放、版本兼容、期初入账、事件恢复、
           对账报告（含并发一致快照）、HTTP 端到端测试
```
