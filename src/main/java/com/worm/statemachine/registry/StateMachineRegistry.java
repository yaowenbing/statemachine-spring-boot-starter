package com.worm.statemachine.registry;

import com.worm.statemachine.core.StateMachineDefinition;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态机注册中心。
 *
 * <p>统一管理所有业务状态机定义，提供按 machineCode 查询、注册、校验能力。
 * 使用 ConcurrentHashMap 存储，因为注册发生在启动阶段（写少）、查询发生在运行阶段（读多），
 * ConcurrentHashMap 无锁读性能优于同步容器。</p>
 *
 * <p>注册中心注册方式纯粹基于 {@link StateMachineDefinition}（YAML 驱动），
 * 所有业务状态机元数据均在启动时由配置初始化器解析 YAML 后注册，不提供编程式适配器注册。</p>
 *
 * @author worm
 */
public class StateMachineRegistry {

    /**
     * 内部存储：machineCode → 状态机定义。
     * 使用 ConcurrentHashMap 保证并发安全，因为注册发生在启动阶段（写少）、
     * 查询发生在运行阶段（读多），ConcurrentHashMap 无锁读性能优于同步容器。
     */
    private final Map<String, StateMachineDefinition> definitions = new ConcurrentHashMap<>();

    /**
     * 通过定义注册状态机。
     *
     * @param machineCode 业务编码
     * @param definition  状态机定义
     * @throws IllegalArgumentException 若 machineCode 已存在
     */
    public void register(String machineCode, StateMachineDefinition definition) {
        StateMachineDefinition existing = definitions.putIfAbsent(machineCode, definition);
        if (existing != null) {
            throw new IllegalArgumentException("machineCode 已注册，不可重复: " + machineCode);
        }
    }

    /**
     * 获取指定业务编码的状态机定义。
     *
     * @param machineCode 业务编码
     * @return 状态机定义，若不存在则返回 null
     */
    public StateMachineDefinition getDefinition(String machineCode) {
        return definitions.get(machineCode);
    }

    /**
     * 检查指定业务编码是否已注册。
     *
     * @param machineCode 业务编码
     * @return true 若已注册
     */
    public boolean contains(String machineCode) {
        return definitions.containsKey(machineCode);
    }

    /**
     * 获取所有已注册的业务编码。
     *
     * @return 不可变的业务编码集合
     */
    public Set<String> getAllMachineCodes() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

}