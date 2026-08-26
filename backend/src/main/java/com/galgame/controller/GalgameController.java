package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

import com.galgame.auth.TokenService;
import com.galgame.constants.TagConstants;
import com.galgame.dao.GalgameDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Galgame;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 作品库接口：列表/详情公开，添加/图片上传/删除需管理员权限。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定管理员（参照 PostController）。
 */
@RestController
@RequestMapping("/api/galgames")
public class GalgameController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final GalgameDao galgameDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public GalgameController(GalgameDao galgameDao, UserDao userDao, TokenService tokenService) {
        this.galgameDao = galgameDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /** 新建 galgame 请求体（links 每项 {label,url}；tags 为 gg-* 标签数组） */
    public record GalgameRequest(String name, String description, String image, String staff,
                                 List<Galgame.Link> links, List<String> tags) {
    }

    /** 1. 作品列表（公开），?q= 名称模糊、?tags= 多标签（逗号分隔或重复参数，AND 语义），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "tags", required = false) List<String> tags) {
        return ResponseEntity.ok(galgameDao.findAll(trimToNull(q), tags));
    }

    /** 2. 作品详情（公开） */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable Long id) {
        Optional<Galgame> opt = galgameDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        return ResponseEntity.ok(opt.get());
    }

    /** 3. 添加作品（管理员，JSON body） */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody(required = false) GalgameRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        long adminId = currentUserId(request).orElseThrow();
        return doCreate(body, adminId);
    }

    /** 4. 更新作品（管理员，JSON body 全量替换） */
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody(required = false) GalgameRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        if (!galgameDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        if (!galgameDao.update(id, n.name(), n.description(), n.image(), n.staff(), n.links(), n.tags())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        // 从 DB 回查，保证 updated_at 为数据库真实值
        Galgame saved = galgameDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后的 galgame 读取失败"));
        return ResponseEntity.ok(saved);
    }

    /** 5. 上传封面图（管理员，multipart 字段 file），返回 {url: "/uploads/galgame_images/<stored>"} */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> uploadImage(@RequestParam("file") MultipartFile file,
                                              HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
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

    /** 6. 删除作品（管理员） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        if (!galgameDao.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        galgameDao.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── 私有辅助 ─────────────────────────────

    private ResponseEntity<Object> doCreate(GalgameRequest body, long adminId) {
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedGalgameRequest n = result.value();
        Long galgameId = galgameDao.insert(n.name(), n.description(), n.image(), n.staff(), n.links(), n.tags(), adminId);
        // 从 DB 回查，保证 created_at / updated_at / id 为数据库真实值
        Galgame saved = galgameDao.findById(galgameId)
                .orElseThrow(() -> new IllegalStateException("写入的 galgame 读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 校验通过的规范化请求体：create 与 update 共用 */
    private record NormalizedGalgameRequest(String name, String description, String image, String staff,
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
        return new ValidationResult(null, new NormalizedGalgameRequest(name, description, image, staff, links, tags));
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

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
