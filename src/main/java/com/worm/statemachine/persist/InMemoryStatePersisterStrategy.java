package com.worm.statemachine.persist;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存状态持久化策略，用于测试场景。
 *
 * <p>仅在 {@code test} profile 下激活，注册为 {@code @Primary}。
 * 支持所有 machineCode，作为通用测试策略使用。</p>
 *
 * @author worm
 */
@Slf4j
public class InMemoryStatePersisterStrategy implements IStatePersisterStrategy {

    /**
     * 内存状态存储：machineCode:bizId → stateId。
     *
     * <p>为什么必须用 Map：需要 O(1) 按复合键（machineCode:bizId）读写状态快照；key 必须用复合键而非单 bizId，
     * 否则不同业务域（如 spider_collect_log 与 spider_task_collect）的同名 bizId 状态会互相覆盖。
     * 仅用于测试 profile（@Profile("test")），生产环境由数据库 persister 接管。</p>
     */
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String readCurrentState(String machineCode, String bizId) {
        if (machineCode == null || bizId == null) {
            throw new IllegalArgumentException("machineCode and bizId must not be null");
        }
        return store.get(machineCode + ":" + bizId);
    }

    @Override
    public void writeNewState(String machineCode, String bizId, String newStateId) {
        if (machineCode == null || bizId == null) {
            throw new IllegalArgumentException("machineCode and bizId must not be null");
        }
        store.put(machineCode + ":" + bizId, newStateId);
        log.debug("内存状态更新: machineCode={}, bizId={}, 新状态={}", machineCode, bizId, newStateId);
    }

    @Override
    public boolean supports(String machineCode) {
        return true;
    }

    @Override
    public boolean isUniversal() {
        return true;
    }
}