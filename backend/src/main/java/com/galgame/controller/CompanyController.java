package com.galgame.controller;

import java.util.Map;
import java.util.Optional;

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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.galgame.auth.TokenService;
import com.galgame.dao.CompanyDao;
import com.galgame.dao.EntityContributorDao;
import com.galgame.dao.UserDao;
import com.galgame.model.Company;
import com.galgame.model.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 会社库接口：公开列表/详情只显示已上架（approved）；提交审核由普通登录用户发起（pending）。
 * <p>该路径未注册到鉴权拦截器，受限接口在此手动解析 token 判定权限（仿 GalgameController）。
 * <p>权限矩阵：列表公开（仅 approved）；详情 pending/rejected 仅创建者或管理员可见；
 * 创建任意登录用户（管理员建即 approved，普通用户建 pending）；编辑管理员可编辑一切、创建者可编辑自己 pending/rejected 提交；
 * 删除管理员可删一切、创建者可删自己 pending 提交；审核接口与待审列表仅管理员。
 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    /** 本控制器的条目类型：写入 entity_contributors 的 entry_type */
    private static final String ENTRY_TYPE = "company";

    private final CompanyDao companyDao;
    private final EntityContributorDao entityContributorDao;
    private final UserDao userDao;
    private final TokenService tokenService;
    private final JsonMapper objectMapper;

    public CompanyController(CompanyDao companyDao, EntityContributorDao entityContributorDao,
                             UserDao userDao, TokenService tokenService, JsonMapper objectMapper) {
        this.companyDao = companyDao;
        this.entityContributorDao = entityContributorDao;
        this.userDao = userDao;
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /** 新建会社请求体 */
    public record CompanyRequest(String name, String description, String website, String logoImage) {
    }

    /** 审核请求体：status 必填（approved 通过 / rejected 拒绝）；reason 仅 rejected 时必填（≤500 字） */
    public record ReviewRequest(String status, String reason) {
    }

    /** 1. 会社列表（公开），?q= 名称模糊搜索、?sort= 排序（created 默认/views/views_asc），均可选 */
    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sort", required = false) String sort) {
        return ResponseEntity.ok(companyDao.findAll(trimToNull(q), sort));
    }

    /**
     * 2. 会社详情：approved 公开（访问 +1 浏览数，返回最新 view_count）；
     * pending/rejected 仅创建者或管理员可见（不 +1 浏览数），其它人一律 404。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Object> getById(@PathVariable Long id, HttpServletRequest request) {
        Optional<Company> opt = companyDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
        }
        Company company = opt.get();
        boolean isApproved = "approved".equals(company.status());
        if (!isApproved) {
            User user = currentUser(request);
            boolean isCreator = user != null && company.createdBy() != null && company.createdBy().equals(user.id());
            if (!isCreator && !isAdmin(user)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
            }
        } else {
            companyDao.incrementView(id);
            company = companyDao.findById(id)
                    .orElseThrow(() -> new IllegalStateException("浏览计数后的会社读取失败"));
        }
        return ResponseEntity.ok(company);
    }

    /**
     * 3. 提交会社（任意登录用户，JSON body）：管理员提交直接上架（status=approved），
     * 普通用户提交进入待审核（status=pending）。
     */
    @PostMapping
    public ResponseEntity<Object> create(@RequestBody(required = false) CompanyRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        String status = isAdmin(user) ? "approved" : "pending";
        return doCreate(body, user.id(), status);
    }

    /**
     * 4. 更新会社（登录，JSON body 全量替换）：管理员可编辑一切；创建者且 status != approved 可编辑自己提交；
     * 任意登录用户对 status=approved 改走「修改申请」——复制原记录为影子行（apply_type='update'、original_id=原id、status='pending'），
     * 等待管理员审核，审核通过合并回原记录；其余 403。update() 不碰 status 列 → 编辑 pending 仍 pending。
     */
    @PutMapping("/{id}")
    public ResponseEntity<Object> update(@PathVariable Long id, @RequestBody(required = false) CompanyRequest body,
                                         HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Company> opt = companyDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
        }
        Company company = opt.get();
        boolean isCreator = company.createdBy() != null && company.createdBy().equals(user.id());
        // 管理员：原地编辑一切；创建者：非 approved 原地编辑；
        // 任意登录用户：对已上架（approved）提交「修改申请」影子行
        boolean canEdit = isAdmin(user) || (isCreator && !"approved".equals(company.status()));
        boolean canApply = !isAdmin(user) && "approved".equals(company.status());
        if (!canEdit && !canApply) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedCompanyRequest n = result.value();
        if (canApply) {
            long shadowId = companyDao.createUpdateShadow(id, n.name(), n.description(), n.website(), n.logoImage(), user.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", shadowId,
                    "status", "pending",
                    "apply_type", "update",
                    "original_id", id,
                    "message", "修改申请已提交，等待审核"));
        }
        if (!companyDao.update(id, n.name(), n.description(), n.website(), n.logoImage())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
        }
        // 管理员原地直改已上架（approved）：管理员列为贡献者并 +10 萌点（尽力而为，失败不阻断更新）。
        // 创建者改自己 pending/rejected 不触发（非管理员，且 status 不是 approved）。
        if (isAdmin(user) && "approved".equals(company.status())) {
            try {
                entityContributorDao.addContribution(ENTRY_TYPE, id, user.id());
                userDao.adjustMoePoints(user.id(), 10);
            } catch (Exception ignored) {
                // 萌点/贡献者异常不阻断更新主流程
            }
        }
        // 从 DB 回查，保证 updated_at 为数据库真实值
        Company saved = companyDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后的会社读取失败"));
        return ResponseEntity.ok(saved);
    }

    /**
     * 5. 删除会社（登录）：管理员可删一切；创建者且 status='pending' 可删自己提交；其余 403。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Object> delete(@PathVariable Long id, HttpServletRequest request) {
        User user = currentUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        Optional<Company> opt = companyDao.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
        }
        Company company = opt.get();
        boolean isCreator = company.createdBy() != null && company.createdBy().equals(user.id());
        if (!isAdmin(user) && !(isCreator && "pending".equals(company.status()))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "没有权限。"));
        }
        // 删原记录前先清理其全部修改申请影子行（含待审/已拒绝的），避免残留
        companyDao.deleteShadows(id);
        companyDao.deleteById(id);
        // 清理该条目的贡献者记录（删除后贡献者列表不再引用已删除条目）
        entityContributorDao.deleteContributions(ENTRY_TYPE, id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 6. 待审核列表（管理员）：返回 status='pending' 的会社（含提交人昵称 creator）。精确路径 /pending 优先于 /{id} 模板。 */
    @GetMapping("/pending")
    public ResponseEntity<Object> pending(HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        return ResponseEntity.ok(companyDao.findPending());
    }

    /** 6.5 「我的提交」（登录）：返回当前用户创建的全部会社（含各审核状态，status/reject_reason 一并返回）。未登录 401。 */
    @GetMapping("/mine")
    public ResponseEntity<Object> mine(HttpServletRequest request) {
        Optional<Long> uid = currentUserId(request);
        if (uid.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "请先登录。"));
        }
        return ResponseEntity.ok(companyDao.findByCreator(uid.get()));
    }

    /**
     * 7. 审核（管理员）：body {status:'approved'|'rejected', reason?}；
     * rejected 时 reason 必填（trim 非空且 ≤500，否则 400「请填写拒绝理由。」）；
     * 通过给提交者 +10 萌点（claimMoeAward 原子防重，每条会社只发一次）；返回更新后的记录。
     * 修改申请（apply_type='update'）通过时：合并回原记录并删除影子行，返回原记录，不给萌点；
     * 拒绝时：不管 create/update 都置 rejected + 理由，不删影子行（提交者可在「我的提交」看到）。
     */
    @PostMapping("/{id}/review")
    public ResponseEntity<Object> review(@PathVariable Long id,
                                         @RequestBody(required = false) ReviewRequest body,
                                         HttpServletRequest request) {
        ResponseEntity<Object> gate = adminGate(request);
        if (gate != null) {
            return gate;
        }
        Optional<Company> targetOpt = companyDao.findById(id);
        if (targetOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "会社不存在。"));
        }
        Company target = targetOpt.get();
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
        // 修改申请审核通过：合并回原记录并删除影子行，返回原记录（Map）。
        // 修改提交者（影子行 created_by）列为贡献者并 +10 萌点；claimMoeAward 在 applyEdit 删影子行之前调用。
        if ("approved".equals(status) && "update".equals(target.applyType())) {
            Long originalId = target.originalId();
            if (originalId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "修改申请缺少原记录。"));
            }
            // 修改提交者（可能为 null：用户被删后 created_by 置 NULL）→ null 就跳过萌点/贡献者
            Long submittedBy = target.createdBy();
            int moeGranted = 0;
            if (submittedBy != null) {
                try {
                    entityContributorDao.addContribution(ENTRY_TYPE, originalId, submittedBy);
                    // claimMoeAward 原子防重——每条修改申请影子行只发一次；必须先于 applyEdit 删影子行
                    if (companyDao.claimMoeAward(id)) {
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
            companyDao.applyEdit(id);
            Company original = companyDao.findById(originalId)
                    .orElseThrow(() -> new IllegalStateException("修改申请合并后的原记录读取失败"));
            Map<String, Object> map = objectMapper.convertValue(original, new TypeReference<Map<String, Object>>() {});
            map.put("applied", true);
            map.put("moe_granted", moeGranted);
            return ResponseEntity.ok(map);
        }
        companyDao.updateStatus(id, status, reason);
        // 萌点：审核通过进入公共列表时给提交者 +10（尽力而为，失败不阻断审核）。
        // claimMoeAward 原子防重——每条会社只发一次，反复通过/拒绝不会重复加分；修改申请通过不给萌点
        Company saved = companyDao.findById(id)
                .orElseThrow(() -> new IllegalStateException("审核后的会社读取失败"));
        if ("approved".equals(status) && saved.createdBy() != null && companyDao.claimMoeAward(id)) {
            try {
                userDao.adjustMoePoints(saved.createdBy(), 10);
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断审核主流程
            }
        }
        return ResponseEntity.ok(saved);
    }

    // ── 私有辅助 ─────────────────────────────

    private ResponseEntity<Object> doCreate(CompanyRequest body, long userId, String status) {
        ValidationResult result = normalizeAndValidate(body);
        if (result.error() != null) {
            return result.error();
        }
        NormalizedCompanyRequest n = result.value();
        Long companyId = companyDao.insert(n.name(), n.description(), n.website(), n.logoImage(), userId, status, "create", null);
        // 创建者即条目贡献者（try-catch 静默，失败不阻断提交）
        try {
            entityContributorDao.addContribution(ENTRY_TYPE, companyId, userId);
        } catch (Exception ignored) {
            // 贡献者记录异常不阻断提交主流程
        }
        // 管理员直接上架（status=approved）即进入公共列表：给提交者 +10 萌点（原子置位，每条只奖一次；尽力而为）
        if ("approved".equals(status)) {
            try {
                if (companyDao.claimMoeAward(companyId)) {
                    userDao.adjustMoePoints(userId, 10);
                }
            } catch (Exception ignored) {
                // 萌点奖励异常不阻断提交主流程
            }
        }
        // 从 DB 回查，保证 created_at / updated_at / id / status 为数据库真实值
        Company saved = companyDao.findById(companyId)
                .orElseThrow(() -> new IllegalStateException("写入的会社读取失败"));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /** 校验通过的规范化请求体：create 与 update 共用 */
    private record NormalizedCompanyRequest(String name, String description, String website, String logoImage) {
    }

    /** 校验结果：error 非 null 表示校验失败（可直接作为响应返回），否则 value 为规范化后的请求体 */
    private record ValidationResult(ResponseEntity<Object> error, NormalizedCompanyRequest value) {
    }

    /** 校验并规范化请求体（create / update 共用）：name 必填且 ≤200，description ≤2000，website ≤500，logoImage ≤500 */
    private ValidationResult normalizeAndValidate(CompanyRequest body) {
        String name = body == null ? "" : trimToNull(body.name());
        if (name == null || name.isEmpty()) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "名称不能为空。")), null);
        }
        if (name.length() > 200) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        String description = body == null ? null : trimToNull(body.description());
        String website = body == null ? null : trimToNull(body.website());
        String logoImage = body == null ? null : trimToNull(body.logoImage());
        if (description != null && description.length() > 2000) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        if (website != null && website.length() > 500) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        if (logoImage != null && logoImage.length() > 500) {
            return new ValidationResult(ResponseEntity.badRequest().body(Map.of("error", "字段长度超出限制。")), null);
        }
        return new ValidationResult(null, new NormalizedCompanyRequest(name, description, website, logoImage));
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

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
