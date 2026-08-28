package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import com.galgame.dao.GalgameDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Galgame;
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

    private final GalgameDao galgameDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final JsonMapper objectMapper;

    public GalgameController(GalgameDao galgameDao, UserDao userDao, TokenService tokenService, JsonMapper objectMapper) {
        this.galgameDao = galgameDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /** 新建 galgame 请求体（links 每项 {label,url}；tags 为 gg-* 标签数组；releaseDate 格式 YYYY-MM-DD，可空。rating 由用户评分产生，本接口不接收） */
    public record GalgameRequest(String name, String description, String image, String staff,
                                 String releaseDate,
                                 List<Galgame.Link> links, List<String> tags) {
    }

    /** 审核请求体：status 必填（approved 通过 / rejected 拒绝）；reason 仅 rejected 时必填（≤500 字） */
    public record ReviewRequest(String status, String reason) {
    }

    /** 1. 作品列表（公开），?q= 名称模糊、?tags= 多标签（逗号分隔或重复参数，AND 语义）、?sort= 排序（created 默认/views/release_date/rating），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "sort", required = false) String sort) {
        return ResponseEntity.ok(galgameDao.findAll(trimToNull(q), tags, sort));
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
        boolean rated = false;
        Optional<Long> uid = currentUserId(request);
        if (uid.isPresent()) {
            rated = galgameDao.hasRated(id, uid.get());
        }
        // convertValue 按全局 SNAKE_CASE 序列化 record → snake_case 键（release_date / rating_avg / rating_count），再附 rated
        Map<String, Object> map = objectMapper.convertValue(galgame, new TypeReference<Map<String, Object>>() {});
        map.put("rated", rated);
        return ResponseEntity.ok(map);
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
     * 4. 更新作品（登录，JSON body 全量替换）：管理员可编辑一切；创建者且 status != approved 可编辑自己提交；
     * 其余 403。update() 不碰 status 列 → 编辑 pending 仍 pending、编辑 approved 仍 approved。
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
        if (!isAdmin(user) && !(isCreator && !"approved".equals(galgame.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        if (!galgameDao.update(id, n.name(), n.description(), n.image(), n.staff(), n.releaseDate(), n.links(), n.tags())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
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
        galgameDao.deleteById(id);
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
     * rejected 时 reason 必填（trim 非空且 ≤500，否则 400「请填写拒绝理由。」）；
     * 调 updateStatus 落库，返回更新后的记录。
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<Object> review(@PathVariable Long id,
                                         @RequestBody(required = false) ReviewRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        if (!galgameDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
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
        galgameDao.updateStatus(id, status, reason);
        // 从 DB 回查，返回最新 status / reject_reason / reviewed_at
        Galgame saved = galgameDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("审核后的 galgame 读取失败"));
        // 萌点：审核通过进入公共列表时给提交者 +10（尽力而为，失败不阻断审核）。
        // claimMoeAward 原子防重——每条 galgame 只发一次，反复通过/拒绝不会重复加分
        int moeGranted = 0;
        if ("approved".equals(status) && saved.createdBy() != null && galgameDao.claimMoeAward(id)) {
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
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        Long galgameId = galgameDao.insert(n.name(), n.description(), n.image(), n.staff(), n.releaseDate(), n.links(), n.tags(), userId, status);
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

    /** 校验通过的规范化请求体：create 与 update 共用 */
    private record NormalizedGalgameRequest(String name, String description, String image, String staff,
                                            LocalDate releaseDate,
                                            List<Galgame.Link> links, List<String> tags) {
    }

    /** 校验结果：error 非 null 表示校验失败（可直接作为响应返回），否则 value 为规范化后的请求体 */
    private record ValidationResult(ResponseEntity<Object> error, NormalizedGalgameRequest value) {
    }

    /**
     * 校验并规范化请求体（create / update 共用）：name 必填且 ≤200，description ≤2000，staff ≤200，image ≤500，
     * 每个 link label≤50 / url≤500，tags 逐个须为 gg-* 标签并去重保序；不合法返回错误响应，合法返回 error=null。
     */
    private ValidationResult normalizeAndValidate(GalgameRequest body) {
        String name = body == null ? "" : trimToNull(body.name());
        if (name == null || name.isEmpty()) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "名称不能为空。")), null);
        }
        if (name.length() > 200) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        String description = body == null ? null : trimToNull(body.description());
        String staff = body == null ? null : trimToNull(body.staff());
        String image = body == null ? null : trimToNull(body.image());
        if (description != null && description.length() > 2000) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        if (staff != null && staff.length() > 200) {
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
        // tags 去重保序（参照 PostController.doCreatePost 的合并写法），再逐个校验为 gg-* 标签
        List<String> rawTags = body == null ? List.of() : (body.tags() == null ? List.of() : body.tags());
        List<String> tags = new ArrayList<>(new LinkedHashSet<>(rawTags));
        for (String tag : tags) {
            if (!TagConstants.isSectionKey(tag)) {
                return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "无效的标签。")), null);
            }
        }
        return new ValidationResult(null, new NormalizedGalgameRequest(name, description, image, staff, releaseDate, links, tags));
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
