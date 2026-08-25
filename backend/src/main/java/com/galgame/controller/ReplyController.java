package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.AuthContext;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.model.LikeResult;
import com.galgame.model.Reply;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 回复点赞接口。
 */
@RestController
@RequestMapping("/api/replies")
public class ReplyController {

    private final ReplyDao replyDao;
    private final PostDao postDao;

    public ReplyController(ReplyDao replyDao, PostDao postDao) {
        this.replyDao = replyDao;
        this.postDao = postDao;
    }

    /** 6. 回复点赞 toggle（需登录，按 user_id 去重） */
    @PostMapping("/{id}/like")
    public ResponseEntity<Object> toggleReplyLike(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<LikeResult> result = replyDao.toggleLike(id, userId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of("liked", result.get().liked(), "like_count", result.get().likeCount()));
    }

    /** 删除自己的回复（需登录 + 本人）。子回复保留、parent_id 自动置 NULL；回复数 -1。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteReply(@PathVariable Long id, HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<Reply> opt = replyDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "回复不存在。"));
        }
        Reply reply = opt.get();
        if (reply.userId() == null || reply.userId() != userId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "只能删除自己的回复。"));
        }
        Long postId = reply.postId();
        replyDao.deleteById(id);
        postDao.decrementReplyCount(postId);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
