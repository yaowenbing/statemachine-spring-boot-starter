package com.worm.statemachine.config;

import com.worm.config.StatemachineAutoConfiguration;
import com.worm.statemachine.registry.StatemachineConfigInitializer;
import com.worm.properties.StatemachineProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED test — 确认 StatemachineAutoConfiguration 本身不消费 StatemachineProperties.definitions。
 * YAML 定义的注册职责在 StatemachineConfigInitializer 中，而非 AutoConfiguration。
 */
class YamlRegistrationRedTest {

    @Test
    void autoConfigurationShouldNotHaveStatemachinePropertiesField() throws Exception {
        // AutoConfiguration 不应该有 StatemachineProperties 的字段
        Field[] fields = StatemachineAutoConfiguration.class.getDeclaredFields();
        for (Field field : fields) {
            assertThat(field.getType()).isNotEqualTo(StatemachineProperties.class);
        }
    }

    @Test
    void autoConfigurationMethodsShouldNotAcceptStatemachinePropertiesParam() throws Exception {
        // AutoConfiguration 的 @Bean 方法不应该接受 StatemachineProperties 参数
        for (var method : StatemachineAutoConfiguration.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) {
                Parameter[] params = method.getParameters();
                for (Parameter param : params) {
                    assertThat(param.getType()).isNotEqualTo(StatemachineProperties.class);
                }
            }
        }
    }

    @Test
    void yamlRegistrationResponsibilityLiesInInitializer() throws Exception {
        // 验证 StatemachineConfigInitializer 持有 StatemachineProperties 依赖
        Field[] fields = StatemachineConfigInitializer.class.getDeclaredFields();
        boolean hasPropertiesField = false;
        for (Field field : fields) {
            if (field.getType() == StatemachineProperties.class) {
                hasPropertiesField = true;
                break;
            }
        }
        assertThat(hasPropertiesField)
                .as("StatemachineConfigInitializer should hold StatemachineProperties to consume definitions")
                .isTrue();
    }
}
