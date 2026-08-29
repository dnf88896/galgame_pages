package com.galgame.model;

/**
 * 角色简要信息：详情页关联数组元素（{id, name, image, description}），
 * 用于 galgame 详情里的角色数组；image 为角色封面图 URL（可为 null）；description 为角色在作品中的定位/备注（可为空字符串）。
 */
public record CharacterBrief(Long id, String name, String image, String description) {
}
