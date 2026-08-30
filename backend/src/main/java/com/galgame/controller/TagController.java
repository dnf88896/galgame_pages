package com.galgame.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.galgame.auth.TokenService;
import com.galgame.dao.GalgameDao;
import com.galgame.dao.TagDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Tag;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 标签库接口（前缀 /api/galgame-tags，避免与帖子标签接口 /api/tags 冲突）：
 * 公开列表/详情只显示已上架（approved）；提交审核由普通登录用户发起（pending）。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定权限（仿 StaffController）。
 * <p>权限矩阵：列表/搜索公开（仅 approved）；详情 pending/rejected 仅创建者或管理员可见（否则 404）；
 * 创建任意登录用户（管理员建即 approved，普通用户建 pending）；
 * 编辑管理员可编辑一切、创建者可编辑自己非 approved 提交；删除管理员可删一切、创建者可删自己非 approved 提交；
 * 审核接口与待审列表仅管理员。
 * <p>详情返回 Map：标签基础字段 + galgame_count + works（该标签下已上架作品列表）。
 */
@RestController
@RequestMapping("/api/galgame-tags")
public class TagController {

    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "type", "language", "platform", "content", "meta", "technical", "sexual");
    private static final Set<Integer> ALLOWED_SPOILER_LEVELS = Set.of(0, 1, 2);

    private final TagDao tagDao;
    private final GalgameDao galgameDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final JsonMapper objectMapper;

    public TagController(TagDao tagDao, GalgameDao galgameDao, UserDao userDao,
                         TokenService tokenService, JsonMapper objectMapper) {
        this.tagDao = tagDao;
        this.galgameDao = galgameDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /** 新建标签请求体：name 必填（唯一）、category 七枚举之一、spoiler_level ∈ {0,1,2}、description 可选 */
    public record TagRequest(String name, String category, Integer spoilerLevel, String description) {
    }

    /** 审核请求体：status 必填（approved 通过 / rejected 拒绝）；reason 仅 rejected 时必填（≤500 字） */
    public record ReviewRequest(String status, String reason) {
    }

    /** 1. 标签列表（公开，仅 approved）：?q= 名称模糊搜索、?category= 类别过滤、?sort= 排序（created 默认/name/count），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "sort", required = false) String sort) {
        return ResponseEntity.ok(tagDao.findAll(trimToNull(q), trimToNull(category), sort));
    }

    /** 1.5 远程搜索（公开，仅 approved）：供编辑选择器下拉，返回 [{id,name,category,spoiler_level}]（不含 count）。
     * limit 拉大以支持「浏览全部标签」：q 为空时返回全部 approved 标签（全量导入后已 2400+，用 5000 覆盖）；q 有值时模糊过滤后远小于此。 */
    @GetMapping("/search")
    public ResponseEntity<Object> search(@RequestParam(value = "q", required = false) String q) {
        return ResponseEntity.ok(tagDao.search(trimToNull(q), 5000));
    }

    /** 2. 待审核列表（管理员）：返回 status='pending' 的标签（含提交人昵称 creator）。精确路径 /pending 优先于 /{id} 模板。 */
    @GetMapping("/pending")
    public ResponseEntity<Object> pending(HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        return ResponseEntity.ok(tagDao.findPending());
    }

    /** 2.5 「我的提交」（登录）：返回当前用户创建的全部标签（含各审核状态，status/reject_reason 一并返回）。未登录 401。 */
    @GetMapping("/mine")
    public ResponseEntity<Object> mine(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        return ResponseEntity.ok(tagDao.findByCreator(uid.get()));
    }

    /**
     * 3. 标签详情：approved 公开；pending/rejected 仅创建者或管理员可见，其它人一律 404。
     * 返回 Map：标签基础字段（含 galgame_count / creator）+ works（该标签下已上架作品列表，支持 limit/offset 分页）+ works_total（总作品数）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable Long id, HttpServletRequest request,
                                          @RequestParam(value = "limit", required = false) Long limit,
                                          @RequestParam(value = "offset", required = false) Long offset) {
        Optional<Tag> opt = tagDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
        }
        Tag tag = opt.get();
        boolean isApproved = "approved".equals(tag.status());
        if (!isApproved) {
            User user = currentUser(request);
            boolean isCreator = user != null && tag.createdBy() != null && tag.createdBy().equals(user.id());
            if (!isCreator && !isAdmin(user)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
            }
        }
        return ResponseEntity.ok(withWorks(tag, limit, offset));
    }

    /**
     * 4. 提交标签（任意登录用户，JSON body）：管理员提交直接上架（status=approved），
     * 普通用户提交进入待审核（status=pending）。
     */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody(required = false) TagRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        String status = isAdmin(user) ? "approved" : "pending";
        return doCreate(body, user.id(), status);
    }

    /**
     * 5. 更新标签（登录，JSON body 全量替换）：管理员可编辑一切；创建者且 status != approved 可编辑自己提交；
     * 其余 403。update() 不碰 status 列 → 编辑 pending 仍 pending、编辑 approved 仍 approved。
     */
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody(required = false) TagRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Tag> opt = tagDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
        }
        Tag tag = opt.get();
        boolean isCreator = tag.createdBy() != null && tag.createdBy().equals(user.id());
        if (!isAdmin(user) && !(isCreator && !"approved".equals(tag.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        ValidationResult result = normalizeAndValidate(body, tag);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedTagRequest n = result.value();
        if (!tagDao.update(id, n.name(), n.category(), n.spoilerLevel(), n.description())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
        }
        // 从 DB 回查，保证 updated_at 为数据库真实值
        Tag saved = tagDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后的标签读取失败"));
        return ResponseEntity.ok(saved);
    }

    /**
     * 6. 删除标签（登录）：管理员可删一切；创建者且 status != approved（pending/rejected）可删自己提交；其余 403。
     * galgame_tag 关联由外键 ON DELETE CASCADE 一并清除。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Tag> opt = tagDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
        }
        Tag tag = opt.get();
        boolean isCreator = tag.createdBy() != null && tag.createdBy().equals(user.id());
        if (!isAdmin(user) && !(isCreator && !"approved".equals(tag.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        tagDao.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 7. 审核（管理员）：body {status:'approved'|'rejected', reason?}；
     * rejected 时 reason 必填（trim 非空且 ≤500，否则 400「请填写拒绝理由。」）；
     * 通过给提交者 +10 萌点（claimMoeAward 原子防重，每个标签只发一次）；返回更新后的记录。
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<Object> review(@PathVariable Long id,
                                         @RequestBody(required = false) ReviewRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        if (!tagDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "标签不存在。"));
        }
        String status = body == null ? null : trimToNull(body.status());
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "审核状态须为 approved 或 rejected。"));
        }
        String reason = body == null ? null : trimToNull(body.reason());
        if ("rejected".equals(status)) {
            if (reason == null || reason.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请填写拒绝理由。"));
            }
            if (reason.length() > 500) {
                return ResponseEntity.badRequest().body(Map.of("error", "拒绝理由不能超过 500 字。"));
            }
        }
        tagDao.updateStatus(id, status, reason);
        // 萌点：审核通过进入公共列表时给提交者 +10（尽力而为，失败不阻断审核）。
        // claimMoeAward 原子防重——每个标签只发一次，反复通过/拒绝不会重复加分
        Tag saved = tagDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("审核后的标签读取失败"));
        if ("approved".equals(status) && saved.createdBy() != null && tagDao.claimMoeAward(id)) {
            try {
                userDao.adjustMoePoints(saved.createdBy(), 10);
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断审核主流程
            }
        }
        return ResponseEntity.ok(saved);
    }

    // ── 私有辅助 ─────────────────────────────

    private ResponseEntity<Object> doCreate(TagRequest body, long userId, String status) {
        ValidationResult result = normalizeAndValidate(body, null);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedTagRequest n = result.value();
        Long tagId = tagDao.insert(n.name(), null, n.category(), n.spoilerLevel(), n.description(), userId, status);
        // 管理员直接上架（status=approved）即进入公共列表：给提交者 +10 萌点（原子置位，每个标签只奖一次；尽力而为）
        if ("approved".equals(status)) {
            try {
                if (tagDao.claimMoeAward(tagId)) {
                    userDao.adjustMoePoints(userId, 10);
                }
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断提交主流程
            }
        }
        // 从 DB 回查，保证 created_at / updated_at / id / status 为数据库真实值
        Tag saved = tagDao.findById(tagId)
                .orElseThrow(() -> new IllegalStateException("写入的标签读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 详情 Map 组装：基础字段（SNAKE_CASE 序列化）+ works（该标签下已上架作品列表，limit/offset 分页，不传返回全部）+ works_total */
    private Map<String, Object> withWorks(Tag tag, Long limit, Long offset) {
        Map<String, Object> map = objectMapper.convertValue(tag, new TypeReference<Map<String, Object>>() {});
        map.put("works", galgameDao.findAll(null, List.of(tag.id()), null, "created", "name", null, null, null, limit, offset));
        map.put("works_total", tag.galgameCount());
        return map;
    }

    /** 校验通过的规范化请求体：create 与 update 共用 */
    private record NormalizedTagRequest(String name, String category, Integer spoilerLevel, String description) {
    }

    /** 校验结果：error 非 null 表示校验失败（可直接作为响应返回），否则 value 为规范化后的请求体 */
    private record ValidationResult(ResponseEntity<Object> error, NormalizedTagRequest value) {
    }

    /**
     * 校验并规范化请求体（create / update 共用）：name 必填且 ≤50、唯一（已存在 → 400「标签已存在。」，
     * 更新时允许与自身同名）；category 必须是七枚举之一（否则 400「无效的标签类别。」）；
     * spoiler_level ∈ {0,1,2}（否则 400「无效的剧透等级。」）；description ≤200。
     */
    private ValidationResult normalizeAndValidate(TagRequest body, Tag current) {
        String name = body == null ? "" : trimToNull(body.name());
        if (name == null || name.isEmpty()) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "请输入标签名称。")), null);
        }
        if (name.length() > 50) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        if (tagDao.nameExists(name) && (current == null || !name.equals(current.name()))) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "标签已存在。")), null);
        }
        String category = body == null ? null : trimToNull(body.category());
        if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "无效的标签类别。")), null);
        }
        Integer spoilerLevel = body == null ? null : body.spoilerLevel();
        if (spoilerLevel == null || !ALLOWED_SPOILER_LEVELS.contains(spoilerLevel)) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "无效的剧透等级。")), null);
        }
        String description = body == null ? null : trimToNull(body.description());
        if (description != null && description.length() > 200) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        return new ValidationResult(null, new NormalizedTagRequest(name, category, spoilerLevel, description));
    }

    /** 管理员守卫：未登录 401，已登录但 admin_level<=0 403；通过返回 null */
    private ResponseEntity<Object> adminGate(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        User admin = userDao.findById(uid.get()).orElse(null);
        if (admin == null || admin.adminLevel() == null || admin.adminLevel() <= 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        return null;
    }

    private Optional<Long> currentUserId(HttpServletRequest request) {
        return tokenService.resolveUserId(request.getHeader("Authorization"));
    }

    /** 当前登录用户实体；未登录 / token 无效 / 用户不存在返回 null */
    private User currentUser(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return null;
        }
        return userDao.findById(uid.get()).orElse(null);
    }

    /** 是否管理员：登录且 admin_level > 0（null 视为非管理员） */
    private boolean isAdmin(User user) {
        return user != null && user.adminLevel() != null && user.adminLevel() > 0;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
