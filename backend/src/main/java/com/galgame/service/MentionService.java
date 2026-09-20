package com.galgame.service;

import java.util.LinkedHashSet;
import java.util.Optional;
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

    /**
     * @用户名匹配：用户名 2~20 位（中英文 / 数字 / 下划线）。
     * <p><b>前后边界都只排除 ASCII 词字符</b>（{@code A-Za-z0-9_}）：既能挡住邮箱（{@code foo@bar.com}）、
     * {@code C:@users} 这类拼接，也能让超长用户名（{@code @} + 21 位）照旧不匹配，
     * 同时不会误挡中文紧贴的情形。
     * <p>⚠️ 2026-09-20 修复：前边界原为 {@code (?<![\p{L}\p{N}_])}，而 {@code \p{L}} **包含汉字**，
     * 导致中文正文里紧贴的 @ 整段漏解析——「谢谢@某某」在中文论坛是高频写法，@ 通知就此静默丢失。
     * <p>用户名本身可含中文（如 {@code @小明}），故字符类保留 {@code \p{L}}；这也意味着
     * {@code @alice你好} 会把「你好」一并吞进候选串（正则无法区分「用户名带中文」与「用户名后紧贴正文」），
     * 交由 {@link #resolveUser(String)} 从长到短回退试探，取第一个真实存在的用户。
     */
    public static final Pattern MENTION_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9_])@([\\p{L}\\p{N}_]{2,20})(?![A-Za-z0-9_])");

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
                resolveUser(matcher.group(1)).ifPresent(user -> {
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

    /**
     * 把正则捕获到的候选串解析成真实存在的用户。
     * <p>候选串可能是「用户名 + 紧贴其后的正文中文」（{@code @alice你好} → 候选 {@code alice你好}），
     * 故先按完整候选查库；未命中且在候选含非 ASCII 字符时，从右往左逐字符缩短重试
     * （用户名最短 2 位），取第一个真实存在的用户。
     * <p>纯 ASCII 候选未命中即放弃——不用回退（ASCII 用户名不会被中文粘住），避免无谓的多次查询。
     * <p>例：{@code @小明你好} → 试「小明你好」→ 试「小明你」→「小明」命中。
     */
    private Optional<User> resolveUser(String candidate) {
        Optional<User> direct = userDao.findByUsername(candidate);
        if (direct.isPresent() || isAscii(candidate)) {
            return direct;
        }
        for (int end = candidate.length() - 1; end >= 2; end--) {
            Optional<User> shortened = userDao.findByUsername(candidate.substring(0, end));
            if (shortened.isPresent()) {
                return shortened;
            }
        }
        return Optional.empty();
    }

    /** 是否全为 ASCII 字符（用于判断候选串里有没有可能粘上正文中文） */
    private static boolean isAscii(String value) {
        return value.chars().allMatch(c -> c < 128);
    }
}
