package com.worm.statemachine.registry;

import com.worm.statemachine.core.*;
import com.worm.statemachine.persist.IStatePersisterStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 状态机模板。
 *
 * <p>干什么：作为状态机引擎的核心模板入口，负责接收外部事件驱动状态流转，
 * 并通过管道模式依次完成"解析→触发→持久化状态"全流程。</p>
 *
 * <p>为什么：状态流转涉及内存状态与数据库状态的一致性、并发安全、业务回调等复杂问题，
 * 需要一个统一的模板来编排这些步骤，避免业务方直接操作 Spring StateMachine 底层 API，
 * 同时通过管道模式使每个步骤职责单一、可独立测试和替换。</p>
 */
@Slf4j
public class StateMachineTemplate {

    /**
     * 状态机注册表。
     *
     * <p>干什么：根据 machineCode 获取状态机定义（状态、事件、流转关系）。</p>
     * <p>为什么：状态机引擎不硬编码任何业务逻辑，所有业务的状态机元数据均通过注册表动态获取，
     * 实现业务与引擎的解耦。</p>
     */
    private final StateMachineRegistry registry;

    /**
     * 状态持久化策略。
     *
     * <p>干什么：负责数据库层面的状态读写（readCurrentState / writeNewState）。</p>
     * <p>为什么：不同业务的状态存储方式可能不同（关系型数据库、NoSQL 等），
     * 通过策略接口允许业务方自定义持久化实现，引擎本身不依赖特定存储。</p>
     */
    private final IStatePersisterStrategy statePersisterStrategy;

    /**
     * 进程内分布式锁实现。
     *
     * <p>干什么：按 machineCode:bizId 粒度加锁，保证同一业务实体的状态变更串行执行。</p>
     * <p>为什么：statemachine 作为状态机模块只管进程内并发安全，MUST NOT 越界承担分布式锁的职责；
     * 默认使用 LocalReentrantDistributedLock（基于 ReentrantLock），由 StatemachineAutoConfiguration 注入。</p>
     */
    private final IDistributedLock lock;

    /**
     * 3 参兼容构造的默认锁等待超时（秒）。
     *
     * <p>干什么：作为未显式传入超时的 3 参兼容构造的兜底默认值。</p>
     * <p>为什么：spider 与 StatemachineAutoConfiguration 等既有调用方仍以 3 参构造实例化模板，
     * 默认值必须与原 LOCK_TIMEOUT_SECONDS=10 完全一致，避免兼容构造悄悄改变既有超时语义；
     * 4 参构造场景下该常量不再参与，超时完全由实例字段 lockTimeoutSeconds 承载。</p>
     */
    private static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 10;

    /**
     * 本实例的锁等待超时时间（秒）。
     *
     * <p>干什么：sendEvent 加锁时以本字段作为 tryLock 的等待上限，超时快速失败。</p>
     * <p>为什么：锁等待超时需跟随模板实例（可配置契约），使不同业务可注入各自的快速失败时长，
     * 而非全局硬编码；final 保证构造后不可变，避免运行期被篡改导致并发行为漂移。</p>
     */
    private final long lockTimeoutSeconds;

    /**
     * 全参构造：显式指定锁等待超时。
     *
     * <p>干什么：接收注册表、持久化策略、锁实现与锁等待超时（秒），构造超时可定制的模板实例。</p>
     *
     * <p>为什么：不同业务对"持锁期间等待多久快速失败"的诉求不同（如实时性高的业务希望秒级失败），
     * 超时必须随实例注入而非全局常量，本构造是超时可配置契约的唯一赋值入口。</p>
     *
     * @param registry              状态机注册表
     * @param statePersisterStrategy 状态持久化策略
     * @param lock                  进程内分布式锁实现
     * @param lockTimeoutSeconds    锁等待超时（秒），0 表示被占用期间立即失败不等待
     */
    public StateMachineTemplate(StateMachineRegistry registry, IStatePersisterStrategy statePersisterStrategy, IDistributedLock lock, long lockTimeoutSeconds) {
        // 为什么：字段为 final 不可变，注册表只能在构造期一次性绑定 —— 干什么：将入参 registry 赋给实例字段
        this.registry = registry;
        // 为什么：字段为 final 不可变，持久化策略只能在构造期一次性绑定 —— 干什么：将入参 statePersisterStrategy 赋给实例字段
        this.statePersisterStrategy = statePersisterStrategy;
        // 为什么：字段为 final 不可变，锁实现只能在构造期一次性绑定 —— 干什么：将入参 lock 赋给实例字段
        this.lock = lock;
        // 为什么：锁等待超时是新增的实例级契约，必须随构造传入而非读静态常量 —— 干什么：将入参 lockTimeoutSeconds 赋给实例字段
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    /**
     * 兼容构造：锁等待超时取默认 10s。
     *
     * <p>干什么：接收注册表、持久化策略、锁实现三参，委托 4 参构造并传入 DEFAULT_LOCK_TIMEOUT_SECONDS。</p>
     *
     * <p>为什么：spider 与 StatemachineAutoConfiguration 等既有调用方均以 3 参构造实例化模板，
     * 该构造必须原样保留，且默认值钉死为历史常量 10s，保证既有行为零漂移。</p>
     *
     * @param registry              状态机注册表
     * @param statePersisterStrategy 状态持久化策略
     * @param lock                  进程内分布式锁实现
     */
    public StateMachineTemplate(StateMachineRegistry registry, IStatePersisterStrategy statePersisterStrategy, IDistributedLock lock) {
        // 为什么：既有调用方以 3 参构造实例化且行为不能变，默认超时必须等于原常量 10s —— 干什么：委托 4 参构造并传入 DEFAULT_LOCK_TIMEOUT_SECONDS
        this(registry, statePersisterStrategy, lock, DEFAULT_LOCK_TIMEOUT_SECONDS);
    }

    /**
     * 发送事件（无上下文），枚举强类型唯一公共入口。
     *
     * <p>干什么：接收 IEvent 枚举事件，取 getEventId() 解析出事件标识后委托私有核心方法执行状态流转全流程。</p>
     *
     * <p>为什么：以枚举强类型在编译期约束事件入参，杜绝裸字符串事件回潮；
     * 同时保证对外仅存在一个 sendEvent 入口（反射契约：getMethods 中名为 sendEvent 的 public 方法恰好 1 个且第三参非 String）。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @param event       事件枚举
     * @param <E>         IEvent 枚举类型
     * @return 状态机执行结果
     */
    public <E extends IEvent> StateMachineResult sendEvent(String machineCode, String bizId, E event) {
        // 为什么：管道与流转匹配均以字符串事件标识运作，枚举入口需先解析为 eventId 才能复用既有管道 —— 干什么：取 event.getEventId() 委托私有核心方法 doSendEvent
        return doSendEvent(machineCode, bizId, event.getEventId());
    }

    /**
     * 查询业务实体当前状态。
     *
     * <p>干什么：根据 machineCode 与 bizId 直接读取持久化策略中记录的当前状态并原样返回。</p>
     *
     * <p>为什么：D2 决策规定持久化层（DB）是状态唯一真实来源（Source of Truth），
     * 查询路径必须与流转路径（resolve 步骤）读取同一份数据，避免内存缓存造成读数漂移；
     * 同时无记录时返回 null 是本方法的钉死契约——initialState 兜底仅属于 resolve 管道的行为，
     * 不应混入纯查询语义，调用方需自行处理 null（表示实体从未流转过）。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @return 当前状态；持久化层无记录时返回 null
     */
    public String getCurrentState(String machineCode, String bizId) {
        // 为什么：查询以持久化策略为唯一真实来源，且纯查询无需加锁、不做缓存与 initialState 兜底 —— 干什么：直接委托 statePersisterStrategy.readCurrentState 并返回结果
        return statePersisterStrategy.readCurrentState(machineCode, bizId);
    }

    /**
     * 查询业务实体当前状态下全部可执行的事件标识。
     *
     * <p>干什么：读取当前状态（无记录时回退 initialState），过滤状态机定义中
     * sourceState 与之匹配的流转，收集其事件标识返回 List&lt;String&gt;。</p>
     *
     * <p>为什么：业务方在驱动流转前需要感知"当前还能做什么"，以避免盲目 sendEvent 撞上不合法事件；
     * 查询侧允许 initialState 回退（D2 决策），使从未流转过的实体也能得到与首次 sendEvent
     * 的 resolve 步骤一致的事件视图，两处语义不漂移。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @return 当前状态下可执行的事件标识列表；状态机定义不存在时返回空列表
     */
    public List<String> getAvailableEvents(String machineCode, String bizId) {
        // 为什么：定义是流转元数据的唯一来源，复用既有注册表查找避免重复实现查找逻辑 —— 干什么：按 machineCode 从注册表获取状态机定义
        StateMachineDefinition definition = registry.getDefinition(machineCode);
        // 为什么：定义不存在时不存在任何合法流转，返回空列表比 NPE 更符合查询语义且不与 resolve 的失败路径耦合 —— 干什么：判空并返回不可变空列表
        if (definition == null) return Collections.emptyList();
        // 为什么：复用本类查询方法，保持"当前状态读持久化策略"的单一来源不漂移 —— 干什么：调用 getCurrentState 读取持久化状态
        String currentState = getCurrentState(machineCode, bizId);
        // 为什么：查询侧允许 initialState 回退（D2 决策），与 resolve 管道对未流转实体的源状态推导语义一致 —— 干什么：当前状态为 null 时回退 definition.getInitialState()
        String effectiveState = (currentState != null) ? currentState : definition.getInitialState();
        // 为什么：可用事件即以当前状态为源状态的全部流转的事件标识，Stream 链式写法避免手写循环 —— 干什么：过滤 sourceState 匹配的流转并收集 event 为 List<String>
        return definition.getTransitions().stream()
                .filter(t -> t.getSourceState().equals(effectiveState))
                .map(StateMachineDefinition.TransitionDefinition::getEvent)
                .collect(Collectors.toList());
    }

    /**
     * 判断指定事件在业务实体当前状态下是否可执行。
     *
     * <p>干什么：复用 getAvailableEvents 查询当前状态下的全部可执行事件标识，判断 eventId 是否在其中。</p>
     *
     * <p>为什么：业务方在驱动流转前需要布尔式的"能否执行"判定以做流程分支，避免盲目 sendEvent 撞上
     * 不合法事件；复用查询视图保证判定口径与 getAvailableEvents 完全一致，不重复实现状态读取与流转过滤。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @param eventId     事件ID
     * @return 当前状态下该事件可执行返回 true，否则返回 false（含状态机定义不存在的情形）
     */
    public boolean canExecute(String machineCode, String bizId, String eventId) {
        // 为什么：判定口径必须与查询视图一致，且未知 machineCode 时 getAvailableEvents 返回空列表使 contains 天然为 false，无需额外判空 —— 干什么：返回当前状态可用事件列表是否包含 eventId
        return getAvailableEvents(machineCode, bizId).contains(eventId);
    }

    /**
     * 发送事件核心实现（私有），承接 String 事件标识形参的管道逻辑。
     *
     * <p>干什么：接收事件标识，构建执行管道上下文，加锁后驱动管道执行状态流转全流程。</p>
     *
     * <p>为什么：对外公共入口仅保留枚举强类型形态，在编译期杜绝裸字符串事件回潮；
     * 而管道与流转匹配均以字符串事件标识运作，事件标识由枚举入口解析一次后传入本方法，
     * 故本方法保持私有、仅由枚举入口调用，对外不暴露任何 String 形态的 sendEvent。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @param eventId     事件ID
     * @return 状态机执行结果
     */
    private StateMachineResult doSendEvent(String machineCode, String bizId, String eventId) {
        // 为什么：过程日志高频且无排障增量，info 级会淹没生产日志 —— 干什么：由 info 降为 debug
        log.debug("开始发送事件: machineCode={}, bizId={}, eventId={}", machineCode, bizId, eventId);

        ExecutionPipelineContext ctx = new ExecutionPipelineContext(machineCode, bizId, eventId);

        // 锁粒度 machineCode:bizId：跨实例按业务实体唯一互斥，避免不同 machineCode 同 bizId 互相阻塞/误锁
        String lockKey = machineCode + ":" + bizId;
        boolean locked = false;
        try {
            // 为什么：锁等待超时改为跟随模板实例（可配置契约），不同业务可注入各自的快速失败时长而非全局 10s —— 干什么：tryLock 等待上限由常量 LOCK_TIMEOUT_SECONDS 换为实例字段 lockTimeoutSeconds
            if (!lock.tryLock(lockKey, lockTimeoutSeconds, TimeUnit.SECONDS)) {
                // 为什么：拒绝日志需反映实际生效的实例超时值，便于按业务配置排查 —— 干什么：日志中的超时值由常量换为实例字段 lockTimeoutSeconds
                log.warn("获取锁超时，并发执行被拒绝: machineCode={}, bizId={}, 超时时间={}s", machineCode, bizId, lockTimeoutSeconds);
                return StateMachineResult.failed(machineCode, "concurrent execution timeout for bizId: " + bizId);
            }
            // 为什么：仅在真正拿到锁后置位，确保 finally 不会在未持锁路径上误调 unlock —— 干什么：避免切换为会抛 IllegalMonitorStateException 的锁实现（如 Redisson）时崩溃
            locked = true;
            log.debug("成功获取锁: machineCode={}, bizId={}", machineCode, bizId);
            StateMachineResult result = executePipeline(ctx);
            // 为什么：过程日志高频且无排障增量，info 级会淹没生产日志 —— 干什么：由 info 降为 debug
            log.debug("事件执行完成: machineCode={}, bizId={}, eventId={}, 成功={}", machineCode, bizId, eventId, result.isSuccess());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("事件执行被中断: machineCode={}, bizId={}, eventId={}, 原因={}", machineCode, bizId, eventId, e.getMessage());
            return StateMachineResult.failed(machineCode, "execution interrupted: " + e.getMessage());
        } finally {
            // 为什么：无论正常返回还是异常，只要持锁就必须释放，覆盖所有已加锁路径 —— 干什么：locked 为 false 时不解锁，避免未持锁调用 unlock 引发异常
            if (locked) {
                lock.unlock(lockKey);
                log.debug("释放锁: machineCode={}, bizId={}", machineCode, bizId);
            }
        }
    }

    /**
     * 执行管道：按顺序执行状态流转的各个步骤，任一步骤失败则短路返回。
     *
     * <p>干什么：编排状态流转的完整生命周期，依次执行 3 个步骤：
     * resolve(解析) → fire(触发) → persistDb(持久化状态)。</p>
     *
     * <p>为什么：将状态流转拆分为独立的管道步骤，每个步骤职责单一，
     * 便于理解、测试和维护。通过 ctx.hasError() 实现步骤间的短路机制，
     * 任何一步失败即停止后续步骤，避免在错误状态下继续执行导致数据不一致。</p>
     */
    private StateMachineResult executePipeline(ExecutionPipelineContext ctx) {
        try {
            resolve(ctx);
            if (ctx.hasError()) return ctx.getResult();

            fire(ctx);
            if (ctx.hasError()) return ctx.getResult();

            persistDb(ctx);
            if (ctx.hasError()) return ctx.getResult();

            return ctx.getResult();
        } catch (Exception e) {
            return StateMachineResult.failed(ctx.getMachineCode(), "pipeline execution failed: " + e.getMessage());
        }
    }

    /**
     * 管道步骤1 - 解析：读取 DB 当前状态，确定源状态、目标状态和流转定义。
     *
     * <p>干什么：根据 machineCode 获取状态机定义，从持久化层读取一次当前状态作为源状态，
     * 然后查找匹配的流转定义（sourceState + event → targetState），确定目标状态。</p>
     *
     * <p>为什么：在真正驱动流转之前，必须先明确"从哪来、到哪去"。
     * 当前状态只从数据库读取一次——并发安全由 bizId 维度的可重入锁保证，
     * 同一业务实体串行执行，无需重复读 DB 做"恢复/校准"二次确认。
     * 如果当前状态上不存在对应事件的流转定义，说明该事件在当前状态下不合法，
     * 应在此处尽早失败，避免后续无效操作。</p>
     */
    private void resolve(ExecutionPipelineContext ctx) {
        String machineCode = ctx.getMachineCode();
        StateMachineDefinition definition = registry.getDefinition(machineCode);
        if (definition == null) {
            ctx.setResult(StateMachineResult.failed(machineCode, "no definition found for machineCode: " + machineCode));
            return;
        }

        String currentState = statePersisterStrategy.readCurrentState(machineCode, ctx.getBizId());
        ctx.setSourceState((currentState != null) ? currentState : definition.getInitialState());

        // 为什么：findTransition 改为直接复用本步骤已取得的 definition，避免对注册表的重复查询 —— 干什么：调用 findTransition 时传入 definition 而非 ctx
        StateMachineDefinition.TransitionDefinition transition = findTransition(definition, ctx.getSourceState(), ctx.getEvent());
        if (transition == null) {
            ctx.setResult(StateMachineResult.failed(machineCode, "no transition from state '" + ctx.getSourceState() + "' on event '" + ctx.getEvent() + "'"));
            return;
        }
        ctx.setTargetState(transition.getTargetState());

        log.debug("管道解析完成: machineCode={}, bizId={}, 源状态={}, 事件={}, 目标状态={}", machineCode, ctx.getBizId(), ctx.getSourceState(), ctx.getEvent(), ctx.getTargetState());
    }

    /**
     * 管道步骤2 - 触发：校验目标状态并生成执行结果。
     *
     * <p>干什么：在解析得到合法的目标状态后，构造成功结果（源状态→目标状态）。
     * 实际的持久化由后续 persistDb 步骤完成。</p>
     *
     * <p>为什么：将"决策"（解析出目标状态）与"落地"（写库）分离，
     * 触发步骤只负责产出结果，便于在失败场景下短路，避免写入非法状态。</p>
     */
    private void fire(ExecutionPipelineContext ctx) {
        if (ctx.getTargetState() == null) {
            ctx.setResult(StateMachineResult.failed(ctx.getMachineCode(), "target state is null, cannot fire event"));
            return;
        }
        ctx.setResult(StateMachineResult.success(ctx.getMachineCode(), ctx.getSourceState(), ctx.getEvent(), ctx.getTargetState()));
    }

    /**
     * 管道步骤3 - 持久化状态：将新状态写入数据库。
     *
     * <p>干什么：在触发成功后，将目标状态通过持久化策略写入数据库。</p>
     *
     * <p>为什么：状态变更结果仅存在于内存上下文中，必须持久化到数据库才能在下次请求时恢复。
     * 仅在执行结果为成功时才持久化，避免将失败的中间状态写入数据库。</p>
     */
    private void persistDb(ExecutionPipelineContext ctx) {
        if (ctx.getResult() != null && ctx.getResult().isSuccess() && ctx.getTargetState() != null) {
            int rows = statePersisterStrategy.writeNewState(
                    ctx.getMachineCode(), ctx.getBizId(), ctx.getSourceState(), ctx.getTargetState());
            if (rows == 0) {
                // 为什么：0 行代表 WHERE currentState=fromState 不满足，状态已被并发推进
                // 干什么：标记失败并且不静默成功，让调用方感知到本次流转被抢
                String msg = "状态已被并发推进，流转被抢: machineCode=" + ctx.getMachineCode() + ", bizId=" + ctx.getBizId()
                        + ", expected=" + ctx.getSourceState() + ", target=" + ctx.getTargetState();
                log.warn(msg);
                ctx.setResult(StateMachineResult.failed(ctx.getMachineCode(), msg));
                return;
            }
            log.debug("状态持久化完成(条件更新): 新状态={}, machineCode={}, bizId={}, 影响行数={}", ctx.getTargetState(), ctx.getMachineCode(), ctx.getBizId(), rows);
        }
    }

    /**
     * 查找流转定义：根据源状态和事件从状态机定义中查找匹配的流转。
     *
     * <p>干什么：在调用方传入的状态机定义的流转列表中，查找 sourceState 和 event 均匹配的流转定义。</p>
     * <p>为什么：状态机的核心语义就是"在某个状态下收到某个事件后迁移到目标状态"，
     * 此方法是流转查找的统一入口，被解析步骤复用，避免流转匹配逻辑散落各处；
     * 定义由调用方（resolve 步骤，已保证非 null）传入而非重复查询注册表，
     * 保证一次流转全程只查一次注册表，匹配语义不变。</p>
     */
    private StateMachineDefinition.TransitionDefinition findTransition(StateMachineDefinition definition, String sourceState, String eventId) {
        // 为什么：调用方 resolve 已取得定义并对 null 提前短路，方法内重复查询注册表与判空属死代码 —— 干什么：删除重复 getDefinition 与判空，直接在传入定义上过滤流转
        return definition.getTransitions().stream().filter(t -> t.getSourceState().equals(sourceState) && t.getEvent().equals(eventId)).findFirst().orElse(null);
    }
}