package com.galgame.controller;

import java.util.List;

/**
 * 新建帖子 JSON 请求体（multipart 表单直接走 @RequestParam）。
 * <p>author 不再接收：由登录 token 对应的用户自动填充。
 * sections 为多标签数组（新），section 为单值标签（向后兼容），二者可并存。
 */
public record CreatePostRequest(String category, List<String> sections, String section, String title, String content) {
}
