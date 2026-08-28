package com.galgame.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.galgame.model.Poll;
import com.galgame.model.PollOption;

/**
 * 帖子投票数据访问层。使用 JdbcTemplate（底层走 JDBC）访问 MySQL。
 * <p>三张表：polls（投票主表）、poll_options（选项，vote_count 冗余计数）、
 * poll_votes（投票记录，唯一键 (poll_id, option_id, user_id) 防重，FK 级联删票）。
 * <p>改票在 vote() 事务内：先删该用户在投票下的旧票并逐选项减计数，再插新票加计数。
 * 删投票 deletePollCascade() 事务内清 votes → options → poll。
 */
@Repository
public class PollDao {

    /** 基础列：polls 表自身全部业务列（不含关联），楼主信息/选项等上下文由 Controller 组装 */
    private static final String BASE_COLUMNS =
            "p.id, p.title, p.description, p.type, p.min_choice, p.max_choice, p.deadline, "
                    + "p.status, p.result_visibility, p.is_anonymous, p.can_change_vote, "
                    + "p.post_id, p.user_id, p.created_at, p.updated_at";

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<Poll> POLL_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            Poll.core(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("type"),
                    rs.getInt("min_choice"),
                    rs.getInt("max_choice"),
                    nullableLocalDateTime(rs, "deadline"),
                    rs.getString("status"),
                    rs.getString("result_visibility"),
                    rs.getBoolean("is_anonymous"),
                    rs.getBoolean("can_change_vote"),
                    rs.getLong("post_id"),
                    rs.getLong("user_id"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<PollOption> OPTION_ROW_MAPPER = (ResultSet rs, int rowNum) ->
            PollOption.core(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getInt("vote_count"));

    public PollDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 某帖子的全部投票（按创建时间倒序，新投票在前） */
    public List<Poll> findByPostId(Long postId) {
        return jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM polls p WHERE p.post_id = ? "
                        + "ORDER BY p.created_at DESC, p.id DESC",
                POLL_ROW_MAPPER, postId);
    }

    public Optional<Poll> findById(Long pollId) {
        List<Poll> rows = jdbcTemplate.query(
                "SELECT " + BASE_COLUMNS + " FROM polls p WHERE p.id = ?",
                POLL_ROW_MAPPER, pollId);
        return rows.stream().findFirst();
    }

    public boolean existsById(Long pollId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM polls WHERE id = ?", Integer.class, pollId);
        return count != null && count > 0;
    }

    /** 某帖子的投票数（创建前校验每帖上限） */
    public int countPollsByPost(Long postId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM polls WHERE post_id = ?", Integer.class, postId);
        return count == null ? 0 : count;
    }

    /** 某投票的选项（按 id 升序，即创建顺序） */
    public List<PollOption> findOptionsByPollId(Long pollId) {
        return jdbcTemplate.query(
                "SELECT id, text, vote_count FROM poll_options WHERE poll_id = ? ORDER BY id",
                OPTION_ROW_MAPPER, pollId);
    }

    /** 新增投票（含选项批量插入），返回数据库生成的自增 id；写 polls 与 poll_options 在同一事务 */
    @Transactional
    public Long insertPoll(String title, String description, String type, int minChoice, int maxChoice,
                           LocalDateTime deadline, String resultVisibility, boolean isAnonymous,
                           boolean canChangeVote, Long postId, Long userId, List<String> optionTexts) {
        String sql = "INSERT INTO polls (title, description, type, min_choice, max_choice, deadline, "
                + "result_visibility, is_anonymous, can_change_vote, post_id, user_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, description);
            ps.setString(3, type);
            ps.setInt(4, minChoice);
            ps.setInt(5, maxChoice);
            if (deadline == null) {
                ps.setNull(6, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(6, java.sql.Timestamp.valueOf(deadline));
            }
            ps.setString(7, resultVisibility);
            ps.setBoolean(8, isAnonymous);
            ps.setBoolean(9, canChangeVote);
            ps.setLong(10, postId);
            ps.setLong(11, userId);
            return ps;
        }, keyHolder);
        Long pollId = keyHolder.getKey().longValue();
        for (String text : optionTexts) {
            insertOptionRow(pollId, text);
        }
        return pollId;
    }

    /**
     * 编辑投票：更新标量 + 选项增删改（同一事务）。
     * add / update / delete 的操作合法性（已有票的选项不能改/删、数量边界等）由 Controller 校验。
     */
    @Transactional
    public void updatePollAndOptions(Long pollId, String title, String description, String type,
                                     int minChoice, int maxChoice, LocalDateTime deadline,
                                     String resultVisibility, boolean isAnonymous, boolean canChangeVote,
                                     List<String> addTexts, List<Long> updateIds, List<String> updateTexts,
                                     List<Long> deleteIds) {
        jdbcTemplate.update(
                "UPDATE polls SET title = ?, description = ?, type = ?, min_choice = ?, max_choice = ?, "
                        + "deadline = ?, result_visibility = ?, is_anonymous = ?, can_change_vote = ? WHERE id = ?",
                title, description, type, minChoice, maxChoice, deadline, resultVisibility,
                isAnonymous, canChangeVote, pollId);
        for (String text : addTexts) {
            insertOptionRow(pollId, text);
        }
        for (int i = 0; i < updateIds.size(); i++) {
            jdbcTemplate.update("UPDATE poll_options SET text = ? WHERE id = ?",
                    updateTexts.get(i), updateIds.get(i));
        }
        for (Long id : deleteIds) {
            jdbcTemplate.update("DELETE FROM poll_options WHERE id = ?", id);
        }
    }

    /** 删除投票：级联清 votes → options → poll（同一事务；poll_votes/poll_options 本有 FK 级联，显式删更稳） */
    @Transactional
    public void deletePollCascade(Long pollId) {
        jdbcTemplate.update("DELETE FROM poll_votes WHERE poll_id = ?", pollId);
        jdbcTemplate.update("DELETE FROM poll_options WHERE poll_id = ?", pollId);
        jdbcTemplate.update("DELETE FROM polls WHERE id = ?", pollId);
    }

    /** 某用户是否已投过该投票 */
    public boolean hasVoted(Long pollId, Long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                Integer.class, pollId, userId);
        return count != null && count > 0;
    }

    /** 批量查某用户已投过的投票 id（防 N+1） */
    public Set<Long> findVotedPollIds(Collection<Long> pollIds, Long userId) {
        if (pollIds == null || pollIds.isEmpty()) {
            return Set.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT poll_id FROM poll_votes WHERE user_id = ? AND poll_id IN (");
        for (int i = 0; i < pollIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        Object[] args = new Object[pollIds.size() + 1];
        args[0] = userId;
        int i = 1;
        for (Long pollId : pollIds) {
            args[i++] = pollId;
        }
        return new HashSet<>(jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rs.getLong("poll_id"), args));
    }

    /** 某用户在该投票中选过的选项 id */
    public Set<Long> findVotedOptionIds(Long pollId, Long userId) {
        return new HashSet<>(jdbcTemplate.query(
                "SELECT option_id FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getLong("option_id"), pollId, userId));
    }

    /** 该投票去重投票人数（voters_count） */
    public int countDistinctVoters(Long pollId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM poll_votes WHERE poll_id = ?",
                Integer.class, pollId);
        return count == null ? 0 : count;
    }

    /** 该投票最近投票的 limit 个去重用户 id（voters 预览用） */
    public List<Long> findRecentVoterIds(Long pollId, int limit) {
        return jdbcTemplate.query(
                "SELECT user_id FROM poll_votes WHERE poll_id = ? "
                        + "GROUP BY user_id ORDER BY MAX(created_at) DESC LIMIT ?",
                (rs, rowNum) -> rs.getLong("user_id"), pollId, limit);
    }

    /** 该选项是否已有投票（已有票的选项编辑时不能改文本或删除） */
    public boolean optionHasVotes(Long optionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poll_votes WHERE option_id = ?", Integer.class, optionId);
        return count != null && count > 0;
    }

    /** 校验一组选项 id 全部属于该投票（投票提交时防投别的投票的选项） */
    public boolean optionsBelongToPoll(Long pollId, List<Long> optionIds) {
        List<Long> unique = optionIds.stream().distinct().toList();
        if (unique.isEmpty()) {
            return true;
        }
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM poll_options WHERE poll_id = ? AND id IN (");
        for (int i = 0; i < unique.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        Object[] args = new Object[unique.size() + 1];
        args[0] = pollId;
        for (int i = 0; i < unique.size(); i++) {
            args[i + 1] = unique.get(i);
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class, args);
        return count != null && count == unique.size();
    }

    /**
     * 投票 / 改票（同一事务）：
     * 先删除该用户在该投票下的旧票并逐选项减计数，再插入新票并逐选项加计数（冗余计数保持一致）。
     */
    @Transactional
    public void vote(Long pollId, Long userId, List<Long> optionIds) {
        List<Long> old = jdbcTemplate.query(
                "SELECT option_id FROM poll_votes WHERE poll_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getLong("option_id"), pollId, userId);
        if (!old.isEmpty()) {
            jdbcTemplate.update("DELETE FROM poll_votes WHERE poll_id = ? AND user_id = ?", pollId, userId);
            for (Long oid : old) {
                jdbcTemplate.update("UPDATE poll_options SET vote_count = GREATEST(0, vote_count - 1) WHERE id = ?", oid);
            }
        }
        List<Long> unique = optionIds.stream().distinct().toList();
        for (Long oid : unique) {
            jdbcTemplate.update(
                    "INSERT INTO poll_votes (poll_id, option_id, user_id) VALUES (?, ?, ?)", pollId, oid, userId);
            jdbcTemplate.update("UPDATE poll_options SET vote_count = vote_count + 1 WHERE id = ?", oid);
        }
    }

    /** 投票日志（分页，按投票 id 倒序最新在前）：返回原始行，用户信息由 Controller 批量组装 */
    public List<Map<String, Object>> findVoteLogs(Long pollId, int offset, int pageSize) {
        return jdbcTemplate.query(
                "SELECT pv.id, pv.created_at, pv.user_id, po.text AS option_text "
                        + "FROM poll_votes pv JOIN poll_options po ON po.id = pv.option_id "
                        + "WHERE pv.poll_id = ? ORDER BY pv.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("created_at", rs.getTimestamp("created_at").toLocalDateTime());
                    m.put("user_id", rs.getLong("user_id"));
                    m.put("option", rs.getString("option_text"));
                    return m;
                }, pollId, pageSize, offset);
    }

    /** 投票日志总数（分页 total） */
    public int countVoteLogs(Long pollId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poll_votes WHERE poll_id = ?", Integer.class, pollId);
        return count == null ? 0 : count;
    }

    private void insertOptionRow(Long pollId, String text) {
        jdbcTemplate.update("INSERT INTO poll_options (poll_id, text) VALUES (?, ?)", pollId, text);
    }

    private static LocalDateTime nullableLocalDateTime(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toLocalDateTime();
    }
}
