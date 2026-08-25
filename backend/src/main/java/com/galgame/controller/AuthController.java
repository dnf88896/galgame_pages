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
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 账号接口：注册 / 登录 / 登出 / 当前用户 / 修改签名 / 上传头像。
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

    public AuthController(UserDao userDao, TokenService tokenService, PasswordEncoder passwordEncoder) {
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
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

    /** 5. 修改签名（需登录） */
    @PutMapping("/profile")
    public ResponseEntity<Object> updateProfile(@RequestBody(required = false) Map<String, String> body,
                                                HttpServletRequest request) {
        long userId = AuthContext.currentUserId(request);
        String bio = body == null ? "" : trim(body.get("bio"));
        if (bio.length() > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "签名过长。"));
        }
        userDao.updateBio(userId, bio);
        User user = userDao.findById(userId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        return ResponseEntity.ok(user);
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
