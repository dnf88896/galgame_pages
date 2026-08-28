package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.galgame.auth.TokenService;
import com.galgame.dao.UserDao;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 萌点签到接口。
 * <p>路径未注册到鉴权拦截器，这里手动解析 token（参照 PollController）：未登录一律 401「请先登录。」。
 * 签到幂等：今天已签过返回 {@code awarded=false}（HTTP 200），不重复加分。
 */
@RestController
@RequestMapping("/api/check-in")
public class CheckInController {

    /** 每日签到奖励萌点数 */
    private static final int CHECK_IN_MOE = 10;

    private final UserDao userDao;
    private final TokenService tokenService;

    public CheckInController(UserDao userDao, TokenService tokenService) {
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    /** 每日签到：今日首次 +10 萌点；已签过返回 awarded=false（幂等，不报错） */
    @PostMapping
    public ResponseEntity<Object> checkIn(HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        boolean awarded = userDao.claimDailyReward(uid.get(), "check_in", CHECK_IN_MOE);
        return ResponseEntity.ok(Map.<String, Object>of("ok", true, "awarded", awarded, "moe_points", currentMoePoints(uid.get())));
    }

    /** 今日签到状态（前端本人资料页加载时调用） */
    @GetMapping("/status")
    public ResponseEntity<Object> status(HttpServletRequest request) {
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        boolean todayCheckedIn = userDao.hasDailyReward(uid.get(), "check_in");
        return ResponseEntity.ok(Map.<String, Object>of("today_checked_in", todayCheckedIn, "moe_points", currentMoePoints(uid.get())));
    }

    private int currentMoePoints(Long userId) {
        return userDao.findById(userId)
                .map(User::moePoints)
                .orElse(0);
    }
}
