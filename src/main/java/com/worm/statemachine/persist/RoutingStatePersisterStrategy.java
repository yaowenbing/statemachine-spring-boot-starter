package com.worm.statemachine.persist;

import cn.hutool.core.text.CharSequenceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久化策略路由器，按 machineCode 路由到具体的 IStatePersisterStrategy 实现（自身不落库）。
 *
 * <p>为什么：引擎只持有单一 IStatePersisterStrategy Bean，但系统需要多个具体策略并存 —— 干什么：本类作为路由器，按 machineCode 把读写请求派发给正确的具体策略。
 * 业务特定策略优先于通用策略（isUniversal=true）；当同一 machineCode 同时被通用策略和业务策略支持时，优先选择业务策略。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class RoutingStatePersisterStrategy implements IStatePersisterStrategy {

    private final List<IStatePersisterStrategy> strategies;

    /**
     * 路由缓存：machineCode → 已解析的 IStatePersisterStrategy。
     *
     * <p>为什么必须用 Map：按 machineCode 缓存首次路由结果（O(1) 命中），避免每次状态变更都遍历 strategies 列表重做路由决策；
     * value 不可用 List 替代——需要按稳定 key（machineCode 业务域编码）直接命中唯一匹配的策略。
     * key 仅为单 machineCode（非复合 key），因为路由粒度就是 machineCode 维度。</p>
     */
    private final Map<String, IStatePersisterStrategy> routeCache = new ConcurrentHashMap<>();

    @Override
    public String readCurrentState(String machineCode, String bizId) {
        IStatePersisterStrategy strategy = resolveStrategy(machineCode);
        if (strategy == null) {
            log.warn("未找到machineCode对应的持久化策略: machineCode={}, 返回null", machineCode);
            return null;
        }
        return strategy.readCurrentState(machineCode, bizId);
    }

    @Override
    public void writeNewState(String machineCode, String bizId, String newStateId) {
        IStatePersisterStrategy strategy = resolveStrategy(machineCode);
        if (strategy == null) {
            throw new IllegalStateException(CharSequenceUtil.format("未找到 machineCode 对应的持久化策略: {}", machineCode));
        }
        strategy.writeNewState(machineCode, bizId, newStateId);
    }

    @Override
    public int writeNewState(String machineCode, String bizId, String fromState, String toState) {
        IStatePersisterStrategy strategy = resolveStrategy(machineCode);
        if (strategy == null) {
            throw new IllegalStateException(CharSequenceUtil.format("未找到 machineCode 对应的持久化策略: {}", machineCode));
        }
        // 为什么：路由器仅做路由，条件更新的语义与受影响行数由具体策略实现决定 —— 干什么：把条件更新请求透传到匹配到的具体策略，并直接返回其受影响行数
        return strategy.writeNewState(machineCode, bizId, fromState, toState);
    }

    @Override
    public boolean supports(String machineCode) {
        return resolveStrategy(machineCode) != null;
    }

    public IStatePersisterStrategy resolveStrategy(String machineCode) {
        IStatePersisterStrategy cached = routeCache.get(machineCode);
        if (cached != null) return cached;

        RouteSelection selection = new RouteSelection();
        for (IStatePersisterStrategy strategy : strategies) {
            if (strategy != this && strategy.supports(machineCode)) {
                selection.apply(strategy, machineCode);
            }
        }
        IStatePersisterStrategy matched = selection.resolved();
        if (matched != null) {
            routeCache.put(machineCode, matched);
        }
        return matched;
    }

    /**
     * 路由选择状态容器，累积遍历策略时得到的业务策略与通用兜底策略，
     * 并在出现业务策略冲突时抛出异常。
     */
    private static final class RouteSelection {
        private IStatePersisterStrategy specific;
        private IStatePersisterStrategy fallback;

        void apply(IStatePersisterStrategy strategy, String machineCode) {
            if (isUniversal(strategy)) {
                if (fallback == null) {
                    fallback = strategy;
                }
            } else {
                if (specific != null) {
                    throw new IllegalStateException(CharSequenceUtil.format("Route conflict: [{}] and [{}] both support machineCode: {}", specific.getClass().getSimpleName(), strategy.getClass().getSimpleName(), machineCode));
                }
                specific = strategy;
            }
        }

        IStatePersisterStrategy resolved() {
            return specific != null ? specific : fallback;
        }

        private static boolean isUniversal(IStatePersisterStrategy strategy) {
            return strategy.isUniversal();
        }
    }

    public void validateHasStrategies(List<String> machineCodes) {
        for (String machineCode : machineCodes) {
            if (resolveStrategy(machineCode) == null) {
                throw new IllegalStateException("No IStatePersisterStrategy implementation found for registered machineCode: " + machineCode);
            }
        }
    }
}