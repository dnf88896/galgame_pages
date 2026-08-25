package com.galgame.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.AuthContext;
import com.galgame.dao.NotificationDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Notification;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 消息通知接口。三个接口均需登录（/api/notifications/** 已注册到鉴权拦截器），
 * 登录用户 id 通过 {@link AuthContext#currentUserId(HttpServletRequest)} 获取。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationDao notificationDao;
    private final UserDao userDao;

    public NotificationController(NotificationDao notificationDao, UserDao userDao) {
        this.notificationDao = notificationDao;
        this.userDao = userDao;
    }

    /** 1. 当前用户的通知列表（最近 50 条，id 倒序），actor 信息随条附带 */
    @GetMapping
    public ResponseEntity<Object> list(HttpServletRequest request) {
        long uid = AuthContext.currentUserId(request);
        List<Notification> notifications = notificationDao.findByUserId(uid, 50);
        if (notifications.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Long> actorIds = notifications.stream()
                .map(Notification::actorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, User> userById = new LinkedHashMap<>();
        for (User user : userDao.findByIds(actorIds)) {
            userById.put(user.id(), user);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Notification n : notifications) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.id());
            item.put("type", n.type());
            item.put("actor", n.actorId() == null ? null : userById.get(n.actorId()));
            item.put("post_id", n.postId());
            item.put("reply_id", n.replyId());
            item.put("title", n.title());
            item.put("content", n.content());
            item.put("is_read", n.isRead());
            item.put("created_at", n.createdAt());
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /** 2. 未读通知数 */
    @GetMapping("/unread-count")
    public ResponseEntity<Object> unreadCount(HttpServletRequest request) {
        long uid = AuthContext.currentUserId(request);
        return ResponseEntity.ok(Map.of("unread", notificationDao.countUnread(uid)));
    }

    /** 3. 全部通知标为已读，返回实际更新的行数 */
    @PostMapping("/read")
    public ResponseEntity<Object> markAllRead(HttpServletRequest request) {
        long uid = AuthContext.currentUserId(request);
        return ResponseEntity.ok(Map.of("read", notificationDao.markAllRead(uid)));
    }
}
