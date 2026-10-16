package com.worm.statemachine.core;

/**
 * 通用状态枚举接口。
 *
 * <p>所有业务状态枚举 MUST 实现该接口，使状态机核心层可以统一访问状态标识、
 * 描述和分组信息，而不感知任何业务枚举类型。getStateId() MUST NOT 返回 null
 * 或空字符串，确保状态标识在全局范围内可比较和序列化。</p>
 *
 * <p>设计为纯接口的理由：业务状态本身是 Java 枚举，枚举只能单继承但可实现多接口，
 * 纯接口提供了最大灵活性。</p>
 *
 * @author worm
 */
public interface IState {

    /**
     * 获取状态唯一标识。
     *
     * <p>返回 String 类型的状态标识，用于跨业务域比较和数据库存储。
     * 每个枚举实例的 stateId 必须在同一业务域内唯一。</p>
     *
     * @return 状态标识，MUST NOT 为 null 或空字符串
     */
    String getStateId();

    /**
     * 获取状态人类可读描述。
     *
     * <p>用于日志输出、管理后台展示等非技术性场景。</p>
     *
     * @return 状态描述，MUST NOT 为 null
     */
    String getDescription();

}
