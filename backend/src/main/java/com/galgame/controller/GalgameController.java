package com.galgame.controller;

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

import com.galgame.auth.TokenService;
import com.galgame.exception.BusinessException;
import com.galgame.model.Galgame;
import com.galgame.model.GalgameReply;
import com.galgame.model.User;
import com.galgame.service.GalgameService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 作品库接口：公开列表/详情只显示已上架（approved）；提交审核由普通登录用户发起（pending）。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定权限（参照 PostController）。
 * <p>权限矩阵：列表公开（仅 approved）；详情 pending/rejected 仅创建者或管理员可见；
 * 创建/封面上传任意登录用户（管理员建即 approved，普通用户建 pending）；
 * 编辑管理员可编辑一切、创建者可编辑自己 pending/rejected 提交；删除管理员可删一切、创建者可删自己 pending 提交；
 * 评分仅 approved 可评；审核接口与待审列表仅管理员。
 * <p>本 Controller 只负责 HTTP 参数解析 + 登录态解析 + 响应组装，业务逻辑全部在 {@link GalgameService}。
 */
@RestController
@RequestMapping("/api/galgames")
public class GalgameController {

    private final GalgameService galgameService;
    private final TokenService tokenService;

    public GalgameController(GalgameService galgameService, TokenService tokenService) {
        this.galgameService = galgameService;
        this.tokenService = tokenService;
    }

    /** 新建 galgame 请求体（links 每项 {label,url}；categories 为旧分类 section_key 数组（24 个 gg-*，可空）；tag_ids 为标签 id 数组（须已上架 approved）；related_ids 为相关系列 galgame id 数组（须已上架 approved，不可关联自身）；releaseDate 格式 YYYY-MM-DD，可空；companyId 关联会社 id，可空——非空时 staff 由后端用公司名覆盖；staffs 关联制作人员数组 / characters 关联角色数组，每项 {id, description}（description 为该人员/角色在本作中的职责/定位，允许空），均可空。rating 由用户评分产生，本接口不接收） */
    public record GalgameRequest(String name, String description, String image, String staff,
                                 Long companyId,
                                 String releaseDate,
                                 List<Galgame.Link> links, List<String> categories, List<Long> tagIds, List<Long> relatedIds,
                                 List<StaffLinkReq> staffs, List<CharacterLinkReq> characters) {
        /** Galgame-制作人员关联请求元素：{id, description}，id 为制作人员 id，description 为职责/备注（允许空） */
        public record StaffLinkReq(Long id, String description) {
        }

        /** Galgame-角色关联请求元素：{id, description}，id 为角色 id，description 为定位/备注（允许空） */
        public record CharacterLinkReq(Long id, String description) {
        }
    }

    /** 审核请求体：status 必填（approved 通过 / rejected 拒绝）；reason 仅 rejected 时必填（≤500 字） */
    public record ReviewRequest(String status, String reason) {
    }

    /** 1. 作品列表（公开），?q= 关键词模糊、?field= 搜索字段（name 名称默认 / staff 制作人员[会社]）、?tags= 多标签 id（逗号分隔或重复参数，AND 语义）、?categories= 多分类 section_key（逗号分隔或重复参数，AND 语义）、?sort= 排序（created 默认/views/release_date/rating）、?company_id= 按关联会社筛选、?staff_id= 按关联制作人员筛选、?character_id= 按关联角色筛选（均可选），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "tags", required = false) List<Long> tags,
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "field", required = false) String field,
            @RequestParam(value = "company_id", required = false) Long companyId,
            @RequestParam(value = "staff_id", required = false) Long staffId,
            @RequestParam(value = "character_id", required = false) Long characterId,
            @RequestParam(value = "limit", required = false) Long limit,
            @RequestParam(value = "offset", required = false) Long offset) {
        return ResponseEntity.ok(galgameService.list(q, tags, categories, sort, field, companyId, staffId, characterId, limit, offset));
    }

    /**
     * 2. 作品详情：approved 公开（访问 +1 浏览数，返回最新 view_count）；
     * pending/rejected 仅创建者或管理员可见（不 +1 浏览数），其它人一律 404；
     * 带登录态时附 rated（当前用户是否已评分）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable Long id, HttpServletRequest request) {
        Long uid = currentUserId(request).orElse(null);
        try {
            Map<String, Object> map = galgameService.getDetail(id, uid);
            return ResponseEntity.ok(map);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 2.5 发表 Galgame 评论（需登录，JSON body {content, is_long?, images?, parent_id?}）：
     * is_long=true 为长评（trim 后须 >300 字），缺省/非布尔视为短评；
     * 仿 PostController.doCreateReply，去掉置顶；仅 approved 条目可评论。
     */
    @PostMapping(value = "/{id}/replies", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createReply(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        // 长短评标记：body.is_long 仅 Boolean true 视为长评，缺省/非布尔一律短评
        Object isLongObj = body == null ? null : body.get("is_long");
        boolean isLong = isLongObj instanceof Boolean b && b.booleanValue();
        // content 由 Controller 解析 trim 后传 Service 专注业务校验
        Object contentObj = body == null ? null : body.get("content");
        String content = contentObj == null ? null : contentObj.toString().trim();
        // parent_id 的 JSON 解析（仅 Number 视为合法）留在 Controller
        Long parentId = null;
        if (body != null && body.get("parent_id") != null) {
            if (body.get("parent_id") instanceof Number n) {
                parentId = n.longValue();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "无效的 parent_id。"));
            }
        }
        try {
            String imagesJson = galgameService.serializeImages(body);
            GalgameReply reply = galgameService.createReply(id, content, isLong, imagesJson, parentId, uid.get());
            return ResponseEntity.status(HttpStatus.CREATED).body(reply);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 3. 提交作品（任意登录用户，JSON body）：管理员提交直接上架（status=approved），
     * 普通用户提交进入待审核（status=pending）。
     */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody(required = false) GalgameRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        boolean isAdmin = user.adminLevel() != null && user.adminLevel() > 0;
        try {
            Galgame saved = galgameService.create(body, user.id(), isAdmin);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 4. 更新作品（登录，JSON body 全量替换）：管理员可编辑一切（原地改，不需要申请）；
     * 创建者编辑自己 pending/rejected 提交（原地改，现有行为）；创建者编辑自己 approved 已上架作品 → 提交「修改申请」
     * （复制原记录为影子行，apply_type='update'、original_id=原id、status='pending'，需管理员审核），原记录保持上架不动；
     * 其余 403。update() 不碰 status/apply_type/original_id 列。
     */
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody(required = false) GalgameRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        try {
            var result = galgameService.update(id, body, user);
            return ResponseEntity.status(result.status()).body(result.data());
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 5. 上传封面图（任意登录用户，multipart 字段 file），返回 {url: "/uploads/galgame_images/<stored>"} */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> uploadImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        try {
            String urlPath = galgameService.uploadImage(file, user.id());
            return ResponseEntity.ok(Map.of("url", urlPath));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 5.5 上传画廊多图（登录，multipart 多个字段名均为 files）：管理员随时可传；创建者仅 status != approved 可传；
     * 其余 403（与 update 权限一致）。逐张校验：扩展名白名单 png/jpg/jpeg/webp/gif、≤10MB；
     * 空文件跳过；全部成功写入 galgame_images（sort_order 按提交顺序），返回该作当前全部画廊（含新增）。
     */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> uploadGalleryImages(@PathVariable Long id,
                                                      @RequestParam("files") MultipartFile[] files,
                                                      HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        try {
            return ResponseEntity.ok(galgameService.uploadGalleryImages(id, files, user.id()));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 6. 删除作品（登录）：管理员可删一切；创建者且 status='pending' 可删自己提交；其余 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        boolean isAdmin = user.adminLevel() != null && user.adminLevel() > 0;
        try {
            galgameService.delete(id, user.id(), isAdmin);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /** 7. 用户评分（任意登录用户，管理员/普通同权限）：body {score: number}，0~10 分，一人一票 */
    @PostMapping("/{id}/rating")
    public ResponseEntity<Object> rate(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Double score = body == null ? null : asDouble(body.get("score"));
        try {
            Galgame saved = galgameService.rate(id, score, uid.get());
            return ResponseEntity.ok(saved);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 8. 待审核列表（管理员）：返回 status='pending' 的 galgame（含提交人昵称 creator）。
     * ⚠️ Spring 精确路径 /pending 优先于 /{id} 模板，不冲突。
     */
    @GetMapping("/pending")
    public ResponseEntity<Object> pending(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        try {
            return ResponseEntity.ok(galgameService.pending(uid.get()));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 8.5 「我的提交」（登录）：返回当前用户创建的全部 galgame（含各审核状态，status/reject_reason 一并返回），
     * 提交者在「我的提交」页回看 / 点进详情编辑自己 pending/rejected 的提交。未登录 401。
     */
    @GetMapping("/mine")
    public ResponseEntity<Object> mine(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        try {
            return ResponseEntity.ok(galgameService.mine(uid.get()));
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 9. 审核（管理员）：body {status:'approved'|'rejected', reason?}；
     * rejected 时 reason 必填（trim 非空且 ≤500，否则 400「请填写拒绝理由。」）。
     * 创建申请（apply_type='create'）通过/拒绝走 updateStatus；通过给提交者 +10 萌点（moe_awarded 原子防重）。
     * 修改申请（apply_type='update'）通过 → applyEdit 把影子行合并回原记录并删影子行（不给萌点）；
     * 修改申请拒绝 → updateStatus 保留影子行（提交者要能在「我的提交」看到拒绝理由）。
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<Object> review(@PathVariable Long id,
                                         @RequestBody(required = false) ReviewRequest body,
                                         HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        // status / reason 由 Controller trim 后传给 Service 专注业务校验
        String status = body == null ? null : body.status();
        status = status == null ? null : status.trim();
        if (status != null && status.isEmpty()) {
            status = null;
        }
        String reason = body == null ? null : body.reason();
        reason = reason == null ? null : reason.trim();
        if (reason != null && reason.isEmpty()) {
            reason = null;
        }
        try {
            Map<String, Object> map = galgameService.review(id, status, reason, uid.get());
            return ResponseEntity.ok(map);
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
        }
    }

    // ── 私有辅助 ─────────────────────────────

    private Optional<Long> currentUserId(HttpServletRequest request) {
        return tokenService.resolveUserId(request.getHeader("Authorization"));
    }

    /** 当前登录用户实体；未登录 / token 无效 / 用户不存在返回 null（用户查询委托 Service） */
    private User currentUser(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return null;
        }
        return galgameService.findUserById(uid.get());
    }

    /** 评分值解析：Integer/Double/BigDecimal 等 Number 或数字字符串 → Double；null / 空 / 非法返回 null */
    private Double asDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
