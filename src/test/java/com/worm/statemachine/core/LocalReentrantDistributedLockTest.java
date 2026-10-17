package com.worm.statemachine.core;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED 测试 —— LocalReentrantDistributedLock 释放后应清理内部锁条目（plan Step 6.1）。
 *
 * <p>干什么：用例一串行对 100 个不同 key 各执行一次 {@code tryLock/unlock}，
 * 随后反射读取内部 {@code locks} Map 并断言其 {@code size()} 归零，钉死
 * 「释放后锁条目必须被清理」的契约；用例二让两个线程对同一 key 并发争用，
 * 以守卫计数校验无重叠临界区，证明清理行为不得破坏同 key 互斥。</p>
 *
 * <p>为什么：现实现 {@code unlock} 从不移除条目（{@code computeIfAbsent} 只增不减），
 * Map 随业务 key 数量无界增长，构成资源泄漏；本测试在现实现下于用例一的 size 断言处转红
 * （期望 0 实际 100），由下一任务实现条目清理后转绿，且用例二在现实现下即应通过
 * （现实现的互斥语义正确，失败点只允许出现在用例一）。</p>
 *
 * @author worm
 */
class LocalReentrantDistributedLockTest {

    /**
     * 断言：串行加放 100 个不同 key 后，内部锁 Map 条目数应归零（释放即清理）。
     */
    @Test
    void unlockShouldRemoveLockEntrySoInternalMapBecomesEmpty() throws Exception {
        // 为什么：用例需独立实例，避免与其他用例共享内部 Map 状态污染 size 断言 —— 干什么：实例化被测的本地可重入锁
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：plan 钉死「串行加放 100 个不同 key」的契约场景 —— 干什么：循环 100 轮，以索引构造互不相同的 key
        for (int i = 0; i < 100; i++) {
            // 为什么：key 语义为调用方传入的 machineCode:bizId 字符串，被测类不感知拼接逻辑 —— 干什么：构造第 i 个业务 key
            String key = "machineCode:" + i;
            // 为什么：串行无争用下 tryLock 必须立即成功，获取失败属锁语义损坏而非清理问题 —— 干什么：以 1 秒等待加锁并断言成功
            assertThat(lock.tryLock(key, 1, TimeUnit.SECONDS)).as("串行场景 tryLock 应成功，key=" + key).isTrue();
            // 为什么：加放一一配对是「释放后条目被清理」契约的前提编排 —— 干什么：立即释放当前 key
            lock.unlock(key);
        }
        // 为什么：内部 locks Map 是私有实现细节，条目清理行为只能经反射观察 —— 干什么：获取 locks 字段对象
        Field locksField = LocalReentrantDistributedLock.class.getDeclaredField("locks");
        // 为什么：私有字段默认拒绝外部访问，反射读取前必须开放可达性 —— 干什么：开启字段可访问标志
        locksField.setAccessible(true);
        // 为什么：GREEN 阶段 Map 值类型将变为 LockEntry，用 Map<?,?> + size() 在 RED/GREEN 两态都成立 —— 干什么：读取字段值并以未知值类型 Map 使用
        Map<?, ?> internalLocks = (Map<?, ?>) locksField.get(lock);
        // 为什么：目标契约是释放后锁条目应被清理（Map 归零），现实现从不移除条目，RED 在此处失败 —— 干什么：断言内部锁 Map 的条目数为 0
        assertThat(internalLocks).isEmpty();
    }

    /**
     * 断言：同 key 两线程并发 tryLock/unlock 保持互斥（无重叠临界区、每次获取均成功），
     * 即清理行为落地时不得破坏既有互斥语义。
     */
    @Test
    void concurrentSameKeyShouldRemainMutuallyExclusive() throws Exception {
        // 为什么：并发用例需独立实例，内部状态不与用例一耦合 —— 干什么：实例化被测的本地可重入锁
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：两线程必须争用同一 key 才构成同 key 互斥场景 —— 干什么：定义共享争用的 key
        String key = "machineCode:concurrent";
        // 为什么：plan 钉死两线程各循环多次（50 次）以放大竞态窗口 —— 干什么：定义每线程循环次数为 50
        int loopsPerThread = 50;
        // 为什么：守卫计数被两线程跨线程读写，必须立即可见避免脏读 —— 干什么：用 AtomicInteger 记录当前处于临界区的线程数
        AtomicInteger active = new AtomicInteger(0);
        // 为什么：临界区重叠（互斥破坏）不能在子线程内直接失败，需累计后由主线程统一断言 —— 干什么：用 AtomicInteger 记录互斥违例次数
        AtomicInteger violations = new AtomicInteger(0);
        // 为什么：编排要求每次 tryLock 都成功，获取失败同样是缺陷需纳入断言 —— 干什么：用 AtomicInteger 记录获取失败次数
        AtomicInteger acquireFailures = new AtomicInteger(0);
        // 为什么：两线程必须同时起跑，消除一方先跑完导致临界区窗口不重叠的编排竞态 —— 干什么：用 CountDownLatch 同步起跑
        CountDownLatch startLatch = new CountDownLatch(1);
        // 为什么：主线程必须等两线程结束后才能断言计数器，否则会读到未写完的中间值 —— 干什么：用 CountDownLatch 等待两线程完成
        CountDownLatch doneLatch = new CountDownLatch(2);
        // 为什么：线程体被两线程复用且 lambda 无法抛受检异常，InterruptedException 需在内部收敛 —— 干什么：定义工作逻辑（等起跑 → 循环 tryLock/守卫校验/unlock）
        Runnable worker = () -> {
            try {
                // 为什么：同步起跑必须等待发令信号，保证两线程临界区窗口真实重叠 —— 干什么：阻塞等待起跑信号
                startLatch.await();
                // 为什么：对同一 key 反复争用多次，放大竞态窗口覆盖互斥语义 —— 干什么：循环 50 次执行加锁与释放
                for (int i = 0; i < loopsPerThread; i++) {
                    // 为什么：5 秒等待给足，避免线程调度偶发超时造成假失败 —— 干什么：以 5 秒等待时长调用 tryLock
                    boolean acquired = lock.tryLock(key, 5, TimeUnit.SECONDS);
                    // 为什么：获取失败与互斥违例是不同缺陷项，分开计数便于定位 —— 干什么：获取失败时递增失败计数并跳过本轮
                    if (!acquired) {
                        acquireFailures.incrementAndGet();
                    } else {
                        // 为什么：进入临界区先递增再立即读，可验证此刻无其他线程仍在临界区内 —— 干什么：递增守卫计数并校验其为 1
                        if (active.incrementAndGet() != 1) {
                            // 为什么：active 不为 1 即说明两线程临界区重叠，互斥语义被破坏 —— 干什么：记录一次互斥违例
                            violations.incrementAndGet();
                        }
                        // 为什么：退出临界区前递减，保证计数器准确反映临界区内线程数 —— 干什么：递减守卫计数
                        active.decrementAndGet();
                        // 为什么：加放一一配对，保证锁对下一轮及另一线程处于可获取状态 —— 干什么：释放同一 key
                        lock.unlock(key);
                    }
                }
            } catch (InterruptedException e) {
                // 为什么：被中断时按约定恢复中断标记不吞掉信号，编排异常将由后续断言暴露 —— 干什么：中断当前线程
                Thread.currentThread().interrupt();
            } finally {
                // 为什么：无论正常或异常退出都必须放行主线程等待，避免测试无限阻塞 —— 干什么：发出完成信号
                doneLatch.countDown();
            }
        };
        // 为什么：需要两个并发线程才能构成同 key 争用 —— 干什么：创建线程 A
        Thread threadA = new Thread(worker, "lock-worker-a");
        // 为什么：需要两个并发线程才能构成同 key 争用 —— 干什么：创建线程 B
        Thread threadB = new Thread(worker, "lock-worker-b");
        // 为什么：先启动线程使其进入等待，起跑信号才对双方同时生效 —— 干什么：启动线程 A
        threadA.start();
        // 为什么：先启动线程使其进入等待，起跑信号才对双方同时生效 —— 干什么：启动线程 B
        threadB.start();
        // 为什么：编排失败（线程未就绪等）应经限时暴露而非无限等待 —— 干什么：释放起跑信号
        startLatch.countDown();
        // 为什么：主线程必须等两线程写完计数器再断言，且编排异常应限时失败 —— 干什么：限时 30 秒等待两线程完成
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).as("工作线程应在 30 秒内完成").isTrue();
        // 为什么：避免异常场景下工作线程泄漏影响同线程池的其他测试 —— 干什么：限时 join 线程 A
        threadA.join(TimeUnit.SECONDS.toMillis(5));
        // 为什么：避免异常场景下工作线程泄漏影响同线程池的其他测试 —— 干什么：限时 join 线程 B
        threadB.join(TimeUnit.SECONDS.toMillis(5));
        // 为什么：plan 编排要求每次 tryLock 都成功，失败即说明锁语义或调度被破坏 —— 干什么：断言无获取失败
        assertThat(acquireFailures.get()).isZero();
        // 为什么：目标契约是同 key 并发无重叠临界区（互斥不得被清理行为破坏） —— 干什么：断言无互斥违例
        assertThat(violations.get()).isZero();
    }
}
