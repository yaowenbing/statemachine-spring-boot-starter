package com.worm.properties;

import com.worm.statemachine.core.StateMachineDefinition;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态机 starter 配置项。
 *
 * <p>配置前缀固定为 {@code com.worm.statemachine}，与项目包名一致。
 * 旧前缀 {@code swcares.statemachine} 不再兼容，业务方 MUST 同步迁移。</p>
 *
 * <p>{@code definitions} 用于接收 YAML 中的状态机定义列表。
 * 每条定义包含 machine-code、biz-entity、biz-column、biz-id-column（可选）、
 * initial-state、transitions。其中 biz-* 字段为实体驱动持久化的新增配置，
 * 用于将状态变更落到 MyBatis-Plus 实体对应的数据库列。</p>
 *
 * @author worm
 */
// 为什么：@ConfigurationProperties 提供类型安全的绑定，避免散落的 @Value —— 干什么：声明配置前缀
@Data
@NoArgsConstructor
@ConfigurationProperties(prefix = "com.worm.statemachine")
public class StatemachineProperties {

    /**
     * 状态机 starter 开关，默认启用。
     */
    // 为什么：开关控制 starter 整体是否装配，便于业务方快速降级 —— 干什么：绑定 com.worm.statemachine.enable
    private Boolean enable = true;

    /**
     * 状态机定义列表。
     *
     * <p>从 YAML 配置中读取，每条定义对应一个业务状态机。
     * Spring Boot 宽松绑定将 kebab-case 映射到 {@link StateMachineDefinition} 的 camelCase 字段：
     * machine-code → machineCode、biz-entity → bizEntity、biz-column → bizColumn、
     * biz-id-column → bizIdColumn。</p>
     *
     * <p>biz-id-column 可选，未配置时运行时默认取实体 {@code @TableId} 的 keyColumn。</p>
     *
     * <p>格式示例：
     * <pre>
     * com:
     *   worm:
     *     statemachine:
     *       definitions:
     *         - machine-code: order
     *           biz-entity: com.worm.entity.Order
     *           biz-column: status
     *           biz-id-column: id
     *           initial-state: PENDING
     *           transitions:
     *             - sourceState: PENDING
     *               event: APPROVE
     *               targetState: APPROVED
     * </pre>
     * </p>
     */
    // 为什么：definitions 是核心配置入口，为空时状态机无定义可注册 —— 干什么：绑定 com.worm.statemachine.definitions 列表
    private List<StateMachineDefinition> definitions = new ArrayList<>();

    /**
     * 分布式锁等待超时秒数，默认 10 秒。
     *
     * <p>Spring Boot 宽松绑定将 kebab-case 配置项 {@code lock-timeout-seconds}
     * 自动映射到该 camelCase 字段，业务方无需额外转换器。</p>
     */
    // 为什么：状态持久化加锁需可配置的超时上限，避免死等 —— 干什么：绑定 com.worm.statemachine.lock-timeout-seconds，默认 10 秒
    private Long lockTimeoutSeconds = 10L;
}