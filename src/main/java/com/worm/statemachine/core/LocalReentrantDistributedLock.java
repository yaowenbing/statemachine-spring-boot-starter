package com.worm.statemachine.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于进程内 {@link ReentrantLock} 的 {@link IDistributedLock} 实现。
 *
 * <p>干什么：在引入 Redisson（跨 JVM 分布式锁）之前或不可用时，作为降级实现，
 * 用进程内的可重入锁保证<strong>单实例</strong>内同一业务实体（{@code machineCode:bizId}）的状态变更串行执行。</p>
 *
 * <p>为什么：对齐 {@code StateMachineTemplate} 现用的 {@code ReentrantLock} 语义
 * （{@code tryLock(LOCK_TIMEOUT_SECONDS, SECONDS)} / {@code unlock()}），使无 Redisson 时
 * 状态机的并发安全行为保持不变，同时为 0b.4 的探测与切换提供可插拔的实现选择。</p>
 *
 * <p>注意：本实现仅在单个 JVM 进程内有效，<strong>不</strong>跨实例互斥；
 * 多实例部署若需跨 JVM 互斥，由业务方直接引入 {@code redisson-plus-spring-boot-starter} 并按其文档使用，不再经由 statemachine 中转。</p>
 *
 * @author worm
 */
@Slf4j
public class LocalReentrantDistributedLock implements IDistributedLock {

    /**
     * 按 key 维护各自独立的锁对象。
     *
     * <p>为什么：锁的粒度是 {@code machineCode:bizId} 字符串（key），不同 key 必须持有各自独立的
     * {@link ReentrantLock} 才能保证细粒度互斥，避免不同业务实体之间互相阻塞——
     * 这正是决策 11 要求"按 key 维护各自独立锁对象"的原因。</p>
     * <p>干什么：以 {@code computeIfAbsent} 惰性创建并缓存每个 key 对应的锁；key 的结构即
     * 调用方传入的 {@code machineCode:bizId} 字符串，本类不感知拼接逻辑。</p>
     */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 尝试获取指定 key 的进程内锁。
     *
     * <p>语义对齐 {@link ReentrantLock#tryLock(long, TimeUnit)}：
     * 在 {@code waitTime} 内持续尝试，成功返回 true，超时返回 false，不抛异常。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}）
     * @param waitTime 最多等待获取锁的时间
     * @param unit waitTime 的时间单位
     * @return true 表示成功获取锁；false 表示超时未获取
     * @throws InterruptedException 若等待获取锁期间当前线程被中断
     */
    @Override
    public boolean tryLock(String key, long waitTime, TimeUnit unit) throws InterruptedException {
        // 为什么：按 key 惰性创建独立锁对象，保证不同 key 互不阻塞 —— 干什么：获取对应锁并在 waitTime 内尝试加锁，结果直接透传
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        return lock.tryLock(waitTime, unit);
    }

    /**
     * 释放指定 key 的进程内锁。
     *
     * <p>安全释放：仅当锁存在且<strong>当前线程持有</strong>该锁时才释放，
     * 未持锁或 key 不存在都不抛 {@link IllegalMonitorStateException}。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}）
     */
    @Override
    public void unlock(String key) {
        // 为什么：必须校验 isHeldByCurrentThread，否则未持锁调用 unlock 会抛 IllegalMonitorStateException —— 干什么：仅在当前线程持有锁时安全释放
        ReentrantLock lock = locks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        } else {
            log.debug("跳过释放：key={} 无锁或当前线程未持有锁", key);
        }
    }
}
