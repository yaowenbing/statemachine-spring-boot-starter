package com.worm.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED 测试 —— StatemachineProperties 锁等待超时属性可配置（Feature 5）。
 *
 * <p>干什么：以 Spring Boot {@link Binder} + {@link MapPropertySource} 模拟真实配置加载，
 * 验证两条契约：绑定 {@code com.worm.statemachine.lock-timeout-seconds=30} 后
 * {@code getLockTimeoutSeconds()} 返回 30；空配置下默认返回 10
 * （plan 5.4 钉死将新增 {@code private Long lockTimeoutSeconds = 10L;}）。</p>
 *
 * <p>为什么：Feature 5 目标是锁等待超时可按业务配置而非硬编码常量，属性绑定是配置入口，
 * 必须先钉死前缀（主类 {@code @ConfigurationProperties} 实际值 {@code com.worm.statemachine}）
 * 与 kebab-case 键名的宽松绑定行为，避免配置键写法与主类脱节。</p>
 *
 * <p>RED 纪律：本阶段只写测试不改主代码，当前 {@code StatemachineProperties} 尚无
 * {@code lockTimeoutSeconds} 字段（Lombok {@code @Data} 也不会凭空生成对应 getter），
 * 本测试在 {@code getLockTimeoutSeconds()} 调用处编译失败（cannot find symbol），
 * 由下一任务（5.4）新增字段后转绿。</p>
 */
class StatemachinePropertiesTest {

    /**
     * 断言：Binder 绑定 com.worm.statemachine.lock-timeout-seconds=30 后 getLockTimeoutSeconds() 返回 30。
     */
    @Test
    void bindShouldReadConfiguredLockTimeoutSeconds() {
        // 为什么：Binder.get(Environment) 走真实的 PropertySource 解析管线，最贴近运行时配置加载 —— 干什么：创建标准环境
        StandardEnvironment environment = new StandardEnvironment();
        // 为什么：绑定数据用 Map 承载，键名按任务钉死的 kebab-case 完整路径书写以验证宽松绑定 —— 干什么：新建可变配置 Map 并写入 lock-timeout-seconds=30
        Map<String, Object> configMap = new HashMap<>();
        // 为什么：前缀取主类 @ConfigurationProperties 实际值 com.worm.statemachine，值 30 为任务钉死的绑定场景 —— 干什么：写入配置键值对
        configMap.put("com.worm.statemachine.lock-timeout-seconds", "30");
        // 为什么：Map 必须包装为 PropertySource 才能加入 Environment 参与解析 —— 干什么：以命名属性源包装配置 Map
        MapPropertySource propertySource = new MapPropertySource("bound-props", configMap);
        // 为什么：addFirst 保证测试属性源优先级最高，不被环境既有属性源干扰 —— 干什么：把测试属性源插入环境首位
        environment.getPropertySources().addFirst(propertySource);
        // 为什么：BindResult 语义为命中时非空、未命中时为空（get() 抛 NoSuchElementException），bindOrCreate 未命中时兜底实例化新对象，规避空绑定异常且与默认值用例 API 对称 —— 干什么：绑定或创建前缀 com.worm.statemachine 的属性实例
        StatemachineProperties properties = Binder.get(environment)
                // 为什么：Bindable.ofInstance 复用新建实例的字段初始化形态，绑定动作只覆盖命中的属性 —— 干什么：以空白属性实例构造绑定目标
                .bindOrCreate("com.worm.statemachine", Bindable.ofInstance(new StatemachineProperties()));
        // 为什么：RED 编译失败点 —— 当前主类无 lockTimeoutSeconds 字段，getLockTimeoutSeconds 符号不存在 —— 干什么：调用 getter 触发编译期符号解析
        Long lockTimeoutSeconds = properties.getLockTimeoutSeconds();
        // 为什么：任务钉死绑定后读取值必须为 30，字段类型为 Long 需以 Long 语义断言 —— 干什么：断言 getter 返回 30
        assertThat(lockTimeoutSeconds).as("绑定 lock-timeout-seconds=30 后应读取为 30").isEqualTo(30L);
    }

    /**
     * 断言：未配置 lock-timeout-seconds 时 getLockTimeoutSeconds() 返回默认值 10。
     */
    @Test
    void defaultShouldBeTenWhenNotConfigured() {
        // 为什么：默认值契约要求空配置（环境不含任何 lock-timeout-seconds 键）也能读出默认 10 —— 干什么：创建不含测试键的标准环境
        StandardEnvironment environment = new StandardEnvironment();
        // 为什么：默认值来自 5.4 将新增的字段初始化（private Long lockTimeoutSeconds = 10L），create 兜底实例化的新对象天然携带该默认 —— 干什么：以空白属性实例构造绑定目标
        Bindable<StatemachineProperties> target = Bindable.ofInstance(new StatemachineProperties());
        // 为什么：空环境零属性命中时 JavaBeanBinder.bind 返回 null（bound=false），BindResult.get() 会抛 NoSuchElementException，bindOrCreate 走 create 兜底实例化新对象、天然携带字段默认 10 —— 干什么：绑定或创建前缀 com.worm.statemachine 的属性实例
        StatemachineProperties properties = Binder.get(environment)
                // 为什么：与绑定用例走同一绑定管线且 API 对称，未命中时由 create 兜底而不再依赖 BindResult.get —— 干什么：绑定或创建前缀 com.worm.statemachine 的属性实例
                .bindOrCreate("com.worm.statemachine", target);
        // 为什么：RED 编译失败点 —— 当前主类无该字段，此处符号解析同样失败 —— 干什么：调用 getter 触发编译期符号解析
        Long lockTimeoutSeconds = properties.getLockTimeoutSeconds();
        // 为什么：任务钉死未配置时默认为 10，字段类型为 Long 需以 Long 语义断言 —— 干什么：断言 getter 返回默认 10
        assertThat(lockTimeoutSeconds).as("未配置时默认 lockTimeoutSeconds 应为 10").isEqualTo(10L);
    }
}
