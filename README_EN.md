# statemachine-spring-boot-starter

A purely YAML-declarative, callback-free state machine starter: with the database as the single source of truth for state, it provides state transition capability featuring strongly-typed enum event driving, concurrency safety, and fail-fast validation at startup.

[中文](README.md) | English

## Introduction

For multi-state business flows such as orders, tasks, and approvals, transition logic scattered across business code tends to run into three classes of problems: "missed validation of event legality", "concurrent writes overwriting state", and "configuration errors surfacing only at runtime". This starter condenses state machines down to three things:

1. **Declare once in YAML**: a state machine's initial state and all transition rules live in configuration — registered at startup, validated at startup;
2. **Enum event-driven**: business events implement the `IEvent` interface and trigger transitions via `StateMachineTemplate.sendEvent(...)`, eliminating bare-string events at compile time;
3. **Database as the source of truth**: the current state is read from the database before every transition, persistence goes through a conditional update, and locks ensure that state changes to the same business entity execute serially.

The engine does not depend on any specific ORM — persistence goes through the `IStatePersisterStrategy` strategy abstraction, with a general-purpose MyBatis-Plus implementation built in; business code only declares an entity anchor to get wired in with "zero persister code".

## Features

- **YAML as the sole definition source** — all state machine metadata is declared via `com.worm.statemachine.definitions`; no programmatic registration, no callbacks, no condition/action extension points
- **Single entry point** — `StateMachineTemplate` exposes only four public APIs: `sendEvent` / `getCurrentState` / `getAvailableEvents` / `canExecute`
- **Strongly-typed enum events** — events must implement `IEvent`; `sendEvent` takes an enum parameter, constraining event legality at compile time
- **Throw on failure** — `StateMachineResult.throwIfFailed()` throws a `StateMachineException` carrying the original failure message, so there is no need to check `success` after every call
- **Zero persister code** — the built-in `EntityMapperStatePersisterStrategy` automatically locates the business `BaseMapper` by `biz-entity` to read and write state, with no hand-assembled SQL
- **Dual concurrency safeguards** — a lock at `machineCode:bizId` granularity serializes the same entity + a database conditional update as the fallback; when the state has already been advanced concurrently, the current transition is judged failed rather than overwriting
- **Startup fail-fast** — machineCode uniqueness, field completeness, entity/Mapper resolvability, and persistence strategy coverage are all validated during startup; configuration errors never make it into runtime

## Requirements

| Dependency | Version |
| --- | --- |
| JDK | 21+ |
| Maven | 3.9+ |
| Spring Boot | 4.0.6 |
| MyBatis-Plus | Optional (only required when using the built-in entity-driven persistence strategy; the dependency is declared as `optional`, bring it in as needed) |

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.worm</groupId>
    <artifactId>statemachine-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Declare the state machine in YAML

```yaml
com:
  worm:
    statemachine:
      enable: true               # 总开关，默认 true，可省略
      lock-timeout-seconds: 10   # 锁等待超时（秒），默认 10，可省略
      definitions:
        - machine-code: order
          biz-entity: com.worm.example.entity.Order   # 业务实体全限定类名
          biz-column: status                           # 状态列名
          biz-id-column: id                            # 可选，默认取实体 @TableId 的主键列
          initial-state: PENDING
          transitions:
            - source-state: PENDING
              event: APPROVE
              target-state: APPROVED
            - source-state: APPROVED
              event: COMPLETE
              target-state: FINISHED
```

Transition keys use kebab-case (`source-state` / `target-state`); Spring Boot relaxed binding also accepts camelCase (`sourceState` / `targetState`). For a real production example, refer to the `application.yml` of `worm-micro-spider-starter`.

### 3. Define the event enum

The event enum implements the `IEvent` interface (example code below):

```java
public enum EnumOrderEvent implements IEvent {

    APPROVE("审批通过"),
    COMPLETE("完成订单");

    private final String description;

    EnumOrderEvent(String description) {
        this.description = description;
    }

    @Override
    public String getEventId() {
        return name();
    }

    @Override
    public String getDescription() {
        return description;
    }
}
```

The value returned by `getEventId()` is exactly the `event` field in the YAML `transitions`; the two must be identical.

### 4. Inject and invoke

```java
@Service
@AllArgsConstructor
public class OrderBizService {

    private final StateMachineTemplate stateMachineTemplate;

    public void approveOrder(String orderId) {
        // 枚举强类型事件驱动流转
        StateMachineResult result = stateMachineTemplate.sendEvent("order", orderId, EnumOrderEvent.APPROVE);
        // 失败即抛 StateMachineException（消息为失败原因原文），成功静默返回
        result.throwIfFailed();
    }
}
```

## YAML Definition Fields

Each definition binds to `StateMachineDefinition`; field names are mapped via Spring Boot relaxed binding (kebab-case → camelCase):

| Field | Required | Description | Validation rules / default behavior |
| --- | --- | --- | --- |
| `machine-code` | Yes | Business code, the unique key for state machine routing | Globally unique; duplicate registration throws `IllegalArgumentException` at startup |
| `biz-entity` | No | Fully qualified class name (FQN) of the business entity, the anchor of entity-driven persistence | When declared, validated at startup: the class is loadable, managed by MyBatis-Plus (`@TableName`), has a `@TableId` primary key, and its associated Mapper is resolvable from `SqlSessionTemplate` |
| `biz-column` | Required when `biz-entity` is declared | Database column name corresponding to the state field | Must match the identifier regex `[A-Za-z_]\w*` and exist in the entity's TableInfo field list |
| `biz-id-column` | No | Primary key column name override | Optional; when not configured, defaults to the entity's `@TableId` keyColumn; must match the identifier regex when declared |
| `initial-state` | Yes | Initial state identifier | Non-empty; when the entity has no transition record, the transition pipeline falls back to this state as the source state |
| `transitions` | Yes | Transition rule list | Non-empty; every transition must have `source-state` / `event` / `target-state` all present — startup fails if any is missing |

Startup also validates that every registered machineCode is covered by a persistence strategy, otherwise startup fails.

## API Reference

All public APIs are centralized in `com.worm.statemachine.registry.StateMachineTemplate`; the signatures below match the source code verbatim:

### sendEvent — Send event (the sole enum-typed entry point)

```java
public <E extends IEvent> StateMachineResult sendEvent(String machineCode, String bizId, E event)
```

- This is the only public `sendEvent` entry; internally it resolves the event identifier via `event.getEventId()` and then drives the transition;
- Situations such as the event being illegal in the current state, an undefined machineCode, lock wait timeout, or concurrent preemption all return a failed result (no exception thrown); chain-call `throwIfFailed()` to turn it into an exception;
- The returned `StateMachineResult` carries `machineCode` / `sourceState` / `event` / `targetState` (on success) or `errorMessage` (on failure).

### getCurrentState — Query the current state

```java
public String getCurrentState(String machineCode, String bizId)
```

- Reads the current state directly from the persistence layer; the database is the single source of truth;
- **Returns `null` when no record exists** (meaning the entity has never transitioned); no `initial-state` fallback is applied — callers must handle null themselves.

### getAvailableEvents — Query available events

```java
public List<String> getAvailableEvents(String machineCode, String bizId)
```

- Returns the list of event identifiers executable in the current state (`List<String>`);
- When the current state is `null` (no transition yet), it falls back to `initial-state`, consistent with the source-state derivation semantics of the transition pipeline;
- Returns an empty list when the machineCode has no definition.

### canExecute — Check whether an event can execute

```java
public boolean canExecute(String machineCode, String bizId, String eventId)
```

- Reuses the query semantics of `getAvailableEvents` to determine whether the event identifier is in the available list;
- Suitable for boolean branch checks before driving a transition, avoiding a blind `sendEvent` running into an illegal event.

### throwIfFailed — Throw on failure

```java
public void throwIfFailed()   // StateMachineResult 实例方法
```

- Returns silently when `success` is `true`; throws `StateMachineException` when it is `false`, with the exception message being the original text of `errorMessage` (no prefix wrapping);
- `StateMachineException` extends `RuntimeException`, so callers are not forced to declare checked exceptions.

## Configuration Properties

The configuration prefix is `com.worm.statemachine`, bound to `com.worm.properties.StatemachineProperties`:

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `com.worm.statemachine.enable` | `Boolean` | `true` | Master switch of the starter; set to `false` to disable the entire state machine auto-configuration |
| `com.worm.statemachine.lock-timeout-seconds` | `Long` | `10` | Lock wait timeout in seconds; fails fast once the wait exceeds this duration while the lock is held; `0` means fail immediately without waiting |
| `com.worm.statemachine.definitions` | `List<StateMachineDefinition>` | Empty list | State machine definition list; see the YAML definition field table above |

The injection chain of `lock-timeout-seconds`: configuration → `StatemachineAutoConfiguration` → `StateMachineTemplate` constructor (an instance-level immutable field); different businesses can customize their own fast-fail durations.

## Persistence and Concurrency

### Execution pipeline

After acquiring the lock at `machineCode:bizId` granularity, `sendEvent` drives a three-step pipeline; any failing step short-circuits and returns:

1. **resolve** — reads the current state from the persistence layer (the database is the single source of truth, read exactly once); when no record exists, `initial-state` is used as the source state; it looks up a matching transition definition and fails if none is found;
2. **fire** — produces the successful result (source state → target state);
3. **persistDb** — persists via a conditional update: `UPDATE ... SET biz-column = target state WHERE primary key = bizId AND biz-column = source state`.

### Zero-row semantics

- **Read path**: when no record exists (or the state column is NULL), `readCurrentState` returns `null` — the query API (`getCurrentState`) returns null as-is, while the transition pipeline (resolve) falls back to `initial-state`.
- **Write path**: when the conditional update affects 0 rows (the record does not exist, or the state has already been advanced concurrently so the source state no longer matches), the transition is judged failed and a failed result is returned — no overwriting of unexpected state. Business records must therefore be created by the business side beforehand, and the state column's initial value must match the resolved source state (usually `initial-state`).

### Lock mechanism

- The default lock is `LocalReentrantDistributedLock` (an in-process `ReentrantLock`): it maintains an independent lock entry per `machineCode:bizId`; an entry is automatically removed from the internal Map when it has "no references, no lock holder, and no queued waiters", preventing unbounded growth in long-running services;
- Lock waiting is capped at `lock-timeout-seconds`; on timeout it fails fast (returning `concurrent execution timeout for bizId: ...`) instead of queueing and waiting indefinitely;
- The database conditional update is the last line of defense beyond the lock: even if the lock fails (e.g., cross-instance scenarios), the 0-row determination still prevents state overwriting.

## Architecture and Package Structure

```text
com.worm
├── config
│   └── StatemachineAutoConfiguration        // The sole auto-configuration class (registered via AutoConfiguration.imports)
├── properties
│   └── StatemachineProperties               // Configuration properties (prefix com.worm.statemachine)
└── statemachine
    ├── core        // StateMachineResult / StateMachineException / IEvent / IState / StateMachineDefinition / IDistributedLock / LocalReentrantDistributedLock
    ├── registry    // StateMachineTemplate (public API) / StateMachineRegistry (definition registry) / StatemachineConfigInitializer (YAML registration & startup validation)
    └── persist     // IStatePersisterStrategy / RoutingStatePersisterStrategy (routing) / EntityMapperStatePersisterStrategy (MyBatis-Plus) / InMemoryStatePersisterStrategy (test fallback)
```

Auto-configured bean list (`StatemachineAutoConfiguration`; business code can override conditionally):

| Bean | Registration condition | Description |
| --- | --- | --- |
| `StateMachineRegistry` | When missing | Definition registry; the sole YAML source |
| `IDistributedLock` | When missing | Defaults to `LocalReentrantDistributedLock` (in-process) |
| `RoutingStatePersisterStrategy` | When missing | Collects all `IStatePersisterStrategy` beans in the container and routes by machineCode; business-specific strategies take precedence over general-purpose ones |
| `StateMachineTemplate` | When missing | The public API; `lock-timeout-seconds` injected via constructor |
| `EntityMapperStatePersisterStrategy` | When a `SqlSessionTemplate` bean exists | General-purpose MyBatis-Plus persistence strategy |
| `InMemoryStatePersisterStrategy` | Under the `test` profile when no MyBatis general strategy exists (`@Primary`) | In-memory fallback for unit tests |
| `StatemachineConfigInitializer` | Unconditional | Registers YAML definitions and validates persistence strategy coverage at startup |

## FAQ

### 1. Where did the String-parameter sendEvent overload go?

It has been removed; the public API keeps only the strongly-typed enum entry point. Migration steps for legacy code:

```java
// 迁移前（已不可用）：
// StateMachineResult result = stateMachineTemplate.sendEvent("order", orderId, "APPROVE");

// 迁移后：事件定义为枚举并实现 IEvent，再以枚举调用
StateMachineResult result = stateMachineTemplate.sendEvent("order", orderId, EnumOrderEvent.APPROVE);
result.throwIfFailed(); // 可选：失败以异常收敛，替代逐次判断 result.isSuccess()
```

In YAML, just fill the `event` field of `transitions` with the value returned by the enum's `getEventId()`; no other changes are needed.

### 2. Is a MyBatis-Plus environment required?

Yes, when using the built-in `EntityMapperStatePersisterStrategy`: it is registered only when a `SqlSessionTemplate` bean exists in the container (i.e., MyBatis-Plus is on the classpath), and it requires the entity to be managed by MyBatis-Plus (`@TableName` / `@TableId` / Mapper registered).

Without MyBatis-Plus, the starter's built-in in-memory strategy only serves as a fallback under the `test` profile; in production you must implement the `IStatePersisterStrategy` interface (`readCurrentState` / `writeNewState` / `supports`) yourself and register it as a bean, and the router will take over automatically.

### 3. What to pay attention to in multi-instance deployments?

The default `LocalReentrantDistributedLock` only provides mutual exclusion within a single JVM process — **not across instances**. Multi-instance deployments must supply their own `IDistributedLock` implementation (e.g., a Redisson-based cross-JVM lock); registering it as a bean overrides the default implementation. Even if the lock fails across instances, the database conditional update (0 rows means failure) still prevents the state from being overwritten concurrently, but the latecomer perceives the preemption in the form of a failed result.

## License

This project is licensed under the [Apache License 2.0](LICENSE). See the [LICENSE](LICENSE) file for details.
