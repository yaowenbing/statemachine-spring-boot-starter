package com.worm.statemachine.persist;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.worm.statemachine.core.StateMachineDefinition;
import com.worm.statemachine.core.Strings;
import com.worm.statemachine.registry.StateMachineRegistry;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 MyBatis-Plus 的通用状态持久化策略。
 *
 * <p>为什么：替代所有消费方自定义的 IStatePersisterStrategy 实现，
 * 通过 machineCode 维度的 Mapper 缓存，自动定位业务 BaseMapper 完成状态读写，
 * 实现「零 persister 代码」目标——消费方只需在 YAML 声明 biz-entity/biz-column 即可接入。
 * 干什么：启动时遍历注册表，按 machineCode 解析业务实体类 → TableInfo → Mapper FQN → BaseMapper，
 * 缓存到 Map&lt;machineCode, MapperHolder&gt; 中供运行时使用；运行时通过 QueryWrapper/UpdateWrapper
 * 完成状态读取与条件更新，不手拼 SQL。</p>
 *
 * <p>仅在 SqlSessionTemplate Bean 存在时激活（即消费方引入了 MyBatis-Plus 依赖）。</p>
 *
 * @author worm
 */
@Slf4j
public class EntityMapperStatePersisterStrategy implements IStatePersisterStrategy {

    /**
     * 状态机注册中心。
     *
     * <p>为什么：启动时需要遍历所有已注册的 machineCode 及其定义（含 bizEntity/bizColumn/bizIdColumn），
     * 逐个解析并缓存 MapperHolder；运行时也需要按 machineCode 查定义以获取列名等元数据。</p>
     */
    private final StateMachineRegistry registry;

    /**
     * MyBatis SqlSessionTemplate。
     *
     * <p>为什么：获取业务 Mapper 实例的唯一入口——sqlSessionTemplate.getMapper(mapperClass)
     * 返回已注册到 Spring 容器的 Mapper 代理，该代理已绑定实体泛型与 SqlSession。</p>
     */
    private final SqlSessionTemplate sqlSessionTemplate;

    /**
     * machineCode → MapperHolder 缓存。
     *
     * <p>为什么必须用 Map：按 machineCode 缓存首次解析的 MapperHolder（O(1) 命中），
     * 避免每次状态读写都重复执行 Class.forName / TableInfoHelper / getMapper 反射链；
     * value 不可用 List 替代——需要按稳定 key（machineCode 业务域编码）直接命中唯一的 MapperHolder。
     * key 仅为单 machineCode（非复合 key），因为缓存粒度就是 machineCode 维度。</p>
     */
    private final Map<String, MapperHolder> mapperCache = new ConcurrentHashMap<>();

    /**
     * 构造策略实例。
     *
     * @param registry           状态机注册中心
     * @param sqlSessionTemplate MyBatis SqlSessionTemplate
     */
    public EntityMapperStatePersisterStrategy(StateMachineRegistry registry, SqlSessionTemplate sqlSessionTemplate) {
        this.registry = registry;
        this.sqlSessionTemplate = sqlSessionTemplate;
    }

    /**
     * 启动时预解析所有已注册 machineCode 的 Mapper 元数据并缓存。
     *
     * <p>为什么：使用 ContextRefreshedEvent 而非 @PostConstruct，确保 StatemachineConfigInitializer
     * 已完成 YAML 定义注册到 StateMachineRegistry 后再执行预解析，避免因 Bean 初始化顺序不确定导致
     * 注册表为空而跳过预解析；ContextRefreshedEvent 在容器刷新完成后触发，所有单例 Bean 已初始化完毕。
     * 干什么：监听 ContextRefreshedEvent → 遍历注册表所有 machineCode → 逐个调用 resolveAndCache 解析并缓存 MapperHolder；
     * 单个 machineCode 解析失败先收集到 failedMachineCodes，循环结束后统一抛 IllegalStateException fail-fast，
     * 避免 misconfigured bizEntity 静默跳过导致运行时 sendEvent 才暴露问题。</p>
     */
    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        // 为什么：注册表持有当前已注册的全部 machineCode，是预解析的输入 —— 干什么：取全量 machineCode 集合
        Set<String> machineCodes = registry.getAllMachineCodes();
        // 为什么：无任何 machineCode 时无需预解析，直接跳过 —— 干什么：空集合时提前返回
        if (machineCodes.isEmpty()) {
            log.debug("EntityMapperStatePersisterStrategy 启动时无已注册 machineCode，跳过预解析");
            return;
        }
        // 为什么：需要收集所有解析失败的 machineCode，循环结束后统一 fail-fast —— 干什么：创建 failedMachineCodes 列表
        List<String> failedMachineCodes = new ArrayList<>();
        // 为什么：逐个 machineCode 解析并缓存，单条失败先收集不阻塞其他 —— 干什么：逐条执行解析
        for (String machineCode : machineCodes) {
            try {
                // 为什么：解析并缓存当前 machineCode 的 MapperHolder —— 干什么：调用 resolveAndCache 完成反射链
                resolveAndCache(machineCode);
            } catch (Exception e) {
                // 为什么：单个 machineCode 解析失败先收集到 failedMachineCodes —— 干什么：记录错误日志并将 machineCode 加入失败列表
                log.error("EntityMapperStatePersisterStrategy 预解析 machineCode 失败: machineCode={}, 原因={}", machineCode, e.getMessage(), e);
                failedMachineCodes.add(machineCode);
            }
        }
        // 为什么：存在解析失败必须 fail-fast，避免应用带着不完整缓存启动后运行时 sendEvent 才暴露问题 —— 干什么：失败列表非空则抛 IllegalStateException 终止启动
        if (!failedMachineCodes.isEmpty()) {
            throw new IllegalStateException("EntityMapperStatePersisterStrategy 预解析失败的 machineCode: " + failedMachineCodes
                    + "，请检查对应的 biz-entity / biz-column / biz-id-column 配置是否正确");
        }
    }

    /**
     * 读取数据库中的当前状态。
     *
     * <p>为什么：状态机事件触发前必须先读取当前状态以判断是否允许迁移；
     * 通过 QueryWrapper 仅 select 业务状态列 + 主键条件，避免全字段查询带来的 IO 与序列化开销。
     * 干什么：从缓存获取 MapperHolder → 构造 QueryWrapper.select(bizColumn).eq(idColumn, bizId) →
     * 调用 mapper.selectMaps 取首行 → 提取 bizColumn 值转为 String 返回。</p>
     *
     * @param machineCode 业务编码
     * @param bizId       业务标识
     * @return 当前状态标识；记录不存在时返回 null
     */
    @Override
    public String readCurrentState(String machineCode, String bizId) {
        // 为什么：需要 MapperHolder 中的 mapper/idColumn/bizColumn 元数据构造查询 —— 干什么：从缓存获取或懒加载解析 MapperHolder
        MapperHolder holder = resolveAndCache(machineCode);
        // 为什么：selectMaps 返回 List<Map>，每行以列名为 key，避免实体类字段映射开销 —— 干什么：通过泛型辅助方法执行查询获取结果列表
        java.util.List<java.util.Map<String, Object>> rows = selectMaps(holder, bizId);
        // 为什么：主键查询至多一行，空列表说明记录不存在 —— 干什么：空列表时返回 null 表示记录不存在
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        // 为什么：需要取首行 bizColumn 值作为当前状态 —— 干什么：获取首行 Map 中 bizColumn 对应的值
        Object stateValue = rows.get(0).get(holder.getBizColumn());
        // 为什么：状态值可能为 null（列允许 NULL），需做空保护 —— 干什么：null 时返回 null，否则转为 String
        return stateValue == null ? null : String.valueOf(stateValue);
    }

    /**
     * 通过业务 Mapper 执行 selectMaps 查询（捕获通配符泛型，避免 wildcard capture 编译错误）。
     *
     * <p>为什么：MapperHolder 持有者声明的 mapper 为 BaseMapper<?>，直接调用 selectMaps(QueryWrapper<?>) 会因
     * wildcard capture 不兼容导致编译失败；通过泛型辅助方法 &lt;T&gt; 捕获具体类型参数，
     * 使 QueryWrapper&lt;T&gt; 与 BaseMapper&lt;T&gt; 的类型参数一致，绕过编译期通配符限制。
     * 干什么：构造 QueryWrapper.select(bizColumn).eq(idColumn, bizId)，委托 mapper.selectMaps 执行并返回结果。</p>
     *
     * @param holder 业务 Mapper 持有者
     * @param bizId  业务标识（主键值）
     * @param <T>    实体类型参数（由编译器推断捕获）
     * @return 查询结果列表（每行为 column → value 的 Map）
     */
    @SuppressWarnings("unchecked")
    private <T> List<Map<String, Object>> selectMaps(MapperHolder holder, String bizId) {
        // 为什么：需要将通配符 BaseMapper<?> 转为具体类型参数以匹配 Wrapper —— 干什么：捕获泛型 T 获取类型化 mapper
        BaseMapper<T> mapper = (BaseMapper<T>) holder.getMapper();
        // 为什么：仅 select bizColumn 可减少 IO 与序列化开销，无需全字段 —— 干什么：构造类型化 QueryWrapper 指定查询列与主键条件
        QueryWrapper<T> qw = new QueryWrapper<>();
        qw.select(holder.getBizColumn()).eq(holder.getIdColumn(), bizId);
        // 为什么：执行查询返回 Map 列表，避免实体类字段映射开销 —— 干什么：调用 mapper.selectMaps 获取结果
        return mapper.selectMaps(qw);
    }

    /**
     * 将新状态写入数据库（无条件落库入口）。
     *
     * <p>为什么：接口保留不携带源状态条件的落库形态，供不需要乐观并发控制的调用方使用；
     * 无条件更新无法防御并发抢占，故内部委托条件更新并以影响行数兜底校验，失败时明确报错而非静默成功。
     * 干什么：以 fromState=null 委托 4 参条件更新完成落库；影响行数为 0 时抛出 IllegalStateException。</p>
     *
     * @param machineCode 业务编码
     * @param bizId       业务标识
     * @param newStateId  新状态标识
     */
    @Override
    public void writeNewState(String machineCode, String bizId, String newStateId) {
        // 为什么：无条件落库不携带 fromState，无法做并发安全条件更新，属于旧接口退化路径 —— 干什么：委托 4-arg 条件更新，fromState 传 null 表示不匹配源状态条件
        int rows = writeNewState(machineCode, bizId, null, newStateId);
        // 为什么：影响行数为 0 说明条件不满足（fromState=null 时不会触发，但保持防御性校验） —— 干什么：落库失败时抛出异常提示调用方
        if (rows == 0) {
            throw new IllegalStateException("EntityMapperStatePersisterStrategy 无条件落库失败: machineCode=" + machineCode + ", bizId=" + bizId);
        }
    }

    /**
     * 条件更新状态（并发安全落库）。
     *
     * <p>为什么：状态可能被并发推进，落库必须携带期望的源状态作为 WHERE 条件，
     * 实现「当前状态仍等于 fromState 才写入」的乐观并发控制，防止并发场景下状态被覆盖或回退。
     * 干什么：从缓存获取 MapperHolder → 构造 UpdateWrapper（WHERE idColumn=bizId，
     * fromState 非空时追加 bizColumn=fromState）→ SET bizColumn=toState → 执行更新并返回影响行数。</p>
     *
     * @param machineCode 业务编码
     * @param bizId       业务标识
     * @param fromState   期望的源状态（WHERE 条件）；为 null 时不校验源状态，仅按主键更新
     * @param toState     目标新状态
     * @return 受影响行数（0 表示条件不满足/状态已被并发推进）
     */
    @Override
    public int writeNewState(String machineCode, String bizId, String fromState, String toState) {
        // 为什么：需要 MapperHolder 中的 mapper/idColumn/bizColumn 元数据构造条件更新 —— 干什么：从缓存获取或懒加载解析 MapperHolder
        MapperHolder holder = resolveAndCache(machineCode);
        // 为什么：UpdateWrapper 构造条件更新 WHERE idColumn=bizId AND bizColumn=from SET bizColumn=to，实现乐观并发控制 —— 干什么：构造类型化 UpdateWrapper 并链式拼接条件
        UpdateWrapper<Object> uw = new UpdateWrapper<>();
        uw.eq(holder.getIdColumn(), bizId);
        // 为什么：fromState 非空时才追加源状态条件；为 null 时退化为无条件落库（仅按主键更新） —— 干什么：fromState 非空时追加 bizColumn=from 条件
        if (fromState != null) {
            uw.eq(holder.getBizColumn(), fromState);
        }
        // 为什么：SET bizColumn=to 是状态迁移的目标值 —— 干什么：追加 SET 子句
        uw.set(holder.getBizColumn(), toState);
        // 为什么：mapper.update(null, uw) 执行条件更新并返回影响行数，0 表示并发失败（fromState 不匹配） —— 干什么：执行更新并返回影响行数
        return mapperUpdate(holder, uw);
    }

    /**
     * 通过业务 Mapper 执行条件更新（捕获通配符泛型，避免 wildcard capture 编译错误）。
     *
     * <p>为什么：MapperHolder.mapper 声明为 BaseMapper<?>，直接调用 update(null, UpdateWrapper&lt;?&gt;) 会因
     * wildcard capture 不兼容导致编译失败；通过泛型辅助方法 &lt;T&gt; 捕获具体类型参数，
     * 使 UpdateWrapper&lt;T&gt; 与 BaseMapper&lt;T&gt; 的类型参数一致，绕过编译期通配符限制。
     * 干什么：委托 mapper.update(null, uw) 执行条件更新并返回影响行数。</p>
     *
     * @param holder 业务 Mapper 持有者
     * @param uw     条件更新构造器
     * @param <T>    实体类型参数（由编译器推断捕获）
     * @return 受影响行数（0 表示条件不满足/被并发抢走）
     */
    @SuppressWarnings("unchecked")
    private <T> int mapperUpdate(MapperHolder holder, UpdateWrapper<T> uw) {
        // 为什么：需要将通配符 BaseMapper<?> 转为具体类型参数以匹配 Wrapper —— 干什么：捕获泛型 T 获取类型化 mapper
        BaseMapper<T> mapper = (BaseMapper<T>) holder.getMapper();
        // 为什么：执行条件更新返回影响行数，调用方据此判断并发结果 —— 干什么：调用 mapper.update(null, uw) 执行并返回影响行数
        return mapper.update(null, uw);
    }

    /**
     * 声明此策略负责的业务编码范围。
     *
     * <p>为什么：通用策略支持所有 machineCode，消费方无需为每个 machineCode 单独配置策略。
     * 干什么：始终返回 true，表示支持任意 machineCode。</p>
     *
     * @param machineCode 业务编码
     * @return true 表示此策略负责该 machineCode
     */
    @Override
    public boolean supports(String machineCode) {
        // 为什么：通用策略支持所有 machineCode —— 干什么：始终返回 true
        return true;
    }

    /**
     * 标识此策略为通用策略。
     *
     * <p>为什么：通用策略在路由时优先级低于业务特定策略（RoutingStatePersisterStrategy 的路由逻辑）；
     * 当同一 machineCode 同时被通用策略和业务策略支持时，优先选择业务策略。
     * 干什么：返回 true 标记为通用策略。</p>
     *
     * @return true 表示此策略为通用策略
     */
    @Override
    public boolean isUniversal() {
        // 为什么：此策略为通用策略，优先级低于业务特定策略 —— 干什么：返回 true 标记为 universal
        return true;
    }

    /**
     * 解析并缓存指定 machineCode 的 MapperHolder。
     *
     * <p>为什么：将 bizEntity FQN → TableInfo → Mapper FQN → BaseMapper 的反射链封装为独立方法，
     * 便于启动时预解析和运行时懒加载复用；StatemachineConfigInitializer 已在启动期完成字段校验，
     * 此处可安全假设 bizEntity 可加载、TableInfo 非空、Mapper 可解析。
     * 干什么：按 machineCode 获取定义 → Class.forName 加载实体类 → TableInfoHelper.getTableInfo 获取表元数据 →
     * getCurrentNamespace 获取 Mapper FQN → sqlSessionTemplate.getMapper 获取 BaseMapper → 构造 MapperHolder 缓存。</p>
     *
     * @param machineCode 业务编码
     * @return 解析得到的 MapperHolder
     */
    private MapperHolder resolveAndCache(String machineCode) {
        // 为什么：先查缓存避免重复解析（运行时懒加载场景下可能已被预解析） —— 干什么：从 mapperCache 获取已缓存的 MapperHolder
        MapperHolder cached = mapperCache.get(machineCode);
        if (cached != null) {
            return cached;
        }
        // 为什么：需要 machineCode 对应的定义获取 bizEntity/bizColumn/bizIdColumn 元数据 —— 干什么：从注册表获取定义
        StateMachineDefinition definition = registry.getDefinition(machineCode);
        if (definition == null) {
            // 为什么：machineCode 未注册说明配置不一致，需明确报错 —— 干什么：抛出异常说明未找到定义
            throw new IllegalStateException("未找到 machineCode 对应的定义: " + machineCode);
        }
        // 为什么：bizEntity 是全限定类名，需要加载实体类以获取 TableInfo —— 干什么：Class.forName 加载业务实体类
        Class<?> entityClass = loadEntityClass(definition.getBizEntity(), machineCode);
        // 为什么：TableInfo 是 MyBatis-Plus 解析 @TableName/@TableId/@TableField 后的元数据，是后续列校验与 Mapper 解析的基础 —— 干什么：获取 TableInfo
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        if (tableInfo == null) {
            // 为什么：TableInfo 为空说明实体未被 MP 管理（无 @TableName 或 Mapper 未注册） —— 干什么：抛出异常说明未找到 TableInfo
            throw new IllegalStateException("未找到实体类的 TableInfo: " + entityClass.getName());
        }
        // 为什么：需要 Mapper FQN 来获取 BaseMapper 实例 —— 干什么：tableInfo.getCurrentNamespace 获取 Mapper 全限定名
        String mapperClassName = tableInfo.getCurrentNamespace();
        // 为什么：需要加载 Mapper 接口类才能调用 getMapper —— 干什么：Class.forName 加载 Mapper 接口类
        Class<?> mapperClass = loadMapperClass(mapperClassName, machineCode);
        // 为什么：需要 BaseMapper 实例执行数据库操作 —— 干什么：sqlSessionTemplate.getMapper 获取 Mapper 代理
        BaseMapper<?> mapper = (BaseMapper<?>) sqlSessionTemplate.getMapper(mapperClass);
        // 为什么：需要确定主键列名用于 WHERE 条件 —— 干什么：优先用 definition.bizIdColumn，否则取 TableInfo.keyColumn
        String idColumn = resolveIdColumn(definition, tableInfo, machineCode);
        // 为什么：需要状态列名用于读写 —— 干什么：从 definition 获取 bizColumn
        String bizColumn = definition.getBizColumn();
        // 为什么：缓存解析结果避免重复反射 —— 干什么：构造 MapperHolder 放入 mapperCache
        MapperHolder holder = new MapperHolder(mapper, idColumn, bizColumn);
        mapperCache.put(machineCode, holder);
        // 为什么：记录缓存成功日志便于排查 —— 干什么：打印 info 日志记录 Mapper 解析结果
        log.info("EntityMapperStatePersisterStrategy 缓存 MapperHolder: machineCode={}, mapper={}, idColumn={}, bizColumn={}",
                machineCode, mapperClassName, idColumn, bizColumn);
        return holder;
    }

    /**
     * 加载 biz-entity 对应的 Class 对象。
     *
     * <p>为什么：biz-entity 是全类名（FQN），必须转为 Class 才能做 TableInfo 解析；
     * ClassNotFoundException 意味着类不在 classpath 中，属于配置错误，应 fail-fast。
     * 干什么：调用 Class.forName 加载类，失败时抛出带 machineCode 与 biz-entity 的 IllegalStateException。</p>
     *
     * @param bizEntity   业务实体全限定类名
     * @param machineCode 业务编码（用于错误信息）
     * @return 实体 Class 对象
     */
    private Class<?> loadEntityClass(String bizEntity, String machineCode) {
        try {
            // 为什么：Class.forName 是 JDK 标准类加载方式，与 MyBatis-Plus 内部加载逻辑一致 —— 干什么：尝试加载实体类
            return Class.forName(bizEntity);
        } catch (ClassNotFoundException e) {
            // 为什么：类不存在是配置错误，需明确反馈给定义编写方 —— 干什么：抛出带 machineCode 与 biz-entity 的异常说明类加载失败
            throw new IllegalStateException(
                    "无法加载 bizEntity 类: machineCode=" + machineCode + ", bizEntity=" + bizEntity, e);
        }
    }

    /**
     * 加载 Mapper 接口对应的 Class 对象。
     *
     * <p>为什么：Mapper FQN 需要转为 Class 才能调用 sqlSessionTemplate.getMapper；
     * ClassNotFoundException 意味着 Mapper 接口不在 classpath 中，属于配置错误，应 fail-fast。
     * 干什么：调用 Class.forName 加载 Mapper 类，失败时抛出带 machineCode 与 Mapper FQN 的 IllegalStateException。</p>
     *
     * @param mapperClassName Mapper 全限定类名
     * @param machineCode     业务编码（用于错误信息）
     * @return Mapper Class 对象
     */
    private Class<?> loadMapperClass(String mapperClassName, String machineCode) {
        try {
            // 为什么：Class.forName 是 JDK 标准类加载方式 —— 干什么：尝试加载 Mapper 接口类
            return Class.forName(mapperClassName);
        } catch (ClassNotFoundException e) {
            // 为什么：Mapper 类不存在是配置错误，需明确反馈 —— 干什么：抛出带 machineCode 与 Mapper FQN 的异常说明类加载失败
            throw new IllegalStateException(
                    "无法加载 Mapper 类: machineCode=" + machineCode + ", mapperClassName=" + mapperClassName, e);
        }
    }

    /**
     * 解析主键列名：优先用 definition.bizIdColumn，否则取 TableInfo.keyColumn。
     *
     * <p>为什么：biz-id-column 是可选配置，用于覆盖 @TableId 的 keyColumn；
     * 未声明时默认取 @TableId 的 keyColumn，与 StatemachineConfigInitializer 的校验逻辑一致。
     * 干什么：判断 definition.getBizIdColumn() 是否非空，非空则使用；否则取 tableInfo.getKeyColumn()。</p>
     *
     * @param definition  状态机定义
     * @param tableInfo   MyBatis-Plus 表元数据
     * @param machineCode 业务编码（用于错误信息）
     * @return 主键列名
     */
    private String resolveIdColumn(StateMachineDefinition definition, TableInfo tableInfo, String machineCode) {
        // 为什么：bizIdColumn 是可选配置，优先使用显式声明 —— 干什么：取 definition 中的 bizIdColumn
        String bizIdColumn = definition.getBizIdColumn();
        if (!Strings.isBlank(bizIdColumn)) {
            // 为什么：显式声明了 bizIdColumn，直接使用 —— 干什么：返回声明的列名
            return bizIdColumn;
        }
        // 为什么：未声明 bizIdColumn 时回退到 @TableId 的 keyColumn —— 干什么：取 tableInfo 的主键列名
        String keyColumn = tableInfo.getKeyColumn();
        if (Strings.isBlank(keyColumn)) {
            // 为什么：keyColumn 为空说明实体未配置主键，无法做条件更新 —— 干什么：抛出异常说明主键列名为空
            throw new IllegalStateException(
                    "无法解析主键列名: machineCode=" + machineCode + ", bizEntity=" + definition.getBizEntity());
        }
        // 为什么：成功获取到主键列名 —— 干什么：返回 tableInfo 的 keyColumn
        return keyColumn;
    }
}
