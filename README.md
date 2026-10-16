# statemachine-spring-boot-starter

## 模块用途

`statemachine-spring-boot-starter` 提供基于 System A 架构的**纯 YAML 声明式、无回调**统一状态流转基础能力，包含：

- **StateMachineRegistry** — 状态机注册中心，按 machineCode 管理 `StateMachineDefinition`（YAML 唯一来源）
- **StateMachineTemplate** — 三步管道执行器（resolve→fire→persistDb），无回调/无上下文副作用
- **IStatePersisterStrategy** — 持久化策略接口；内置 `EntityMapperStatePersisterStrategy`（通用，基于业务 BaseMapper + MP Wrapper）与 `InMemoryStatePersisterStrategy`（测试兜底）
- **YAML 驱动注册** — 通过 `com.worm.statemachine.definitions` 声明状态机（含 `biz-entity`/`biz-column` 实体锚点）

> 本模块已废弃注解驱动（`@EnableBizStateMachine`）、Java 适配器（`IStateMachineAdapter`）与回调（`onStateChanged`/`StateMachineEventContext`）机制。

## 引入方式

```xml
<dependency>
    <groupId>com.worm</groupId>
    <artifactId>statemachine-spring-boot-starter</artifactId>
    <version>${revision}</version>
</dependency>
```

## 配置项

状态机定义唯一来源于 YAML，每条定义以实体为锚点（`biz-entity` 提供表名与 `@TableId`，`biz-column` 指定状态列）：

```yaml
com:
  worm:
    statemachine:
      enable: true  # 默认 true，设置为 false 可禁用整个 starter
      definitions:
        - machine-code: my_business
          biz-entity: com.worm.core.entity.MyBiz          # 业务实体全类名（表名/@TableId 由此解析）
          biz-column: status                              # 状态列名
          biz-id-column: id                               # 可选，默认取 @TableId 主键列
          initial-state: PENDING
          transitions:
            - source-state: PENDING
              event: START
              target-state: RUNNING
            - source-state: RUNNING
              event: COMPLETE
              target-state: SUCCESS
```

启动校验（fail-fast）：`biz-entity` 可加载且有 `@TableName`/`@TableId`；`biz-column` 匹配标识符正则且存在于实体字段列表；`biz-id-column`（若声明）匹配标识符正则；业务 `BaseMapper` 可解析。任一不满足启动即报错。

## 使用方式

### 1. 执行状态机事件（枚举强类型）

业务方 MUST 以 `IEvent` 枚举调用，不保留旧 String 重载：

```java
@Autowired
private StateMachineTemplate executor;

public void startOrder(Long orderId) {
    // 枚举强类型事件驱动流转，内部委托 event.getEventId()
    StateMachineResult result = executor.sendEvent("my_business", String.valueOf(orderId), EnumMyBizChangeEvent.START);
    if (!result.isSuccess()) {
        throw new RuntimeException("状态流转失败: " + result.getErrorMessage());
    }
}
```

### 2. 持久化策略

- **EntityMapperStatePersisterStrategy** — 通用实现，`@ConditionalOnBean(SqlSessionTemplate.class)` 时注册。启动时按 `biz-entity` 解析 `TableInfo` 取出表元数据，经 `currentNamespace` 取业务 `BaseMapper` 并缓存 `Map<machineCode, MapperHolder>`。读用 `QueryWrapper.select(bizColumn).eq(idColumn, bizId)`；写用 `UpdateWrapper.eq(idColumn, bizId).eq(bizColumn, from).set(bizColumn, to)`，条件更新影响行数为 0 即并发推进失败。**不手拼 SQL。**
- **InMemoryStatePersisterStrategy** — 内存兜底，仅在 `test` profile 且容器中**不存在** `EntityMapperStatePersisterStrategy`（即未引入 MyBatis）时注册为 `@Primary`，供无 DB 单测使用。条件针对具体实现类型而非 `IStatePersisterStrategy`（组合策略本身即实现该接口，按其判断会被自身遮蔽）。

业务侧**无需**再为每个 machineCode 编写自定义 `*StatusPersisterStrategy` 或 `*StatusAdapter`。

### 3. 管道步骤

`StateMachineTemplate` 仅三步，异常短路：

1. **resolve** — 以数据库为真实来源，读取当前状态
2. **fire** — 校验事件合法性并计算目标状态
3. **persistDb** — 条件更新落库（并发冲突返回失败，不覆盖非预期状态）

## 核心 Bean

模块通过 `StatemachineAutoConfiguration` 注册以下 Bean（通过 `@ConditionalOnMissingBean` 保护，允许业务方覆盖）：

- `StateMachineRegistry` — 状态机注册中心
- `RoutingStatePersisterStrategy` — 持久化策略路由器
- `StateMachineTemplate` — 状态机执行器

## 测试

```bash
mvn -f "E:/workspace_idea/project_private/worm/worm-starter/pom.xml" -pl "statemachine-spring-boot-starter" test
```
