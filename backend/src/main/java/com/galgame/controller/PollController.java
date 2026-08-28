package com.galgame.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
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

import com.galgame.auth.TokenService;
import com.galgame.dao.PollDao;
import com.galgame.dao.PostDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Poll;
import com.galgame.model.PollOption;
import com.galgame.model.Post;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 帖子投票接口（与 kungal 论坛行为一致）。
 * <p>所有路径不注册鉴权拦截器，这里手动解析 token：
 * GET 列表可选登录；创建/编辑/删除/日志仅帖子楼主（管理员非楼主同样无权，延续评论置顶规则）；投票需登录。
 * 楼主判断只看 post.user_id == 当前用户，不做管理员判断。
 */
@RestController
@RequestMapping("/api")
public class PollController {

    /** 每帖最多投票数（与 kungal MaxPollsPerTopic 一致） */
    private static final int MAX_POLLS_PER_POST = 30;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 20;
    /** 投票日志页容量上限 */
    private static final int MAX_LOG_PAGE_SIZE = 50;
    /** 结果可见时预览的投票人头像数 */
    private static final int VOTERS_PREVIEW = 5;
    private static final Set<String> RESULT_VISIBILITIES = Set.of("always", "after_vote", "after_deadline");

    private final PollDao pollDao;
    private final PostDao postDao;
    private final UserDao userDao;
    private final TokenService tokenService;

    public PollController(PollDao pollDao, PostDao postDao, UserDao userDao, TokenService tokenService) {
        this.pollDao = pollDao;
        this.postDao = postDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
    }

    // ── 1. 投票列表（可选登录）──────────────────────────

    @GetMapping("/posts/{postId}/polls")
    public ResponseEntity<Object> listPolls(@PathVariable Long postId, HttpServletRequest request) {
        if (!postDao.existsById(postId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        Optional<Long> uid = tokenService.resolveUserId(request.getHeader("Authorization"));
        List<Poll> polls = pollDao.findByPostId(postId);
        if (polls.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(buildPolls(polls, uid.orElse(null)));
    }

    // ── 2. 创建投票（登录 + 仅楼主）──────────────────────

    @PostMapping("/posts/{postId}/polls")
    public ResponseEntity<Object> createPoll(@PathVariable Long postId,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        Optional<Long> uid = requireLogin(request);
        if (uid.isEmpty()) {
            return unauthorized();
        }
        Optional<Post> postOpt = postDao.findById(postId);
        if (postOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "帖子不存在。"));
        }
        if (!postOpt.get().userId().equals(uid.get())) {
            return forbiddenPoll();
        }
        if (pollDao.countPollsByPost(postId) >= MAX_POLLS_PER_POST) {
            return badRequest("每帖最多创建 " + MAX_POLLS_PER_POST + " 个投票。");
        }
        PollInput in = parsePollInput(body);
        if (in.error() != null) {
            return badRequest(in.error());
        }
        Object optionsObj = body == null ? null : body.get("options");
        List<String> optionTexts = parseOptionTexts(optionsObj);
        if (optionTexts.size() < MIN_OPTIONS) {
            return badRequest("投票至少需要 " + MIN_OPTIONS + " 个选项。");
        }
        if (optionTexts.size() > MAX_OPTIONS) {
            return badRequest("投票最多 " + MAX_OPTIONS + " 个选项。");
        }
        if ("multiple".equals(in.type()) && in.maxChoice() > optionTexts.size()) {
            return badRequest("至多选择数不能超过选项数。");
        }
        Long pollId = pollDao.insertPoll(in.title(), in.description(), in.type(), in.minChoice(), in.maxChoice(),
                in.deadline(), in.resultVisibility(), in.isAnonymous(), in.canChangeVote(),
                postId, uid.get(), optionTexts);
        Poll poll = pollDao.findById(pollId).orElseThrow(() -> new IllegalStateException("写入的投票读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(buildPolls(List.of(poll), uid.get()).get(0));
    }

    // ── 3. 编辑投票（登录 + 仅楼主）──────────────────────

    @PutMapping("/polls/{id}")
    public ResponseEntity<Object> updatePoll(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, Object> body,
                                             HttpServletRequest request) {
        Optional<Long> uid = requireLogin(request);
        if (uid.isEmpty()) {
            return unauthorized();
        }
        Optional<Poll> pollOpt = pollDao.findById(id);
        if (pollOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "投票不存在。"));
        }
        Poll poll = pollOpt.get();
        if (!poll.userId().equals(uid.get())) {
            return forbiddenPoll();
        }
        PollInput in = parsePollInput(body);
        if (in.error() != null) {
            return badRequest(in.error());
        }
        // 现有选项：校验更新/删除目标合法 + 计算最终选项数
        List<PollOption> existing = pollDao.findOptionsByPollId(id);
        Set<Long> existingIds = existing.stream().map(PollOption::id).collect(Collectors.toSet());
        List<String> addTexts = new ArrayList<>();
        List<Long> updateIds = new ArrayList<>();
        List<String> updateTexts = new ArrayList<>();
        List<Long> deleteIds = new ArrayList<>();
        if (body != null && body.get("options") instanceof Map<?, ?> optionsMap) {
            Object addObj = optionsMap.get("add");
            if (addObj instanceof List<?> addList) {
                for (Object o : addList) {
                    if (o instanceof Map<?, ?> m && m.get("text") != null) {
                        String t = String.valueOf(m.get("text")).trim();
                        if (t.isEmpty()) {
                            return badRequest("新增选项内容不能为空。");
                        }
                        if (t.length() > 100) {
                            return badRequest("字段长度超出限制。");
                        }
                        addTexts.add(t);
                    }
                }
            }
            Object updateObj = optionsMap.get("update");
            if (updateObj instanceof List<?> updateList) {
                for (Object o : updateList) {
                    if (o instanceof Map<?, ?> m && m.get("option_id") instanceof Number n) {
                        long optionId = n.longValue();
                        String t = m.get("text") == null ? "" : String.valueOf(m.get("text")).trim();
                        if (t.isEmpty()) {
                            return badRequest("修改的选项内容不能为空。");
                        }
                        if (t.length() > 100) {
                            return badRequest("字段长度超出限制。");
                        }
                        if (!existingIds.contains(optionId)) {
                            return badRequest("无效的选项。");
                        }
                        if (pollDao.optionHasVotes(optionId)) {
                            return badRequest("已有投票的选项不能修改文本。");
                        }
                        updateIds.add(optionId);
                        updateTexts.add(t);
                    }
                }
            }
            Object deleteObj = optionsMap.get("delete");
            if (deleteObj instanceof List<?> deleteList) {
                for (Object o : deleteList) {
                    if (o instanceof Number n) {
                        long optionId = n.longValue();
                        if (!existingIds.contains(optionId)) {
                            return badRequest("无效的选项。");
                        }
                        if (updateIds.contains(optionId)) {
                            return badRequest("同一选项不能同时修改和删除。");
                        }
                        if (pollDao.optionHasVotes(optionId)) {
                            return badRequest("已有投票的选项不能删除。");
                        }
                        deleteIds.add(optionId);
                    }
                }
            }
        }
        if (!in.canChangeVote() && (!addTexts.isEmpty() || !updateIds.isEmpty() || !deleteIds.isEmpty())) {
            return badRequest("该投票不允许修改选项。");
        }
        // 去重（前端已去重，这里防御）
        List<String> addUnique = new ArrayList<>(new LinkedHashSet<>(addTexts));
        List<Long> deleteUnique = new ArrayList<>(new LinkedHashSet<>(deleteIds));
        int finalCount = existing.size() + addUnique.size() - deleteUnique.size();
        if (finalCount < MIN_OPTIONS) {
            return badRequest("投票至少需要 " + MIN_OPTIONS + " 个选项。");
        }
        if (finalCount > MAX_OPTIONS) {
            return badRequest("投票最多 " + MAX_OPTIONS + " 个选项。");
        }
        if ("multiple".equals(in.type()) && in.maxChoice() > finalCount) {
            return badRequest("至多选择数不能超过选项数。");
        }
        pollDao.updatePollAndOptions(id, in.title(), in.description(), in.type(), in.minChoice(), in.maxChoice(),
                in.deadline(), in.resultVisibility(), in.isAnonymous(), in.canChangeVote(),
                addUnique, updateIds, updateTexts, deleteUnique);
        Poll updated = pollDao.findById(id).orElseThrow(() -> new IllegalStateException("更新的投票读取失败"));
        return ResponseEntity.ok(buildPolls(List.of(updated), uid.get()).get(0));
    }

    // ── 4. 删除投票（登录 + 仅楼主）──────────────────────

    @DeleteMapping("/polls/{id}")
    public ResponseEntity<Object> deletePoll(@PathVariable Long id, HttpServletRequest request) {
        Optional<Long> uid = requireLogin(request);
        if (uid.isEmpty()) {
            return unauthorized();
        }
        Optional<Poll> pollOpt = pollDao.findById(id);
        if (pollOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "投票不存在。"));
        }
        if (!pollOpt.get().userId().equals(uid.get())) {
            return forbiddenPoll();
        }
        pollDao.deletePollCascade(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── 5. 投票 / 改票（登录）───────────────────────────

    @PostMapping("/polls/{id}/vote")
    public ResponseEntity<Object> vote(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        Optional<Long> uid = requireLogin(request);
        if (uid.isEmpty()) {
            return unauthorized();
        }
        Optional<Poll> pollOpt = pollDao.findById(id);
        if (pollOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "投票不存在。"));
        }
        Poll poll = pollOpt.get();
        if ("closed".equals(poll.status())) {
            return badRequest("投票已结束。");
        }
        if (poll.deadline() != null && poll.deadline().isBefore(LocalDateTime.now())) {
            return badRequest("投票已截止。");
        }
        boolean hasVoted = pollDao.hasVoted(id, uid.get());
        if (hasVoted && !Boolean.TRUE.equals(poll.canChangeVote())) {
            return badRequest("该投票不可修改投票。");
        }
        // 解析 option_id_array
        List<Long> ids = new ArrayList<>();
        Object arr = body == null ? null : body.get("option_id_array");
        if (arr instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o instanceof String s) {
                    try {
                        ids.add(Long.parseLong(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // 非法 id 忽略，稍后统一校验
                    }
                }
            }
        }
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        if ("single".equals(poll.type())) {
            if (unique.size() != 1) {
                return badRequest("单选投票必须选择 1 个选项。");
            }
        } else {
            int min = poll.minChoice() == null ? 1 : poll.minChoice();
            int max = poll.maxChoice() == null ? unique.size() : poll.maxChoice();
            if (unique.size() < min) {
                return badRequest("请至少选择 " + min + " 个选项。");
            }
            if (unique.size() > max) {
                return badRequest("最多只能选择 " + max + " 个选项。");
            }
        }
        if (unique.isEmpty()) {
            return badRequest("请选择选项。");
        }
        if (!pollDao.optionsBelongToPoll(id, unique)) {
            return badRequest("存在无效的选项。");
        }
        pollDao.vote(id, uid.get(), unique);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── 6. 投票日志（登录 + 仅楼主）──────────────────────

    @GetMapping("/polls/{id}/logs")
    public ResponseEntity<Object> pollLogs(@PathVariable Long id,
                                           @RequestParam(value = "page", defaultValue = "1") int page,
                                           @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
                                           HttpServletRequest request) {
        Optional<Long> uid = requireLogin(request);
        if (uid.isEmpty()) {
            return unauthorized();
        }
        Optional<Poll> pollOpt = pollDao.findById(id);
        if (pollOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "投票不存在。"));
        }
        Poll poll = pollOpt.get();
        if (!poll.userId().equals(uid.get())) {
            return forbiddenPoll();
        }
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1 || pageSize > MAX_LOG_PAGE_SIZE) {
            pageSize = 20;
        }
        boolean anonymous = Boolean.TRUE.equals(poll.isAnonymous());
        List<Map<String, Object>> rows = pollDao.findVoteLogs(id, (page - 1) * pageSize, pageSize);
        // 非匿名时批量查用户（防 N+1）
        Map<Long, User> usersById = Map.of();
        if (!anonymous && !rows.isEmpty()) {
            List<Long> userIds = rows.stream()
                    .map(r -> ((Number) r.get("user_id")).longValue())
                    .distinct()
                    .toList();
            usersById = userDao.findByIds(userIds).stream()
                    .collect(Collectors.toMap(User::id, Function.identity()));
        }
        List<Map<String, Object>> logs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", row.get("id"));
            entry.put("created", row.get("created_at"));
            entry.put("option", row.get("option"));
            if (!anonymous) {
                User user = usersById.get(((Number) row.get("user_id")).longValue());
                if (user != null) {
                    entry.put("user", userMap(user));
                }
            }
            logs.add(entry);
        }
        return ResponseEntity.ok(Map.of("logs", logs, "total", pollDao.countVoteLogs(id)));
    }

    // ── 组装 / 辅助 ─────────────────────────────────────

    /**
     * 组装对外投票对象（带 canViewResults 逻辑）：
     * 楼主恒可看结果；结束（closed 或过 deadline）可看；result_visibility 决定其余。
     * 结果不可见时：options 不带票数、voters 省略、vote_count 省略；has_voted/is_voted 恒给（前端要判断）。
     * 匿名投票：voters（投票人头像）一律省略，但投票人数/票数照常返回（匿名只隐藏身份）。
     */
    private List<Poll> buildPolls(List<Poll> polls, Long uid) {
        // 楼主信息批量查
        Map<Long, User> usersById = userDao.findByIds(
                        polls.stream().map(Poll::userId).distinct().toList())
                .stream().collect(Collectors.toMap(User::id, Function.identity()));
        Set<Long> votedPollIds = uid == null ? Set.of() : pollDao.findVotedPollIds(
                polls.stream().map(Poll::id).toList(), uid);

        List<Poll> result = new ArrayList<>();
        for (Poll p : polls) {
            boolean hasVoted = uid != null && votedPollIds.contains(p.id());
            boolean canView = canViewResults(p, uid, hasVoted);
            List<PollOption> options = pollDao.findOptionsByPollId(p.id());
            Set<Long> votedOptionIds = uid == null ? Set.of() : pollDao.findVotedOptionIds(p.id(), uid);
            List<PollOption> optionsWithView = new ArrayList<>();
            for (PollOption o : options) {
                Integer voteCount = canView ? o.voteCount() : null;
                optionsWithView.add(o.withView(votedOptionIds.contains(o.id()), voteCount));
            }
            Integer voteTotal = null;
            List<Map<String, Object>> voters = null;
            Integer votersCount = null;
            if (canView) {
                voteTotal = optionsWithView.stream()
                        .mapToInt(o -> o.voteCount() == null ? 0 : o.voteCount()).sum();
                votersCount = pollDao.countDistinctVoters(p.id());
                if (!Boolean.TRUE.equals(p.isAnonymous())) {
                    voters = new ArrayList<>();
                    for (Long voterId : pollDao.findRecentVoterIds(p.id(), VOTERS_PREVIEW)) {
                        User u = usersById.get(voterId);
                        if (u != null) {
                            voters.add(userMap(u));
                        }
                    }
                }
            }
            Map<String, Object> author = usersById.containsKey(p.userId()) ? userMap(usersById.get(p.userId())) : null;
            result.add(p.withContext(author, optionsWithView, hasVoted, voteTotal, voters, votersCount));
        }
        return result;
    }

    /** canViewResults：仅楼主 → true；结束 → true；按 result_visibility 分支（管理员非楼主按普通用户规则） */
    private boolean canViewResults(Poll poll, Long uid, boolean hasVoted) {
        if (uid != null && uid.equals(poll.userId())) {
            return true;
        }
        if (isEnded(poll)) {
            return true;
        }
        String vis = poll.resultVisibility() == null ? "always" : poll.resultVisibility();
        return switch (vis) {
            case "after_vote" -> hasVoted;
            case "after_deadline" -> isEnded(poll);
            default -> true; // always / 未知值回退可见
        };
    }

    private boolean isEnded(Poll poll) {
        if ("closed".equals(poll.status())) {
            return true;
        }
        return poll.deadline() != null && poll.deadline().isBefore(LocalDateTime.now());
    }

    /** 用户精简信息 {id, nickname, avatar_url}（avatar_url 为 null 时省略） */
    private Map<String, Object> userMap(User user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", user.id());
        m.put("nickname", user.nickname());
        if (user.avatarUrl() != null) {
            m.put("avatar_url", user.avatarUrl());
        }
        return m;
    }

    private Optional<Long> requireLogin(HttpServletRequest request) {
        return tokenService.resolveUserId(request.getHeader("Authorization"));
    }

    private ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
    }

    private ResponseEntity<Object> forbiddenPoll() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "只有楼主可以管理投票。"));
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    // ── 请求体解析 ──────────────────────────────────────

    /** 创建/编辑共用的标量解析结果；error 非 null 表示校验失败 */
    private record PollInput(String title, String description, String type, int minChoice, int maxChoice,
                             LocalDateTime deadline, String resultVisibility, boolean isAnonymous,
                             boolean canChangeVote, String error) {
        static PollInput error(String msg) {
            return new PollInput(null, null, null, 0, 0, null, null, false, false, msg);
        }
    }

    private PollInput parsePollInput(Map<String, Object> body) {
        if (body == null) {
            return PollInput.error("请求体不能为空。");
        }
        String title = body.get("title") == null ? null : String.valueOf(body.get("title")).trim();
        if (title == null || title.isEmpty()) {
            return PollInput.error("投票标题不能为空。");
        }
        if (title.length() > 100) {
            return PollInput.error("字段长度超出限制。");
        }
        String description = body.get("description") == null ? "" : String.valueOf(body.get("description")).trim();
        if (description.length() > 500) {
            return PollInput.error("字段长度超出限制。");
        }
        String type = body.get("type") == null ? "single" : String.valueOf(body.get("type"));
        if (!("single".equals(type) || "multiple".equals(type))) {
            return PollInput.error("无效的投票类型。");
        }
        int minChoice = 1;
        int maxChoice = 1;
        if ("multiple".equals(type)) {
            minChoice = intVal(body.get("min_choice"), 1);
            maxChoice = intVal(body.get("max_choice"), 1);
            if (minChoice < 1) {
                return PollInput.error("至少选择数不能小于 1。");
            }
            if (maxChoice < minChoice) {
                return PollInput.error("至多选择数不能小于至少选择数。");
            }
        }
        LocalDateTime deadline = null;
        if (body.get("deadline") != null) {
            try {
                deadline = LocalDateTime.parse(String.valueOf(body.get("deadline")));
            } catch (Exception e) {
                return PollInput.error("无效的截止时间。");
            }
            if (deadline.isBefore(LocalDateTime.now())) {
                return PollInput.error("截止时间不能早于当前时间。");
            }
        }
        String visibility = body.get("result_visibility") == null
                ? "always" : String.valueOf(body.get("result_visibility"));
        if (!RESULT_VISIBILITIES.contains(visibility)) {
            return PollInput.error("无效的结果可见性。");
        }
        boolean anonymous = boolVal(body.get("is_anonymous"), false);
        boolean canChangeVote = boolVal(body.get("can_change_vote"), true);
        return new PollInput(title, description, type, minChoice, maxChoice, deadline, visibility,
                anonymous, canChangeVote, null);
    }

    /** 创建时解析 options 数组 → 选项文本列表（过滤无效项，数量边界由调用方校验） */
    private List<String> parseOptionTexts(Object optionsObj) {
        List<String> texts = new ArrayList<>();
        if (optionsObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("text") != null) {
                    String t = String.valueOf(m.get("text")).trim();
                    if (t.isEmpty()) {
                        return List.of(); // 空选项 → 返回空让上层报错
                    }
                    if (t.length() > 100) {
                        return List.of();
                    }
                    texts.add(t);
                }
            }
        }
        return texts;
    }

    private int intVal(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean boolVal(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }
}
