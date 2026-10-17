package com.worm.statemachine.config;

import com.worm.config.StatemachineAutoConfiguration;
import com.worm.statemachine.persist.RoutingStatePersisterStrategy;
import com.worm.statemachine.registry.StateMachineTemplate;
import com.worm.statemachine.registry.StateMachineRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED test — 确认仅引入 starter 依赖时，核心 Bean 不会在没有正确配置的情况下自动注册。
 * 验证 AutoConfiguration 的条件化注册逻辑是否正确。
 */
class StatemachineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    StatemachineAutoConfiguration.class
            ));

    @Test
    void coreBeansAreRegisteredByDefault() {
        // 默认情况下（matchIfMissing=true），核心 Bean 应该被注册
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StateMachineRegistry.class);
            assertThat(context).hasSingleBean(RoutingStatePersisterStrategy.class);
            assertThat(context).hasSingleBean(StateMachineTemplate.class);
        });
    }

    @Test
    void coreBeansAreNotRegisteredWhenDisabled() {
        // 当 com.worm.statemachine.enable=false 时，核心 Bean 不应该被注册
        contextRunner
                .withPropertyValues("com.worm.statemachine.enable=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StateMachineRegistry.class);
                    assertThat(context).doesNotHaveBean(RoutingStatePersisterStrategy.class);
                    assertThat(context).doesNotHaveBean(StateMachineTemplate.class);
                });
    }

    /**
     * RED 测试 —— 自动配置应把 lock-timeout-seconds 注入 StateMachineTemplate 实例超时（Feature 5 / 任务 5.5）。
     *
     * <p>干什么：以 {@code ApplicationContextRunner} 加载 {@link StatemachineAutoConfiguration} 并配置
     * {@code com.worm.statemachine.lock-timeout-seconds=30}，获取 {@link StateMachineTemplate} Bean 后
     * 反射读取其实例字段 {@code lockTimeoutSeconds}，断言为 30s。</p>
     *
     * <p>为什么：锁等待超时的目标契约是「属性 → 自动配置 → 模板实例」全链路生效；当前自动配置
     * 仍以 3 参兼容构造实例化模板（ObjectProvider 注入属任务 5.6），实例字段回落到默认值 10，
     * 断言 30 ≠ 10 必然失败，构成 RED 证据。</p>
     */
    @Test
    void templateUsesConfiguredLockTimeout() {
        // 为什么：复用类级 contextRunner 保持与既有用例一致的轻量自动配置测试形态 —— 干什么：以配置 lock-timeout-seconds=30 启动上下文并校验
        contextRunner
                // 为什么：Spring Boot 宽松绑定把 kebab-case 配置项映射到 StatemachineProperties.lockTimeoutSeconds —— 干什么：注入属性 com.worm.statemachine.lock-timeout-seconds=30
                .withPropertyValues("com.worm.statemachine.lock-timeout-seconds=30")
                // 为什么：断言需在上下文成功加载后针对真实 Bean 执行，上下文启动失败也应直接报错 —— 干什么：运行 runner 并在回调内断言
                .run(context -> {
                    // 为什么：目标 Bean 必须先存在，避免 getBean 抛 NoSuchBeanDefinitionException 掩盖真实失败原因 —— 干什么：断言容器中存在唯一 StateMachineTemplate Bean
                    assertThat(context).hasSingleBean(StateMachineTemplate.class);
                    // 为什么：反射目标是模板实例本身，需先从容器取出 Bean —— 干什么：获取 StateMachineTemplate Bean
                    StateMachineTemplate template = context.getBean(StateMachineTemplate.class);
                    // 为什么：lockTimeoutSeconds 为 private final 实例字段，无法经 getter 读取只能反射 —— 干什么：按名称 "lockTimeoutSeconds" 获取字段对象
                    Field lockTimeoutField = StateMachineTemplate.class.getDeclaredField("lockTimeoutSeconds");
                    // 为什么：private 字段反射读取前必须放开访问检查（同类 unnamed module 下安全） —— 干什么：置 accessible 为 true
                    lockTimeoutField.setAccessible(true);
                    // 为什么：字段类型为 long，getLong 直接以原始类型取值避免装箱歧义 —— 干什么：读取模板实例的 lockTimeoutSeconds 值
                    long actualTimeoutSeconds = lockTimeoutField.getLong(template);
                    // 为什么：目标契约是配置 30 时实例超时为 30，当前主代码经 3 参兼容构造回落默认 10，断言必然失败构成 RED —— 干什么：断言实例字段等于 30L 且携带实际值
                    assertThat(actualTimeoutSeconds)
                            .as("配置 lock-timeout-seconds=30 时模板实例超时应为 30s，实际=%ss", actualTimeoutSeconds)
                            .isEqualTo(30L);
                });
    }
}