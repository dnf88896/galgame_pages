package com.galgame.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import com.galgame.auth.AuthContext;
import com.galgame.auth.TokenService;
import com.galgame.exception.BusinessException;
import com.galgame.model.LikeResult;
import com.galgame.model.Post;
import com.galgame.model.PostFilter;
import com.galgame.model.Reply;
import com.galgame.service.PostService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 帖子接口。对应 Python 版 server.py 的帖子相关路由。
 * <p>业务逻辑全部委托 {@link PostService}，本层只负责参数解析、登录态解析与业务异常转响应。
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;
    private final TokenService tokenService;

    public PostController(PostService postService, TokenService tokenService) {
        this.postService = postService;
        this.tokenService = tokenService;
    }

    /** 1. 帖子列表（公开），?q= 关键词、?category= 分区、?sections= 多标签（逗号分隔或重复参数，AND 语义，可配合旧 ?section=）、?sort= 排序（time 默认/hot/likes/views/following），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "section", required = false) String section,
            @RequestParam(value = "sections", required = false) List<String> sections,
            @RequestParam(value = "sort", required = false) String sort,
            HttpServletRequest request) {
        try {
            PostFilter filter = PostFilter.of(q, category, section, sections, sort);
            Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
            return ResponseEntity.ok(postService.listPosts(filter, uid.orElse(null)));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 2. 帖子详情（公开，可选登录）：先 +view 再读；liked 按当前用户计算 */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(
            @PathVariable Long id,
            HttpServletRequest request) {
        try {
            Optional<Long> currentUserId = tokenService.resolveUserId(request.getHeader("Authorization"));
            return ResponseEntity.ok(postService.getPostDetail(id, currentUserId.orElse(null)));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
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
        try {
            Post post = postService.createPost(title, content, category, sections, section, attachments, cover, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(post);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
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
        try {
            Post post = postService.createPost(title, content, category, sections, section, null, null, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(post);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 4a. 回复帖子（需登录，multipart/form-data；无图片） */
    @PostMapping(value = "/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> createReplyMultipart(
            @PathVariable Long id,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "parent_id", required = false) Long parentId,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        try {
            Reply reply = postService.createReply(id, content, parentId, null, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(reply);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
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
        try {
            String imagesJson = postService.serializeImages(body);
            Reply reply = postService.createReply(id, content, parentId, imagesJson, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(reply);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 5. 帖子点赞 toggle（需登录，按 user_id 去重） */
    @PostMapping("/{id}/like")
    public ResponseEntity<Object> togglePostLike(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        try {
            LikeResult result = postService.toggleLike(id, userId);
            return ResponseEntity.ok(Map.<String, Object>of("liked", result.liked(), "like_count", result.likeCount()));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 5b. 帖子收藏 toggle（需登录，按 user_id 去重，收藏无计数） */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Object> toggleFavorite(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        try {
            Boolean favorited = postService.toggleFavorite(id, userId);
            return ResponseEntity.ok(Map.<String, Object>of("favorited", favorited));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 5c. 帖子点踩 toggle（需登录，与点赞独立，不互斥） */
    @PostMapping("/{id}/dislike")
    public ResponseEntity<Object> togglePostDislike(
            @PathVariable Long id,
            HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        try {
            LikeResult result = postService.toggleDislike(id, userId);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "disliked", result.liked(), "dislike_count", result.likeCount()));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 6a. 修改帖子分区（需登录 + 管理员）：body {category}，返回 {ok, category} */
    @PutMapping("/{id}/category")
    public ResponseEntity<Object> updateCategory(@PathVariable Long id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        long currentUserId = AuthContext.currentUserId(request);
        Object catObj = body == null ? null : body.get("category");
        String category = catObj == null ? null : catObj.toString().trim();
        try {
            postService.updateCategory(id, category, currentUserId);
            return ResponseEntity.ok(Map.of("ok", true, "category", category));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 6c. 帖子置顶（需登录 + 管理员）：body {days}，days>0 置顶 N 天（到期自动取消），days=0 取消置顶 */
    @PutMapping("/{id}/pin")
    public ResponseEntity<Object> pinPost(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        long currentUserId = AuthContext.currentUserId(request);
        Object daysObj = body == null ? null : body.get("days");
        if (!(daysObj instanceof Number n)) {
            return ResponseEntity.badRequest().body(Map.of("error", "置顶天数不能为空。"));
        }
        long days = n.longValue();
        try {
            LocalDateTime until = postService.pinPost(id, days, currentUserId);
            Map<String, Object> result = new HashMap<>();
            result.put("ok", true);
            result.put("pinned_until", until);
            return ResponseEntity.ok(result);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 6b. 删除自己的帖子（需登录 + 作者本人） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deletePost(@PathVariable Long id, HttpServletRequest request) {
        // 该路径未注册到鉴权拦截器，这里手动解析 token 判定登录
        Optional<Long> userId = tokenService.resolveUserId(request.getHeader("Authorization"));
        try {
            postService.deletePost(id, userId.orElse(null));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }
}
