package com.worm.statemachine.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于进程内 {@link ReentrantLock} 的 {@link IDistributedLock} 实现。
 *
 * <p>干什么：在引入 Redisson（跨 JVM 分布式锁）之前或不可用时，作为降级实现，
 * 用进程内的可重入锁保证<strong>单实例</strong>内同一业务实体（{@code machineCode:bizId}）的状态变更串行执行；
 * 内部按 key 维护「锁对象 + 引用计数」条目，条目在引用归零且未持锁、无排队等待时自动从 Map 移除，
 * 避免 Map 随业务 key 数量无界增长造成资源泄漏。</p>
 *
 * <p>为什么：对齐 {@code StateMachineTemplate} 现用的 {@code ReentrantLock} 语义
 * （{@code tryLock(long, TimeUnit)} / {@code unlock()}），使无 Redisson 时
 * 状态机的并发安全行为保持不变；而锁条目按业务 key 惰性创建且 key 不可枚举，
 * 若只增不减，长期运行的服务会持续累积失效条目，故「释放即清理」是降级实现必须自带的生命周期管理。</p>
 *
 * <p>注意：本实现仅在单个 JVM 进程内有效，<strong>不</strong>跨实例互斥；
 * 多实例部署若需跨 JVM 互斥，由业务方直接引入 {@code redisson-plus-spring-boot-starter} 并按其文档使用，不再经由 statemachine 中转。</p>
 *
 * @author worm
 */
@Slf4j
public class LocalReentrantDistributedLock implements IDistributedLock {

    /**
     * 按 key 维护各自独立的「锁 + 引用计数」条目。
     *
     * <p>为什么：锁的粒度是 {@code machineCode:bizId} 字符串（key），不同 key 必须持有各自独立的
     * {@link ReentrantLock} 才能保证细粒度互斥，避免不同业务实体之间互相阻塞——
     * 这正是要求"按 key 维护各自独立锁对象"的原因；而条目的「取用（含引用自增）」
     * 与「空闲判定后移除」必须互斥执行，否则清理判定会读到过期引用数、把正被取用的条目移除，
     * 同一 key 将出现两个锁对象并破坏互斥——因此两条路径统一走 {@code ConcurrentHashMap#compute}
     * 的按 key 原子性。</p>
     * <p>干什么：以 {@code compute} 惰性创建并缓存每个 key 对应的 {@link LockEntry}；key 的结构即
     * 调用方传入的 {@code machineCode:bizId} 字符串，本类不感知拼接逻辑。</p>
     */
    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();

    /**
     * 尝试获取指定 key 的进程内锁。
     *
     * <p>语义对齐 {@link ReentrantLock#tryLock(long, TimeUnit)}：
     * 在 {@code waitTime} 内持续尝试，成功返回 true，超时返回 false，不抛异常。
     * 取条目与引用自增在同一次 {@code compute} 内原子完成（防清理竞态的加固点）：
     * 获取失败（含等待中被中断）在 finally 统一释放引用并触发条目清理，
     * 获取成功则引用保留至 {@link #unlock(String)} 释放。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}）
     * @param waitTime 最多等待获取锁的时间
     * @param unit waitTime 的时间单位
     * @return true 表示成功获取锁；false 表示超时未获取
     * @throws InterruptedException 若等待获取锁期间当前线程被中断
     */
    @Override
    public boolean tryLock(String key, long waitTime, TimeUnit unit) throws InterruptedException {
        // 为什么：取条目与引用自增必须在同一次 compute 内原子完成，拆开则条目可能在自增前被并发清理，同 key 换锁对象会破坏互斥 —— 干什么：原子取条目并计入本线程引用（新建条目以引用数 1 起步）
        LockEntry entry = locks.compute(key, (k, existing) -> {
            // 为什么：key 首次使用无既有条目，条目必须出生即带创建者引用才能防住创建后的清理窗口 —— 干什么：以引用数 1 构造新条目
            if (existing == null) {
                return new LockEntry(1);
            }
            // 为什么：既有条目同样必须计入本线程引用，清理判定在此引用释放前不会归零 —— 干什么：对既有条目引用计数原子自增
            existing.refCount.incrementAndGet();
            // 为什么：compute 的返回值即该 key 的映射值，取用与自增在此原子落定 —— 干什么：返回引用加一后的既有条目
            return existing;
        });
        // 为什么：成功与失败的引用释放路径不同（成功保留引用待 unlock 释放，失败须立即释放），需先预置结果标志 —— 干什么：声明加锁结果标志并预置为未获取
        boolean acquired = false;
        try {
            // 为什么：接口语义对齐 ReentrantLock.tryLock，超时返回 false、被中断时 InterruptedException 透传 —— 干什么：在 waitTime 内尝试加锁并记录结果
            acquired = entry.lock.tryLock(waitTime, unit);
            // 为什么：加锁结果即接口返回值，成功时引用保留给 unlock 释放，不在 finally 重复释放 —— 干什么：透传加锁结果
            return acquired;
        } finally {
            // 为什么：获取失败（含等待被中断）者必须立即释放引用，否则条目因引用不归零而永久滞留 Map —— 干什么：仅在未获取成功时执行统一的引用释放与条目清理
            if (!acquired) {
                decRefAndCleanup(key, entry);
            }
        }
    }

    /**
     * 释放指定 key 的进程内锁。
     *
     * <p>安全释放：仅当条目存在且<strong>当前线程持有</strong>该锁时才释放并递减引用，
     * 未持锁或条目已被清理都不抛 {@link IllegalMonitorStateException}；
     * 持锁者引用在此与 {@code tryLock} 的取用自增配对释放，释放后按空闲判定清理条目。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}）
     */
    @Override
    public void unlock(String key) {
        // 为什么：条目可能已被清理，需先按 key 取回本次要释放的条目 —— 干什么：读取当前 key 映射的锁条目
        LockEntry entry = locks.get(key);
        // 为什么：必须校验 isHeldByCurrentThread，否则未持锁调用 unlock 会抛 IllegalMonitorStateException —— 干什么：仅在当前线程持有该条目锁时执行释放
        if (entry != null && entry.lock.isHeldByCurrentThread()) {
            // 为什么：先真正释放锁再清理引用，保证清理判定读到的是释放后的锁状态 —— 干什么：释放可重入锁的一次持有
            entry.lock.unlock();
            // 为什么：持锁者引用在 unlock 时释放，与 tryLock 的取用自增一一配对 —— 干什么：执行统一的引用释放与条目清理
            decRefAndCleanup(key, entry);
        } else {
            // 为什么：未持锁的 unlock 属调用方时序问题，降级为日志便于排查而不中断业务 —— 干什么：debug 日志记录跳过释放
            log.debug("跳过释放：key={} 无锁或当前线程未持有锁", key);
        }
    }

    /**
     * 释放一个引用，并在条目完全空闲时将其从内部 Map 移除。
     *
     * <p>为什么：「引用归零 + 未持锁 + 无排队」的空闲判定若与取用路径并发交错，
     * 可能基于过期引用数移除正被取用的条目，导致同一 key 出现两个锁对象破坏互斥——
     * 故判定与移除统一放进 {@code ConcurrentHashMap#compute}，与取用路径按 key 互斥执行。</p>
     * <p>干什么：先原子自减引用，再在按 key 原子的映射计算中校验
     * 「映射仍指向本条目且引用归零、未持锁、无排队」成立才移除，否则维持现状。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}）
     * @param entry 本次调用方持有引用的锁条目
     */
    private void decRefAndCleanup(String key, LockEntry entry) {
        // 为什么：引用归零是移除的前提条件之一，先自减再做空闲判定 —— 干什么：对本条目引用计数原子自减
        entry.refCount.decrementAndGet();
        // 为什么：清理判定必须与取用路径互斥，否则会基于过期引用数移除正被取用的条目 —— 干什么：在按 key 原子的 compute 中做条件移除
        locks.compute(key, (k, existing) -> {
            // 为什么：仅当映射仍指向本条目、引用归零且未持锁、无排队时才可移除，任一不满足都保留条目 —— 干什么：完全空闲则返回 null 触发移除
            if (existing == entry
                    && entry.refCount.get() == 0
                    && !entry.lock.isLocked()
                    && !entry.lock.hasQueuedThreads()) {
                // 为什么：compute 映射函数返回 null 即原子移除该 key 的映射 —— 干什么：返回 null 移除空闲条目
                return null;
            }
            // 为什么：条目仍被引用、持锁、排队或映射已被替换时必须保留，避免误伤在用条目 —— 干什么：返回既有映射维持不变
            return existing;
        });
    }

    /**
     * 单个 key 的锁条目：聚合该 key 独立的锁对象与存活引用计数。
     *
     * <p>为什么：条目能否移除由「引用归零 + 未持锁 + 无排队」共同决定，
     * 引用计数使正在等待或持有的线程能阻止清理，是防止条目被提前移除破坏互斥的关键；
     * 以私有静态内部类聚合，不新增公共 API 面。</p>
     * <p>干什么：持有该 key 专属的 {@link ReentrantLock} 与 {@link AtomicInteger} 引用计数。</p>
     */
    private static final class LockEntry {

        /**
         * 为什么：不同 key 必须持有独立锁对象才能细粒度互斥 —— 干什么：该 key 专属的可重入锁
         */
        private final ReentrantLock lock = new ReentrantLock();

        /**
         * 为什么：引用计数被持有者/等待者与清理路径跨线程读写，必须原子且立即可见 —— 干什么：记录本条目的存活引用数
         */
        private final AtomicInteger refCount;

        /**
         * 为什么：新建条目必须出生即带创建者引用，杜绝「创建后、自增前」被并发清理的窗口 —— 干什么：以指定初始引用数构造条目
         *
         * @param initialRefCount 创建者占用的初始引用数
         */
        private LockEntry(int initialRefCount) {
            // 为什么：引用计数需以创建者引用为初值原子起步 —— 干什么：按初始引用数构造原子计数器
            this.refCount = new AtomicInteger(initialRefCount);
        }
    }
}
