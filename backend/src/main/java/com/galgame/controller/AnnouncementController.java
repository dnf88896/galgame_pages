package com.galgame.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.galgame.auth.AuthContext;
import com.galgame.dao.NotificationDao;
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * 公告发布接口：1 级管理员向所有用户广播一条 announcement 通知，支持附带媒体附件（图片/音频/视频）。
 * 需登录（/api/announcements 已注册到鉴权拦截器），需 admin_level ≥ 1。
 */
@RestController
@RequestMapping("/api")
public class AnnouncementController {

    private static final long MAX_UPLOAD_BYTES = 250L * 1024 * 1024;

    private final NotificationDao notificationDao;
    private final UserDao userDao;
    private final ObjectMapper objectMapper;

    public AnnouncementController(NotificationDao notificationDao, UserDao userDao, ObjectMapper objectMapper) {
        this.notificationDao = notificationDao;
        this.userDao = userDao;
        this.objectMapper = objectMapper;
    }

    /** 发布公告（需 1 级管理员，multipart/form-data）：向所有用户广播一条 announcement 通知，返回实际广播条数 */
    @Transactional
    @PostMapping(value = "/announcements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> announce(@RequestParam("title") String titleRaw,
                                           @RequestParam("content") String contentRaw,
                                           @RequestParam(value = "attachments", required = false) MultipartFile[] attachments,
                                           HttpServletRequest request) {
        long adminId = AuthContext.currentUserId(request);
        User admin = userDao.findById(adminId).orElseThrow(() -> new IllegalStateException("登录用户不存在"));
        if (admin.adminLevel() == null || admin.adminLevel() < 1) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "请通过1级管理员认证。"));
        }
        String title = titleRaw == null ? "" : titleRaw.trim();
        String content = contentRaw == null ? "" : contentRaw.trim();
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "公告标题不能为空。"));
        }
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "公告内容不能为空。"));
        }
        if (title.length() > 100 || content.length() > 500) {
            return ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。"));
        }
        // 媒体校验：mime 白名单（图片/音频/视频）+ 全部文件总大小上限
        long totalSize = 0;
        if (attachments != null) {
            for (MultipartFile f : attachments) {
                if (f == null || f.isEmpty()) {
                    continue;
                }
                String mime = f.getContentType();
                if (mime == null || !(mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/"))) {
                    return ResponseEntity.badRequest().body(Map.of("error", "仅支持图片、音频、视频附件。"));
                }
                totalSize += f.getSize();
            }
            if (totalSize > MAX_UPLOAD_BYTES) {
                return ResponseEntity.badRequest().body(Map.of("error", "附件总大小超出限制。"));
            }
        }
        List<Map<String, String>> mediaList = saveMedia(attachments);
        // 媒体 JSON 序列化（Jackson 3，writeValueAsString 抛运行时异常，无需 try-catch）：空列表传 null
        String mediaJson = null;
        if (!mediaList.isEmpty()) {
            mediaJson = objectMapper.writeValueAsString(mediaList);
        }
        int count = 0;
        for (Long uid : userDao.findAllUserIds()) {
            notificationDao.insert(uid, "announcement", adminId, null, null, title, content, mediaJson);
            count++;
        }
        return ResponseEntity.ok(Map.of("ok", true, "count", count, "media", mediaList));
    }

    /** 保存公告媒体附件到 uploads/announcements/，返回 {url, mime, name} 列表（空文件跳过） */
    private List<Map<String, String>> saveMedia(MultipartFile[] files) {
        List<Map<String, String>> mediaList = new ArrayList<>();
        if (files == null || files.length == 0) {
            return mediaList;
        }
        try {
            Path dir = Path.of("uploads/announcements");
            Files.createDirectories(dir);
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String mime = file.getContentType();
                String originalName = sanitizeFilename(file.getOriginalFilename());
                String storedName = String.format("%08d_%s_%s_%s", System.currentTimeMillis() % 100000000L,
                        nowToken(), UUID.randomUUID().toString().replace("-", ""), originalName);
                Path target = dir.resolve(storedName);
                file.transferTo(target);
                String urlPath = "/uploads/announcements/" + storedName;
                mediaList.add(Map.of("url", urlPath, "mime", mime, "name", originalName));
            }
            return mediaList;
        } catch (IOException e) {
            throw new IllegalStateException("附件保存失败", e);
        }
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
