package com.worm.statemachine.persist;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 业务 Mapper 持有者。
 *
 * <p>为什么：需要将业务 BaseMapper 实例与其对应的业务主键列名、状态列名绑定为一个整体缓存，
 * 避免运行时重复解析 TableInfo 与 Mapper 全限定名；不可变对象天然线程安全，
 * 与 ConcurrentHashMap 配合可无锁并发读。
 * 干什么：封装业务 BaseMapper 代理及其对应的业务主键列名（idColumn）、状态列名（bizColumn），
 * 供 EntityMapperStatePersisterStrategy 缓存复用。</p>
 *
 * @author worm
 */
public final class MapperHolder {

    /**
     * 业务 BaseMapper 实例。
     *
     * <p>为什么：状态读写最终都委托给业务方逆向生成的 BaseMapper，
     * 而非通用的 Mapper 接口；BaseMapper 已绑定实体泛型，可直接构造 QueryWrapper/UpdateWrapper。</p>
     */
    private final BaseMapper<?> mapper;

    /**
     * 业务主键列名。
     *
     * <p>为什么：条件更新 WHERE 条件必须按主键定位行，列名优先取 YAML 显式声明的 biz-id-column，
     * 未声明时回退到 @TableId 的 keyColumn。</p>
     */
    private final String idColumn;

    /**
     * 状态列名。
     *
     * <p>为什么：状态读写的目标列，由 YAML biz-column 声明；
     * 启动期 StatemachineConfigInitializer 已校验该列存在于 TableInfo 字段列表，此处直接使用。</p>
     */
    private final String bizColumn;

    /**
     * 构造 Mapper 持有者。
     *
     * @param mapper    业务 BaseMapper 实例
     * @param idColumn  业务主键列名
     * @param bizColumn 状态列名
     */
    public MapperHolder(BaseMapper<?> mapper, String idColumn, String bizColumn) {
        this.mapper = mapper;
        this.idColumn = idColumn;
        this.bizColumn = bizColumn;
    }

    public BaseMapper<?> getMapper() {
        return mapper;
    }

    public String getIdColumn() {
        return idColumn;
    }

    public String getBizColumn() {
        return bizColumn;
    }
}
