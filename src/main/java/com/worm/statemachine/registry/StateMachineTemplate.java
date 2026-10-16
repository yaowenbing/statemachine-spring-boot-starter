package com.worm.statemachine.registry;

import com.worm.statemachine.core.*;
import com.worm.statemachine.persist.IStatePersisterStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

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
@RequiredArgsConstructor
public class StateMachineTemplate {

    /**
     * 状态机注册表。
     *
     * <p>干什么：根据 machineCode 获取状态机定义（状态、事件、流转关系）和业务适配器。</p>
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
     * 获取锁的超时时间（秒）。
     *
     * <p>干什么：限制等待锁的最大时长，超时则拒绝执行。</p>
     * <p>为什么：防止某个业务实体的状态变更长时间阻塞后续请求，
     * 超时快速失败比无限等待更利于系统的稳定性和可观测性。</p>
     */
    private static final long LOCK_TIMEOUT_SECONDS = 10;

    /**
     * 发送事件（无上下文），核心入口方法。
     *
     * <p>干什么：接收外部事件，构建执行管道上下文，加锁后驱动管道执行状态流转全流程。</p>
     *
     * <p>为什么：通过 bizId 维度的可重入锁保证同一业务实体的状态变更串行执行，
     * 避免并发场景下状态不一致问题（例如两个请求同时将订单从"待支付"改为"已取消"和"已支付"，
     * 不加锁会导致最终状态不可预测）。纯 YAML 驱动下不再接收任何业务上下文参数。</p>
     *
     * @param machineCode 业务编码，标识状态机类型
     * @param bizId       业务实体ID
     * @param eventId     事件ID
     * @return 状态机执行结果
     */
    public StateMachineResult sendEvent(String machineCode, String bizId, String eventId) {
        log.info("开始发送事件: machineCode={}, bizId={}, eventId={}", machineCode, bizId, eventId);

        ExecutionPipelineContext ctx = new ExecutionPipelineContext(machineCode, bizId, eventId);

        // 锁粒度 machineCode:bizId：跨实例按业务实体唯一互斥，避免不同 machineCode 同 bizId 互相阻塞/误锁
        String lockKey = machineCode + ":" + bizId;
        boolean locked = false;
        try {
            if (!lock.tryLock(lockKey, LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("获取锁超时，并发执行被拒绝: machineCode={}, bizId={}, 超时时间={}s", machineCode, bizId, LOCK_TIMEOUT_SECONDS);
                return StateMachineResult.failed(machineCode, "concurrent execution timeout for bizId: " + bizId);
            }
            // 为什么：仅在真正拿到锁后置位，确保 finally 不会在未持锁路径上误调 unlock —— 干什么：避免切换为会抛 IllegalMonitorStateException 的锁实现（如 Redisson）时崩溃
            locked = true;
            log.debug("成功获取锁: machineCode={}, bizId={}", machineCode, bizId);
            StateMachineResult result = executePipeline(ctx);
            log.info("事件执行完成: machineCode={}, bizId={}, eventId={}, 成功={}", machineCode, bizId, eventId, result.isSuccess());
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

        StateMachineDefinition.TransitionDefinition transition = findTransition(ctx, ctx.getSourceState(), ctx.getEvent());
        if (transition == null) {
            ctx.setResult(StateMachineResult.failed(machineCode, "no transition from state '" + ctx.getSourceState() + "' on event '" + ctx.getEvent() + "'"));
            return;
        }
        ctx.setTargetState(transition.targetState());

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
     * <p>干什么：在状态机定义的流转列表中，查找 sourceState 和 event 均匹配的流转定义。</p>
     * <p>为什么：状态机的核心语义就是"在某个状态下收到某个事件后迁移到目标状态"，
     * 此方法是流转查找的统一入口，被解析步骤复用，避免流转匹配逻辑散落各处。</p>
     */
    private StateMachineDefinition.TransitionDefinition findTransition(ExecutionPipelineContext ctx, String sourceState, String eventId) {
        StateMachineDefinition definition = registry.getDefinition(ctx.getMachineCode());
        if (definition == null) return null;
        return definition.getTransitions().stream().filter(t -> t.sourceState().equals(sourceState) && t.event().equals(eventId)).findFirst().orElse(null);
    }
}