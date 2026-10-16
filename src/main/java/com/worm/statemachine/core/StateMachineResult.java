package com.worm.statemachine.core;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 状态机执行结果模型。
 *
 * <p>封装一次状态机事件发送的完整结果。所有状态和事件标识统一使用 String
 * 类型，不携带任何业务枚举类型，使结果模型与具体业务解耦。</p>
 *
 * <p>使用 builder 模式构造，因为成功和失败场景的字段不同：
 * 成功时需要 sourceState/targetState，失败时只需要 errorMessage。</p>
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StateMachineResult {

    /** 执行是否成功 */
    private final boolean success;

    /** 业务编码 */
    private final String machineCode;

    /** 源状态标识 */
    private final String sourceState;

    /** 事件标识 */
    private final String event;

    /** 目标状态标识 */
    private final String targetState;

    /** 失败原因（仅成功时为 null） */
    private final String errorMessage;

    /** 自定义扩展上下文 */
    private final String context;

    /**
     * 创建成功的执行结果。
     *
     * @param machineCode 业务编码
     * @param sourceState 源状态
     * @param event 事件
     * @param targetState 目标状态
     * @return 成功结果实例
     */
    public static StateMachineResult success(String machineCode, String sourceState, String event, String targetState) {
        return builder()
                .success(true)
                .machineCode(machineCode)
                .sourceState(sourceState)
                .event(event)
                .targetState(targetState)
                .build();
    }

    /**
     * 创建失败的执行结果。
     *
     * @param machineCode 业务编码
     * @param errorMessage 失败原因
     * @return 失败结果实例
     */
    public static StateMachineResult failed(String machineCode, String errorMessage) {
        return builder()
                .success(false)
                .machineCode(machineCode)
                .errorMessage(errorMessage)
                .build();
    }
}