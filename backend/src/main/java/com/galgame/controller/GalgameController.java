package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.galgame.auth.TokenService;
import com.galgame.constants.TagConstants;
import com.galgame.dao.CompanyDao;
import com.galgame.dao.EntityContributorDao;
import com.galgame.dao.GalgameDao;
import com.galgame.dao.GalgameReplyDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Galgame;
import com.galgame.model.GalgameCharacterLink;
import com.galgame.model.GalgameReply;
import com.galgame.model.GalgameStaffLink;
import com.galgame.model.NormalizedGalgameRequest;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 作品库接口：公开列表/详情只显示已上架（approved）；提交审核由普通登录用户发起（pending）。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定权限（参照 PostController）。
 * <p>权限矩阵：列表公开（仅 approved）；详情 pending/rejected 仅创建者或管理员可见；
 * 创建/封面上传任意登录用户（管理员建即 approved，普通用户建 pending）；
 * 编辑管理员可编辑一切、创建者可编辑自己 pending/rejected 提交；删除管理员可删一切、创建者可删自己 pending 提交；
 * 评分仅 approved 可评；审核接口与待审列表仅管理员。
 */
@RestController
@RequestMapping("/api/galgames")
public class GalgameController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");
    /** 本控制器的条目类型：写入 entity_contributors 的 entry_type */
    private static final String ENTRY_TYPE = "galgame";

    private final GalgameDao galgameDao;
    private final CompanyDao companyDao;
    private final GalgameReplyDao galgameReplyDao;
    private final EntityContributorDao entityContributorDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final JsonMapper objectMapper;

    public GalgameController(GalgameDao galgameDao, CompanyDao companyDao, GalgameReplyDao galgameReplyDao,
                             EntityContributorDao entityContributorDao,
                             UserDao userDao, TokenService tokenService, JsonMapper objectMapper) {
        this.galgameDao = galgameDao;
        this.companyDao = companyDao;
        this.galgameReplyDao = galgameReplyDao;
        this.entityContributorDao = entityContributorDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
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
            @RequestParam(value = "character_id", required = false) Long characterId) {
        return ResponseEntity.ok(galgameDao.findAll(trimToNull(q), tags, categories, sort, field, companyId, staffId, characterId));
    }

    /**
     * 2. 作品详情：approved 公开（访问 +1 浏览数，返回最新 view_count）；
     * pending/rejected 仅创建者或管理员可见（不 +1 浏览数），其它人一律 404；
     * 带登录态时附 rated（当前用户是否已评分）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable Long id, HttpServletRequest request) {
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame galgame = opt.get();
        boolean isApproved = "approved".equals(galgame.status());
        if (!isApproved) {
            User user = currentUser(request);
            boolean isCreator = user != null && galgame.createdBy() != null && galgame.createdBy().equals(user.id());
            if (!isCreator && !isAdmin(user)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
            }
        } else {
            galgameDao.incrementView(id);
            galgame = galgameDao.findById(id)
                    .orElseThrow(() -> new IllegalStateException("浏览计数后的 galgame 读取失败"));
        }
        // 带登录态时返回 rated（是否已评）+ my_score（当前用户自己的评分，回显用；未评 / 旧记录无分数为 null）
        Optional<Long> uid = currentUserId(request);
        Optional<Double> myScore = uid.flatMap(u -> galgameDao.findScoreByUser(id, u));
        // 评论区：全量返回（不置顶、不分页）；登录态批量注入 liked / disliked，未登录保持 null（JSON 省略）
        List<GalgameReply> replies = galgameReplyDao.findByGalgameId(id);
        if (uid.isPresent() && !replies.isEmpty()) {
            List<Long> replyIds = replies.stream().map(GalgameReply::id).toList();
            Set<Long> likedReplyIds = galgameReplyDao.findLikedReplyIds(replyIds, uid.get());
            Set<Long> dislikedReplyIds = galgameReplyDao.findDislikedReplyIds(replyIds, uid.get());
            replies = replies.stream()
                    .map(r -> r.withLikeState(likedReplyIds.contains(r.id()), dislikedReplyIds.contains(r.id())))
                    .toList();
        }
        // 评论作者评分注入：每条评论带作者对该 galgame 的评分（galgame_replies.user_id → galgame_ratings.score）。
        // 与当前登录者无关（匿名同样返回），作者无评分时 rating 保持 null（JSON 省略）。
        if (!replies.isEmpty()) {
            List<Long> authorIds = replies.stream().map(GalgameReply::userId)
                    .filter(u -> u != null)
                    .distinct()
                    .toList();
            if (!authorIds.isEmpty()) {
                Map<Long, Double> ratingByUser = galgameReplyDao.findRatingsByUsers(id, authorIds);
                replies = replies.stream()
                        .map(r -> r.withRating(r.userId() != null ? ratingByUser.get(r.userId()) : null))
                        .toList();
            }
        }
        // convertValue 按全局 SNAKE_CASE 序列化 record → snake_case 键（release_date / rating_avg / rating_count），再附 rated / my_score / replies / staffs / characters
        Map<String, Object> map = objectMapper.convertValue(galgame, new TypeReference<Map<String, Object>>() {});
        map.put("rated", myScore.isPresent());
        map.put("my_score", myScore.orElse(null));
        map.put("replies", replies);
        // 详情附关联的制作人员 / 角色 / 标签数组（列表接口不返回，保持轻量）；
        // tags 用 findTagsByGalgame 覆盖 BASE_COLUMNS 的简要数组，附带全站已上架作品计数 galgame_count
        map.put("staffs", galgameDao.findStaffBriefs(id));
        map.put("characters", galgameDao.findCharacterBriefs(id));
        map.put("tags", galgameDao.findTagsByGalgame(id));
        // 画廊多图 + 相关系列：所有审核状态（approved/pending/rejected）都返回，
        // 创建者/管理员看自己 pending 提交时也能看到画廊与相关系列（含尚未上架时关联的目标作品）。
        map.put("gallery", galgameDao.findImages(id));
        map.put("related_games", galgameDao.findRelatedGames(id));
        return ResponseEntity.ok(map);
    }

    /**
     * 2.5 发表 Galgame 评论（需登录，JSON body {content, is_long?, parent_id?}）：
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
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        if (!"approved".equals(opt.get().status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "该条目尚未通过审核，不能评论。"));
        }
        User user = userDao.findById(uid.get()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        // 快照写当前昵称（昵称优先、账号名兜底），改昵称后老评论由查询 COALESCE 实时显示新昵称
        String author = (user.nickname() == null || user.nickname().isBlank()) ? user.username() : user.nickname();
        // 长短评标记：body.is_long 仅 Boolean true 视为长评，缺省/非布尔一律短评
        Object isLongObj = body == null ? null : body.get("is_long");
        boolean isLong = isLongObj instanceof Boolean b && b.booleanValue();
        Object contentObj = body == null ? null : body.get("content");
        String content = contentObj == null ? null : contentObj.toString().trim();
        if (content == null || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "回复内容不能为空。"));
        }
        // 长评必须 >300 字（trim 后），短评沿用现有非空校验；两者共用 ≤2000 上限
        if (isLong && content.length() <= 300) {
            return ResponseEntity.badRequest().body(Map.of("error", "长评至少需要 300 字。"));
        }
        if (content.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        ImagesValidation images = validateImages(body);
        if (images.error() != null) {
            return images.error();
        }
        Long parentId = null;
        if (body != null && body.get("parent_id") != null) {
            if (body.get("parent_id") instanceof Number n) {
                parentId = n.longValue();
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "无效的 parent_id。"));
            }
        }
        String parentAuthor = null;
        if (parentId != null) {
            Optional<GalgameReply> parent = galgameReplyDao.findById(parentId);
            if (parent.isEmpty() || !parent.get().galgameId().equals(id)) {
                return ResponseEntity.badRequest().body(Map.of("error", "父回复不存在。"));
            }
            // 嵌套评论：快照父评论的作者名，父评论被删后子评论仍能显示「回复 @xx」
            parentAuthor = parent.get().author();
        }
        Long replyId = galgameReplyDao.insert(id, author, content, isLong, images.imagesJson(), uid.get(), parentId, parentAuthor);
        // 每日首次 Galgame 评论 +5 萌点（奖励尽力而为：失败不阻断评论）
        try {
            userDao.claimDailyReward(uid.get(), "galgame_reply", 5);
        } catch (Exception ignored) {
            // 萌点奖励异常不阻断评论主流程
        }
        GalgameReply reply = galgameReplyDao.findById(replyId)
                .orElseThrow(() -> new IllegalStateException("写入的评论读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(reply);
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
        String status = isAdmin(user) ? "approved" : "pending";
        return doCreate(body, user.id(), status);
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
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame galgame = opt.get();
        boolean isCreator = galgame.createdBy() != null && galgame.createdBy().equals(user.id());
        // 权限：管理员原地编辑一切；创建者可编辑自己 pending/rejected 提交；
        // 任意登录用户对已上架（approved）作品提交「修改申请」——复制原记录为影子行
        // （apply_type='update'、original_id=原id、status='pending'），原记录不动保持上架；
        // 关联表（分类/标签/制作人员/角色/相关系列）写到影子行，画廊图不复制；
        // relatedIds 校验 selfId 仍用原 id（不能关联自身，且目标必须 approved）。
        boolean canEdit = isAdmin(user) || (isCreator && !"approved".equals(galgame.status()));
        boolean canApply = !isAdmin(user) && "approved".equals(galgame.status());
        if (!canEdit && !canApply) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        ValidationResult result = normalizeAndValidate(body, id);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        if (canApply) {
            long shadowId = galgameDao.createUpdateShadow(id, n, user.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", shadowId,
                    "status", "pending",
                    "apply_type", "update",
                    "original_id", id,
                    "message", "修改申请已提交，等待审核"));
        }
        if (!galgameDao.update(id, n.name(), n.description(), n.image(), n.staff(), n.companyId(), n.releaseDate(), n.links(), n.categories(), n.tagIds(), n.staffLinks(), n.characterLinks(), n.relatedIds())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        // 管理员原地直改已上架（approved）：管理员列为贡献者并 +10 萌点（尽力而为，失败不阻断更新）。
        // 创建者改自己 pending/rejected 不触发（非管理员，且 status 不是 approved）。
        if (isAdmin(user) && "approved".equals(galgame.status())) {
            try {
                entityContributorDao.addContribution(ENTRY_TYPE, id, user.id());
                userDao.adjustMoePoints(user.id(), 10);
            } catch (Exception ignored) {
                // 萌点/贡献者异常不阻断更新主流程
            }
        }
        // 从 DB 回查，保证 updated_at 为数据库真实值
        Galgame saved = galgameDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后的 galgame 读取失败"));
        return ResponseEntity.ok(saved);
    }

    /** 5. 上传封面图（任意登录用户，multipart 字段 file），返回 {url: "/uploads/galgame_images/<stored>"} */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> uploadImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传图片文件。"));
        }
        String ext = detectImageExt(file);
        if (ext == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "仅支持 png/jpeg/webp/gif 图片。"));
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "图片大小不能超过 10MB。"));
        }
        try {
            String dirName = "uploads/galgame_images";
            Files.createDirectories(Path.of(dirName));
            String storedName = "galgame_" + nowToken() + "_"
                    + UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path target = Path.of(dirName, storedName);
            file.transferTo(target);
            String urlPath = "/uploads/galgame_images/" + storedName;
            return ResponseEntity.ok(Map.of("url", urlPath));
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败", e);
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
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame galgame = opt.get();
        boolean isCreator = galgame.createdBy() != null && galgame.createdBy().equals(user.id());
        if (!isAdmin(user) && !(isCreator && !"approved".equals(galgame.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传图片文件。"));
        }
        try {
            String dirName = "uploads/galgame_images";
            Files.createDirectories(Path.of(dirName));
            List<String> urls = new ArrayList<>();
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String ext = detectImageExt(file);
                if (ext == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "仅支持 png/jpeg/webp/gif 图片。"));
                }
                if (file.getSize() > MAX_IMAGE_BYTES) {
                    return ResponseEntity.badRequest().body(Map.of("error", "图片大小不能超过 10MB。"));
                }
                String storedName = "galgame_gallery_" + nowToken() + "_"
                        + UUID.randomUUID().toString().replace("-", "") + "." + ext;
                Path target = Path.of(dirName, storedName);
                file.transferTo(target);
                urls.add("/uploads/galgame_images/" + storedName);
            }
            if (urls.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请上传图片文件。"));
            }
            galgameDao.insertImages(id, urls);
            // 返回该作当前全部画廊（含新增），前端可直接覆盖展示
            return ResponseEntity.ok(galgameDao.findImages(id));
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败", e);
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
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame galgame = opt.get();
        boolean isCreator = galgame.createdBy() != null && galgame.createdBy().equals(user.id());
        if (!isAdmin(user) && !(isCreator && "pending".equals(galgame.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        // 删除原记录前先清理其「修改申请」影子行（否则 original_id 悬空指向已删除记录）
        galgameDao.deleteShadows(id);
        galgameDao.deleteById(id);
        // 清理该条目的贡献者记录（删除后贡献者列表不再引用已删除条目）
        entityContributorDao.deleteContributions(ENTRY_TYPE, id);
        return ResponseEntity.ok(Map.of("ok", true));
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
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        if (!"approved".equals(opt.get().status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "该条目尚未通过审核，不能评分。"));
        }
        Double score = body == null ? null : asDouble(body.get("score"));
        if (score == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入评分。"));
        }
        if (score < 0 || score > 10) {
            return ResponseEntity.badRequest().body(Map.of("error", "评分需在 0~10 分之间。"));
        }
        if (galgameDao.rate(id, uid.get(), score) == 1) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "你已经评过分了。"));
        }
        // 从 DB 回查，返回最新 rating_avg / rating_count
        Galgame saved = galgameDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("评分后的 galgame 读取失败"));
        return ResponseEntity.ok(saved);
    }

    /**
     * 8. 待审核列表（管理员）：返回 status='pending' 的 galgame（含提交人昵称 creator）。
     * ⚠️ Spring 精确路径 /pending 优先于 /{id} 模板，不冲突。
     */
    @GetMapping("/pending")
    public ResponseEntity<Object> pending(HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        return ResponseEntity.ok(galgameDao.findPending());
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
        return ResponseEntity.ok(galgameDao.findByCreator(uid.get()));
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
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame row = opt.get();
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
        // 「修改申请」（apply_type='update'）审核通过：把影子行内容合并回原记录并删除影子行。
        // 修改提交者（影子行 created_by）列为贡献者并 +10 萌点；claimMoeAward 在 applyEdit 删影子行之前调用
        // （否则拿不到影子行 id 的原子置位）。原记录 status 保持 approved 上架显示不动，返回合并后的原记录信息。
        if ("approved".equals(status) && "update".equals(row.applyType())) {
            Long originalId = row.originalId();
            if (originalId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "修改申请缺少原记录。"));
            }
            // 修改提交者（可能为 null：用户被删后 created_by 置 NULL）→ null 就跳过萌点/贡献者
            Long submittedBy = row.createdBy();
            int moeGranted = 0;
            if (submittedBy != null) {
                try {
                    entityContributorDao.addContribution(ENTRY_TYPE, originalId, submittedBy);
                    // claimMoeAward 原子防重——每条修改申请影子行只发一次；必须先于 applyEdit 删影子行
                    if (galgameDao.claimMoeAward(id)) {
                        userDao.adjustMoePoints(submittedBy, 10);
                        // 提交者恰为当前操作者（管理员审核自己的修改申请）时，前端据此显示「+10萌点」
                        Optional<Long> uid = currentUserId(request);
                        if (uid.isPresent() && uid.get().longValue() == submittedBy.longValue()) {
                            moeGranted = 10;
                        }
                    }
                } catch (Exception ignored) {
                    // 萌点/贡献者异常不阻断审核主流程
                }
            }
            galgameDao.applyEdit(id);
            Galgame original = galgameDao.findById(originalId)
                    .orElseThrow(() -> new IllegalStateException("合并后的原记录读取失败"));
            Map<String, Object> map = objectMapper.convertValue(original, new TypeReference<Map<String, Object>>() {});
            map.put("applied", true);
            map.put("moe_granted", moeGranted);
            return ResponseEntity.ok(map);
        }
        // 普通审核：创建申请通过/拒绝，或修改申请拒绝——更新状态与拒绝理由（拒绝时不删影子行）
        galgameDao.updateStatus(id, status, reason);
        // 从 DB 回查，返回最新 status / reject_reason / reviewed_at
        Galgame saved = galgameDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("审核后的 galgame 读取失败"));
        // 萌点：仅「创建申请」（apply_type != 'update'）审核通过时给提交者 +10（修改申请不奖励）。
        // claimMoeAward 原子防重——每条 galgame 只发一次，反复通过/拒绝不会重复加分
        int moeGranted = 0;
        if ("approved".equals(status) && !"update".equals(saved.applyType())
                && saved.createdBy() != null && galgameDao.claimMoeAward(id)) {
            try {
                userDao.adjustMoePoints(saved.createdBy(), 10);
                // 提交者恰为当前操作者（管理员审核自己的提交）时，前端据此显示「+10萌点」
                Optional<Long> uid = currentUserId(request);
                if (uid.isPresent() && uid.get().longValue() == saved.createdBy().longValue()) {
                    moeGranted = 10;
                }
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断审核主流程
            }
        }
        Map<String, Object> map = objectMapper.convertValue(saved, new TypeReference<Map<String, Object>>() {});
        map.put("moe_granted", moeGranted);
        return ResponseEntity.ok(map);
    }

    // ── 私有辅助 ─────────────────────────────

    private ResponseEntity<Object> doCreate(GalgameRequest body, long userId, String status) {
        ValidationResult result = normalizeAndValidate(body, null);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        Long galgameId = galgameDao.insert(n.name(), n.description(), n.image(), n.staff(), n.companyId(), n.releaseDate(), n.links(), n.categories(), n.tagIds(), n.staffLinks(), n.characterLinks(), n.relatedIds(), userId, status, "create", null);
        // 创建者即条目贡献者（try-catch 静默，失败不阻断提交）
        try {
            entityContributorDao.addContribution(ENTRY_TYPE, galgameId, userId);
        } catch (Exception ignored) {
            // 贡献者记录异常不阻断提交主流程
        }
        // 管理员直接上架（status=approved）即进入公共列表：给提交者 +10 萌点
        // （claimMoeAward 原子置位，每条只奖一次；尽力而为，失败不阻断提交）
        if ("approved".equals(status)) {
            try {
                if (galgameDao.claimMoeAward(galgameId)) {
                    userDao.adjustMoePoints(userId, 10);
                }
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断提交主流程
            }
        }
        // 从 DB 回查，保证 created_at / updated_at / id / status 为数据库真实值
        Galgame saved = galgameDao.findById(galgameId)
                .orElseThrow(() -> new IllegalStateException("写入的 galgame 读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 校验结果：error 非 null 表示校验失败（可直接作为响应返回），否则 value 为规范化后的请求体 */
    private record ValidationResult(ResponseEntity<Object> error, NormalizedGalgameRequest value) {
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

    /**
     * 校验并规范化请求体（create / update 共用）：name 必填且 ≤200，description ≤2000，staff ≤200，image ≤500，
     * 每个 link label≤50 / url≤500，tag_ids 逐个须存在且已上架（approved）并去重保序；
     * related_ids 逐个须存在且已上架（approved）并去重保序，update 时（selfId 非 null）不能关联自身；
     * 不合法返回错误响应，合法返回 error=null。
     */
    private ValidationResult normalizeAndValidate(GalgameRequest body, Long selfId) {
        String name = body == null ? "" : trimToNull(body.name());
        if (name == null || name.isEmpty()) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "名称不能为空。")), null);
        }
        if (name.length() > 200) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        String description = body == null ? null : trimToNull(body.description());
        String staff = body == null ? null : trimToNull(body.staff());
        Long companyId = body == null ? null : body.companyId();
        String image = body == null ? null : trimToNull(body.image());
        // 会社关联：companyId 非空时 staff 用已上架会社名称覆盖（body 里的 staff 不采用），
        // 公司不存在或未上架 → 400；companyId 为空时保留 body 里的 staff（兼容旧客户端/历史数据，trim 后可为空）
        if (companyId != null) {
            Optional<String> companyName = companyDao.findApprovedNameById(companyId);
            if (companyName.isEmpty()) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "所选会社不存在。")), null);
            }
            staff = companyName.get();
        }
        if (description != null && description.length() > 2000) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        // staff 长度校验仅在不关联会社（companyId 为空，staff 来自前端）时执行；关联会社时 staff 由后端用公司名生成（≤200）
        if (companyId == null && staff != null && staff.length() > 200) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        if (image != null && image.length() > 500) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        // releaseDate 可选：非空时须为 YYYY-MM-DD；rating 不在此接收（由用户评分产生，管理员不可写）
        LocalDate releaseDate = null;
        String releaseDateRaw = body == null ? null : trimToNull(body.releaseDate());
        if (releaseDateRaw != null) {
            try {
                releaseDate = LocalDate.parse(releaseDateRaw);
            } catch (DateTimeParseException e) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "发售日期格式应为 YYYY-MM-DD。")), null);
            }
        }
        List<Galgame.Link> links = body == null ? List.of() : (body.links() == null ? List.of() : body.links());
        for (Galgame.Link link : links) {
            if (link == null) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "无效的链接。")), null);
            }
            String label = link.label() == null ? "" : link.label().trim();
            String url = link.url() == null ? "" : link.url().trim();
            if (label.length() > 50 || url.length() > 500) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
            }
        }
        // 分类（旧系统 galgame_tags 的 section_key）去重保序（LinkedHashSet），
        // 逐个校验须为已知的 gg-* section_key（TagConstants.isSectionKey），否则 400「无效的分类。」
        List<String> rawCategories = body == null ? List.of() : (body.categories() == null ? List.of() : body.categories());
        List<String> categories = new ArrayList<>(new LinkedHashSet<>(rawCategories));
        for (String cat : categories) {
            if (cat == null || !TagConstants.isSectionKey(cat)) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "无效的分类。")), null);
            }
        }
        // tag_ids 去重保序（LinkedHashSet，参照 PostController.doCreatePost 的合并写法），
        // 再逐个校验存在且已上架（approved），否则 400「所选标签不存在或未通过审核。」
        List<Long> rawTagIds = body == null ? List.of() : (body.tagIds() == null ? List.of() : body.tagIds());
        List<Long> tagIds = new ArrayList<>(new LinkedHashSet<>(rawTagIds));
        for (Long tagId : tagIds) {
            if (tagId == null || !galgameDao.tagExists(tagId)) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "所选标签不存在或未通过审核。")), null);
            }
        }
        // 相关系列 related_ids 去重保序（LinkedHashSet），
        // 逐个校验须存在且已上架（approved），否则 400「所选的相关系列不存在或未上架。」；
        // update 时（selfId 非 null）不能关联自身，否则 400「不能关联自身。」
        List<Long> rawRelatedIds = body == null ? List.of() : (body.relatedIds() == null ? List.of() : body.relatedIds());
        List<Long> relatedIds = new ArrayList<>(new LinkedHashSet<>(rawRelatedIds));
        for (Long relatedId : relatedIds) {
            if (relatedId == null || !galgameDao.relatedExists(relatedId)) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "所选的相关系列不存在或未上架。")), null);
            }
            if (selfId != null && relatedId.equals(selfId)) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "不能关联自身。")), null);
            }
        }
        // 制作人员 / 角色关联：staffs / characters 为 {id, description} 对象数组。
        // 逐项校验 id 非空且存在（不存在 400「所选制作人员不存在。」/「所选角色不存在。」）；
        // description 允许空，trim 后长度 ≤200，超长 400「描述不能超过 200 字。」；
        // 按 id 去重保序（保留首现 description，防止重复 id 触发关联表主键冲突），
        // 返回 List<GalgameStaffLink> / List<GalgameCharacterLink>；空列表/未传 → 空关联（不报错）
        List<GalgameStaffLink> staffLinks = new ArrayList<>();
        List<GalgameRequest.StaffLinkReq> rawStaffs = body == null ? List.of() : (body.staffs() == null ? List.of() : body.staffs());
        Map<Long, String> staffMap = new LinkedHashMap<>();
        for (GalgameRequest.StaffLinkReq link : rawStaffs) {
            if (link == null || link.id() == null || !galgameDao.staffExists(link.id())) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "所选制作人员不存在。")), null);
            }
            String desc = link.description() == null ? "" : link.description().trim();
            if (desc.length() > 200) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "描述不能超过 200 字。")), null);
            }
            staffMap.putIfAbsent(link.id(), desc);
        }
        staffMap.forEach((sid, desc) -> staffLinks.add(new GalgameStaffLink(sid, desc)));
        List<GalgameCharacterLink> characterLinks = new ArrayList<>();
        List<GalgameRequest.CharacterLinkReq> rawCharacters = body == null ? List.of() : (body.characters() == null ? List.of() : body.characters());
        Map<Long, String> characterMap = new LinkedHashMap<>();
        for (GalgameRequest.CharacterLinkReq link : rawCharacters) {
            if (link == null || link.id() == null || !galgameDao.characterExists(link.id())) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "所选角色不存在。")), null);
            }
            String desc = link.description() == null ? "" : link.description().trim();
            if (desc.length() > 200) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "描述不能超过 200 字。")), null);
            }
            characterMap.putIfAbsent(link.id(), desc);
        }
        characterMap.forEach((cid, desc) -> characterLinks.add(new GalgameCharacterLink(cid, desc)));
        return new ValidationResult(null, new NormalizedGalgameRequest(name, description, image, staff, companyId, releaseDate, links, categories, tagIds, relatedIds, staffLinks, characterLinks));
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

    private String detectImageExt(MultipartFile file) {
        String ext = null;
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toLowerCase();
            }
        }
        if (ext == null || !ALLOWED_IMAGE_EXT.contains(ext)) {
            String contentType = file.getContentType();
            if (contentType != null) {
                ext = switch (contentType) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpg";
                    case "image/webp" -> "webp";
                    case "image/gif" -> "gif";
                    default -> null;
                };
            }
        }
        return (ext != null && ALLOWED_IMAGE_EXT.contains(ext)) ? ext : null;
    }

    private String nowToken() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS"));
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
            String t = trimToNull(s);
            if (t == null) {
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

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
