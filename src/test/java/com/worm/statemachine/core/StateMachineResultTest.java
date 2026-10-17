package com.worm.statemachine.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED 测试 —— StateMachineResult#throwIfFailed 失败即抛异常。
 *
 * <p>为什么：调用方需要一种"结果不判空、失败直接炸"的显式收敛手段，避免拿到失败结果后继续往下走；
 * 干什么：钉死目标契约——失败结果调用 throwIfFailed() 抛出携带 errorMessage 的 StateMachineException，
 * 成功结果调用 throwIfFailed() 静默返回。</p>
 *
 * <p>RED 纪律：本阶段只写测试不改主代码，{@code StateMachineResult#throwIfFailed} 与
 * {@code StateMachineException} 均尚不存在，本测试类应编译失败（cannot find symbol），由下一任务（3.2）实现后转绿。</p>
 */
class StateMachineResultTest {

    /**
     * 断言①：失败结果调用 throwIfFailed() 抛出 StateMachineException，且异常消息携带 errorMessage。
     */
    @Test
    void throwIfFailedShouldThrowWithErrorMessageOnFailedResult() {
        // 为什么：断言①的被测对象是失败结果，工厂 failed(machineCode, errorMessage) 即可构造最小失败场景 —— 干什么：创建 machineCode="m"、errorMessage="boom" 的失败结果
        StateMachineResult result = StateMachineResult.failed("m", "boom");
        // 为什么：目标契约是"失败即抛"，异常类型必须是新增的 StateMachineException 而非 RuntimeException 裸抛 —— 干什么：断言 throwIfFailed() 抛出 StateMachineException
        assertThatThrownBy(result::throwIfFailed)
                // 为什么：异常消息必须携带 errorMessage，调用方凭它向用户透出失败原因 —— 干什么：断言异常消息包含 "boom"
                .isInstanceOf(StateMachineException.class)
                .hasMessageContaining("boom");
    }

    /**
     * 断言②：成功结果调用 throwIfFailed() 静默返回，不抛任何异常。
     */
    @Test
    void throwIfFailedShouldReturnSilentlyOnSuccessResult() {
        // 为什么：断言②的被测对象是成功结果，工厂 success(machineCode, sourceState, event, targetState) 需要四个合法标识 —— 干什么：创建全链路标识齐全的成功结果
        StateMachineResult result = StateMachineResult.success("m", "PENDING", "START", "RUNNING");
        // 为什么：目标契约是"成功不干预"，正常流转路径必须零异常穿透 —— 干什么：断言 throwIfFailed() 不抛任何异常
        assertThatCode(result::throwIfFailed).doesNotThrowAnyException();
    }
}
