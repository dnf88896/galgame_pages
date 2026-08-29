package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.AuthContext;
import com.galgame.dao.GalgameReplyDao;
import com.galgame.dao.NotificationDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.dao.ReportDao;
import com.galgame.dao.UserDao;
import com.galgame.model.GalgameReply;
import com.galgame.model.Post;
import com.galgame.model.Reply;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 举报接口。三个接口均需登录（路径已注册到鉴权拦截器），
 * 登录用户 id 通过 {@link AuthContext#currentUserId(HttpServletRequest)} 获取。
 */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportDao reportDao;
    private final PostDao postDao;
    private final ReplyDao replyDao;
    private final GalgameReplyDao galgameReplyDao;
    private final UserDao userDao;
    private final NotificationDao notificationDao;

    public ReportController(ReportDao reportDao, PostDao postDao, ReplyDao replyDao, GalgameReplyDao galgameReplyDao,
                            UserDao userDao, NotificationDao notificationDao) {
        this.reportDao = reportDao;
        this.postDao = postDao;
        this.replyDao = replyDao;
        this.galgameReplyDao = galgameReplyDao;
        this.userDao = userDao;
        this.notificationDao = notificationDao;
    }

    /** 举报帖子（需登录） */
    @PostMapping("/posts/{id}/report")
    public ResponseEntity<Object> reportPost(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body,
                                             HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        if (!postDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        reportDao.insertIfAbsent(userId, "post", id, extractReason(body));
        return ResponseEntity.ok(Map.of("reported", true));
    }

    /** 举报评论（需登录） */
    @PostMapping("/replies/{id}/report")
    public ResponseEntity<Object> reportReply(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<Reply> reply = replyDao.findById(id);
        if (reply.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "评论不存在。"));
        }
        reportDao.insertIfAbsent(userId, "reply", id, extractReason(body));
        return ResponseEntity.ok(Map.of("reported", true));
    }

    /** 举报列表（需登录 + 管理员，admin_level ≥ 1）。每条返回嵌套结构：
     *  reporter{id,username}、post{id,title} 或 reply{id,content,post_id}（目标已删则对应对象为 null）。 */
    @GetMapping("/reports")
    public ResponseEntity<Object> listReports(HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        User u = userDao.findById(userId)
                .orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (u.adminLevel() == null || u.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        return ResponseEntity.ok(toNestedList(reportDao.findAll()));
    }

    /** 处理举报（需登录 + 管理员）。action=delete 删除目标内容并警告作者；action=ban 删除目标内容并封禁作者（body：ban_permanent="true" 永久封禁，或 ban_days=1~3650 指定天数）；action=ignore 忽略（不删内容）。
     *  三种动作都会向举报人发一条受理结果通知。 */
    @Transactional
    @PostMapping("/reports/{id}/handle")
    public ResponseEntity<Object> handleReport(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, String> body,
                                               HttpServletRequest request) {
        long adminId = AuthContext.currentUserId(request);
        User admin = userDao.findById(adminId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (admin.adminLevel() == null || admin.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        Optional<ReportDao.ReportRow> rowOpt = reportDao.findById(id);
        if (rowOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "举报不存在。"));
        }
        ReportDao.ReportRow row = rowOpt.get();
        String action = body == null ? null : body.get("action");
        String banPermanentStr = body == null ? null : body.get("ban_permanent");
        String banDaysStr = body == null ? null : body.get("ban_days");
        int status;
        String result;
        if ("delete".equals(action)) {
            status = 1;
            result = "已删除并警告作者";
            Long author = deleteTargetContent(row);
            if (author == null) {
                result = "内容已删除";
            } else {
                // 目标已删，post_id/reply_id 必须传 null（通知表 FK ON DELETE CASCADE 会拒绝引用已删内容）
                if ("post".equals(row.targetType())) {
                    notifyWarning(adminId, author, null, null,
                            "你发布的帖子", "你发布的帖子因违规已被管理员删除，请遵守社区规范。");
                } else {
                    notifyWarning(adminId, author, null, null,
                            "你发布的评论", "你发布的评论因违规已被管理员删除，请遵守社区规范。");
                }
            }
        } else if ("ban".equals(action)) {
            boolean permanent = "true".equalsIgnoreCase(banPermanentStr);
            LocalDateTime until;
            if (permanent) {
                until = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
            } else {
                int days;
                try {
                    days = banDaysStr == null ? 0 : Integer.parseInt(banDaysStr.trim());
                } catch (NumberFormatException e) {
                    days = 0;
                }
                if (days < 1 || days > 3650) {
                    return ResponseEntity.badRequest().body(Map.of("error", "请指定封禁时长（1~3650 天）或选择永久封禁。"));
                }
                until = LocalDateTime.now().plusDays(days);
            }
            status = 1;
            Long author = deleteTargetContent(row);
            if (author == null) {
                result = "内容已删除";
            } else {
                userDao.updateBanUntil(author, until);
                result = permanent
                        ? "已删除并永久封禁"
                        : "已删除并封禁作者（封禁至 " + until.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "）";
            }
        } else if ("ignore".equals(action)) {
            status = 2;
            result = "已忽略";
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "无效的操作。"));
        }
        // 无论哪种处理，都通知举报人受理结果
        notifyReporter(adminId, row, result);
        reportDao.markHandled(id, status, result);
        return ResponseEntity.ok(Map.of("ok", true, "status", status, "result", result));
    }

    /** 给目标内容作者发警告通知（type=warning） */
    private void notifyWarning(long adminId, long authorUserId, Long postId, Long replyId,
                               String targetDesc, String content) {
        notificationDao.insert(authorUserId, "warning", adminId, postId, replyId, "内容被删除", content);
    }

    /** 给举报人发受理结果通知（type=report）：您在 {举报时间} 提交的举报已受理，结果为：{result} */
    private void notifyReporter(long adminId, ReportDao.ReportRow row, String result) {
        // post_id/reply_id 统一传 null：目标可能已被删除，FK 拒绝引用已删内容
        String time = row.createdAt() == null ? "" : row.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        notificationDao.insert(row.reporterId(), "report", adminId, null, null,
                "举报受理结果", "您在 " + time + " 提交的举报已受理，结果为：" + result + "。");
    }

    /** 删除帖子附件目录 uploads/post_<id>/（数据库记录由外键级联） */
    private void deletePostAttachmentFiles(Long postId) {
        Path dir = Path.of("uploads/post_" + postId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 删除被举报目标内容，返回目标作者 id（目标已删返回 null） */
    private Long deleteTargetContent(ReportDao.ReportRow row) {
        if ("post".equals(row.targetType())) {
            Optional<Post> p = postDao.findById(row.targetId());
            if (p.isEmpty()) return null;
            postDao.deleteById(row.targetId());
            deletePostAttachmentFiles(row.targetId());
            return p.get().userId();
        }
        // Galgame 评论（greply）：子评论 parent_id 由外键 ON DELETE SET NULL 自动置空；赞/踩由外键级联删除
        if ("greply".equals(row.targetType())) {
            Optional<GalgameReply> gr = galgameReplyDao.findById(row.targetId());
            if (gr.isEmpty()) return null;
            galgameReplyDao.deleteById(row.targetId());
            return gr.get().userId();
        }
        Optional<Reply> r = replyDao.findById(row.targetId());
        if (r.isEmpty()) return null;
        replyDao.deleteById(row.targetId());
        postDao.decrementReplyCount(r.get().postId());
        return r.get().userId();
    }

    /** 把扁平行转成前端友好的嵌套对象列表（HashMap 允许 null value，reporter/post/reply 缺失时为 null） */
    private List<Map<String, Object>> toNestedList(List<ReportDao.ReportRow> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", row.id());
            m.put("target_type", row.targetType());
            m.put("target_id", row.targetId());
            m.put("reason", row.reason());
            m.put("created_at", row.createdAt());
            m.put("status", row.status());
            m.put("handled_at", row.handledAt());
            m.put("result", row.result());
            m.put("reporter", Map.of("id", row.reporterId(), "username", row.reporterUsername()));
            m.put("post", row.postTitle() == null ? null
                    : Map.of("id", row.targetId(), "title", row.postTitle()));
            m.put("reply", row.replyPostId() == null ? null
                    : Map.of("id", row.targetId(), "content", row.replyContent(), "post_id", row.replyPostId()));
            m.put("greply", row.galgameReplyContent() == null ? null
                    : Map.of("id", row.targetId(), "content", row.galgameReplyContent()));
            return m;
        }).toList();
    }

    /** 提取举报原因：可选，trim 后超 200 字符截断到 200 */
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
}
