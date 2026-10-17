package com.worm.statemachine.registry;

import com.worm.statemachine.core.IEvent;
import com.worm.statemachine.core.LocalReentrantDistributedLock;
import com.worm.statemachine.core.StateMachineDefinition;
import com.worm.statemachine.core.StateMachineResult;
import com.worm.statemachine.persist.InMemoryStatePersisterStrategy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED 测试 —— StateMachineTemplate 枚举强类型 sendEvent 唯一入口。
 *
 * <p>为什么：当前主代码仅剩 {@code sendEvent(String, String, String)} 一个重载（枚举重载在早期重构中丢失），
 * 本测试钉死目标行为：IEvent 枚举经 sendEvent 按 getEventId() 驱动流转成功，
 * 且反射断言 sendEvent 恰好一个重载、其第三参类型不是 String。</p>
 *
 * <p>RED 纪律：本阶段只写测试不改主代码，断言①因无 {@code sendEvent(String, String, IEvent)} 重载
 * 导致测试类编译失败，由下一任务（2.2）实现后转绿。</p>
 */
class StateMachineTemplateTest {

    /**
     * 测试用事件枚举。
     *
     * <p>为什么：目标契约要求业务事件以 IEvent 枚举直传 sendEvent，引擎按 getEventId() 解析事件标识；
     * 干什么：定义 START 常量（getEventId() 返回 name() 即 "START"），与测试定义中流转的 event 严格匹配。</p>
     */
    private enum TestEvent implements IEvent {

        /** 启动事件：事件标识 "START"，对应流转 PENDING -START-> RUNNING */
        START;

        @Override
        public String getEventId() {
            // 为什么：任务约定 getEventId() 返回 name()，保证事件标识与枚举常量一一对应 —— 干什么：返回枚举常量名 "START"
            return name();
        }

        @Override
        public String getDescription() {
            // 为什么：IEvent 契约要求描述非空，仅供日志与展示，不参与流转匹配 —— 干什么：返回固定中文描述
            return "测试启动事件";
        }
    }

    /**
     * 断言①：IEvent 枚举经 sendEvent 按 getEventId() 驱动流转成功。
     */
    @Test
    void enumEventShouldDriveTransitionByEventId() {
        // 为什么：注册表是模板按 machineCode 路由定义的唯一来源，测试先构造空注册表再登记定义 —— 干什么：实例化 StateMachineRegistry
        StateMachineRegistry registry = new StateMachineRegistry();
        // 为什么：YAML 定义模型是注册中心接受的唯一元数据形态，测试以编程式构造等价最小定义 —— 干什么：新建 StateMachineDefinition
        StateMachineDefinition definition = new StateMachineDefinition();
        // 为什么：sendEvent 第一参为 machineCode，与任务约定的 "m" 保持一致 —— 干什么：设置 machineCode 为 "m"
        definition.setMachineCode("m");
        // 为什么：bizId 无历史状态时 resolve 以 initialState 作为源状态出发 —— 干什么：设置 initialState 为 "PENDING"
        definition.setInitialState("PENDING");
        // 为什么：TestEvent.START.getEventId() 返回 "START"，流转 event 必须与其一致才能命中目标态 —— 干什么：登记 PENDING -START-> RUNNING 一条流转
        definition.setTransitions(Collections.singletonList(new StateMachineDefinition.TransitionDefinition("PENDING", "START", "RUNNING")));
        // 为什么：未登记的定义对模板不可见，sendEvent 会报 no definition found —— 干什么：按 machineCode "m" 注册定义
        registry.register("m", definition);
        // 为什么：测试场景不依赖数据库，用内存策略承接状态读写即可 —— 干什么：实例化 InMemoryStatePersisterStrategy
        InMemoryStatePersisterStrategy persister = new InMemoryStatePersisterStrategy();
        // 为什么：sendEvent 按 machineCode:bizId 加锁串行执行，测试需提供进程内锁实现 —— 干什么：实例化 LocalReentrantDistributedLock
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：被测对象是模板本身，按 @RequiredArgsConstructor 字段顺序注入三个依赖 —— 干什么：构造 StateMachineTemplate
        StateMachineTemplate template = new StateMachineTemplate(registry, persister, lock);
        // 为什么：目标行为是"枚举直传"，引擎内部按 getEventId() 解析为 "START" 驱动流转；当前无此重载，RED 预期本行编译失败 —— 干什么：以 ("m", "1", TestEvent.START) 发送事件
        StateMachineResult result = template.sendEvent("m", "1", TestEvent.START);
        // 为什么：流转成功是断言①的前提，附带 errorMessage 便于失败时直接定位管道短路原因 —— 干什么：断言结果为 success
        assertThat(result.isSuccess()).as("流转应成功，errorMessage=%s", result.getErrorMessage()).isTrue();
        // 为什么：目标行为要求 targetState 与定义中的目标状态一致 —— 干什么：断言 targetState 等于 "RUNNING"
        assertThat(result.getTargetState()).isEqualTo("RUNNING");
    }

    /**
     * 断言②：sendEvent 恰好一个重载，且其第三参类型不是 String。
     */
    @Test
    void sendEventShouldHaveExactlyOneOverloadWithNonStringThirdParam() {
        // 为什么：契约面向公开 API 面，getMethods 覆盖含继承在内的全部 public 方法 —— 干什么：收集 StateMachineTemplate 上所有名为 sendEvent 的方法
        List<Method> sendEventMethods = new ArrayList<>();
        // 为什么：getMethods 返回数组无按名筛选能力，需逐个过滤方法名 —— 干什么：遍历 public 方法并按名 "sendEvent" 收集
        for (Method method : StateMachineTemplate.class.getMethods()) {
            // 为什么：仅方法名匹配 sendEvent 的才计入重载数量 —— 干什么：命中即加入集合
            if (method.getName().equals("sendEvent")) {
                // 为什么：收集到的重载集合是后续两条断言的输入 —— 干什么：加入集合
                sendEventMethods.add(method);
            }
        }
        // 为什么：枚举唯一入口的目标形态要求 sendEvent 不存在第二个重载 —— 干什么：断言 sendEvent 方法恰好 1 个
        assertThat(sendEventMethods).as("sendEvent 应恰好一个重载，实际=%s", sendEventMethods.size()).hasSize(1);
        // 为什么：唯一入口第三参必须是 IEvent 枚举而非 String，钉死"枚举直传"契约、防止退回字符串事件 —— 干什么：断言第三参类型不是 String
        assertThat(sendEventMethods.get(0).getParameterTypes()[2]).as("sendEvent 第三参类型不应是 String").isNotEqualTo(String.class);
    }

    /**
     * RED 断言③：getCurrentState 返回持久化策略读取的当前状态，DB 无记录返回 null。
     *
     * <p>为什么：查询 API 的目标契约是"当前状态以持久化策略为 Source of Truth"，
     * 引擎不得绕过 persister 自行推导；DB 无记录时返回 null 而非 initialState 兜底。</p>
     *
     * <p>RED 纪律：本阶段只写测试不改主代码，当前 {@code StateMachineTemplate} 无
     * {@code getCurrentState} 方法，本测试因找不到符号导致测试类编译失败，由下一任务（4.2）实现后转绿。</p>
     */
    @Test
    void getCurrentStateReturnsPersistedState() {
        // 为什么：注册表是模板按 machineCode 路由定义的唯一来源，复用既有 setup 模式先构造空注册表 —— 干什么：实例化 StateMachineRegistry
        StateMachineRegistry registry = new StateMachineRegistry();
        // 为什么：复用既有 setup 模式需编程式构造最小定义，保证模板构造路径与既有用例一致 —— 干什么：新建 StateMachineDefinition
        StateMachineDefinition definition = new StateMachineDefinition();
        // 为什么：查询键与任务约定保持一致，machineCode 为 "m" —— 干什么：设置 machineCode 为 "m"
        definition.setMachineCode("m");
        // 为什么：查询 API 无记录时必须返回 null 而非 initialState，此值仅作对照不参与断言 —— 干什么：设置 initialState 为 "PENDING"
        definition.setInitialState("PENDING");
        // 为什么：定义需与真实形态一致（至少一条流转），查询断言本身不依赖它 —— 干什么：登记 PENDING -START-> RUNNING 一条流转
        definition.setTransitions(Collections.singletonList(new StateMachineDefinition.TransitionDefinition("PENDING", "START", "RUNNING")));
        // 为什么：未登记的定义对模板不可见，需按 machineCode 注册 —— 干什么：按 machineCode "m" 注册定义
        registry.register("m", definition);
        // 为什么：测试场景不依赖数据库，用内存策略预置与读取状态快照 —— 干什么：实例化 InMemoryStatePersisterStrategy
        InMemoryStatePersisterStrategy persister = new InMemoryStatePersisterStrategy();
        // 为什么：目标契约要求 getCurrentState 读到的正是持久化策略中的快照，先预置 ("m","1") 为 RUNNING —— 干什么：调用 persister.writeNewState 写入 "RUNNING"
        persister.writeNewState("m", "1", "RUNNING");
        // 为什么：sendEvent 按 machineCode:bizId 加锁串行执行，复用既有 setup 模式提供进程内锁 —— 干什么：实例化 LocalReentrantDistributedLock
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：被测对象是模板本身，按 @RequiredArgsConstructor 字段顺序注入三个依赖 —— 干什么：构造 StateMachineTemplate
        StateMachineTemplate template = new StateMachineTemplate(registry, persister, lock);
        // 为什么：目标行为是查询结果取自持久化策略读取的当前状态；当前无此方法，RED 预期本行编译失败 —— 干什么：调用 getCurrentState("m", "1") 查询已预置实体
        String persistedState = template.getCurrentState("m", "1");
        // 为什么：钉死"已预置实体返回持久化策略中的状态"这一契约 —— 干什么：断言返回值等于 "RUNNING"
        assertThat(persistedState).as("已预置实体应返回持久化状态 RUNNING，实际=%s", persistedState).isEqualTo("RUNNING");
        // 为什么：契约要求 DB 无记录返回 null 而非 initialState 兜底，用未预置的 bizId "2" 验证 —— 干什么：调用 getCurrentState("m", "2") 查询未预置实体
        String missingState = template.getCurrentState("m", "2");
        // 为什么：钉死"DB 无记录返回 null"，防止实现回退到 initialState 兜底 —— 干什么：断言返回值为 null
        assertThat(missingState).as("未预置实体应返回 null，实际=%s", missingState).isNull();
    }

    /**
     * RED 断言④：getAvailableEvents 返回当前状态下全部可执行事件标识。
     *
     * <p>为什么：查询 API 的目标契约是"以持久化策略中的当前状态为真实来源，返回该状态下
     * 全部可执行事件标识"；本用例预置 PENDING 状态，定义含 PENDING -START-> RUNNING 与
     * PENDING -CANCEL-> CANCELLED 两条流转，期望返回 ["START", "CANCEL"]。</p>
     *
     * <p>RED 纪律：本阶段只写测试不改主代码，当前 {@code StateMachineTemplate} 无
     * {@code getAvailableEvents} 方法，本测试因找不到符号导致测试类编译失败，由下一任务（4.4）实现后转绿。</p>
     */
    @Test
    void getAvailableEventsReturnsEventsForCurrentState() {
        // 为什么：注册表是模板按 machineCode 路由定义的唯一来源，复用既有 setup 模式先构造空注册表 —— 干什么：实例化 StateMachineRegistry
        StateMachineRegistry registry = new StateMachineRegistry();
        // 为什么：复用既有 setup 模式需编程式构造最小定义，保证模板构造路径与既有用例一致 —— 干什么：新建 StateMachineDefinition
        StateMachineDefinition definition = new StateMachineDefinition();
        // 为什么：查询键与任务约定保持一致，machineCode 为 "m" —— 干什么：设置 machineCode 为 "m"
        definition.setMachineCode("m");
        // 为什么：当前状态由内存 persister 显式预置，initialState 不参与本用例断言，此值仅保证定义形态完整 —— 干什么：设置 initialState 为 "PENDING"
        definition.setInitialState("PENDING");
        // 为什么：目标契约要求返回当前状态下的全部可执行事件，需两条以 PENDING 为源状态的流转 —— 干什么：登记 PENDING -START-> RUNNING 与 PENDING -CANCEL-> CANCELLED 两条流转
        definition.setTransitions(Arrays.asList(
                new StateMachineDefinition.TransitionDefinition("PENDING", "START", "RUNNING"),
                new StateMachineDefinition.TransitionDefinition("PENDING", "CANCEL", "CANCELLED")));
        // 为什么：未登记的定义对模板不可见，需按 machineCode 注册 —— 干什么：按 machineCode "m" 注册定义
        registry.register("m", definition);
        // 为什么：测试场景不依赖数据库，用内存策略预置并承接状态快照 —— 干什么：实例化 InMemoryStatePersisterStrategy
        InMemoryStatePersisterStrategy persister = new InMemoryStatePersisterStrategy();
        // 为什么：显式预置当前状态为 PENDING，不依赖 4.4 是否实现 initialState 回退，测试更稳健 —— 干什么：调用 persister.writeNewState 写入 "PENDING"
        persister.writeNewState("m", "1", "PENDING");
        // 为什么：sendEvent 按 machineCode:bizId 加锁串行执行，复用既有 setup 模式提供进程内锁 —— 干什么：实例化 LocalReentrantDistributedLock
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：被测对象是模板本身，按 @RequiredArgsConstructor 字段顺序注入三个依赖 —— 干什么：构造 StateMachineTemplate
        StateMachineTemplate template = new StateMachineTemplate(registry, persister, lock);
        // 为什么：目标行为是查询当前状态 PENDING 下的全部可执行事件标识；当前无此方法，RED 预期本行编译失败 —— 干什么：调用 getAvailableEvents("m", "1") 查询可用事件
        List<String> availableEvents = template.getAvailableEvents("m", "1");
        // 为什么：钉死"PENDING 状态下返回全部可执行事件标识"契约，且不依赖实现返回顺序，顺序保证交由 4.4 决定 —— 干什么：断言集合精确包含 "START" 与 "CANCEL"
        assertThat(availableEvents).as("PENDING 状态下应返回 START 与 CANCEL，实际=%s", availableEvents)
                .containsExactlyInAnyOrder("START", "CANCEL");
    }

    /**
     * RED 断言⑤：canExecute 判断当前状态下事件是否可执行，合法事件返回 true、非法事件返回 false。
     *
     * <p>为什么：查询 API 的目标契约是"以持久化策略中的当前状态为真实来源，判断指定事件标识
     * 是否存在以其为源状态的流转"；本用例预置 PENDING 状态，定义含 PENDING -START-> RUNNING 与
     * PENDING -CANCEL-> CANCELLED 两条流转，期望 START 返回 true、NOT_EXIST 返回 false。</p>
     *
     * <p>RED 纪律：本阶段只写测试不改主代码，当前 {@code StateMachineTemplate} 无
     * {@code canExecute} 方法，本测试因找不到符号导致测试类编译失败，由下一任务（4.6）实现后转绿。</p>
     */
    @Test
    void canExecuteReturnsTrueForLegalEventFalseForIllegalEvent() {
        // 为什么：注册表是模板按 machineCode 路由定义的唯一来源，复用既有 setup 模式先构造空注册表 —— 干什么：实例化 StateMachineRegistry
        StateMachineRegistry registry = new StateMachineRegistry();
        // 为什么：复用既有 setup 模式需编程式构造最小定义，保证模板构造路径与既有用例一致 —— 干什么：新建 StateMachineDefinition
        StateMachineDefinition definition = new StateMachineDefinition();
        // 为什么：查询键与任务约定保持一致，machineCode 为 "m" —— 干什么：设置 machineCode 为 "m"
        definition.setMachineCode("m");
        // 为什么：当前状态由内存 persister 显式预置，initialState 不参与本用例断言，此值仅保证定义形态完整 —— 干什么：设置 initialState 为 "PENDING"
        definition.setInitialState("PENDING");
        // 为什么：目标契约要求按当前状态匹配流转事件，需两条以 PENDING 为源状态的流转与 4.3 口径一致 —— 干什么：登记 PENDING -START-> RUNNING 与 PENDING -CANCEL-> CANCELLED 两条流转
        definition.setTransitions(Arrays.asList(
                new StateMachineDefinition.TransitionDefinition("PENDING", "START", "RUNNING"),
                new StateMachineDefinition.TransitionDefinition("PENDING", "CANCEL", "CANCELLED")));
        // 为什么：未登记的定义对模板不可见，需按 machineCode 注册 —— 干什么：按 machineCode "m" 注册定义
        registry.register("m", definition);
        // 为什么：测试场景不依赖数据库，用内存策略预置并承接状态快照 —— 干什么：实例化 InMemoryStatePersisterStrategy
        InMemoryStatePersisterStrategy persister = new InMemoryStatePersisterStrategy();
        // 为什么：显式预置当前状态为 PENDING，不依赖 initialState 回退，与 4.3 口径一致 —— 干什么：调用 persister.writeNewState 写入 "PENDING"
        persister.writeNewState("m", "1", "PENDING");
        // 为什么：sendEvent 按 machineCode:bizId 加锁串行执行，复用既有 setup 模式提供进程内锁 —— 干什么：实例化 LocalReentrantDistributedLock
        LocalReentrantDistributedLock lock = new LocalReentrantDistributedLock();
        // 为什么：被测对象是模板本身，按 @RequiredArgsConstructor 字段顺序注入三个依赖 —— 干什么：构造 StateMachineTemplate
        StateMachineTemplate template = new StateMachineTemplate(registry, persister, lock);
        // 为什么：目标契约是合法事件（PENDING 源状态存在 START 流转）返回 true；当前无此方法，RED 预期本行编译失败 —— 干什么：调用 canExecute("m", "1", "START") 判断合法事件
        boolean legalEventExecutable = template.canExecute("m", "1", "START");
        // 为什么：钉死"合法事件返回 true"契约，附带返回值便于失败时直接定位 —— 干什么：断言 START 判定结果为 true
        assertThat(legalEventExecutable).as("PENDING 状态下 START 应可执行，实际=%s", legalEventExecutable).isTrue();
        // 为什么：目标契约是非法事件（不存在以 PENDING 为源状态的 NOT_EXIST 流转）返回 false；当前无此方法，RED 预期本行编译失败 —— 干什么：调用 canExecute("m", "1", "NOT_EXIST") 判断非法事件
        boolean illegalEventExecutable = template.canExecute("m", "1", "NOT_EXIST");
        // 为什么：钉死"非法事件返回 false"契约，防止实现误把未知事件放行为可执行 —— 干什么：断言 NOT_EXIST 判定结果为 false
        assertThat(illegalEventExecutable).as("PENDING 状态下 NOT_EXIST 不应可执行，实际=%s", illegalEventExecutable).isFalse();
    }
}
