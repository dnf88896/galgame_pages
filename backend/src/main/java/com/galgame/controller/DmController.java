package com.galgame.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.BlockDao;
import com.galgame.dao.DmDao;
import com.galgame.dao.UserDao;
import com.galgame.model.DmMessage;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 私聊（DM）接口。所有 /api/dm 路径均未注册到鉴权拦截器，
 * 这里手动解析 token 判定登录（与 PostController.deletePost 一致）。
 */
@RestController
@RequestMapping("/api/dm")
public class DmController {

    private final DmDao dmDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final BlockDao blockDao;

    public DmController(DmDao dmDao, UserDao userDao, TokenService tokenService, BlockDao blockDao) {
        this.dmDao = dmDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.blockDao = blockDao;
    }

    /** 会话列表条目：对方用户、最后一条消息（无则 null）、未读数 */
    private record ConversationItem(User user, DmMessage lastMessage, int unread) {
    }

    /** 1. 当前用户的会话列表，按最后一条消息 id 倒序（无消息的会话排最后） */
    @GetMapping("/conversations")
    public ResponseEntity<Object> listConversations(HttpServletRequest request) {
        Optional<Long> uidOpt = requireLogin(request);
        if (uidOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long me = uidOpt.get();
        List<ConversationItem> items = new ArrayList<>();
        for (DmDao.ConversationRef ref : dmDao.findConversationsByUser(me)) {
            User other = userDao.findById(ref.otherId()).orElse(null);
            if (other == null) {
                continue; // 对方账号已删除（理论上会话会级联删除，这里兜底）
            }
            DmMessage lastMessage = dmDao.findLastMessage(ref.conversationId()).orElse(null);
            int unread = dmDao.countUnread(ref.conversationId(), me);
            items.add(new ConversationItem(other, lastMessage, unread));
        }
        items.sort((a, b) -> {
            Long aId = a.lastMessage() == null ? null : a.lastMessage().id();
            Long bId = b.lastMessage() == null ? null : b.lastMessage().id();
            if (aId == null && bId == null) {
                return 0;
            }
            if (aId == null) {
                return 1;
            }
            if (bId == null) {
                return -1;
            }
            return Long.compare(bId, aId);
        });
        return ResponseEntity.ok(items);
    }

    /** 2. 与某用户的会话：无则创建，返回全部消息（id 升序），并把对方发来的未读标为已读 */
    @GetMapping("/conversations/{userId}")
    public ResponseEntity<Object> getConversation(@PathVariable Long userId, HttpServletRequest request) {
        Optional<Long> uidOpt = requireLogin(request);
        if (uidOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long me = uidOpt.get();
        if (userId == null || userId == me) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能给自己发私聊。"));
        }
        User other = userDao.findById(userId).orElse(null);
        if (other == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        Long conversationId = dmDao.getOrCreateConversation(me, userId);
        // 只能把对方发给我的未读消息标为已读
        dmDao.markRead(conversationId, me);
        List<DmMessage> messages = dmDao.findMessages(conversationId);
        boolean blockedByMe = blockDao.isBlocked(me, userId);
        boolean blockedByThem = blockDao.isBlocked(userId, me);
        return ResponseEntity.ok(Map.of(
                "user", other, "messages", messages,
                "blocked_by_me", blockedByMe, "blocked_by_them", blockedByThem));
    }

    /** 3.1 撤回消息（仅发送者本人）：置 is_recalled=true，已读不做。401 未登录 / 403 非发送者 / 404 不存在 */
    @PostMapping("/messages/{id}/recall")
    public ResponseEntity<Object> recallMessage(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uidOpt = requireLogin(request);
        if (uidOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long me = uidOpt.get();
        Optional<DmMessage> opt = dmDao.findMessageById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "消息不存在。"));
        }
        DmMessage message = opt.get();
        if (message.senderId() != me) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "只能撤回自己发送的消息。"));
        }
        if (message.isRecalled()) {
            // 已撤回：幂等返回当前消息，不因超时再报错（按钮已隐藏，正常流程不会触发）
            return ResponseEntity.ok(message);
        }
        // 发送超过两分钟不可撤回（前端按 created_at 隐藏按钮，这里后端硬校验兜底）
        if (message.createdAt().isBefore(LocalDateTime.now().minusMinutes(2))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "发送超过两分钟，无法撤回。"));
        }
        DmMessage updated = dmDao.recallMessage(id, me).orElse(message);
        return ResponseEntity.ok(updated);
    }

    /** 4. 发消息 */
    @PostMapping("/conversations/{userId}/messages")
    public ResponseEntity<Object> sendMessage(@PathVariable Long userId,
                                              @RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        Optional<Long> uidOpt = requireLogin(request);
        if (uidOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        long me = uidOpt.get();
        if (userId == null || userId == me) {
            return ResponseEntity.badRequest().body(Map.of("error", "不能给自己发私聊。"));
        }
        if (userDao.findById(userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "用户不存在。"));
        }
        String content = body == null ? null : body.get("content");
        if (content == null || content.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息内容不能为空。"));
        }
        content = content.trim();
        if (content.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        boolean blockedByMe = blockDao.isBlocked(me, userId);
        if (blockedByMe) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "你已屏蔽对方，无法发送消息。"));
        }
        boolean blockedByThem = blockDao.isBlocked(userId, me);
        if (blockedByThem) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "对方已屏蔽你，无法发送消息。"));
        }
        Long conversationId = dmDao.getOrCreateConversation(me, userId);
        Long messageId = dmDao.insertMessage(conversationId, me, content);
        // 从 DB 回查，保证 created_at / is_read 为数据库真实值
        DmMessage message = dmDao.findMessageById(messageId)
                .orElseThrow(() -> new IllegalStateException("写入的消息读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    /** 手动鉴权：该路径未注册到拦截器，解析 token 判定登录 */
    private Optional<Long> requireLogin(HttpServletRequest request) {
        return tokenService.resolveUserId(request.getHeader("Authorization"));
    }
}
