package com.worm.statemachine.core;

/**
 * 通用事件枚举接口。
 *
 * <p>所有业务事件枚举 MUST 实现该接口，使状态机核心层可以统一访问事件标识
 * 和描述信息，而不感知任何业务事件类型。getEventId() MUST NOT 返回 null
 * 或空字符串，确保事件标识在全局范围内可比较和序列化。</p>
 *
 * @author worm
 */
public interface IEvent {

    /**
     * 获取事件唯一标识。
     *
     * <p>返回 String 类型的事件标识，用于跨业务域比较和日志记录。
     * 每个枚举实例的 eventId 必须在同一业务域内唯一。</p>
     *
     * @return 事件标识，MUST NOT 为 null 或空字符串
     */
    String getEventId();

    /**
     * 获取事件人类可读描述。
     *
     * <p>用于日志输出、管理后台展示等非技术性场景。</p>
     *
     * @return 事件描述，MUST NOT 为 null
     */
    String getDescription();
}
