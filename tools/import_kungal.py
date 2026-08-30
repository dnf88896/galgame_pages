# -*- coding: utf-8 -*-
"""从 KUNGal 公开 API 批量导入 galgame 详情到本地 MySQL（直连，status=approved 立即可见）。

用法:
    python import_kungal.py                  # 默认抓列表前 10 部
    python import_kungal.py --limit 10       # 抓 N 部
    python import_kungal.py --ids 4572,862   # 按 gid 导入指定作品
    python import_kungal.py --min-rating 8   # 仅导入评分 >= 8 的（可配 --limit）
    python import_kungal.py --list           # 只列出列表页作品，不导入

说明:
    - 抓取: GET https://www.kungal.com/api/galgame?page=1&limit=50 目录 → 逐部 GET /api/galgame/{gid} 详情
    - 写库: 直连 MySQL。实体表 (tags/staffs/characters/companies) 按 name 去重 upsert，
            关联表 galgame_tag/galgame_staff/galgame_character/staff_character 均 INSERT IGNORE 幂等。
    - 防重复: galgames 存 kungal_id（KUNGal 作品 gid）+ 唯一索引，主防线按 gid 去重；
            老数据（无 gid）按 name 兜底命中并回填 gid。主循环预载已导入 gid 集合，直接跳过不发详情请求。
    - 描述截断 ≤2000（后端校验）、image ≤500；status 一律置 approved。
    - 仅本地脚本（凭据从 application.properties 解析）。
"""
import os
import re
import json
import sys
import time
import argparse
import subprocess
import urllib.request

import pymysql

PROPS = r"C:\Users\yinsh\Desktop\claude code\galgame_pages\backend\src\main\resources\application.properties"
BASE = "https://www.kungal.com/api"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GalgameImport/1.0"

# ---- 实体表 name → id 缓存 ----
_tags = {}
_staff = {}
_chars = {}
_companies = {}

# KUNGal 字段 → 项目 24 分类 gg-* section_key 映射（group: type/lang/plat/work）
TYPE_MAP = {
    "game": "gg-type-game", "patch": "gg-type-patch", "collection": "gg-type-collection",
    "voice": "gg-type-voice", "image": "gg-type-image", "ai": "gg-type-ai",
    "video": "gg-type-video", "others": "gg-type-others",
}
LANG_MAP = {
    "ja-jp": "gg-lang-ja-jp", "en-us": "gg-lang-en-us",
    "zh-cn": "gg-lang-zh-cn", "zh-tw": "gg-lang-zh-tw", "others": "gg-lang-others",
}
PLAT_MAP = {
    "windows": "gg-plat-windows", "mac": "gg-plat-mac", "linux": "gg-plat-linux",
    "emulator": "gg-plat-emulator", "app": "gg-plat-app", "others": "gg-plat-others",
}


def build_categories(detail):
    """从 KUNGal type/language/platform 映射出项目分类 section_key 列表（去重，跳过未知）。"""
    cats = set()
    for t in detail.get("type") or []:
        if t in TYPE_MAP:
            cats.add(TYPE_MAP[t])
    for l in detail.get("language") or []:
        if l in LANG_MAP:
            cats.add(LANG_MAP[l])
    for p in detail.get("platform") or []:
        if p in PLAT_MAP:
            cats.add(PLAT_MAP[p])
    return sorted(cats)


def build_links(detail):
    """从 KUNGal refs（VNDB/Bangumi/DLsite/Getchu/DMM/ErogameScape 外链 id）+ dlsite 购买短链 → [{label,url}]。"""
    links = []
    refs = detail.get("refs") or {}
    if refs.get("vndb"):
        links.append({"label": "VNDB", "url": f"https://vndb.org/{refs['vndb']}"})
    if refs.get("bangumi"):
        links.append({"label": "Bangumi", "url": f"https://bgm.tv/subject/{refs['bangumi']}"})
    if refs.get("dlsite"):
        links.append({"label": "DLsite", "url": f"https://www.dlsite.com/maniax/work/=/product_id/{refs['dlsite']}.html"})
    if refs.get("getchu"):
        links.append({"label": "Getchu", "url": f"https://www.getchu.com/soft.phtml?id={refs['getchu']}"})
    if refs.get("dmm"):
        links.append({"label": "DMM", "url": f"https://www.dmm.co.jp/monthly/girls_detail/=/cid={refs['dmm']}/"})
    if refs.get("erogamescape"):
        links.append({"label": "ErogameScape", "url": f"https://erogamescape.dyndns.org/~ap2/ero/toukei_kaiseki/game.php?game={refs['erogamescape']}"})
    if detail.get("dlsite_purchase_url"):
        links.append({"label": "DLsite 购买", "url": detail["dlsite_purchase_url"]})
    return links


def load_db_conf():
    """读取 DB 配置。优先从 application.properties 解析（本地）；文件不存在时（如服务器上直接跑）
    回退默认 localhost/3306/galgame/galgame123（与项目约定一致，服务器上 localhost 即本机 MySQL）。
    """
    def placeholder(v):
        m = re.match(r"\$\{([^:}]+):([^}]*)\}", v)
        if m:
            return os.environ.get(m.group(1), m.group(2))
        return v

    try:
        with open(PROPS, "r", encoding="utf-8") as f:
            cfg = {}
            for line in f:
                line = line.strip()
                if line and "=" in line and not line.startswith("#"):
                    k, v = line.split("=", 1)
                    cfg[k.strip()] = v.strip()
        url = cfg.get("spring.datasource.url", "")
        m = re.search(r"jdbc:mysql://([^:/]+):([^/]+)/([^?]+)", url)
        if m:
            return {
                "host": m.group(1), "port": int(placeholder(m.group(2))),
                "db": m.group(3),
                "user": placeholder(cfg.get("spring.datasource.username", "root")),
                "password": placeholder(cfg.get("spring.datasource.password", "")),
            }
    except FileNotFoundError:
        pass  # 服务器上没有该文件，回退默认
    return {
        "host": "localhost", "port": 3306,
        "db": "galgame", "user": "galgame", "password": "galgame123",
    }


def http_get(path, timeout=30):
    req = urllib.request.Request(BASE + path, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def safe_text(v, max_len):
    if not v:
        return None
    v = str(v).strip()
    if not v:
        return None
    return v[:max_len]


# ---- VNDB 补会社简介 ----
# KUNGal 的 company/official 详情接口要登录（401），但详情里的 vndb_id 可走 VNDB kana API：
# vndb_id → VN developers → producer description。urllib 直连 VNDB 会被 TLS 重置（GFW 指纹干扰），
# 系统 curl 稳定，故 subprocess 调 curl。失败一律降级返回 None，不影响导入主流程。
VNDB_URL = "https://api.vndb.org/kana"
_vn_devs = {}   # vndb_id -> [producer ids]（None 也缓存，避免重复请求）
_prod_desc = {}  # producer id -> description（None 也缓存）
_use_vndb = True


def vndb_query(endpoint, body):
    try:
        p = subprocess.run(
            ["curl", "-s", "--max-time", "20", "-X", "POST", f"{VNDB_URL}/{endpoint}",
             "-H", "Content-Type: application/json",
             "-H", "User-Agent: GalgameImport/1.0 (contact: none)",
             "-d", json.dumps(body, ensure_ascii=False)],
            capture_output=True, text=True, timeout=30,
        )
        if p.returncode != 0 or not p.stdout:
            return None
        return json.loads(p.stdout)
    except Exception:
        return None


def clean_vndb_desc(s):
    """清理 VNDB 简介的 BBCode（[url=X]文字[/url]→文字、其余标签去掉），纯文本给前端。"""
    if not s:
        return s
    s = re.sub(r"\[url=[^\]]*\](.*?)\[/url\]", r"\1", s, flags=re.S)
    s = re.sub(r"\[/?[a-z0-9_=\- ]+\]", "", s, flags=re.I)
    return s.strip()


def fetch_company_description(vndb_id):
    """经 VNDB 拿会社简介，返回清理后的 description 或 None（找不到/无简介/请求失败都返回 None）。"""
    if not _use_vndb or not vndb_id:
        return None
    if vndb_id in _vn_devs:
        devs = _vn_devs[vndb_id]
    else:
        vn = vndb_query("vn", {"filters": ["id", "=", vndb_id], "fields": "developers.id", "results": 1})
        devs = ((vn or {}).get("results") or [{}])[0].get("developers") or []
        _vn_devs[vndb_id] = devs
    for d in devs:
        pid = d.get("id")
        if not pid:
            continue
        if pid in _prod_desc:
            dsc = _prod_desc[pid]
        else:
            pr = vndb_query("producer", {"filters": ["id", "=", pid], "fields": "description", "results": 1})
            r = ((pr or {}).get("results") or [{}])[0]
            dsc = r.get("description")
            _prod_desc[pid] = dsc
        if dsc:
            return clean_vndb_desc(dsc)
    return None


def upsert_tag(cur, name, category, spoiler):
    """tags 按 name 去重（唯一索引）。返回 id。"""
    key = name
    if key in _tags:
        return _tags[key]
    cur.execute("SELECT id FROM tags WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        _tags[key] = row[0]
        return row[0]
    cat = category if category in ("type", "language", "platform", "content", "meta", "technical", "sexual") else "content"
    cur.execute(
        "INSERT INTO tags (name, category, spoiler_level, status) VALUES (%s,%s,%s,'approved')",
        (name, cat, int(spoiler or 0)),
    )
    _tags[key] = cur.lastrowid
    return cur.lastrowid


def upsert_company(cur, name, link=None, description=None):
    key = name
    if key in _companies:
        # 库内已有但简介空、这次拿到新简介 → 补上
        if description:
            cur.execute(
                "UPDATE companies SET description=%s WHERE id=%s AND (description IS NULL OR description='')",
                (safe_text(description, 2000), _companies[key]),
            )
        return _companies[key]
    cur.execute("SELECT id, website, description FROM companies WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        # 补网站 / 简介（若原来没有）
        if link and not row[1]:
            cur.execute("UPDATE companies SET website=%s WHERE id=%s", (link[:500], row[0]))
        if description and not row[2]:
            cur.execute("UPDATE companies SET description=%s WHERE id=%s", (safe_text(description, 2000), row[0]))
        _companies[key] = row[0]
        return row[0]
    cur.execute(
        "INSERT INTO companies (name, website, description, status) VALUES (%s,%s,%s,'approved')",
        (name, safe_text(link, 500), safe_text(description, 2000)),
    )
    _companies[key] = cur.lastrowid
    return cur.lastrowid


def upsert_staff(cur, name):
    key = name
    if key in _staff:
        return _staff[key]
    cur.execute("SELECT id FROM staffs WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        _staff[key] = row[0]
        return row[0]
    cur.execute("INSERT INTO staffs (name, status) VALUES (%s,'approved')", (name,))
    _staff[key] = cur.lastrowid
    return cur.lastrowid


def upsert_character(cur, name, image=None, description=None):
    key = name
    if key in _chars:
        return _chars[key]
    cur.execute("SELECT id FROM characters WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        _chars[key] = row[0]
        return row[0]
    cur.execute(
        "INSERT INTO characters (name, image, description, status) VALUES (%s,%s,%s,'approved')",
        (name, safe_text(image, 500), safe_text(description, 2000)),
    )
    _chars[key] = cur.lastrowid
    return cur.lastrowid


def import_one(cur, gid, detail, dry=False):
    """导入一部 galgame 详情。返回 (gid, name, 是否新建, series_list)。

    series_list 为 [(series_id, series_name)]，供 main() 导入完后建 galgame_related 关联。
    """
    name = safe_text(detail.get("name"), 200)
    if not name:
        return gid, "(无名称)", False, None
    # 防重复（主防线：KUNGal gid 唯一键，同一作品无论 name 怎么变都不会重复；
    #         兜底：name 精确匹配命中老数据 → 回填 gid，之后按 gid 稳定去重）
    cur.execute("SELECT id FROM galgames WHERE kungal_id=%s", (gid,))
    if cur.fetchone():
        return gid, f"{name} (gid={gid} 已导入过，跳过)", False, None
    cur.execute("SELECT id FROM galgames WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        cur.execute("UPDATE galgames SET kungal_id=%s WHERE id=%s AND kungal_id IS NULL", (gid, row[0]))
        return gid, f"{name} (同名已存在，跳过；回填 gid={gid})", False, None

    description = safe_text(detail.get("intro_text") or detail.get("introduction"), 2000)
    image = detail.get("effective_banner_url") or detail.get("effective_portrait_url") or None
    image = safe_text(image, 500)
    release_date = detail.get("release_date") or None
    if release_date and not re.match(r"^\d{4}-\d{2}-\d{2}$", str(release_date)):
        release_date = None

    # 会社：取第一个 official（或 publisher 优先）→ companies + company_id + staff 快照
    officials = detail.get("official") or []
    company_id = None
    company_name = None
    if officials:
        # 优先 developer 角色 / 否则第一个
        chosen = None
        for o in officials:
            roles = o.get("roles") or []
            if "developer" in roles:
                chosen = o
                break
        if chosen is None:
            chosen = officials[0]
        company_name = safe_text(chosen.get("name"), 200)
        if company_name:
            # 会社简介：KUNGal 没有（官方接口要登录），经 vndb_id 走 VNDB producer description 补
            company_desc = fetch_company_description(detail.get("vndb_id"))
            company_id = upsert_company(cur, company_name, chosen.get("link"), company_desc)

    # 标签 → tags + galgame_tag
    tag_ids = []
    for t in detail.get("tag") or []:
        tname = safe_text(t.get("name"), 50)
        if not tname:
            continue
        tid = upsert_tag(cur, tname, t.get("category"), t.get("spoiler_level"))
        if tid not in tag_ids:
            tag_ids.append(tid)

    # 制作人员 → staffs + galgame_staff（description=职位）
    staff_links = []  # (staff_id, role)
    for grp in detail.get("staff") or []:
        role = safe_text(grp.get("role_name"), 200) or "制作"
        seen = set()
        for p in grp.get("people") or []:
            pname = safe_text(p.get("name"), 200)
            if not pname or pname in seen:
                continue
            seen.add(pname)
            staff_links.append((upsert_staff(cur, pname), role))

    # 角色 + CV → characters / staff_character
    char_links = []  # (character_id, kind)
    cv_links = []    # (staff_id, character_id)
    for c in detail.get("characters") or []:
        cname = safe_text(c.get("name"), 200)
        if not cname:
            continue
        kind = safe_text(c.get("kind"), 200) or None
        cid = upsert_character(cur, cname, c.get("image"), kind)
        char_links.append((cid, kind))
        for v in c.get("voices") or []:
            vname = safe_text(v.get("name"), 200)
            if not vname:
                continue
            vid = upsert_staff(cur, vname)
            cv_links.append((vid, cid))

    # 分类（galgame_tags：type/lang/plat 映射）与资源链接（links JSON）
    categories = build_categories(detail)
    links = build_links(detail)
    links_json = json.dumps(links, ensure_ascii=False)

    # 插入 galgame
    staff_snapshot = company_name  # 关联会社时 staff 用公司名（与后端一致）
    cur.execute(
        """INSERT INTO galgames
           (name, description, image, staff, company_id, release_date, links, status, kungal_id)
           VALUES (%s,%s,%s,%s,%s,%s,%s,'approved',%s)""",
        (name, description, image, staff_snapshot, company_id, release_date, links_json, gid),
    )
    gid_db = cur.lastrowid

    # 关联表
    for c in categories:
        cur.execute("INSERT IGNORE INTO galgame_tags (galgame_id, section_key) VALUES (%s,%s)", (gid_db, c))
    for tid in tag_ids:
        cur.execute("INSERT IGNORE INTO galgame_tag (galgame_id, tag_id) VALUES (%s,%s)", (gid_db, tid))
    for sid, role in staff_links:
        cur.execute(
            "INSERT IGNORE INTO galgame_staff (galgame_id, staff_id, description) VALUES (%s,%s,%s)",
            (gid_db, sid, role),
        )
    for cid, kind in char_links:
        cur.execute(
            "INSERT IGNORE INTO galgame_character (galgame_id, character_id, description) VALUES (%s,%s,%s)",
            (gid_db, cid, kind),
        )
    for vid, cid in cv_links:
        cur.execute(
            "INSERT IGNORE INTO staff_character (staff_id, character_id) VALUES (%s,%s)",
            (vid, cid),
        )

    # 画廊（galgame_images：KUNGal screenshots 的 cdn_url 按 sort_order 排序）
    for sc in detail.get("screenshots") or []:
        u = safe_text(sc.get("cdn_url"), 500)
        if not u:
            continue
        cur.execute(
            "INSERT INTO galgame_images (galgame_id, url, sort_order) VALUES (%s,%s,%s)",
            (gid_db, u, sc.get("sort_order") or 0),
        )

    # 系列：持久化到 series + galgame_series（供跨批全库重算关联）
    for s in detail.get("series") or []:
        sid = s.get("id")
        if not sid:
            continue
        cur.execute(
            "INSERT IGNORE INTO series (id, name) VALUES (%s,%s)",
            (sid, safe_text(s.get("name"), 200)),
        )
        cur.execute(
            "INSERT IGNORE INTO galgame_series (galgame_id, series_id) VALUES (%s,%s)",
            (gid_db, sid),
        )

    return gid, name, True, gid_db


def _ensure_kungal_id_column(cur):
    """galgames 加 kungal_id 列 + 唯一索引（幂等：MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，
    用 information_schema 判断）。kungal_id = KUNGal 作品 gid，作为批量导入的稳定唯一键。
    唯一索引允许 NULL，存量老数据（未存 gid）不受影响。"""
    cur.execute(
        "SELECT COUNT(*) FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='galgames' AND COLUMN_NAME='kungal_id'")
    if cur.fetchone()[0] == 0:
        cur.execute(
            "ALTER TABLE galgames ADD COLUMN kungal_id BIGINT NULL "
            "COMMENT 'KUNGal 作品 gid（批量导入唯一键，防重复）' AFTER id")
    cur.execute(
        "SELECT COUNT(*) FROM information_schema.STATISTICS "
        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='galgames' AND INDEX_NAME='uk_galgames_kungal_id'")
    if cur.fetchone()[0] == 0:
        cur.execute("ALTER TABLE galgames ADD UNIQUE INDEX uk_galgames_kungal_id (kungal_id)")


def _ensure_series_tables(cur):
    """建 series / galgame_series 表（幂等，兜底：schema.sql 正式建表前脚本可独立运行）。"""
    cur.execute(
        """CREATE TABLE IF NOT EXISTS series (
            id         BIGINT       NOT NULL PRIMARY KEY COMMENT 'KUNGal series id',
            name       VARCHAR(200) NOT NULL,
            created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci"""
    )
    cur.execute(
        """CREATE TABLE IF NOT EXISTS galgame_series (
            galgame_id BIGINT   NOT NULL,
            series_id  BIGINT   NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (galgame_id, series_id),
            CONSTRAINT fk_gser_galgame FOREIGN KEY (galgame_id) REFERENCES galgames (id) ON DELETE CASCADE,
            CONSTRAINT fk_gser_series  FOREIGN KEY (series_id) REFERENCES series (id) ON DELETE CASCADE
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci"""
    )


def rebuild_related(cur):
    """全库按系列重算 galgame_related（双向，INSERT IGNORE 幂等）。

    返回 (新增关联条数, {series_id: (series_name, [galgame_db_id,...])})。
    每次导入后调用：库中任意系列只要有 ≥2 部（无论哪批导入）都会两两关联。
    """
    cur.execute("SELECT s.id, s.name, gs.galgame_id FROM series s JOIN galgame_series gs ON gs.series_id = s.id")
    groups = {}
    for sid, sname, gid_db in cur.fetchall():
        groups.setdefault(sid, {"name": sname, "ids": []})["ids"].append(gid_db)
    linked = 0
    for sid, info in groups.items():
        ids = list(set(info["ids"]))
        if len(ids) < 2:
            continue
        for a in ids:
            for b in ids:
                if a != b:
                    cur.execute(
                        "INSERT IGNORE INTO galgame_related (galgame_id, related_id) VALUES (%s,%s)",
                        (a, b),
                    )
                    linked += 1
    return linked, {sid: (info["name"], list(set(info["ids"]))) for sid, info in groups.items()}


def backfill_descriptions(cur):
    """给现有库补会社简介：扫 galgames 的 VNDB 链接 → VNDB producer description，
    写回 companies.description（只补空值，幂等）。返回更新行数。"""
    cur.execute(
        """SELECT g.id, g.links, g.company_id, c.name
           FROM galgames g JOIN companies c ON c.id = g.company_id
           WHERE c.description IS NULL OR c.description = ''"""
    )
    rows = cur.fetchall()
    done = 0
    for gid, links, cid, cname in rows:
        v = None
        try:
            for l in json.loads(links) if links else []:
                if l.get("label") == "VNDB":
                    v = l.get("url", "").rstrip("/").split("/")[-1]
                    break
        except Exception:
            pass
        if not v:
            continue
        desc = fetch_company_description(v)
        if not desc:
            continue
        cur.execute(
            "UPDATE companies SET description=%s WHERE id=%s AND (description IS NULL OR description='')",
            (safe_text(desc, 2000), cid),
        )
        if cur.rowcount:
            done += 1
            print(f"  补简介: {cname} <- {desc[:60]}…")
    return done


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=10, help="导入数量（默认 10）")
    ap.add_argument("--ids", default=None, help="指定 gid 列表，逗号分隔")
    ap.add_argument("--min-rating", type=float, default=None, help="只导入评分>=此值的")
    ap.add_argument("--list", action="store_true", help="只列目录不导入")
    ap.add_argument("--no-vndb", action="store_true", help="跳过 VNDB 补会社简介（服务器 VNDB 不可用时用）")
    ap.add_argument("--backfill-desc", action="store_true", help="只给现有库补会社简介（按 VNDB 链接，幂等），不导入新作品")
    args = ap.parse_args()
    global _use_vndb
    _use_vndb = not args.no_vndb

    if args.backfill_desc:
        print("[backfill] 给现有库补会社简介…")
        conn = pymysql.connect(**load_db_conf(), charset="utf8mb4")
        try:
            with conn.cursor() as cur:
                n = backfill_descriptions(cur)
                conn.commit()
            print(f"完成：补简介 {n} 家会社。")
        finally:
            conn.close()
        return

    # 抓列表（--ids 模式不需要：直接按 gid 抓详情）
    if args.ids:
        picks = [{"id": int(x.strip())} for x in args.ids.split(",") if x.strip()]
        # 同一 gid 重复传只导一次
        seen, picks = set(), []
        for x in args.ids.split(","):
            x = x.strip()
            if not x:
                continue
            gid = int(x)
            if gid not in seen:
                seen.add(gid)
                picks.append({"id": gid})
    else:
        print("[1/2] 抓取 KUNGal 目录…")
        # KUNGal limit 上限 50/页（实际返回 40+ 条）。--limit 大时翻页抓候选；
        # 全量（limit>=total）时抓完所有页（4459 部约 104 页）
        data = http_get(f"/galgame?page=1&limit=50")
        total = (data.get("data") or {}).get("total") or 0
        candidates = list((data.get("data") or {}).get("galgames") or [])
        page = 2
        target = max(args.limit * 2, args.limit)
        if target > total:
            target = total  # 全量/超量：抓完所有页
        while len(candidates) < target:
            data = http_get(f"/galgame?page={page}&limit=50")
            galgames = (data.get("data") or {}).get("galgames") or []
            if not galgames:
                break
            candidates.extend(galgames)
            page += 1
            time.sleep(0.3)  # 目录页也限速
            if page > 400:  # 兜底防死循环（4459/40≈112 页）
                break
        print(f"      目录 total={total}，已抓取 {len(candidates)} 条候选")
        picks = candidates
        if args.min_rating is not None:
            picks = [g for g in picks if (g.get("rating") or 0) >= args.min_rating]
        # sfw 优先：content_limit=sfw 排前面（不够再补）
        picks.sort(key=lambda g: 0 if g.get("content_limit") == "sfw" else 1)
        picks = picks[: args.limit]

    if args.list:
        for g in picks:
            print(f"  {g.get('id'):>7}  {g.get('name')}  | {g.get('company')} | {g.get('release_date')} | rating {g.get('rating')}")
        return

    if not picks:
        print("!! 没有符合条件的作品")
        return

    print(f"[2/2] 抓详情并写入数据库（{len(picks)} 部）…")
    conn = pymysql.connect(**load_db_conf(), charset="utf8mb4")
    try:
        with conn.cursor() as cur:
            _ensure_series_tables(cur)
            _ensure_kungal_id_column(cur)
            # 已导入 gid 集合：防重复，跑第二批/重跑时直接跳过已存在作品（不发详情请求）
            cur.execute("SELECT kungal_id FROM galgames WHERE kungal_id IS NOT NULL")
            existing = {row[0] for row in cur.fetchall()}
            print(f"      库中已有 {len(existing)} 部带 gid 的作品（防重复将跳过）")
            ok, skip = 0, 0
            for i, g in enumerate(picks, 1):
                gid = g.get("id")
                print(f"  [{i}/{len(picks)}] gid={gid} {g.get('name')}…", flush=True)
                if gid in existing:
                    skip += 1
                    print(f"      -> {g.get('name')} (gid={gid} 已导入过，跳过)")
                    continue
                try:
                    det = http_get(f"/galgame/{gid}")
                except Exception as e:
                    print(f"      !! 详情抓取失败: {e}")
                    continue
                detail = det.get("data") or {}
                res = import_one(cur, gid, detail)
                created = res[2]
                if created:
                    ok += 1
                else:
                    skip += 1
                print(f"      -> {res[1]}")
                time.sleep(0.3)  # 限速
                # 每 50 部增量提交：长任务断点续传保障（中途失败最多丢一批，防重复补抓；不 commit 则全量积压在一个事务里，失败全回滚）
                if i % 50 == 0:
                    conn.commit()
                    print(f"      [进度] 已提交前 {i} 部（DB 可见）")

            # 全库按系列重算关联（galgame_related，双向）：无论哪个批次导入的作品，
            # 只要属于同一系列（galgame_series 有记录）就两两关联，INSERT IGNORE 幂等补全。
            linked, group_info = rebuild_related(cur)
            for sid, (sname, ids) in group_info.items():
                if len(ids) >= 2:
                    print(f"  系列「{sname}」关联 {len(ids)} 部")
            conn.commit()
        print(f"完成：新建 {ok} 部，跳过 {skip} 部，系列关联 {linked} 条。")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
