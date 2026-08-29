package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.GalgameDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Galgame;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Galgame 画廊图片删除接口（独立路径 /api/galgame-images，与 /api/galgames 区分）。
 * <p>路径未注册到鉴权拦截器，此处手动 {@code tokenService.resolveUserId} 解析 token 判定权限（参照 GalgameController）。
 * <p>权限：管理员可删一切；创建者且 status != approved 可删自己提交；其余 403（与 update 一致）。
 * 删除只清数据库记录，不清理磁盘图片文件（孤儿，与 galgame 封面上传行为一致）。
 */
@RestController
@RequestMapping("/api/galgame-images")
public class GalgameImageController {

    private final GalgameDao galgameDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public GalgameImageController(GalgameDao galgameDao, UserDao userDao, TokenService tokenService) {
        this.galgameDao = galgameDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /** 删除画廊图片（登录）：先查图片归属 galgame 再判断权限；图片不存在 404，无权 403 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> deleteImage(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        User user = userDao.findById(uid.get()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Long> galgameIdOpt = galgameDao.findImageGalgameId(id);
        if (galgameIdOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "画廊图片不存在。"));
        }
        Optional<Galgame> opt = galgameDao.findById(galgameIdOpt.get());
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Galgame 不存在。"));
        }
        Galgame galgame = opt.get();
        boolean isCreator = galgame.createdBy() != null && galgame.createdBy().equals(user.id());
        if (!(user.adminLevel() != null && user.adminLevel() > 0)
                && !(isCreator && !"approved".equals(galgame.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        galgameDao.deleteImage(id);
        // 与 DELETE /api/galgames/{id} 保持一致的返回风格
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
