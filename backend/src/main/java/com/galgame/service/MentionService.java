package com.galgame.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.galgame.dao.NotificationDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Post;
import com.galgame.model.User;

/**
 * @提及解析与消息通知。
 * <p>在发帖/回复后由上层调用 {@link #notifyMention(long, Long, Long, String, String)}，
 * 解析正文里的 {@code @用户名}，为每个命中的用户写一条 mention 通知。
 * 任何异常都只记录日志，绝不向上抛，避免影响发帖/回复主流程。
 */
@Service
public class MentionService {

    private static final Logger log = LoggerFactory.getLogger(MentionService.class);

    /** @用户名匹配：前后不能是字母/数字/下划线（避免匹配邮箱/路径里的 @），用户名 2~20 位 */
    public static final Pattern MENTION_PATTERN =
            Pattern.compile("(?<![\\p{L}\\p{N}_])@([\\p{L}\\p{N}_]{2,20})(?![\\p{L}\\p{N}_])");

    private final UserDao userDao;
    private final PostDao postDao;
    private final NotificationDao notificationDao;

    public MentionService(UserDao userDao, PostDao postDao, NotificationDao notificationDao) {
        this.userDao = userDao;
        this.postDao = postDao;
        this.notificationDao = notificationDao;
    }

    /**
     * 解析 content 中的 {@code @用户名}，给每个命中且非 actor 自己的用户生成一条 mention 通知。
     * <p>content 为 null / 空白时直接返回；title 为 null 时从 postId 反查帖子标题；
     * content 超过 500 字截断。整体静默失败，异常只记日志。
     *
     * @param actorId  触发者（发帖/回复用户）
     * @param postId   关联帖子，可为 null
     * @param replyId  关联回复，可为 null
     * @param title    通知标题（通常为帖子标题），可为 null
     * @param content  通知正文（帖/回复内容，含 @），可为 null
     */
    public void notifyMention(long actorId, Long postId, Long replyId, String title, String content) {
        try {
            if (content == null || content.isBlank()) {
                return;
            }
            Set<Long> userIds = new LinkedHashSet<>();
            Matcher matcher = MENTION_PATTERN.matcher(content);
            while (matcher.find()) {
                String username = matcher.group(1);
                userDao.findByUsername(username).ifPresent(user -> {
                    if (user.id() != actorId) {
                        userIds.add(user.id());
                    }
                });
            }
            if (userIds.isEmpty()) {
                return;
            }
            String resolvedTitle = title;
            if (resolvedTitle == null && postId != null) {
                resolvedTitle = postDao.findById(postId).map(Post::title).orElse(null);
            }
            String contentTrunc = content.length() > 500 ? content.substring(0, 500) : content;
            for (Long userId : userIds) {
                notificationDao.insert(userId, "mention", actorId, postId, replyId, resolvedTitle, contentTrunc);
            }
        } catch (Exception e) {
            log.warn("解析 @提及并写入通知失败 actorId={} postId={} replyId={}", actorId, postId, replyId, e);
        }
    }
}
