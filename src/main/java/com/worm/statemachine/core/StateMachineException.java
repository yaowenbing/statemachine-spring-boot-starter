package com.worm.statemachine.core;

/**
 * 状态机业务失败异常。
 *
 * <p>为什么：调用方需要一种"结果不判空、失败直接炸"的显式收敛手段，失败结果经
 * {@link StateMachineResult#throwIfFailed()} 抛出本异常，把失败原因以异常语义向上透出；
 * 干什么：承载状态机事件发送失败的 errorMessage，继承 RuntimeException 使调用方免于强制声明受检异常。</p>
 */
public class StateMachineException extends RuntimeException {

    /**
     * 使用失败原因原文构造异常。
     *
     * @param message 失败原因（errorMessage 原文，不加前缀包装）
     */
    public StateMachineException(String message) {
        // 为什么：异常消息必须原样携带 errorMessage，调用方凭它向用户透出失败原因 —— 干什么：将 message 原文透传给 RuntimeException
        super(message);
    }
}
