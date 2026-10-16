package com.worm.statemachine.core;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态机定义模型。
 *
 * <p>用于配置驱动（YAML）方式定义状态机。每条定义包含一个业务的状态机完整元数据：
 * 业务编码、初始状态、以及所有流转规则。启动时 YAML 配置被解析为此模型，
 * 然后注册到 StateMachineRegistry 中。</p>
 */
@Data
@NoArgsConstructor
public class StateMachineDefinition {

    /**
     * 业务编码，用于唯一标识一个状态机定义
     */
    private String machineCode;

    /**
     * 业务实体全限定类名（FQN），状态机持久化时定位 MyBatis-Plus 实体类
     */
    private String bizEntity;

    /**
     * 业务实体中状态字段对应的数据库列名，持久化时写入/读取该列
     */
    private String bizColumn;

    /**
     * 业务实体主键列名，可选。未配置时默认取实体 @TableId 的 keyColumn
     */
    private String bizIdColumn;

    /**
     * 初始状态标识，新业务对象进入状态机时的起始状态
     */
    private String initialState;

    /**
     * 状态流转定义列表
     */
    private List<TransitionDefinition> transitions = new ArrayList<>();

    /**
     * 状态流转定义。
     *
     * <p>描述一条从源状态到目标状态的合法转换规则。
     * 使用 record 类型保证不可变性，符合配置模型的语义。
     * 纯 YAML 驱动模型，不含条件/动作回调引用（conditionBean/actionBean 已移除）。</p>
     */
    public record TransitionDefinition(String sourceState, String event, String targetState) {
        /**
         * 构造简单流转，要求源状态、事件、目标状态均非空。
         *
         * @param sourceState 源状态标识
         * @param event       事件标识
         * @param targetState 目标状态标识
         */
        public TransitionDefinition {
            // 为什么：流转三要素不可缺失，否则状态机无法路由 —— 干什么：校验非空并快速失败
            if (sourceState == null || event == null || targetState == null) {
                throw new IllegalArgumentException("TransitionDefinition sourceState, event, and targetState MUST NOT be null");
            }
        }
    }
}