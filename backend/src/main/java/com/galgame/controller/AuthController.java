package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.galgame.auth.AuthContext;
import com.galgame.auth.TokenService;
import com.galgame.config.AdminLevels;
import com.galgame.dao.AuthTokenDao;
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 账号接口：注册 / 登录 / 登出 / 当前用户 / 修改签名 / 上传头像 / 管理员认证。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long MAX_AVATAR_BYTES = 4L * 1024 * 1024;
    private static final String USERNAME_PATTERN = "^[\\u4e00-\\u9fa5A-Za-z0-9_]{2,20}$";
    private static final Set<String> ALLOWED_AVATAR_EXT = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final UserDao userDao;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenDao authTokenDao;

    public AuthController(UserDao userDao, TokenService tokenService, PasswordEncoder passwordEncoder,
                          AuthTokenDao authTokenDao) {
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.authTokenDao = authTokenDao;
    }

    /** 1. 注册（注册即登录），返回明文 token + user */
    @PostMapping("/register")
    public ResponseEntity<Object> register(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? "" : trim(body.get("username"));
        String password = body == null ? "" : (body.get("password") == null ? "" : body.get("password"));

        if (!username.matches(USERNAME_PATTERN)) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名需为 2~20 位中英文、数字或下划线。"));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码至少 6 位。"));
        }
        if (userDao.existsByUsername(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "用户名已被使用。"));
        }

        Long userId = userDao.insert(username, passwordEncoder.encode(password));
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("注册后读取用户失败"));
        String token = tokenService.createToken(userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("token", token, "user", user));
    }

    /** 2. 登录 */
    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? "" : trim(body.get("username"));
        String password = body == null ? "" : (body.get("password") == null ? "" : body.get("password"));

        Optional<String> hashOpt = userDao.findPasswordHashByUsername(username);
        if (hashOpt.isEmpty() || !passwordEncoder.matches(password, hashOpt.get())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "用户名或密码错误。"));
        }
        User user = userDao.findByUsername(username).orElseThrow(() -> new IllegalStateException("登录用户读取失败"));
        String token = tokenService.createToken(user.id());
        return ResponseEntity.ok(Map.of("token", token, "user", user));
    }

    /** 3. 登出（需登录）：删除当前 token 记录 */
    @PostMapping("/logout")
    public ResponseEntity<Object> logout(HttpServletRequest request) {
        tokenService.deleteToken(extractBearerToken(request));
        return ResponseEntity.ok(Map.of());
    }

    /** 4. 当前用户（需登录） */
    @GetMapping("/me")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        return ResponseEntity.ok(user);
    }

    /** 5. 修改资料（需登录）：部分更新——body 里只更新出现的字段（bio 缺省不清空、hide_favorites 缺省不动） */
    @PutMapping("/profile")
    public ResponseEntity<Object> updateProfile(@RequestBody(required = false) Map<String, String> body,
                                                HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        // bio：仅在显式提供时更新；缺省保持原签名
        if (body != null && body.containsKey("bio")) {
            String bio = body.get("bio") == null ? "" : body.get("bio").trim();
            if (bio.length() > 200) {
                return ResponseEntity.badRequest().body(Map.of("error", "签名过长。"));
            }
            userDao.updateBio(userId, bio);
        }
        // hide_favorites：可选（"true"/"1" → 1 隐藏，其余 → 0 公开）
        if (body != null && body.containsKey("hide_favorites")) {
            String v = body.get("hide_favorites");
            int val = ("true".equalsIgnoreCase(v) || "1".equals(v)) ? 1 : 0;
            userDao.updateHideFavorites(userId, val);
        }
        // nickname：可选，部分更新；昵称可重复不查重，非空、按字符数（含 emoji）限 32
        if (body != null && body.containsKey("nickname")) {
            String nickname = body.get("nickname") == null ? "" : body.get("nickname").trim();
            if (nickname.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "昵称不能为空。"));
            }
            if (nickname.codePointCount(0, nickname.length()) > 32) {
                return ResponseEntity.badRequest().body(Map.of("error", "昵称最长 32 个字符。"));
            }
            userDao.updateNickname(userId, nickname);
        }
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        return ResponseEntity.ok(user);
    }

    /** 5.5 修改密码（需登录）：校验旧密码后更新，并删除该用户全部 token 使旧会话失效 */
    @PutMapping("/password")
    public ResponseEntity<Object> changePassword(@RequestBody(required = false) Map<String, String> body,
                                                 HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        String oldPassword = body == null ? "" : (body.get("old_password") == null ? "" : body.get("old_password"));
        String newPassword = body == null ? "" : (body.get("new_password") == null ? "" : body.get("new_password"));
        String hash = userDao.findPasswordHashById(userId)
                .orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (!passwordEncoder.matches(oldPassword, hash)) {
            return ResponseEntity.badRequest().body(Map.of("error", "旧密码错误。"));
        }
        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "密码至少 6 位。"));
        }
        userDao.updatePassword(userId, passwordEncoder.encode(newPassword));
        authTokenDao.deleteByUser(userId);
        return ResponseEntity.ok(Map.of());
    }

    /** 6. 上传头像（需登录），multipart 字段 file */
    @PostMapping("/avatar")
    public ResponseEntity<Object> uploadAvatar(@RequestParam("file") MultipartFile file,
                                               HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传头像文件。"));
        }
        String ext = detectAvatarExt(file);
        if (ext == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "头像仅支持 png/jpeg/webp/gif 格式。"));
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "头像文件不能超过 4MB。"));
        }
        try {
            String dirName = "uploads/avatars";
            Files.createDirectories(Path.of(dirName));
            String storedName = "avatar_" + userId + "_" + nowToken() + "." + ext;
            Path target = Path.of(dirName, storedName);
            file.transferTo(target);
            String urlPath = "/uploads/avatars/" + storedName;
            userDao.updateAvatarUrl(userId, urlPath);
            return ResponseEntity.ok(Map.of("avatar_url", urlPath));
        } catch (IOException e) {
            throw new IllegalStateException("头像保存失败", e);
        }
    }

    /** 7. 管理员认证（需登录）：输入管理员权限密码，正确则把账号提升到对应权限等级 */
    @PostMapping("/admin-verify")
    public ResponseEntity<Object> adminVerify(@RequestBody(required = false) Map<String, String> body,
                                              HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        String password = body == null ? "" : (body.get("password") == null ? "" : body.get("password"));
        Integer level = AdminLevels.resolve(password);
        if (level == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "管理员密码错误。"));
        }
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (user.adminLevel() == null || user.adminLevel() < level) {
            userDao.grantAdminLevel(userId, level);
            user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        }
        return ResponseEntity.ok(user);
    }

    // ── 私有辅助 ─────────────────────────────

    private String detectAvatarExt(MultipartFile file) {
        String ext = null;
        String name = file.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toLowerCase();
            }
        }
        if (ext == null || !ALLOWED_AVATAR_EXT.contains(ext)) {
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
        return (ext != null && ALLOWED_AVATAR_EXT.contains(ext)) ? ext : null;
    }

    private String nowToken() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS"));
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return null;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
