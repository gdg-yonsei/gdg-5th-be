"""SQLite: LIKE 풀스캔 vs FTS5(unicode61 / prefix / trigram) — 한글 검색 누락 재현"""
import sqlite3, time, json
from gen_data import make_reviews

N = 1_000_000
docs = make_reviews(N)
c = sqlite3.connect(":memory:")
c.execute("CREATE TABLE reviews(id INTEGER PRIMARY KEY, body TEXT)")
c.executemany("INSERT INTO reviews(body) VALUES (?)", ((d,) for d in docs))
c.execute("CREATE INDEX idx_body ON reviews(body)")   # B-Tree: LIKE '%x%'에는 무용
out = {"N": N}
def t(sql, args=(), rep=3):
    best = 1e9
    for _ in range(rep):
        s = time.perf_counter(); r = c.execute(sql, args).fetchall(); best = min(best, time.perf_counter()-s)
    return round(best*1000, 2), r[0][0] if r else None
for name, tok in (("uni", "unicode61"), ("tri", "trigram")):
    s = time.perf_counter()
    c.execute(f"CREATE VIRTUAL TABLE fts_{name} USING fts5(body, tokenize='{tok}', content='reviews', content_rowid='id')")
    c.execute(f"INSERT INTO fts_{name}(rowid, body) SELECT id, body FROM reviews")
    out[f"build_{name}_s"] = round(time.perf_counter()-s, 1)
tests = {
 "like_substr":   ("SELECT count(*) FROM reviews WHERE body LIKE '%치킨%'", ()),
 "like_prefix":   ("SELECT count(*) FROM reviews WHERE body LIKE '치킨%'", ()),
 "fts_unicode61": ("SELECT count(*) FROM fts_uni WHERE fts_uni MATCH '치킨'", ()),
 "fts_prefix":    ("SELECT count(*) FROM fts_uni WHERE fts_uni MATCH '치킨*'", ()),
 "fts_trigram_3": ("SELECT count(*) FROM fts_tri WHERE fts_tri MATCH '\"후라이\"'", ()),
 "fts_trigram_2": ("SELECT count(*) FROM fts_tri WHERE fts_tri MATCH '\"치킨\"'", ()),
 "like_3":        ("SELECT count(*) FROM reviews WHERE body LIKE '%후라이%'", ()),
}
for k, (q, a) in tests.items():
    ms, n = t(q, a); out[k] = {"ms": ms, "hits": n}; print(f"{k:15s} {ms:9.2f} ms  hits={n}")
print("EXPLAIN LIKE:", c.execute("EXPLAIN QUERY PLAN SELECT count(*) FROM reviews WHERE body LIKE '%치킨%'").fetchall())
print("EXPLAIN prefix:", c.execute("EXPLAIN QUERY PLAN SELECT count(*) FROM reviews WHERE body LIKE '치킨%'").fetchall())
print({k:v for k,v in out.items() if k.startswith('build')})
json.dump(out, open("results_sqlite.json","w"), ensure_ascii=False, indent=1)
