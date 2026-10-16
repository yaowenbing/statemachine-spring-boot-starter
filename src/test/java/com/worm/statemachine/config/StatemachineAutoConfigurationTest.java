package com.worm.statemachine.config;

import com.worm.config.StatemachineAutoConfiguration;
import com.worm.statemachine.persist.RoutingStatePersisterStrategy;
import com.worm.statemachine.registry.StateMachineTemplate;
import com.worm.statemachine.registry.StateMachineRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}