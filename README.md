# statemachine-spring-boot-starter

纯 YAML 声明式、无回调的状态机 starter：以数据库为状态唯一真实来源，提供枚举强类型事件驱动、并发安全、启动期 fail-fast 校验的状态流转能力。

[English](README_EN.md) | 中文

## 简介

订单、任务、审批等多状态业务的流转逻辑散落在业务代码里，容易出现「事件合法性漏校验」「并发把状态写覆盖」「配置错误拖到运行时才暴露」三类问题。本 starter 把状态机收敛为三件事：

1. **YAML 声明一次**：状态机的初始状态与全部流转规则写在配置里，启动时注册、启动时校验；
2. **枚举事件驱动**：业务事件实现 `IEvent` 接口，经 `StateMachineTemplate.sendEvent(...)` 触发流转，编译期杜绝裸字符串事件；
3. **数据库即真相**：每次流转前从数据库读取当前状态，落库走条件更新，配合锁保证同一业务实体的状态变更串行执行。

引擎不依赖任何具体 ORM——持久化经 `IStatePersisterStrategy` 策略抽象，内置 MyBatis-Plus 通用实现，业务方声明实体锚点即可「零 persister 代码」接入。

## 特性

- **YAML 唯一定义来源** — 状态机元数据全部经 `com.worm.statemachine.definitions` 声明，无编程式注册、无回调、无条件/动作扩展点
- **单一入口** — `StateMachineTemplate` 仅暴露 `sendEvent` / `getCurrentState` / `getAvailableEvents` / `canExecute` 四个公共 API
- **事件枚举强类型** — 事件必须实现 `IEvent`，`sendEvent` 以枚举入参在编译期约束事件合法性
- **失败即抛** — `StateMachineResult.throwIfFailed()` 失败时抛出携带失败原因原文的 `StateMachineException`，无需逐次判 `success`
- **零 persister 代码** — 内置 `EntityMapperStatePersisterStrategy` 按 `biz-entity` 自动定位业务 `BaseMapper` 完成状态读写，不手拼 SQL
- **并发双保险** — `machineCode:bizId` 粒度锁串行化同一实体 + 数据库条件更新兜底，状态被并发推进时本次流转判失败而非覆盖
- **启动期 fail-fast** — machineCode 唯一性、字段完整性、实体/Mapper 可解析性、持久化策略覆盖，全部在启动阶段校验，配置错误不进运行时

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 21+ |
| Maven | 3.9+ |
| Spring Boot | 4.0.6 |
| MyBatis-Plus | 可选（仅使用内置实体驱动持久化策略时需要；依赖声明为 `optional`，按需引入） |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.worm</groupId>
    <artifactId>statemachine-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. YAML 声明状态机

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

流转键使用 kebab-case（`source-state` / `target-state`），Spring Boot 宽松绑定同样接受 camelCase（`sourceState` / `targetState`）。真实生产示例可参考 `worm-micro-spider-starter` 的 `application.yml`。

### 3. 定义事件枚举

事件枚举实现 `IEvent` 接口（以下为示例代码）：

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

`getEventId()` 返回值即 YAML `transitions` 中的 `event` 字段，二者必须一致。

### 4. 注入并调用

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

## YAML 定义字段表

每条定义绑定到 `StateMachineDefinition`，字段名经 Spring Boot 宽松绑定映射（kebab-case → camelCase）：

| 字段 | 必填 | 说明 | 校验规则 / 默认行为 |
| --- | --- | --- | --- |
| `machine-code` | 是 | 业务编码，状态机路由唯一键 | 全局唯一，重复注册启动即抛 `IllegalArgumentException` |
| `biz-entity` | 否 | 业务实体全限定类名（FQN），实体驱动持久化的锚点 | 声明后启动时校验：类可加载、被 MyBatis-Plus 管理（`@TableName`）、有 `@TableId` 主键、关联 Mapper 可从 `SqlSessionTemplate` 解析 |
| `biz-column` | `biz-entity` 已声明时必填 | 状态字段对应的数据库列名 | 必须匹配标识符正则 `[A-Za-z_]\w*`，且存在于实体的 TableInfo 字段列表 |
| `biz-id-column` | 否 | 主键列名覆盖 | 可选；未配置时默认取实体 `@TableId` 的 keyColumn；声明时需匹配标识符正则 |
| `initial-state` | 是 | 初始状态标识 | 非空；实体无流转记录时，流转管道以该状态作为源状态兜底 |
| `transitions` | 是 | 流转规则列表 | 非空；每条流转必须同时具备 `source-state` / `event` / `target-state`，任一缺失启动即失败 |

启动期还会校验：所有已注册 machineCode 必须有持久化策略覆盖，否则启动失败。

## API 参考

全部公共 API 集中在 `com.worm.statemachine.registry.StateMachineTemplate`，以下签名与源码逐字一致：

### sendEvent — 发送事件（枚举唯一入口）

```java
public <E extends IEvent> StateMachineResult sendEvent(String machineCode, String bizId, E event)
```

- 对外仅此一个 `sendEvent` 入口，内部取 `event.getEventId()` 解析事件标识后驱动流转；
- 事件在当前状态下不合法、machineCode 无定义、锁等待超时、并发抢占等情形均以失败结果返回（不抛异常），可链式调用 `throwIfFailed()` 转为异常；
- 返回的 `StateMachineResult` 携带 `machineCode` / `sourceState` / `event` / `targetState`（成功）或 `errorMessage`（失败）。

### getCurrentState — 查询当前状态

```java
public String getCurrentState(String machineCode, String bizId)
```

- 直接读取持久化层中的当前状态，数据库即唯一真实来源；
- **无记录时返回 `null`**（表示实体从未流转过），不做 `initial-state` 兜底，调用方需自行处理 null。

### getAvailableEvents — 查询可用事件

```java
public List<String> getAvailableEvents(String machineCode, String bizId)
```

- 返回当前状态下可执行的事件标识列表（`List<String>`）；
- 当前状态为 `null`（未流转）时回退 `initial-state`，与流转管道的源状态推导语义一致；
- machineCode 无定义时返回空列表。

### canExecute — 判断事件可否执行

```java
public boolean canExecute(String machineCode, String bizId, String eventId)
```

- 复用 `getAvailableEvents` 的查询口径，判断事件标识是否在可用列表中；
- 适合驱动流转前做布尔式分支判定，避免盲目 `sendEvent` 撞上不合法事件。

### throwIfFailed — 失败即抛

```java
public void throwIfFailed()   // StateMachineResult 实例方法
```

- `success` 为 `true` 时静默返回；为 `false` 时抛出 `StateMachineException`，异常消息为 `errorMessage` 原文（不加前缀包装）；
- `StateMachineException` 继承 `RuntimeException`，调用方无需强制声明受检异常。

## 配置项

配置前缀 `com.worm.statemachine`，绑定到 `com.worm.properties.StatemachineProperties`：

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `com.worm.statemachine.enable` | `Boolean` | `true` | starter 总开关，设为 `false` 禁用整个状态机装配 |
| `com.worm.statemachine.lock-timeout-seconds` | `Long` | `10` | 锁等待超时秒数；被占用期间等待超过该时长即快速失败，`0` 表示立即失败不等待 |
| `com.worm.statemachine.definitions` | `List<StateMachineDefinition>` | 空列表 | 状态机定义列表，见上文 YAML 定义字段表 |

`lock-timeout-seconds` 的注入链：配置 → `StatemachineAutoConfiguration` → `StateMachineTemplate` 构造器（实例级不可变字段），不同业务可各自定制快速失败时长。

## 持久化与并发机制

### 执行管道

`sendEvent` 内部按 `machineCode:bizId` 粒度加锁后，驱动三步管道，任一步失败即短路返回：

1. **resolve（解析）** — 从持久化层读取当前状态（数据库为唯一真实来源，只读一次）；无记录时以 `initial-state` 作为源状态；查找匹配的流转定义，找不到即失败；
2. **fire（触发）** — 生成成功结果（源状态 → 目标状态）；
3. **persistDb（持久化）** — 条件更新落库：`UPDATE ... SET biz-column = 目标状态 WHERE 主键 = bizId AND biz-column = 源状态`。

### 0 行语义

- **读路径**：无记录（或状态列为 NULL）时 `readCurrentState` 返回 `null`——查询 API（`getCurrentState`）原样返回 null；流转管道（resolve）则回退 `initial-state`。
- **写路径**：条件更新影响行数为 0（记录不存在，或状态已被并发推进导致源状态不再匹配）时，本次流转判为失败并返回失败结果，不覆盖非预期状态。因此业务记录由业务方先行创建，且状态列初值需与解析出的源状态一致（通常即 `initial-state`）。

### 锁机制

- 默认锁为 `LocalReentrantDistributedLock`（进程内 `ReentrantLock`）：按 `machineCode:bizId` 维护独立锁条目；条目在「无引用、未持锁、无排队」时自动从内部 Map 移除，避免长期运行的无界增长；
- 加锁等待上限为 `lock-timeout-seconds`，超时快速失败（返回 `concurrent execution timeout for bizId: ...`），不排队死等；
- 数据库条件更新是锁之外的最后兜底：即使锁失效（如跨实例场景），0 行判定仍能阻止状态覆盖。

## 架构与包结构

```text
com.worm
├── config
│   └── StatemachineAutoConfiguration        // 唯一自动配置类（经 AutoConfiguration.imports 注册）
├── properties
│   └── StatemachineProperties               // 配置属性（前缀 com.worm.statemachine）
└── statemachine
    ├── core        // StateMachineResult / StateMachineException / IEvent / IState / StateMachineDefinition / IDistributedLock / LocalReentrantDistributedLock
    ├── registry    // StateMachineTemplate（对外 API）/ StateMachineRegistry（定义注册中心）/ StatemachineConfigInitializer（YAML 注册与启动校验）
    └── persist     // IStatePersisterStrategy / RoutingStatePersisterStrategy（路由）/ EntityMapperStatePersisterStrategy（MyBatis-Plus）/ InMemoryStatePersisterStrategy（test 兜底）
```

自动装配的 Bean 清单（`StatemachineAutoConfiguration`，业务方可按条件覆盖）：

| Bean | 注册条件 | 说明 |
| --- | --- | --- |
| `StateMachineRegistry` | 缺失时 | 定义注册中心，YAML 唯一来源 |
| `IDistributedLock` | 缺失时 | 默认 `LocalReentrantDistributedLock`（进程内） |
| `RoutingStatePersisterStrategy` | 缺失时 | 收集容器内全部 `IStatePersisterStrategy`，按 machineCode 路由；业务特定策略优先于通用策略 |
| `StateMachineTemplate` | 缺失时 | 对外 API；`lock-timeout-seconds` 经构造注入 |
| `EntityMapperStatePersisterStrategy` | 存在 `SqlSessionTemplate` Bean 时 | MyBatis-Plus 通用持久化策略 |
| `InMemoryStatePersisterStrategy` | `test` profile 且无 MyBatis 通用策略时（`@Primary`） | 单测内存兜底 |
| `StatemachineConfigInitializer` | 无条件 | 启动时注册 YAML 定义并校验持久化策略覆盖 |

## FAQ

### 1. 原来 String 参数的 sendEvent 重载去哪了？

已移除，公共 API 仅保留枚举强类型入口。老代码迁移步骤：

```java
// 迁移前（已不可用）：
// StateMachineResult result = stateMachineTemplate.sendEvent("order", orderId, "APPROVE");

// 迁移后：事件定义为枚举并实现 IEvent，再以枚举调用
StateMachineResult result = stateMachineTemplate.sendEvent("order", orderId, EnumOrderEvent.APPROVE);
result.throwIfFailed(); // 可选：失败以异常收敛，替代逐次判断 result.isSuccess()
```

YAML 中 `transitions` 的 `event` 字段填枚举的 `getEventId()` 返回值即可，无需其他改动。

### 2. 必须有 MyBatis-Plus 环境吗？

使用内置 `EntityMapperStatePersisterStrategy` 时需要：它仅在容器中存在 `SqlSessionTemplate` Bean（即引入了 MyBatis-Plus）时注册，且要求实体被 MyBatis-Plus 管理（`@TableName` / `@TableId` / Mapper 已注册）。

未引入 MyBatis-Plus 时，starter 内置的内存策略仅在 `test` profile 下兜底，生产环境需自行实现 `IStatePersisterStrategy` 接口（`readCurrentState` / `writeNewState` / `supports`）并注册为 Bean，路由器会自动接管。

### 3. 多实例部署要注意什么？

默认的 `LocalReentrantDistributedLock` 只在单个 JVM 进程内互斥，**不跨实例**。多实例部署需自行提供 `IDistributedLock` 实现（如基于 Redisson 的跨 JVM 锁），注册为 Bean 即可覆盖默认实现。即使锁跨实例失效，数据库条件更新（0 行即失败）仍能阻止状态被并发覆盖，但会以失败结果的形式让后到方感知抢占。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源，详见 [LICENSE](LICENSE) 文件。
