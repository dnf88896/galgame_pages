package com.galgame.model;

/**
 * Galgame-制作人员关联请求元素：staffs 数组项 {id, description}，
 * id 为制作人员 id，description 为制作人员在作品中的职责/备注（允许空字符串）。
 */
public record GalgameStaffLink(Long staffId, String description) {
}
