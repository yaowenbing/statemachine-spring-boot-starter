package com.worm.statemachine.registry;

import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.worm.properties.StatemachineProperties;
import com.worm.statemachine.core.StateMachineDefinition;
import com.worm.statemachine.core.StateMachineDefinition.TransitionDefinition;
import com.worm.statemachine.core.Strings;
import com.worm.statemachine.persist.RoutingStatePersisterStrategy;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 状态机配置初始化器。
 *
 * <p>在 Spring 容器启动完成后，从 YAML 配置中解析状态机定义并注册到 {@link StateMachineRegistry}，
 * 同时校验每个已注册的 machineCode 是否有对应的持久化策略覆盖。</p>
 *
 * <p>初始化流程：
 * <ol>
 *   <li>读取 {@code com.worm.statemachine.definitions} 配置项</li>
 *   <li>逐条校验并注册到注册中心</li>
 *   <li>校验所有 machineCode 均有持久化策略可用</li>
 * </ol>
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class StatemachineConfigInitializer {

    /**
     * 标识符正则：字母或下划线开头，后接字母/数字/下划线，用于校验 biz-column / biz-id-column 列名合法性
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_]\\w*");

    /**
     * 状态机注册中心
     */
    private final StateMachineRegistry registry;

    /**
     * 组合持久化策略（用于校验覆盖）
     */
    private final RoutingStatePersisterStrategy routingPersister;

    /**
     * SqlSessionTemplate（可选 Bean）。
     *
     * <p>为什么：biz-entity / biz-column / biz-id-column 的校验依赖 MyBatis-Plus 的 TableInfo 元数据与 Mapper 解析，
     * 而这些能力需要通过 SqlSessionTemplate 获取；但 starter 的 MyBatis-Plus 依赖是 optional/provided，
     * 消费侧可能未引入 MyBatis（例如纯内存状态机场景），此时该 Bean 不应强制存在。
     * 干什么：通过 ObjectProvider 延迟获取，未注入时跳过持久化元数据校验，避免非 MyBatis 场景启动失败。</p>
     */
    private final ObjectProvider<SqlSessionTemplate> sqlSessionTemplateProvider;

    /**
     * 状态机配置属性（YAML 定义来源）。
     *
     * <p>为什么：该属性由 {@code @EnableConfigurationProperties} 绑定为容器 Bean，但按架构约定
     * （见 {@code YamlRegistrationRedTest}）{@code StatemachineAutoConfiguration} 的 {@code @Bean} 方法
     * 不得直接以 {@code StatemachineProperties} 类型入参。干什么：改为构造器注入，由 {@code @Bean} 方法经
     * {@code ObjectProvider<StatemachineProperties>} 间接取 Bean 后传入，既消除字段注入、又使参数类型保持为
     * {@code ObjectProvider}（非 {@code StatemachineProperties}），从而「@Bean 方法不得接收 StatemachineProperties 参数」的断言仍然成立。</p>
     */
    private final StatemachineProperties properties;

    /**
     * 容器启动后执行初始化：注册 YAML 定义 + 校验策略覆盖。
     *
     * <p>为什么：Spring 容器完成依赖注入后才可读取到完整的 YAML 配置与已注册的 persister 策略集合，
     * 此时一次性完成注册与覆盖校验，能在启动阶段即暴露配置错误（而非等到首次状态变更时才失败）。
     * 干什么：串行执行两步——① registerYamlDefinitions 把 YAML 定义注册到注册中心、② validateStrategyCoverage
     * 逐 machineCode 校验有持久化策略覆盖；任一步抛异常则容器启动快速失败。</p>
     */
    @PostConstruct
    public void init() {
        // 为什么：YAML 定义是状态机引擎的元数据来源，必须先注册才能被后续 sendEvent 路由 —— 干什么：第一步注册全部 YAML 定义并完成字段校验
        registerYamlDefinitions();
        // 为什么：仅注册定义不够，若 machineCode 没有对应 persister 则状态变更时写库会报"未找到策略"，提前在启动期暴露 —— 干什么：第二步逐 machineCode 校验策略覆盖
        validateStrategyCoverage();
    }

    /**
     * 从 YAML 配置中注册状态机定义。
     *
     * <p>为什么：状态机引擎以 machineCode 路由状态定义与策略，定义来源就是 YAML 配置；必须在容器启动时一次性读入注册中心，
     * 后续 sendEvent 才能按 machineCode 查到对应的 initialState/transitions/persister。
     * 干什么：逐条校验 machineCode 唯一性、initialState 非空、transitions 非空且每条流转字段完整，
     * 校验通过后注册到 {@link StateMachineRegistry}；配置为空时仅打 debug 日志跳过。</p>
     */
    void registerYamlDefinitions() {
        // 为什么：YAML 定义是注册的输入源，来自 StatemachineProperties —— 干什么：拉取全部已配置的状态机定义
        List<StateMachineDefinition> definitions = properties.getDefinitions();
        // 为什么：definitions 可能未配置（空列表/null），此时无需注册也无需告警，debug 留痕即可 —— 干什么：空配置时提前返回
        if (definitions == null || definitions.isEmpty()) {
            log.debug("未配置YAML状态机定义");
            return;
        }
        // 为什么：每条定义需独立校验唯一性与字段完整性，单条失败不应阻塞其他定义 —— 干什么：逐条执行注册
        for (StateMachineDefinition def : definitions) {
            registerDefinition(def);
        }
    }

    /**
     * 注册单条状态机定义到注册中心。
     *
     * <p>为什么：同一条 machineCode 在 YAML 不允许重复注册，否则后续 sendEvent 路由会命中到错误定义；
     * machineCode 为空的定义是噪声配置，只警告不抛异常避免阻塞其他合法定义。
     * 干什么：先校验 machineCode 非空 → 再校验 machineCode 唯一 → 再校验字段完整性 → 最后注册并打 info 日志。</p>
     */
    private void registerDefinition(StateMachineDefinition def) {
        // 为什么：machineCode 是路由唯一键，必须非空 —— 干什么：掠取 machineCode 供后续校验与注册
        String machineCode = def.getMachineCode();
        // 为什么：machineCode 为空是非法的噪声配置，不应阻塞其他定义注册 —— 干什么：空 machineCode 跳过并告警
        if (Strings.isBlank(machineCode)) {
            log.warn("跳过machineCode为空的定义");
            return;
        }
        // 为什么：machineCode 重复会导致 sendEvent 路由歧义，必须在注册期拦截 —— 干什么：重复时立即抛出 IllegalArgumentException 快速失败
        if (registry.contains(machineCode)) {
            throw new IllegalArgumentException("YAML 状态机定义中存在重复的 machineCode: " + machineCode);
        }
        // 为什么：initialState / transitions 缺失会导致执行期 findTransition 失败，提前在注册期卡住 —— 干什么：继续向下校验字段完整性
        validateDefinitionFields(def, machineCode);
        // 为什么：biz-entity / biz-column / biz-id-column 是持久化元数据，写错只会在运行时才暴露，不如在启动期一次性卡住 —— 干什么：校验实体可加载、列名合法且 Mapper 可解析
        validateBizMetadata(def, machineCode);
        // 为什么：至此全部校验通过，可将定义交由注册中心持有 —— 干什么：注册并记录审计日志
        registry.register(machineCode, def);
        log.info("注册YAML状态机定义: machineCode={}", machineCode);
    }

    /**
     * 校验状态机定义的字段完整性（initialState + transitions）。
     *
     * <p>为什么：initialState 缺失会让 resolve 阶段无法确定源状态；transitions 缺失会让 findTransition 永远找不到匹配流转；
     * 两者都会在运行时才爆发，不如在注册期一次性卡住。
     * 干什么：校验 initialState 非空 → 校验 transitions 非空 → 逐条校验每条流转字段完整。</p>
     */
    private void validateDefinitionFields(StateMachineDefinition def, String machineCode) {
        // 为什么：initialState 是 resolve 阶段推断源状态的起点，缺失将导致内存/DB 状态无法对齐 —— 干什么：空则抛异常终止注册
        if (Strings.isBlank(def.getInitialState())) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 initialState 不能为空");
        }
        // 为什么：transitions 是状态机流转规则集合，缺失则状态机完全无法推进 —— 干事：空则抛异常终止注册
        if (def.getTransitions() == null || def.getTransitions().isEmpty()) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 transitions 不能为空");
        }
        // 为什么：每条流转的 source/event/target 任缺其一该条流转即不可执行，需要逐条把关 —— 干什么：逐条调用 validateTransition 校验字段
        for (TransitionDefinition t : def.getTransitions()) {
            validateTransition(t, machineCode);
        }
    }

    /**
     * 校验单条流转定义的字段完整性。
     *
     * <p>为什么：每条流转必须同时具备 sourceState（源状态）、event（触发事件）、targetState（目标状态）三者缺一不可；
     * 缺失任一字段都会导致后续状态机执行时 findTransition 找不到匹配流转，或 resolve 阶段解错目标状态。
     * 干什么：任一字段为 null 时立即抛 IllegalArgumentException，回压到 YAML 定义编写方修正。</p>
     */
    private void validateTransition(TransitionDefinition t, String machineCode) {
        // 为什么：sourceState / event / targetState 是合法流转的三个必要维度，任缺一则该流转不可执行 —— 干什么：任一字段为 null 即判定为非法定义并终止注册
        if (t.getSourceState() == null || t.getEvent() == null || t.getTargetState() == null) {
            // 为什么：需要把 machineCode 与具体缺失维度反馈给定义编写方以快速定位 —— 干什么：抛出带 machineCode 的异常说明"每条流转必须同时具备 source、event、target"
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的每条流转必须同时具备 source、event、target");
        }
    }

    /**
     * 校验单条定义的持久化元数据（biz-entity / biz-column / biz-id-column / Mapper）。
     *
     * <p>为什么：biz-* 字段是实体驱动持久化的锚点，配置写错（实体类不存在、列名拼错、Mapper 未注册）只会在运行时 sendEvent 才暴露；
     * 启动期一次性校验可在容器启动阶段即 fail-fast，避免运行中才逐个失败。
     * 干什么：① 若未声明 biz-entity 或 SqlSessionTemplate 不可用则跳过（纯内存场景无需校验）；
     * ② 校验 biz-entity 可加载且有 @TableName/@TableId；③ 校验 biz-column 匹配标识符正则且存在于 TableInfo 字段列表；
     * ④ 校验 biz-id-column（若声明）匹配标识符正则；⑤ 校验 Mapper 可解析（sqlSessionTemplate.getMapper 不抛异常）。</p>
     */
    private void validateBizMetadata(StateMachineDefinition def, String machineCode) {
        // 为什么：biz-entity 是持久化元数据的起点，未声明则无需校验 —— 干什么：未配置 biz-entity 时直接跳过
        String bizEntity = def.getBizEntity();
        if (Strings.isBlank(bizEntity)) {
            return;
        }
        // 为什么：SqlSessionTemplate 是 optional Bean，未注入说明消费侧未引入 MyBatis，无法做持久化元数据校验 —— 干什么：SqlSessionTemplate 不可用时跳过，避免非 MyBatis 场景误杀
        SqlSessionTemplate sqlSessionTemplate = sqlSessionTemplateProvider.getIfAvailable();
        if (sqlSessionTemplate == null) {
            log.warn("machineCode '{}' 声明了 biz-entity='{}' 但 SqlSessionTemplate 不可用，跳过持久化元数据校验", machineCode, bizEntity);
            return;
        }
        // 为什么：biz-entity 必须能加载到 Class 对象，否则后续 TableInfo 解析无从谈起 —— 干什么：通过 Class.forName 加载实体类，失败则快速失败
        Class<?> entityClass = loadEntityClass(bizEntity, machineCode);
        // 为什么：TableInfo 是 MyBatis-Plus 解析 @TableName/@TableId/@TableField 后的元数据，是后续列校验与 Mapper 解析的基础 —— 干什么：获取 TableInfo，为空说明实体未被 MP 管理（无 @TableName 或未注册 Mapper）
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        if (tableInfo == null) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 未被 MyBatis-Plus 管理（缺少 @TableName 或 Mapper 未注册）");
        }
        // 为什么：@TableId 是持久化写状态的必要条件（biz-id-column 默认取 @TableId 的 keyColumn），缺失则无法做条件更新 —— 干什么：校验实体存在主键
        if (!tableInfo.havePK()) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 缺少 @TableId 主键注解");
        }
        // 为什么：biz-column 是状态写入的目标列，列名非法或不存在会在运行时才暴露 —— 干什么：校验 biz-column 匹配标识符正则且存在于 TableInfo 字段列表
        validateBizColumn(def.getBizColumn(), tableInfo, machineCode, bizEntity);
        // 为什么：biz-id-column 若声明则必须合法，否则运行时拼 SQL 会报错 —— 干什么：校验 biz-id-column（若声明）匹配标识符正则
        validateBizIdColumn(def.getBizIdColumn(), machineCode);
        // 为什么：Mapper 是持久化执行的主体，未注册到 SqlSessionTemplate 则 getMapper 会抛异常 —— 干什么：通过 tableInfo.getCurrentNamespace() 拿 Mapper 全限定名并校验可解析
        validateMapperResolvable(sqlSessionTemplate, tableInfo, machineCode, bizEntity);
    }

    /**
     * 加载 biz-entity 对应的 Class 对象。
     *
     * <p>为什么：biz-entity 是全类名（FQN），必须转为 Class 才能做 TableInfo 解析与注解检查；
     * ClassNotFoundException 意味着类不在 classpath 中，属于配置错误，应 fail-fast。
     * 干什么：调用 Class.forName 加载类，失败时抛出带 machineCode 与 biz-entity 的 IllegalArgumentException。</p>
     */
    private Class<?> loadEntityClass(String bizEntity, String machineCode) {
        // 为什么：Class.forName 是 JDK 标准类加载方式，与 MyBatis-Plus 内部加载逻辑一致 —— 干什么：尝试加载实体类
        try {
            return Class.forName(bizEntity);
        } catch (ClassNotFoundException e) {
            // 为什么：类不存在是配置错误，需明确反馈给定义编写方 —— 干什么：抛出带 machineCode 与 biz-entity 的异常说明类加载失败
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 无法加载（ClassNotFoundException）", e);
        }
    }

    /**
     * 校验 biz-column 的合法性：匹配标识符正则且存在于 TableInfo 字段列表。
     *
     * <p>为什么：biz-column 是状态写入的目标列，列名非法（含特殊字符）会导致 SQL 注入风险或语法错误；
     * 列名不存在于实体字段中则运行时写状态会落到不存在的列上。
     * 干什么：先校验非空 → 再校验标识符正则 → 最后校验列名存在于 TableInfo.getFieldList() 的 column 中。</p>
     */
    private void validateBizColumn(String bizColumn, TableInfo tableInfo, String machineCode, String bizEntity) {
        // 为什么：biz-column 是持久化必填项，缺失则状态无列可写 —— 干什么：空则抛异常终止注册
        if (Strings.isBlank(bizColumn)) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 未声明 biz-column（状态列必填）");
        }
        // 为什么：列名含特殊字符（空格、引号、运算符等）会导致 SQL 语法错误或注入风险 —— 干什么：用标识符正则卡住非法列名
        if (!IDENTIFIER_PATTERN.matcher(bizColumn).matches()) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-column '"
                    + bizColumn + "' 不是合法标识符（必须匹配 [A-Za-z_]\\w*）");
        }
        // 为什么：列名合法不代表实体中存在该列，需与 TableInfo 字段列表做交叉校验 —— 干什么：遍历 fieldList 检查 column 是否匹配
        boolean columnExists = tableInfo.getFieldList().stream()
                .anyMatch(field -> bizColumn.equals(field.getColumn()));
        if (!columnExists) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-column '"
                    + bizColumn + "' 不存在于实体 '" + bizEntity + "' 的 TableInfo 字段列表中");
        }
    }

    /**
     * 校验 biz-id-column（若声明）的合法性：匹配标识符正则。
     *
     * <p>为什么：biz-id-column 是可选配置，用于覆盖 @TableId 的 keyColumn；若声明则必须合法，
     * 否则运行时拼 WHERE 条件时会出 SQL 语法错误。
     * 干什么：仅在校验对象非空时校验标识符正则，未声明则跳过。</p>
     */
    private void validateBizIdColumn(String bizIdColumn, String machineCode) {
        // 为什么：biz-id-column 是可选配置，未声明时跳过 —— 干什么：空值直接返回
        if (Strings.isBlank(bizIdColumn)) {
            return;
        }
        // 为什么：主键列名含特殊字符同样会导致 SQL 语法错误 —— 干什么：用标识符正则卡住非法列名
        if (!IDENTIFIER_PATTERN.matcher(bizIdColumn).matches()) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-id-column '"
                    + bizIdColumn + "' 不是合法标识符（必须匹配 [A-Za-z_]\\w*）");
        }
    }

    /**
     * 校验业务 Mapper 可解析：通过 TableInfo.currentNamespace 拿 Mapper 全限定名，再经 SqlSessionTemplate.getMapper 校验。
     *
     * <p>为什么：Mapper 是持久化执行的主体，若业务 Mapper 未被 @MapperScan/@Mapper 注册进容器，
     * getMapper 会抛异常；启动期校验可 fail-fast，避免运行时 sendEvent 才暴露。
     * 干什么：从 TableInfo.getCurrentNamespace() 取 Mapper FQN → Class.forName 加载 → sqlSessionTemplate.getMapper 校验可解析。</p>
     */
    private void validateMapperResolvable(SqlSessionTemplate sqlSessionTemplate, TableInfo tableInfo, String machineCode, String bizEntity) {
        // 为什么：currentNamespace 是 MyBatis-Plus 在初始化 Mapper 时记录的全限定名，即业务 Mapper 接口名 —— 干什么：取出 Mapper FQN
        String mapperClassName = tableInfo.getCurrentNamespace();
        // 为什么：currentNamespace 为空说明 Mapper 未被正确注册到 MP 环境 —— 干什么：空则抛异常说明 Mapper 未注册
        if (Strings.isBlank(mapperClassName)) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 未关联到任何 MyBatis Mapper（TableInfo.currentNamespace 为空）");
        }
        // 为什么：Mapper 类本身必须存在于 classpath 中 —— 干什么：加载 Mapper 类，失败则快速失败
        Class<?> mapperClass;
        try {
            mapperClass = Class.forName(mapperClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 关联的 Mapper '" + mapperClassName + "' 无法加载（ClassNotFoundException）", e);
        }
        // 为什么：getMapper 会校验 Mapper 是否已注册到 SqlSession，未注册则抛异常 —— 干什么：调用 getMapper 校验可解析，失败则 fail-fast
        try {
            sqlSessionTemplate.getMapper(mapperClass);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML 状态机定义非法，machineCode '" + machineCode + "' 的 biz-entity '"
                    + bizEntity + "' 关联的 Mapper '" + mapperClassName + "' 无法从 SqlSessionTemplate 解析（请确认 @MapperScan 已扫描该 Mapper）", e);
        }
    }

    /**
     * 校验所有已注册的 machineCode 均有持久化策略覆盖。
     *
     * <p>为什么：定义已注册但 persister 策略未覆盖时，运行时 writeNewState 会找不到策略抛错；
     * 不如在启动期一次性把所有未覆盖 machineCode 暴露出来，避免运行中才逐个失败。
     * 干什么：拿到注册中心全部 machineCode，委托 routingPersister 逐条校验有策略覆盖，未覆盖则抛 IllegalStateException。</p>
     */
    void validateStrategyCoverage() {
        // 为什么：注册中心持有当前已注册的全部 machineCode，是校验覆盖率的输入 —— 干什么：取全量 machineCode 集合
        java.util.Set<String> machineCodes = registry.getAllMachineCodes();
        // 为什么：无任何 machineCode 时无需校验，直接跳过 —— 干什么：空集合时提前返回
        if (machineCodes.isEmpty()) return;
        // 为什么：覆盖校验委托组合策略完成，组合策略内部逐条调用 resolveStrategy 校验 —— 干什么：校验每个 machineCode 都有持久化策略
        routingPersister.validateHasStrategies(new ArrayList<>(machineCodes));
    }
}
