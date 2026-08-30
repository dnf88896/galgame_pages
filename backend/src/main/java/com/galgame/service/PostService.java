package com.galgame.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.ObjectMapper;

import com.galgame.constants.TagConstants;
import com.galgame.dao.AttachmentDao;
import com.galgame.dao.BlockDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.dao.UserDao;
import com.galgame.exception.BusinessException;
import com.galgame.model.Attachment;
import com.galgame.model.LikeResult;
import com.galgame.model.Post;
import com.galgame.model.PostFilter;
import com.galgame.model.Reply;
import com.galgame.model.User;

/**
 * 帖子业务逻辑层：帖子/回复的创建与查询、点赞/点踩/收藏、管理员分区修改与置顶、删除及文件落盘。
 * <p>业务失败统一抛 {@link BusinessException}，由 Controller 捕获后转为对应 HTTP 响应；
 * 当前登录用户 id 由 Controller 从 Authorization 头解析后传入，本层不接触 HttpServletRequest。
 */
@Service
public class PostService {

    /** 附件总大小上限 250MB */
    private static final long MAX_UPLOAD_BYTES = 250L * 1024 * 1024;

    /** 封面图上限 8MB，仅支持 png / jpg / jpeg / webp / gif */
    private static final long MAX_COVER_BYTES = 8L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final PostDao postDao;
    private final ReplyDao replyDao;
    private final AttachmentDao attachmentDao;
    private final UserDao userDao;
    private final BlockDao blockDao;
    private final MentionService mentionService;
    private final ObjectMapper objectMapper;

    public PostService(PostDao postDao, ReplyDao replyDao, AttachmentDao attachmentDao,
                       UserDao userDao, BlockDao blockDao, MentionService mentionService,
                       ObjectMapper objectMapper) {
        this.postDao = postDao;
        this.replyDao = replyDao;
        this.attachmentDao = attachmentDao;
        this.userDao = userDao;
        this.blockDao = blockDao;
        this.mentionService = mentionService;
        this.objectMapper = objectMapper;
    }

    /** 1. 帖子列表（公开）。屏蔽是双向的：不可见作者 = 我屏蔽的人 ∪ 屏蔽我的人；未登录则为空集合 */
    public List<Post> listPosts(PostFilter filter, Long currentUserId) {
        Set<Long> hidden = new HashSet<>();
        if (currentUserId != null) {
            hidden.addAll(blockDao.findBlockedUserIds(currentUserId));
            hidden.addAll(blockDao.findBlockers(currentUserId));
        }
        List<Post> posts = postDao.findAll(filter, hidden, currentUserId);
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = posts.stream().map(Post::id).toList();
        Map<Long, List<Attachment>> attachmentsByPost = attachmentDao.findByPostIds(ids);
        return posts.stream()
                .map(p -> p.withContext(null, null, null, attachmentsByPost.getOrDefault(p.id(), List.of()), null))
                .toList();
    }

    /** 2. 帖子详情（公开，可选登录）：先 +view 再读；liked 按当前用户计算 */
    public Post getPostDetail(Long id, Long currentUserId) {
        Optional<Post> opt = postDao.findById(id);
        if (opt.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        Post post = opt.get();
        // 双方任一方向屏蔽 → 帖子视为不存在
        if (currentUserId != null && post.userId() != null) {
            long uid = currentUserId;
            if (blockDao.isBlocked(uid, post.userId()) || blockDao.isBlocked(post.userId(), uid)) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
            }
        }
        postDao.incrementView(id);
        // 重新读取以拿到最新 view_count
        opt = postDao.findById(id);
        if (opt.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        post = opt.get();

        List<Attachment> attachments = attachmentDao.findByPostId(id);
        List<Reply> replies = replyDao.findByPostId(id);

        boolean liked = false;
        boolean disliked = false;
        boolean favorited = false;
        Set<Long> likedReplyIds = new HashSet<>();
        Set<Long> dislikedReplyIds = new HashSet<>();
        if (currentUserId != null) {
            long uid = currentUserId;
            liked = postDao.isLiked(id, uid);
            disliked = postDao.isDisliked(id, uid);
            favorited = postDao.isFavorited(id, uid);
            if (!replies.isEmpty()) {
                List<Long> replyIds = replies.stream().map(Reply::id).toList();
                likedReplyIds = replyDao.findLikedReplyIds(replyIds, uid);
                dislikedReplyIds = replyDao.findDislikedReplyIds(replyIds, uid);
            }
        }
        final Set<Long> likedSet = likedReplyIds;
        final Set<Long> dislikedSet = dislikedReplyIds;
        List<Reply> repliesWithLiked = replies.stream()
                .map(r -> r.withLikeState(likedSet.contains(r.id()), dislikedSet.contains(r.id())))
                .toList();

        return post.withContext(liked, disliked, favorited, attachments, repliesWithLiked);
    }

    /** 3. 新建帖子（需登录）：sections 数组优先，其次兼容单值 section，均无则为空数组；LinkedHashSet 去重保序 */
    public Post createPost(String titleRaw, String contentRaw, String categoryRaw,
                           List<String> sectionsRaw, String sectionRaw,
                           MultipartFile[] files, MultipartFile cover, long userId) {
        String title = titleRaw == null ? "" : titleRaw.trim();
        String content = contentRaw == null ? "" : contentRaw.trim();
        if (title.isEmpty() || content.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "标题和正文不能为空。");
        }
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        String author = user.nickname(); // 快照写当前昵称，改昵称后老帖/老回复由查询 COALESCE 实时显示新昵称
        // 多标签：sections 数组优先，其次兼容单值 section，均无则为空数组；LinkedHashSet 去重保序
        List<String> merged = new ArrayList<>();
        if (sectionsRaw != null && !sectionsRaw.isEmpty()) {
            for (String s : sectionsRaw) {
                if (s != null) {
                    merged.add(s.trim());
                }
            }
        } else if (sectionRaw != null && !sectionRaw.isBlank()) {
            merged.add(sectionRaw.trim());
        }
        List<String> tags = new ArrayList<>(new LinkedHashSet<>(merged));
        for (String tag : tags) {
            if (!TagConstants.isSectionKey(tag)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "无效的标签。");
            }
        }
        String category = categoryRaw == null || categoryRaw.isBlank() ? "话题" : categoryRaw.trim();
        if (title.length() > 80 || content.length() > 2000 || category.length() > 32) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "字段长度超出限制。");
        }
        // 封面图校验：单张可选，≤8MB，仅支持常见图片格式
        boolean hasCover = cover != null && !cover.isEmpty();
        if (hasCover && cover.getSize() > MAX_COVER_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "封面图不能超过 8MB。");
        }
        if (hasCover && detectImageExt(cover) == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "封面图仅支持 png / jpg / jpeg / webp / gif。");
        }
        long totalSize = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    totalSize += f.getSize();
                }
            }
        }
        if (totalSize > MAX_UPLOAD_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "附件总大小超出限制。");
        }

        Long postId = postDao.insert(author, category, title, content, userId, tags);
        // 每日首次发帖 +10 萌点（奖励尽力而为：失败不阻断发帖）
        try {
            userDao.claimDailyReward(userId, "post", 10);
        } catch (Exception ignored) {
            // 萌点奖励异常不阻断发帖主流程
        }
        saveAttachments(postId, files);
        // 封面存 uploads/post_<id>/ 目录（与附件同目录，删帖时 deleteAttachmentFiles 一并清理）
        if (hasCover) {
            String coverImage = saveCover(postId, cover);
            postDao.updateCoverImage(postId, coverImage);
        }
        // 从 DB 回查，保证附件 id / created_at 为数据库真实值
        List<Attachment> attachments = attachmentDao.findByPostId(postId);
        Optional<Post> opt = postDao.findById(postId);
        Post post = opt.orElseThrow(() -> new IllegalStateException("写入的帖子读取失败"));
        mentionService.notifyMention(userId, postId, null, title, content);
        return post.withContext(false, null, null, attachments, List.of());
    }

    /** 4. 创建回复（需登录）：嵌套回复快照父回复作者名，父评论被删后子回复仍能显示「回复 @xx」 */
    public Reply createReply(Long postId, String contentRaw, Long parentId, String imagesJson, long userId) {
        if (!postDao.existsById(postId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        String author = user.nickname(); // 快照写当前昵称，改昵称后老帖/老回复由查询 COALESCE 实时显示新昵称
        String content = contentRaw == null ? "" : contentRaw.trim();
        if (content.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "回复内容不能为空。");
        }
        if (content.length() > 2000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "字段长度超出限制。");
        }
        String parentAuthor = null;
        if (parentId != null) {
            Optional<Reply> parent = replyDao.findById(parentId);
            if (parent.isEmpty() || !parent.get().postId().equals(postId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "父回复不存在。");
            }
            // 嵌套回复：快照父回复的作者名，父评论被删后子回复仍能显示「回复 @xx」
            parentAuthor = parent.get().author();
        }
        Long replyId = replyDao.insert(postId, author, content, imagesJson, userId, parentId, parentAuthor);
        // 每日首次评论 +5 萌点（奖励尽力而为：失败不阻断评论）
        try {
            userDao.claimDailyReward(userId, "reply", 5);
        } catch (Exception ignored) {
            // 萌点奖励异常不阻断评论主流程
        }
        postDao.incrementReplyCount(postId);
        mentionService.notifyMention(userId, postId, replyId, null, content);
        return replyDao.findById(replyId).orElseThrow(() -> new IllegalStateException("写入的回复读取失败"));
    }

    /**
     * 校验并序列化评论图片：images 可 null / List&lt;String&gt;；每项 trim 非空、以 /uploads/ 开头、≤500 字；条数 ≤9。
     * 不合法抛 400「图片参数不合法。」；空数组视为无图片（返回 null）。
     */
    public String serializeImages(Map<String, Object> body) {
        Object imagesObj = body == null ? null : body.get("images");
        if (imagesObj == null) {
            return null;
        }
        if (!(imagesObj instanceof List<?> list)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "图片参数不合法。");
        }
        if (list.size() > 9) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "图片参数不合法。");
        }
        List<String> images = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "图片参数不合法。");
            }
            String t = s.trim();
            if (t.isEmpty() || t.length() > 500 || !t.startsWith("/uploads/")) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "图片参数不合法。");
            }
            images.add(t);
        }
        if (images.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(images);
    }

    /** 5. 帖子点赞 toggle（需登录，按 user_id 去重） */
    public LikeResult toggleLike(Long id, long userId) {
        return postDao.toggleLike(id, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。"));
    }

    /** 5b. 帖子收藏 toggle（需登录，按 user_id 去重，收藏无计数） */
    public Boolean toggleFavorite(Long id, long userId) {
        return postDao.toggleFavorite(id, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。"));
    }

    /** 5c. 帖子点踩 toggle（需登录，与点赞独立，不互斥） */
    public LikeResult toggleDislike(Long id, long userId) {
        return postDao.toggleDislike(id, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。"));
    }

    /** 6a. 修改帖子分区（需登录 + 管理员） */
    public void updateCategory(Long id, String category, long currentUserId) {
        User current = userDao.findById(currentUserId)
                .orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "需要管理员权限。");
        }
        if (!postDao.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        if (category == null || category.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "分区不能为空。");
        }
        if (category.length() > 32) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "字段长度超出限制。");
        }
        postDao.updateCategory(id, category);
    }

    /** 6c. 帖子置顶（需登录 + 管理员）：days>0 置顶 N 天（到期自动取消），days=0 取消置顶；返回置顶截止时间 */
    public LocalDateTime pinPost(Long id, long days, long currentUserId) {
        User current = userDao.findById(currentUserId)
                .orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "需要管理员权限。");
        }
        if (!postDao.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        if (days < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "置顶天数不能为负。");
        }
        if (days > 3650) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "置顶天数不能超过 3650 天。");
        }
        LocalDateTime until = days == 0 ? null : LocalDateTime.now().plusDays(days);
        postDao.setPinnedUntil(id, until);
        return until;
    }

    /** 6b. 删除自己的帖子（需登录 + 作者本人）：userId 为 null 表示未登录 */
    public void deletePost(Long id, Long userId) {
        if (!postDao.existsById(id)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "帖子不存在。");
        }
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "请先登录。");
        }
        if (!postDao.deleteByIdAndUser(id, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "只能删除自己发布的帖子。");
        }
        deleteAttachmentFiles(id);
    }

    /** 删除帖子附件的本地文件目录 uploads/post_<id>/（数据库记录由外键级联删除） */
    private void deleteAttachmentFiles(Long postId) {
        Path dir = Path.of("uploads/post_" + postId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 单个文件删除失败不影响主流程
                        }
                    });
        } catch (IOException ignored) {
            // 目录不存在/遍历失败时静默忽略
        }
    }

    private void saveAttachments(Long postId, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            return;
        }
        try {
            String dirName = "uploads/post_" + postId;
            Path postDir = Path.of(dirName);
            Files.createDirectories(postDir);
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String originalName = sanitizeFilename(file.getOriginalFilename());
                String storedName = String.format("%08d_%s_%s_%s", postId, nowToken(),
                        UUID.randomUUID().toString().replace("-", ""), originalName);
                Path target = postDir.resolve(storedName);
                file.transferTo(target);
                String mime = file.getContentType();
                String urlPath = "/uploads/post_" + postId + "/" + storedName;
                attachmentDao.insert(postId, originalName, storedName, mime, file.getSize(), urlPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("附件保存失败", e);
        }
    }

    /** 保存帖子封面图到 uploads/post_<id>/，返回可访问 URL；封面校验已在上层完成 */
    private String saveCover(Long postId, MultipartFile cover) {
        String ext = detectImageExt(cover);
        String storedName = String.format("cover_%s_%s.%s", nowToken(),
                UUID.randomUUID().toString().replace("-", ""), ext);
        Path dir = Path.of("uploads/post_" + postId);
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName);
            cover.transferTo(target);
        } catch (IOException e) {
            throw new IllegalStateException("封面保存失败", e);
        }
        return "/uploads/post_" + postId + "/" + storedName;
    }

    /** 按文件名后缀 / Content-Type 判定图片扩展名（png/jpg/jpeg/webp/gif），不支持返回 null */
    private String detectImageExt(MultipartFile file) {
        String name = file.getOriginalFilename();
        String lower = name == null ? "" : name.toLowerCase();
        String ext = null;
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            ext = lower.substring(dot + 1);
        }
        if (ext != null && IMAGE_EXTS.contains(ext)) {
            return ext.equals("jpeg") ? "jpg" : ext;
        }
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (ct.contains("png")) return "png";
        if (ct.contains("jpeg")) return "jpg";
        if (ct.contains("gif")) return "gif";
        if (ct.contains("webp")) return "webp";
        return null;
    }

    /** 文件名净化：取 basename，非法字符替换为 _，最长 100 */
    private String sanitizeFilename(String name) {
        String base;
        if (name == null || name.isBlank()) {
            base = "file";
        } else {
            String normalized = name.replace('\\', '/');
            int idx = normalized.lastIndexOf('/');
            base = (idx >= 0) ? normalized.substring(idx + 1) : normalized;
            base = base.trim();
            if (base.isEmpty()) {
                base = "file";
            }
        }
        base = base.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        if (base.length() > 100) {
            base = base.substring(0, 100);
        }
        return base;
    }

    private String nowToken() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS"));
    }
}
