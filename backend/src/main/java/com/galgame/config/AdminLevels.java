package com.galgame.config;

import java.util.Map;

/**
 * 管理员权限密码 → 权限等级 对照表。
 * <p>用户在设置里输入管理员密码完成认证：密码匹配则把该账号的 admin_level 提升到对应等级（只升不降）。
 * 后续新增更高等级时在此追加「密码 → 等级」条目即可。</p>
 */
public final class AdminLevels {

    private AdminLevels() {
    }

    /** 管理员权限密码 → 权限等级。 */
    private static final Map<String, Integer> PASSWORD_TO_LEVEL = Map.of(
            "galgame1", 1);

    /** 解析密码对应的权限等级；密码为 null 或未匹配返回 null。 */
    public static Integer resolve(String password) {
        if (password == null) {
            return null;
        }
        return PASSWORD_TO_LEVEL.get(password);
    }
}
