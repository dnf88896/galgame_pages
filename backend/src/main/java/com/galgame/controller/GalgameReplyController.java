package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.GalgameReplyDao;
import com.galgame.dao.ReportDao;
import com.galgame.model.GalgameReply;
import com.galgame.model.LikeResult;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 详情页评论的点赞 / 点踩 / 删除 / 举报接口。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定权限（参照 {@link GalgameController} / {@link PostController}）。
 */
@RestController
@RequestMapping("/api/galgame-replies")
public class GalgameReplyController {

    private final GalgameReplyDao galgameReplyDao;
    private final ReportDao reportDao;
    private final TokenService tokenService;

    public GalgameReplyController(GalgameReplyDao galgameReplyDao, ReportDao reportDao, TokenService tokenService) {
        this.galgameReplyDao = galgameReplyDao;
        this.reportDao = reportDao;
        this.tokenService = tokenService;
    }

    /** 评论点赞 toggle（需登录，按 user_id 去重） */
    @PostMapping("/{id}/like")
    public ResponseEntity<Object> toggleLike(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = resolveUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<LikeResult> result = galgameReplyDao.toggleLike(id, uid.get());
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of("liked", result.get().liked(), "like_count", result.get().likeCount()));
    }

    /** 评论点踩 toggle（需登录，与点赞独立，不互斥） */
    @PostMapping("/{id}/dislike")
    public ResponseEntity<Object> toggleDislike(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = resolveUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<LikeResult> result = galgameReplyDao.toggleDislike(id, uid.get());
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of("disliked", result.get().liked(), "dislike_count", result.get().likeCount()));
    }

    /** 删除自己的评论（需登录 + 本人）。子评论保留、parent_id 自动置 NULL。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = resolveUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<GalgameReply> opt = galgameReplyDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        GalgameReply reply = opt.get();
        // ⚠️ userId 与 uid 都是 Long 包装类，必须用 equals 数值比较（`!=` 是引用比较，恒判不等导致作者也 403）
        if (reply.userId() == null || !reply.userId().equals(uid.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "只能删除自己的回复。"));
        }
        galgameReplyDao.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 举报评论（需登录）：target_type 用 "greply"（Galgame 评论，reports.target_type VARCHAR(10)），reason 可空 */
    @PostMapping("/{id}/report")
    public ResponseEntity<Object> report(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         HttpServletRequest request) {
        Optional<Long> uid = resolveUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<GalgameReply> reply = galgameReplyDao.findById(id);
        if (reply.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        reportDao.insertIfAbsent(uid.get(), "greply", id, extractReason(body));
        return ResponseEntity.ok(Map.of("reported", true));
    }

    /** 提取举报原因：可选，trim 后超 200 字符截断到 200（同 ReportController.extractReason） */
    private String extractReason(Map<String, String> body) {
        if (body == null) {
            return null;
        }
        String raw = body.get("reason");
        if (raw == null) {
            return null;
        }
        String reason = raw.trim();
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);
        }
        return reason;
    }

    private Optional<Long> resolveUserId(HttpServletRequest request) {
        return tokenService.resolveUserId(request.getHeader("Authorization"));
    }
}
