# Resilient Finance Ledger (Java)

本地可运行的复式记账与交易事件回放服务。纯内存实现，**不依赖外部消息系统、外部数据库或真实凭据**。
在并发提交、重复请求、事件乱序和版本升级下，账务结论始终一致且可解释。

## 账务模型

- **账户 Account**：按 `(tenantId, accountId)` 唯一，余额以最小货币单位 `long` 表示，带币种。
  普通账户不允许透支；`EQUITY:{tenantId}` 为租户级权益对方科目（允许为负），用于期初余额的复式配平。
- **复式记账**：每笔成功转账产生一借一贷两条流水（借方账户减少、贷方账户增加）；
  开户期初余额记 `借:EQUITY / 贷:账户`。任意时刻租户内全部流水的有符号金额之和为 0（总借贷守恒），
  可通过 `GET /api/tenants/{t}/conservation` 或 `LedgerService.conservationSum` 验证。
- **审计流水 JournalEntry**：记录事件序号、交易号、租户、账户、借贷方向、金额、币种、
  **过账后余额**与原因，足以还原影响结论的关键状态。
- **交易事件 TransferEvent**：不可变，含 `schemaVersion` 与全局单调递增 `sequence`（由 EventStore 追加时分配）。

### 拒绝原因（稳定、可区分）

| 原因 | 含义 |
|---|---|
| `INVALID_AMOUNT` | 金额 <= 0 |
| `ACCOUNT_NOT_FOUND` | 账户在本租户不存在 |
| `TENANT_MISMATCH` | 账户存在但属于其他租户 |
| `CURRENCY_MISMATCH` | 币种与账户币种不一致 |
| `INSUFFICIENT_FUNDS` | 余额不足 |
| `UNSUPPORTED_EVENT_VERSION` | 回放时事件版本不受支持 |

## 并发与幂等规则

- **并发安全**：校验、事件追加、余额过账、流水登记、幂等记录全部在同一把全局锁内串行完成，
  并发提交不会透支、丢失更新或重复入账（见 `ConcurrencyTest`）。
- **请求幂等**：`(tenantId, idempotencyKey)` 唯一映射到交易号 `tenantId:idempotencyKey` 与首次结果；
  重复提交（包括并发重复）返回完全相同的结果，不重复入账。
- **事件追加幂等**：`EventStore.append` 按交易号去重，重复事件返回已存储事件。

## 回放规则

- 回放按 `sequence` 升序重排，**乱序到达被自动纠正**。
- 按交易号去重：已应用的事件跳过，**不改变余额、不重复生成流水**。
- **重启恢复**：事件先落 EventStore 再过账；用同一 EventStore 构造新 `LedgerService`、
  重新登记账户元数据后调用 `replayFromStore()`，即可恢复全部已确认交易，且不丢失、不重复。
- 回放结果 `ReplayResult` 明确列出：应用数、去重跳过数、拒绝列表（交易号 + 稳定原因）。

## 版本兼容边界

- 当前事件版本 **v2**（含 `tenantId`、`memo`）。
- **v1 → v2 升级**：v1 无租户概念，升级时归入明确的默认租户 `legacy`（`EventUpcaster.LEGACY_DEFAULT_TENANT`），
  保证旧事件可回放且归属可解释。
- **不兼容即拒绝**：版本号 < 1 或 > 2 的事件在回放时被明确拒绝
  （`UNSUPPORTED_EVENT_VERSION`），记录在 `ReplayResult.rejections` 中，**不产生任何账务影响**，
  其余可兼容事件照常入账。

## 本地验证方法

```bash
# 全部测试（并发转账、重复提交、乱序重放、旧版本兼容、REST 端到端）
mvn test

# 本地启动（随机端口见日志，或指定 --server.port=8080）
mvn spring-boot:run
```

测试日志中会打印输入、关键判定与最终结果（`[input]` / `[decision]` / `[result]`）。

### REST 示例

```bash
# 开户
curl -X POST localhost:8080/api/tenants/acme/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"cash","currency":"CNY","initialBalance":1000}'

# 幂等转账（重复提交返回同一交易号，不重复入账）
curl -X POST localhost:8080/api/tenants/acme/transfers \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"order-1","fromAccountId":"cash","toAccountId":"revenue","amount":250,"currency":"CNY","memo":"order payment"}'

# 余额 / 审计流水 / 守恒校验 / 回放（均按租户隔离）
curl localhost:8080/api/tenants/acme/accounts/cash/balance
curl localhost:8080/api/tenants/acme/journal
curl localhost:8080/api/tenants/acme/conservation
curl -X POST localhost:8080/api/tenants/acme/replay
```

## 代码结构

```
domain/    Account, TransferEvent(版本化), JournalEntry, TransferResult, RejectionReason
ledger/    LedgerService(核心: 提交/幂等/回放/查询), EventStore(仅追加), EventUpcaster(v1->v2)
api/       LedgerController(REST), LedgerConfig(内存装配)
tests/     LedgerServiceTest, ConcurrencyTest, ReplayTest, RestApiTest
```
