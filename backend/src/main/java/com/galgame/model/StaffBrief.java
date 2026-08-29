package com.galgame.model;

/**
 * 制作人员简要信息：详情页关联数组元素（{id, name, description}），
 * 用于 galgame 详情里的制作人员数组；description 为制作人员在作品中的职责/备注（可为空字符串）。
 */
public record StaffBrief(Long id, String name, String description) {
}
