package com.worm.statemachine.registry;

import com.worm.statemachine.core.IDistributedLock;
import com.worm.statemachine.core.IEvent;
import com.worm.statemachine.core.StateMachineDefinition;
import com.worm.statemachine.core.StateMachineResult;
import com.worm.statemachine.persist.InMemoryStatePersisterStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED 测试 —— StateMachineTemplate 锁等待超时可配置（Feature 5）。
 *
 * <p>干什么：以 200ms 超时场景构造模板（plan 5.2 钉死实例字段为 {@code lockTimeoutSeconds} 秒粒度，
 * 200ms 经 TimeUnit 换算截断为 0s，即"占用期间立即超时失败"语义），线程 A 经测试替身锁持有
 * {@code machineCode:bizId} 对应锁并睡眠 1s，主线程对同 key 调 {@code sendEvent}，
 * 期望在持有者释放（1s）之前返回失败结果且 {@code errorMessage} 含超时信息。</p>
 *
 * <p>为什么：模板当前把锁等待超时硬编码为 {@code LOCK_TIMEOUT_SECONDS = 10}，
 * 目标契约要求超时跟随模板实例（4 参构造）传入，使不同业务可配置各自的锁等待时长，
 * 并保证同 bizId 并发请求在持锁期间按注入超时快速失败而非长时间阻塞。</p>
 *
 * <p>RED 纪律：本阶段只写测试不改主代码，当前模板仅有 3 参构造（registry、persister、lock），
 * 本测试在 4 参构造处因"无适用构造器"编译失败，由下一任务（5.2）实现 4 参构造与实例字段后转绿。</p>
 */
class StateMachineTemplateTimeoutTest {

    /**
     * 目标超时场景：200ms。
     *
     * <p>为什么：任务钉死「以 200ms 超时构造模板」场景，而 plan 5.2 钉死实例字段名为
     * {@code lockTimeoutSeconds}（秒粒度、默认 10），200ms 无法以整秒精确表达，
     * 经 {@link TimeUnit#toSeconds} 换算为 0s（即 tryLock 不等待、占用期间立即返回 false），
     * 耗时断言按简报放宽为"持有者释放之前（&lt;1s）"，仍可有效区分
     * "实例超时生效"与"回退 10s 常量"两种实现。</p>
     * <p>干什么：记录目标超时的毫秒值，构造模板时换算为秒传入。</p>
     */
    private static final long LOCK_TIMEOUT_MILLIS = 200L;

    /**
     * 持锁线程 A 的持有时长：1s。
     *
     * <p>为什么：持有时长必须远大于超时场景（200ms），保证主线程 sendEvent 必然在占用期间
     * 超时失败，不受持有者释放时机的竞争影响；1s 也是 plan 钉死的编排值。</p>
     * <p>干什么：记录持有线程的持锁时长，同时作为耗时断言的上界。</p>
     */
    private static final long HOLDER_HOLD_MILLIS = 1000L;

    /**
     * 测试用事件枚举。
     *
     * <p>为什么：sendEvent 枚举入口按 getEventId() 解析事件标识，测试需提供与流转定义
     * event 严格匹配的事件枚举；干什么：定义 START 常量（getEventId() 返回 name() 即 "START"），
     * 对应定义中 PENDING -START-&gt; RUNNING 流转（超时路径不触达管道，仅保证构造形态完整）。</p>
     */
    private enum TestEvent implements IEvent {

        /** 启动事件：事件标识 "START" */
        START;

        @Override
        public String getEventId() {
            // 为什么：任务约定 getEventId() 返回 name()，保证事件标识与枚举常量一一对应 —— 干什么：返回枚举常量名 "START"
            return name();
        }

        @Override
        public String getDescription() {
            // 为什么：IEvent 契约要求描述非空，仅供日志与展示，不参与流转匹配 —— 干什么：返回固定中文描述
            return "测试启动事件";
        }
    }

    /**
     * 测试用单槽可占用锁替身。
     *
     * <p>为什么：需要模拟「线程 A 持有、主线程争用超时」的确定性编排（真实锁实现依赖线程调度时机
     * 无法钉死行为），tryLock 必须诚实遵循调用方传入的 waitTime 语义——占用期间最多等待
     * waitTime，超时返回 false，与 {@code ReentrantLock.tryLock(long, TimeUnit)} 对齐。</p>
     * <p>干什么：以 volatile 占用标志模拟单 key 锁槽位，用 CountDownLatch 向主线程发出"已占用"信号。</p>
     */
    private static final class OccupiableTestLock implements IDistributedLock {

        /**
         * 为什么：占用标志被持有线程与争用线程跨线程共享，必须立即可见避免脏读 —— 干什么：volatile 布尔模拟单槽锁的占用状态
         */
        private volatile boolean occupied = false;

        /**
         * 为什么：主线程必须在确认"持有线程已占用"后才能发起争用，否则存在主线程先抢到锁的编排竞态 —— 干什么：CountDownLatch 一次性信号表示"槽位已被占用"
         */
        private final CountDownLatch occupiedLatch = new CountDownLatch(1);

        /**
         * 尝试获取单槽锁。
         *
         * <p>为什么：替身必须诚实遵循调用方传入的 waitTime 语义（占用期间最多等待 waitTime，
         * 超时返回 false），否则耗时断言无法验证模板把实例超时透传给 tryLock ——
         * 干什么：按 waitTime 计算截止时间，空闲则占用成功，占用中则轮询至截止后返回 false。</p>
         */
        @Override
        public boolean tryLock(String key, long waitTime, TimeUnit unit) throws InterruptedException {
            // 为什么：以截止时间而非累计睡眠计时，保证 waitTime=0 时绝不等待 —— 干什么：由 waitTime 换算纳秒截止时刻
            long deadlineNanos = System.nanoTime() + unit.toNanos(waitTime);
            while (true) {
                // 为什么：槽位空闲即可直接获取，无需等待 —— 干什么：置位占用标志、发出占用信号并返回 true
                if (!occupied) {
                    // 为什么：必须先置位再发信号，保证等待方被唤醒时占用状态已可见 —— 干什么：写入 volatile 占用标志
                    occupied = true;
                    // 为什么：本测试编排中首个获取者恒为线程 A，发信号即表示"A 已持锁" —— 干什么：countDown 通知等待中的主线程
                    occupiedLatch.countDown();
                    // 为什么：获取成功语义对齐 ReentrantLock.tryLock —— 干什么：返回 true
                    return true;
                }
                // 为什么：槽位被占且已到截止时刻，对齐 tryLock"超时返回 false 不抛异常"语义 —— 干什么：返回 false 表示获取失败
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                // 为什么：占用期间轮询等待需让出 CPU，避免忙等空转 —— 干什么：小步睡眠 5ms 后重试
                TimeUnit.MILLISECONDS.sleep(5);
            }
        }

        /**
         * 释放单槽锁。
         *
         * <p>为什么：替身仅管理单槽位且不感知 key 拼接逻辑（key 按接口契约透传），
         * 释放即恢复槽位空闲 —— 干什么：清除 volatile 占用标志。</p>
         */
        @Override
        public void unlock(String key) {
            // 为什么：单槽语义下释放动作就是清标志，持有者调用后槽位对新争用者可见为空闲 —— 干什么：清除占用标志
            occupied = false;
        }

        /**
         * 等待"槽位已被占用"信号。
         *
         * <p>为什么：主线程必须等持有线程完成占用后才能发起 sendEvent 争用，
         * 保证编排确定性（不存在主线程先抢到锁的窗口）—— 干什么：限时等待占用信号并返回等待结果。</p>
         */
        boolean awaitOccupied(long timeout, TimeUnit unit) throws InterruptedException {
            // 为什么：复用 CountDownLatch 的限时等待能力，超时返回 false 由调用方断言失败 —— 干什么：返回 await 限时等待结果
            return occupiedLatch.await(timeout, unit);
        }
    }

    /**
     * 断言：持锁线程占用期间，同 bizId 并发 sendEvent 在注入超时内返回失败结果且 errorMessage 含超时信息。
     */
    @Test
    void sendEventShouldReturnTimeoutFailureWhileLockHeld() throws Exception {
        // 为什么：注册表是模板按 machineCode 路由定义的唯一来源，超时路径虽不触达管道仍按既有测试形态构造完整依赖 —— 干什么：实例化 StateMachineRegistry
        StateMachineRegistry registry = new StateMachineRegistry();
        // 为什么：编程式构造与 YAML 等价的最小定义，保证模板构造形态与既有用例一致 —— 干什么：新建 StateMachineDefinition
        StateMachineDefinition definition = new StateMachineDefinition();
        // 为什么：machineCode 与 sendEvent 入参一致，注册后定义才对模板可见 —— 干什么：设置 machineCode 为 "m"
        definition.setMachineCode("m");
        // 为什么：bizId 无历史状态时 resolve 以 initialState 作为源状态出发（仅回归兜底，超时路径不触达） —— 干什么：设置 initialState 为 "PENDING"
        definition.setInitialState("PENDING");
        // 为什么：定义需与真实形态一致（至少一条流转），保证若锁意外获取成功也能走完整管道 —— 干什么：登记 PENDING -START-> RUNNING 一条流转
        definition.setTransitions(List.of(new StateMachineDefinition.TransitionDefinition("PENDING", "START", "RUNNING")));
        // 为什么：未登记的定义对模板不可见 —— 干什么：按 machineCode "m" 注册定义
        registry.register("m", definition);
        // 为什么：测试场景不依赖数据库，用内存策略承接状态读写 —— 干什么：实例化 InMemoryStatePersisterStrategy
        InMemoryStatePersisterStrategy persister = new InMemoryStatePersisterStrategy();
        // 为什么：锁替身必须被持有线程与主线程共享，才能构成「A 持有、主线程争用」的确定性编排 —— 干什么：实例化 OccupiableTestLock
        OccupiableTestLock testLock = new OccupiableTestLock();
        // 为什么：plan 5.2 钉死实例字段为 lockTimeoutSeconds（秒粒度），200ms 经 TimeUnit 换算截断为 0s，语义为"占用期间立即超时失败" —— 干什么：把 200ms 换算为秒
        long lockTimeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(LOCK_TIMEOUT_MILLIS);
        // 为什么：目标契约是超时跟随模板实例（4 参构造），当前主代码仅有 3 参构造，RED 预期本行编译失败报"无适用构造器" —— 干什么：以 (registry, persister, testLock, lockTimeoutSeconds) 构造 StateMachineTemplate
        StateMachineTemplate template = new StateMachineTemplate(registry, persister, testLock, lockTimeoutSeconds);
        // 为什么：模板内部按 machineCode + ":" + bizId 拼接锁 key，持有线程必须占用同一 key 才能构成同 bizId 争用 —— 干什么：定义 ("m","1") 对应的锁 key
        String lockKey = "m:1";
        // 为什么：持有线程先占用锁再由主线程争用；lambda 无法抛受检异常，InterruptedException 需在内部收敛 —— 干什么：定义持有线程 A（占用 → 睡眠 1s → 释放）
        Thread holderThread = new Thread(() -> {
            try {
                // 为什么：A 在主线程发起争用前占用，此刻槽位必然空闲，tryLock 立即成功 —— 干什么：以 0 等待时长调用替身 tryLock 占用锁
                boolean acquired = testLock.tryLock(lockKey, 0, TimeUnit.SECONDS);
                // 为什么：仅成功占用才执行 1s 持有，编排要么"持锁 1s"要么"未占用（主线程断言占用信号失败）" —— 干什么：获取成功后睡眠并释放
                if (acquired) {
                    // 为什么：持有时长必须覆盖主线程的全部超时等待窗口，保证争用必然发生在占用期间 —— 干什么：睡眠 1s 模拟业务持锁执行中
                    TimeUnit.MILLISECONDS.sleep(HOLDER_HOLD_MILLIS);
                    // 为什么：占用期满必须释放，恢复槽位空闲并保持替身状态干净 —— 干什么：调用替身 unlock 释放锁
                    testLock.unlock(lockKey);
                }
            } catch (InterruptedException e) {
                // 为什么：被中断时按约定恢复中断标记，不吞掉中断信号 —— 干什么：中断当前线程
                Thread.currentThread().interrupt();
            }
        }, "lock-holder-thread");
        // 为什么：编排要求持有线程先启动并完成占用，主线程才能发起争用 —— 干什么：启动线程 A
        holderThread.start();
        // 为什么：主线程必须等"A 已持锁"信号后才能 sendEvent，否则存在主线程先抢到锁的竞态；超时说明编排失败应立即报错 —— 干什么：限时 5s 等待占用信号并断言成功
        assertThat(testLock.awaitOccupied(5, TimeUnit.SECONDS)).as("持有线程应在 5s 内完成占锁").isTrue();
        // 为什么：耗时断言只应度量 sendEvent 自身，编排准备开销不应计入 —— 干什么：记录 sendEvent 起始纳秒时刻
        long startNanos = System.nanoTime();
        // 为什么：目标行为是同 bizId 并发 sendEvent 在占用期间按注入超时返回失败结果 —— 干什么：主线程对同 key ("m","1") 发送 START 事件
        StateMachineResult result = template.sendEvent("m", "1", TestEvent.START);
        // 为什么：耗时上界断言需要 sendEvent 的实际耗时数据 —— 干什么：计算 sendEvent 耗时（毫秒）
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        // 为什么：锁超时场景的目标契约是失败而非成功，附带 errorMessage 便于失败时直接定位 —— 干什么：断言结果为失败
        assertThat(result.isSuccess()).as("持锁占用期间 sendEvent 应返回失败，errorMessage=%s", result.getErrorMessage()).isFalse();
        // 为什么：以模板既有锁失败分支文案为准（"concurrent execution timeout for bizId: ..."），失败消息必须携带超时信息 —— 干什么：断言 errorMessage 含 "timeout"
        assertThat(result.getErrorMessage()).as("errorMessage 应含超时信息，实际=%s", result.getErrorMessage()).contains("timeout");
        // 为什么：失败结果由 failed(machineCode, ...) 工厂构造，应携带业务标识以钉死结果归属 —— 干什么：断言 machineCode 为 "m"
        assertThat(result.getMachineCode()).as("失败结果应携带 machineCode=m，实际=%s", result.getMachineCode()).isEqualTo("m");
        // 为什么：按简报放宽为"持有者释放之前（<1s）返回失败"，若实现回退 10s 常量则耗时约 10s 必触发本断言失败 —— 干什么：断言耗时小于持有者持有时长
        assertThat(elapsedMillis).as("应在持有者释放前超时返回失败，实际耗时=%sms", elapsedMillis).isLessThan(HOLDER_HOLD_MILLIS);
        // 为什么：避免仍在睡眠的持有线程泄漏影响同线程池的其他测试，限时 join 保证异常场景不会无限阻塞 —— 干什么：限时 5s 等待线程 A 结束
        holderThread.join(TimeUnit.SECONDS.toMillis(5));
    }
}
