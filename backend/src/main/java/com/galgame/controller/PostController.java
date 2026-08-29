package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.ObjectMapper;

import com.galgame.auth.AuthContext;
import com.galgame.auth.TokenService;
import com.galgame.constants.TagConstants;
import com.galgame.dao.AttachmentDao;
import com.galgame.dao.BlockDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.ReplyDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Attachment;
import com.galgame.model.LikeResult;
import com.galgame.model.Post;
import com.galgame.model.PostFilter;
import com.galgame.model.Reply;
import com.galgame.model.User;
import com.galgame.service.MentionService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 帖子接口。对应 Python 版 server.py 的帖子相关路由。
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private static final long MAX_UPLOAD_BYTES = 250L * 1024 * 1024;

    /** 封面图上限 8MB，仅支持 png / jpg / jpeg / webp / gif */
    private static final long MAX_COVER_BYTES = 8L * 1024 * 1024;
    private static final java.util.Set<String> IMAGE_EXTS = java.util.Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final PostDao postDao;
    private final ReplyDao replyDao;
    private final AttachmentDao attachmentDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final BlockDao blockDao;
    private final MentionService mentionService;
    private final ObjectMapper objectMapper;

    public PostController(PostDao postDao, ReplyDao replyDao, AttachmentDao attachmentDao,
                          UserDao userDao, TokenService tokenService, BlockDao blockDao,
                          MentionService mentionService, ObjectMapper objectMapper) {
        this.postDao = postDao;
        this.replyDao = replyDao;
        this.attachmentDao = attachmentDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.blockDao = blockDao;
        this.mentionService = mentionService;
        this.objectMapper = objectMapper;
    }

    /** 1. 帖子列表（公开），?q= 关键词、?category= 分区、?sections= 多标签（逗号分隔或重复参数，AND 语义，可配合旧 ?section=）、?sort= 排序（time 默认/hot/likes/following），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "section", required = false) String section,
            @RequestParam(value = "sections", required = false) List<String> sections,
            @RequestParam(value = "sort", required = false) String sort,
            HttpServletRequest request) {
        PostFilter filter = PostFilter.of(q, category, section, sections, sort);
        // 屏蔽是双向的：不可见作者 = 我屏蔽的人 ∪ 屏蔽我的人；未登录则为空集合
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        Set<Long> hidden = new HashSet<>();
        if (uid.isPresent()) {
            hidden.addAll(blockDao.findBlockedUserIds(uid.get()));
            hidden.addAll(blockDao.findBlockers(uid.get()));
        }
        List<Post> posts = postDao.findAll(filter, hidden, uid.orElse(null));
        if (posts.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Long> ids = posts.stream().map(Post::id).toList();
        Map<Long, List<Attachment>> attachmentsByPost = attachmentDao.findByPostIds(ids);
        List<Post> result = posts.stream()
                .map(p -> p.withContext(null, null, null, attachmentsByPost.getOrDefault(p.id(), List.of()), null))
                .toList();
        return ResponseEntity.ok(result);
    }

    /** 2. 帖子详情（公开，可选登录）：先 +view 再读；liked 按当前用户计算 */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(
            @PathVariable Long id,
            HttpServletRequest request) {
        Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
        Optional<Post> opt = postDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        Post post = opt.get();
        // 双方任一方向屏蔽 → 帖子视为不存在
        if (currentUserId.isPresent() && post.userId() != null) {
            long uid = currentUserId.get();
            if (blockDao.isBlocked(uid, post.userId()) || blockDao.isBlocked(post.userId(), uid)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
            }
        }
        postDao.incrementView(id);
        // 重新读取以拿到最新 view_count
        opt = postDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        post = opt.get();

        List<Attachment> attachments = attachmentDao.findByPostId(id);
        List<Reply> replies = replyDao.findByPostId(id);

        boolean liked = false;
        boolean disliked = false;
        boolean favorited = false;
        Set<Long> likedReplyIds = new HashSet<>();
        Set<Long> dislikedReplyIds = new HashSet<>();
        if (currentUserId.isPresent()) {
            long uid = currentUserId.get();
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

        return ResponseEntity.ok(post.withContext(liked, disliked, favorited, attachments, repliesWithLiked));
    }

    /** 3a. 新建帖子（需登录，multipart/form-data，可带多个 attachments 文件 + 可选单张 cover 封面图） */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> createMultipart(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "sections", required = false) List<String> sections,
            @RequestParam(value = "section", required = false) String section,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "attachments", required = false) MultipartFile[] attachments,
            @RequestParam(value = "cover", required = false) MultipartFile cover,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        return doCreatePost(category, sections, section, title, content, attachments, cover, userId);
    }

    /** 3b. 新建帖子（需登录，application/json，忽略附件与封面） */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createJson(@RequestBody(required = false) CreatePostRequest body,
                                             HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        String category = body == null ? null : body.category();
        List<String> sections = body == null ? null : body.sections();
        String section = body == null ? null : body.section();
        String title = body == null ? null : body.title();
        String content = body == null ? null : body.content();
        return doCreatePost(category, sections, section, title, content, null, null, userId);
    }

    /** 4a. 回复帖子（需登录，multipart/form-data；无图片） */
    @PostMapping(value = "/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> createReplyMultipart(
            @PathVariable Long id,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "parent_id", required = false) Long parentId,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        return doCreateReply(id, content, userId, parentId, null);
    }

    /** 4b. 回复帖子（需登录，application/json，body 可带 images 图片 URL 数组） */
    @PostMapping(value = "/{id}/replies", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createReplyJson(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Object contentObj = body == null ? null : body.get("content");
        String content = contentObj == null ? null : contentObj.toString();
        Long parentId = null;
        if (body != null && body.get("parent_id") != null) {
            if (body.get("parent_id") instanceof Number n) {
                parentId = n.longValue();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "无效的 parent_id。"));
            }
        }
        ImagesValidation images = validateImages(body);
        if (images.error() != null) {
            return images.error();
        }
        return doCreateReply(id, content, userId, parentId, images.imagesJson());
    }

    /** 5. 帖子点赞 toggle（需登录，按 user_id 去重） */
    @PostMapping("/{id}/like")
    public ResponseEntity<Object> togglePostLike(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<LikeResult> result = postDao.toggleLike(id, userId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of("liked", result.get().liked(), "like_count", result.get().likeCount()));
    }

    /** 5b. 帖子收藏 toggle（需登录，按 user_id 去重，收藏无计数） */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Object> toggleFavorite(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<Boolean> result = postDao.toggleFavorite(id, userId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of("favorited", result.get()));
    }

    /** 5c. 帖子点踩 toggle（需登录，与点赞独立，不互斥） */
    @PostMapping("/{id}/dislike")
    public ResponseEntity<Object> togglePostDislike(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        Optional<LikeResult> result = postDao.toggleDislike(id, userId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        return ResponseEntity.ok(Map.<String, Object>of(
                "disliked", result.get().liked(), "dislike_count", result.get().likeCount()));
    }

    /** 6a. 修改帖子分区（需登录 + 管理员）：body {category}，返回 {ok, category} */
    @PutMapping("/{id}/category")
    public ResponseEntity<Object> updateCategory(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        long currentUserId = AuthContext.currentUserId(request);
        User current = userDao.findById(currentUserId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        if (!postDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        Object catObj = body == null ? null : body.get("category");
        String category = catObj == null ? null : catObj.toString().trim();
        if (category == null || category.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "分区不能为空。"));
        }
        if (category.length() > 32) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        postDao.updateCategory(id, category);
        return ResponseEntity.ok(Map.of("ok", true, "category", category));
    }

    /** 6c. 帖子置顶（需登录 + 管理员）：body {days}，days>0 置顶 N 天（到期自动取消），days=0 取消置顶 */
    @PutMapping("/{id}/pin")
    public ResponseEntity<Object> pinPost(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        long currentUserId = AuthContext.currentUserId(request);
        User current = userDao.findById(currentUserId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (current.adminLevel() == null || current.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        if (!postDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        Object daysObj = body == null ? null : body.get("days");
        if (!(daysObj instanceof Number n)) {
            return ResponseEntity.badRequest().body(Map.of("error", "置顶天数不能为空。"));
        }
        long days = n.longValue();
        if (days < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "置顶天数不能为负。"));
        }
        if (days > 3650) {
            return ResponseEntity.badRequest().body(Map.of("error", "置顶天数不能超过 3650 天。"));
        }
        LocalDateTime until = days == 0 ? null : LocalDateTime.now().plusDays(days);
        postDao.setPinnedUntil(id, until);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("pinned_until", until);
        return ResponseEntity.ok(result);
    }

    /** 6b. 删除自己的帖子（需登录 + 作者本人） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deletePost(@PathVariable Long id, HttpServletRequest request) {
        if (!postDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        // 该路径未注册到鉴权拦截器，这里手动解析 token 判定登录
        Optional<Long> userId = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (userId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        if (!postDao.deleteByIdAndUser(id, userId.get())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "只能删除自己发布的帖子。"));
        }
        deleteAttachmentFiles(id);
        return ResponseEntity.ok(Map.of("ok", true));
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

    // ── 私有辅助 ─────────────────────────────

    private ResponseEntity<Object> doCreatePost(String categoryRaw, List<String> sectionsRaw, String sectionRaw,
                                                String titleRaw, String contentRaw, MultipartFile[] files,
                                                MultipartFile cover, long userId) {
        String title = titleRaw == null ? "" : titleRaw.trim();
        String content = contentRaw == null ? "" : contentRaw.trim();
        if (title.isEmpty() || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "标题和正文不能为空。"));
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
                return ResponseEntity.badRequest().body(Map.of("error", "无效的标签。"));
            }
        }
        String category = categoryRaw == null || categoryRaw.isBlank() ? "话题" : categoryRaw.trim();
        if (title.length() > 80 || content.length() > 2000 || category.length() > 32) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        // 封面图校验：单张可选，≤8MB，仅支持常见图片格式
        boolean hasCover = cover != null && !cover.isEmpty();
        if (hasCover && cover.getSize() > MAX_COVER_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "封面图不能超过 8MB。"));
        }
        if (hasCover && detectImageExt(cover) == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "封面图仅支持 png / jpg / jpeg / webp / gif。"));
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
            return ResponseEntity.badRequest().body(Map.of("error", "附件总大小超出限制。"));
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(post.withContext(false, null, null, attachments, List.of()));
    }

    private ResponseEntity<Object> doCreateReply(Long postId, String contentRaw, long userId, Long parentId, String imagesJson) {
        if (!postDao.existsById(postId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        String author = user.nickname(); // 快照写当前昵称，改昵称后老帖/老回复由查询 COALESCE 实时显示新昵称
        String content = contentRaw == null ? "" : contentRaw.trim();
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "回复内容不能为空。"));
        }
        if (content.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        String parentAuthor = null;
        if (parentId != null) {
            Optional<Reply> parent = replyDao.findById(parentId);
            if (parent.isEmpty() || !parent.get().postId().equals(postId)) {
                return ResponseEntity.badRequest().body(Map.of("error", "父回复不存在。"));
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
        Reply reply = replyDao.findById(replyId).orElseThrow(() -> new IllegalStateException("写入的回复读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(reply);
    }

    /** 图片校验结果：error 非 null 表示校验失败（可直接作为响应返回），否则 imagesJson 为序列化后的图片 URL 数组文本（无图片为 null） */
    private record ImagesValidation(ResponseEntity<Object> error, String imagesJson) {
    }

    /**
     * 校验并序列化评论图片：images 可 null / List&lt;String&gt;；每项 trim 非空、以 /uploads/ 开头、≤500 字；条数 ≤9。
     * 不合法返回 400「图片参数不合法。」；空数组视为无图片（返回 null）。
     */
    private ImagesValidation validateImages(Map<String, Object> body) {
        Object imagesObj = body == null ? null : body.get("images");
        if (imagesObj == null) {
            return new ImagesValidation(null, null);
        }
        if (!(imagesObj instanceof List<?> list)) {
            return new ImagesValidation(ResponseEntity.badRequest().body(Map.of("error", "图片参数不合法。")), null);
        }
        if (list.size() > 9) {
            return new ImagesValidation(ResponseEntity.badRequest().body(Map.of("error", "图片参数不合法。")), null);
        }
        List<String> images = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String s)) {
                return new ImagesValidation(ResponseEntity.badRequest().body(Map.of("error", "图片参数不合法。")), null);
            }
            String t = s.trim();
            if (t.isEmpty() || t.length() > 500 || !t.startsWith("/uploads/")) {
                return new ImagesValidation(ResponseEntity.badRequest().body(Map.of("error", "图片参数不合法。")), null);
            }
            images.add(t);
        }
        if (images.isEmpty()) {
            return new ImagesValidation(null, null);
        }
        return new ImagesValidation(null, objectMapper.writeValueAsString(images));
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
