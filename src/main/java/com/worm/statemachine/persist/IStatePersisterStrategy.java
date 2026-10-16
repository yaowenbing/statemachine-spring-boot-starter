package com.worm.statemachine.persist;

/**
 * 状态持久化策略接口。
 *
 * <p>将"读取数据库状态"和"落库最新状态"抽象为策略模式，使状态机核心层
 * 完全不感知任何 ORM 框架（MyBatis-Plus / JPA / JDBC 等）。
 * 业务方 MUST 实现此接口提供具体的数据库读写逻辑。</p>
 *
 * <p>接口签名统一使用 String 类型标识（stateId），不使用泛型。
 * 原因：① 这是 ORM 无关层，不应感知业务枚举类型；
 * ② String 是最通用的标识，Executor 内部负责 String ↔ 枚举的转换；
 * ③ 多策略路由（supports）在 String 层面即可判定，无需泛型。</p>
 *
 * @author worm
 */
public interface IStatePersisterStrategy {

    /**
     * 读取数据库中的当前状态。
     *
     * <p>执行器在每次事件发送前调用此方法，从数据库获取业务对象的真实状态，
     * 用于恢复到状态机实例。这是状态恢复的 Source of Truth。</p>
     *
     * @param machineCode 业务编码
     * @param bizId 业务标识
     * @return 当前状态标识（String 类型的 stateId），若数据库无记录则返回 null
     */
    String readCurrentState(String machineCode, String bizId);

    /**
     * 将新状态写入数据库。
     *
     * <p>状态机完成一次有效流转后，执行器调用此方法将目标状态持久化到数据库。
     * 此操作与业务事务解耦——由状态机执行器自身的事务管理。</p>
     *
     * @param machineCode 业务编码
     * @param bizId 业务标识
     * @param newStateId 新状态标识（String 类型的 stateId）
     */
    void writeNewState(String machineCode, String bizId, String newStateId);

    /**
     * 条件更新状态（并发安全落库）。
     *
     * <p>为什么：引擎层虽有 bizId 维度的分布式锁，但仍需 DB 层兜底，
     * 防止极端并发（锁失效/跨实例）下出现状态覆盖。
     * 干什么：仅当数据库中该实体的当前状态等于 fromState 时才更新为 toState，
     * 并返回受影响行数；影响行数 = 0 表示状态已被并发推进（fromState 不再匹配），流转被抢。</p>
     *
     * @param machineCode 业务编码
     * @param bizId 业务标识
     * @param fromState 期望的源状态（WHERE 条件）
     * @param toState 目标新状态
     * @return 受影响行数（>0 表示成功；0 表示条件不满足/被并发抢走）
     */
    default int writeNewState(String machineCode, String bizId, String fromState, String toState) {
        // 为什么：向后兼容默认实现——旧策略未覆盖条件更新时退化为原无条件落库并返回 1
        // 干什么：委托 3-arg 落库并固定返回 1 表示成功
        writeNewState(machineCode, bizId, toState);
        return 1;
    }

    /**
     * 声明此策略负责的业务编码范围。
     *
     * <p>RoutingStatePersisterStrategy 按此方法路由到具体实现。
     * 每个已注册的 machineCode MUST 有且仅有一个策略实现返回 true。</p>
     *
     * @param machineCode 业务编码
     * @return true 表示此策略负责该 machineCode
     */
    boolean supports(String machineCode);

    /**
     * 标识此策略是否为通用策略（支持所有 machineCode）。
     *
     * <p>通用策略在路由时优先级低于业务特定策略。
     * 当同一 machineCode 同时被通用策略和业务策略支持时，优先选择业务策略。</p>
     *
     * @return true 表示此策略为通用策略
     */
    default boolean isUniversal() {
        return false;
    }
}