package com.galgame.model;

/**
 * Galgame-角色关联请求元素：characters 数组项 {id, description}，
 * id 为角色 id，description 为角色在作品中的定位/备注（允许空字符串）。
 */
public record GalgameCharacterLink(Long characterId, String description) {
}
