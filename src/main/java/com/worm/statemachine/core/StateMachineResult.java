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

    /**
     * 失败即抛：调用方无需判空检查 success，失败结果直接炸出异常收敛处理。
     *
     * <p>为什么：调用方拿到失败结果后若继续往下走会掩盖失败原因，需要显式收敛手段强制感知失败；
     * 干什么：success 为 false 时抛出携带 errorMessage 原文的 {@link StateMachineException}，成功时静默返回。</p>
     */
    public void throwIfFailed() {
        // 为什么：success 为 true 时正常流转路径必须零异常穿透 —— 干什么：成功结果直接返回不干预
        if (success) {
            return;
        }
        // 为什么：异常消息必须携带 errorMessage 原文供调用方透出失败原因，不加前缀包装 —— 干什么：失败时抛出 StateMachineException
        throw new StateMachineException(errorMessage);
    }

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