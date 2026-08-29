package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.CharacterDao;
import com.galgame.dao.CompanyDao;
import com.galgame.dao.GalgameDao;
import com.galgame.dao.StaffDao;
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 全局审核待办计数：管理员查看四类实体（galgame/会社/制作人员/角色）的待审核（pending）数量，
 * 前端顶栏「审核」入口据此显示红点/徽标。计数含创建申请与「修改申请」影子行（apply_type=update 的 pending 行）。
 * <p>该路径未注册到鉴权拦截器，这里手动解析 token 判定权限（照抄 GalgameController 的 currentUser + isAdmin）：
 * 未登录 401，非管理员 403。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewCountController {

    private final GalgameDao galgameDao;
    private final CompanyDao companyDao;
    private final StaffDao staffDao;
    private final CharacterDao characterDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public ReviewCountController(GalgameDao galgameDao, CompanyDao companyDao, StaffDao staffDao,
                                 CharacterDao characterDao, UserDao userDao, TokenService tokenService) {
        this.galgameDao = galgameDao;
        this.companyDao = companyDao;
        this.staffDao = staffDao;
        this.characterDao = characterDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /**
     * GET /api/review/pending-count：四类实体的待审核（status='pending'）数量 + 总数。
     * 返回 {galgame, company, staff, character, total}（管理员）。
     */
    @GetMapping("/pending-count")
    public ResponseEntity<Object> pendingCount(HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        User admin = userDao.findById(uid.get()).orElse(null);
        if (admin == null || admin.adminLevel() == null || admin.adminLevel() <= 0) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "需要管理员权限。"));
        }
        int galgame = galgameDao.countPending();
        int company = companyDao.countPending();
        int staff = staffDao.countPending();
        int character = characterDao.countPending();
        return ResponseEntity.ok(Map.of(
                "galgame", galgame,
                "company", company,
                "staff", staff,
                "character", character,
                "total", galgame + company + staff + character));
    }
}
