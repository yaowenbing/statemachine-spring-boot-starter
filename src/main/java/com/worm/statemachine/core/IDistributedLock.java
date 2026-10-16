package com.worm.statemachine.core;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁抽象接口。
 *
 * <p>将"获取锁"和"释放锁"抽象为接口，使状态机核心层与具体的锁实现
 * （ReentrantLock / Redis 锁 / Zookeeper 锁 等）完全解耦。
 * 业务方 MUST 通过实现此接口提供具体的分布式锁逻辑（见 0b.2/0b.3 实现）。</p>
 *
 * <p>接口语义对齐 {@link java.util.concurrent.locks.ReentrantLock}：
 * 获取锁失败（等待超时或中断）时不抛异常，而是由 {@code tryLock} 返回 false；
 * 释放锁统一经 {@code unlock} 委托给具体实现，调用方不再直接操作锁对象。</p>
 *
 * <p>{@code key} 的语义为锁粒度，调用方传入的值为 {@code machineCode:bizId}，
 * 接口本身不感知拼接逻辑，仅透传 key 字符串给具体实现。</p>
 *
 * @author worm
 */
public interface IDistributedLock {

    /**
     * 尝试获取指定 key 的锁。
     *
     * <p>语义对齐 {@link java.util.concurrent.locks.ReentrantLock#tryLock(long, java.util.concurrent.TimeUnit)}：
     * 在 {@code waitTime} 时间内持续尝试获取锁，成功返回 true，
     * 超时仍未获取成功则返回 false（不抛异常）。</p>
     *
     * <p>若等待期间当前线程被中断，抛出 {@link InterruptedException}（与 ReentrantLock.tryLock 一致）。
     * 接口本身不感知 key 的拼接逻辑，仅透传调用方传入的 {@code machineCode:bizId} 字符串。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}），接口仅透传
     * @param waitTime 最多等待获取锁的时间
     * @param unit waitTime 的时间单位
     * @return true 表示成功获取锁；false 表示超时未获取
     * @throws InterruptedException 若等待获取锁期间当前线程被中断
     */
    boolean tryLock(String key, long waitTime, TimeUnit unit) throws InterruptedException;

    /**
     * 释放指定 key 的锁。
     *
     * <p>语义对齐锁的释放语义：释放操作必须由锁的持有方调用，
     * 统一经此方法委托给具体实现（不再由调用方直接操作 ReentrantLock 等锁对象）。
     * 接口本身不感知 key 的拼接逻辑，仅透传调用方传入的 {@code machineCode:bizId} 字符串。</p>
     *
     * @param key 锁的标识（调用方传入 {@code machineCode:bizId}），接口仅透传
     */
    void unlock(String key);
}
