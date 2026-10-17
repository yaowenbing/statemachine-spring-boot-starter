package com.worm.statemachine.core;

/**
 * 字符串空判定工具。
 *
 * <p>为什么：JDK 11 才提供 {@code String.isBlank()}，本开源版本需兼容 JDK 8，
 * 且不想为一次空判定引入第三方工具依赖 —— 干什么：提供等价的 isBlank 判定，
 * 语义与 {@code String.isBlank()} 一致（null 或去除首尾空白后为空串时返回 true）。</p>
 */
public final class Strings {

    private Strings() {
    }

    /**
     * 判断字符串是否为空白（null 或去除首尾空白后为空）。
     *
     * @param s 待判定字符串，允许 null
     * @return null 或去除首尾空白后为空串时返回 true，否则 false
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
