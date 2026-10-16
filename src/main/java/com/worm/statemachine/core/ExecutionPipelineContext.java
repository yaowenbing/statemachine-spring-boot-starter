package com.worm.statemachine.core;

import lombok.Data;

/**
 * 状态机执行管道上下文。
 *
 * <p>贯穿整个执行管道的生命周期，承载从事件触发到状态持久化的全部中间数据。
 * 管道各步骤（解析、触发、持久化）通过读写此上下文进行协作。</p>
 */
@Data
public class ExecutionPipelineContext {

    /** 业务编码，标识状态机类型 */
    private final String machineCode;

    /** 业务标识，标识状态机实例 */
    private final String bizId;

    /** 触发事件标识 */
    private final String event;

    /** 源状态标识，由解析步骤填充 */
    private String sourceState;

    /** 目标状态标识，由解析步骤填充 */
    private String targetState;

    /** 执行结果，由各步骤按需设置 */
    private StateMachineResult result;

    /**
     * 判断当前管道是否已产生错误。
     *
     * @return true 表示执行结果为失败
     */
    public boolean hasError() {
        return result != null && !result.isSuccess();
    }
}