package com.worm.config;

import com.worm.properties.StatemachineProperties;
import com.worm.statemachine.core.IDistributedLock;
import com.worm.statemachine.persist.IStatePersisterStrategy;
import com.worm.statemachine.core.LocalReentrantDistributedLock;
import com.worm.statemachine.registry.StateMachineRegistry;
import com.worm.statemachine.persist.EntityMapperStatePersisterStrategy;
import com.worm.statemachine.persist.InMemoryStatePersisterStrategy;
import com.worm.statemachine.persist.RoutingStatePersisterStrategy;
import com.worm.statemachine.registry.StateMachineTemplate;
import com.worm.statemachine.registry.StatemachineConfigInitializer;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * statemachine starter 唯一自动配置类，集中持有全部 Bean 定义。
 *
 * <p>为什么：starter 不应依赖消费方的 {@code @ComponentScan} 来装配 Bean（消费方未扫描
 * {@code com.worm} 时状态机会整组静默缺失），所有 Bean 必须由本类通过 {@code &#64;Bean} 显式注册，
 * 这是 Spring Boot starter 的规范做法。小体量 starter 无需按职责拆成多个 {@code @Configuration}
 * 文件——单一自动配置类 + 方法级条件注解即可，既标准又避免文件散落。</p>
 *
 * <p>干什么：声明启用条件与属性绑定，并以方法级 {@code @ConditionalOnXxx} 分别注册
 * 核心组件（注册表 / 锁 / 执行器 / 组合策略）、MyBatis 持久化策略、测试内存兜底、启动时
 * YAML 初始化器。本类保持「无 StatemachineProperties 字段、无接收 StatemachineProperties 的
 * {@code &#64;Bean} 方法」，以便 YamlRegistrationRedTest 断言「YAML 定义注册职责在 StatemachineConfigInitializer
 * 而非本类」。</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatemachineProperties.class)
@ConditionalOnProperty(prefix = "com.worm.statemachine", name = "enable", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(name = "com.worm.mybatis.config.MybatisPlusAutoConfiguration")
public class StatemachineAutoConfiguration {

    // ============ 核心组件：与持久化实现解耦，始终注册，允许消费方覆盖 ============

    @Bean
    @ConditionalOnMissingBean(StateMachineRegistry.class)
    public StateMachineRegistry stateMachineRegistry() {
        log.info("注册StateMachineRegistry Bean");
        return new StateMachineRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(IDistributedLock.class)
    public IDistributedLock distributedLock() {
        // 为什么：statemachine 只管进程内并发安全 —— 干什么：默认提供本地可重入锁
        log.info("注册 LocalReentrantDistributedLock Bean（进程内本地锁）");
        return new LocalReentrantDistributedLock();
    }

    @Bean
    @ConditionalOnMissingBean(RoutingStatePersisterStrategy.class)
    public RoutingStatePersisterStrategy routingStatePersisterStrategy(List<IStatePersisterStrategy> strategies) {
        // 为什么：路由器收集容器内全部 IStatePersisterStrategy（starter 内置 + 消费方自定义），按 machineCode 路由
        log.info("注册RoutingStatePersisterStrategy, 策略数量={}", strategies.size());
        return new RoutingStatePersisterStrategy(strategies);
    }

    @Bean
    @ConditionalOnMissingBean(StateMachineTemplate.class)
    public StateMachineTemplate stateMachineTemplate(StateMachineRegistry registry,
                                                   RoutingStatePersisterStrategy strategy,
                                                   IDistributedLock lock,
                                                   // 为什么：锁等待超时须从配置读入，且 YamlRegistrationRedTest 契约禁止 @Bean 方法直接以 StatemachineProperties 类型入参 —— 干什么：经 ObjectProvider 间接获取属性 Bean
                                                   ObjectProvider<StatemachineProperties> propertiesProvider) {
        // 为什么：getIfAvailable 在属性 Bean 缺失时返回 null，直接解引用会 NPE —— 干什么：先取出属性对象再判空
        StatemachineProperties properties = propertiesProvider.getIfAvailable();
        // 为什么：StateMachineTemplate.DEFAULT_LOCK_TIMEOUT_SECONDS 为 private 常量无法复用，回落值必须与 3 参兼容构造的历史默认 10s 完全一致 —— 干什么：属性 Bean 缺失时回落 10L
        long lockTimeoutSeconds = properties == null ? 10L : properties.getLockTimeoutSeconds();
        log.info("注册 StateMachineTemplate Bean, lockTimeoutSeconds={}s", lockTimeoutSeconds);
        // 为什么：模板实例超时须承载配置值（属性→自动配置→模板实例全链路契约）—— 干什么：改用 4 参构造传入 lockTimeoutSeconds
        return new StateMachineTemplate(registry, strategy, lock, lockTimeoutSeconds);
    }

    // ============ MyBatis 持久化策略：仅当 SqlSessionTemplate 已就绪时注册 ============
    // 顺序由类级 @AutoConfigureAfter(MybatisPlusAutoConfiguration) 保证，@ConditionalOnBean 评估可靠

    @Bean
    @ConditionalOnBean(SqlSessionTemplate.class)
    public EntityMapperStatePersisterStrategy entityMapperStatePersisterStrategy(StateMachineRegistry registry,
                                                                              SqlSessionTemplate sqlSessionTemplate) {
        log.info("注册EntityMapperStatePersisterStrategy（MyBatis 环境通用持久化策略）");
        return new EntityMapperStatePersisterStrategy(registry, sqlSessionTemplate);
    }

    // ============ 测试内存兜底：仅 test profile 且无 MyBatis 通用策略时注册 ============

    @Bean
    @Profile("test")
    @Primary
    @ConditionalOnMissingBean(EntityMapperStatePersisterStrategy.class)
    public InMemoryStatePersisterStrategy inMemoryStatePersisterStrategy() {
        // 为什么：条件针对具体实现类型而非 IStatePersisterStrategy（路由器本身实现该接口，按接口判断会被自身遮蔽）
        log.info("注册InMemoryStatePersisterStrategy（test profile 内存兜底）");
        return new InMemoryStatePersisterStrategy();
    }

    // ============ 启动初始化器：消费 YAML 定义并注册到注册中心，随 starter 无条件装配 ============
    // StatemachineProperties 由初始化器经构造器注入获取（架构约定：主配置 @Bean 不直接持有该属性，仅经 ObjectProvider 间接传入）

    @Bean
    public StatemachineConfigInitializer statemachineConfigInitializer(StateMachineRegistry registry,
                                                                    RoutingStatePersisterStrategy routingPersister,
                                                                    ObjectProvider<SqlSessionTemplate> sqlSessionTemplateProvider,
                                                                    ObjectProvider<StatemachineProperties> propertiesProvider) {
        // 为什么：按 YamlRegistrationRedTest 契约，本 @Bean 方法不得直接以 StatemachineProperties 类型入参，
        // 故经 ObjectProvider 间接取 Bean 后传入构造器，保持参数类型为 ObjectProvider（非 StatemachineProperties）
        return new StatemachineConfigInitializer(registry, routingPersister, sqlSessionTemplateProvider, propertiesProvider.getIfAvailable());
    }
}
