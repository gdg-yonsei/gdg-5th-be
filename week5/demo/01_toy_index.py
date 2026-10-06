"""장난감 역색인: 전체 스캔 vs 역색인 조회 (순수 파이썬, 외부 의존성 없음)"""
import time, json, sys
from collections import defaultdict
from gen_data import make_reviews

def build_index(docs):
    idx = defaultdict(list)
    for doc_id, text in enumerate(docs):
        for tok in set(text.split()):
            idx[tok].append(doc_id)          # posting list (doc_id 오름차순)
    return idx

def intersect(a, b):                          # 정렬된 두 posting list 교집합
    i = j = 0; out = []
    while i < len(a) and j < len(b):
        if a[i] == b[j]: out.append(a[i]); i += 1; j += 1
        elif a[i] < b[j]: i += 1
        else: j += 1
    return out

def bench(fn, repeat=5):
    best = 1e9
    for _ in range(repeat):
        t = time.perf_counter(); r = fn(); best = min(best, time.perf_counter() - t)
    return best * 1000, r

if __name__ == "__main__":
    results = []
    for n in (10_000, 100_000, 1_000_000):
        docs = make_reviews(n)
        t = time.perf_counter(); idx = build_index(docs); build_ms = (time.perf_counter() - t) * 1000
        scan_ms, scan_hits = bench(lambda: [i for i, d in enumerate(docs) if "치킨" in d])
        tok_scan_ms, tok_hits = bench(lambda: [i for i, d in enumerate(docs) if "치킨" in d.split()])
        idx_ms, idx_hits = bench(lambda: idx.get("치킨", []))
        and_ms, and_hits = bench(lambda: intersect(idx.get("치킨을", []), idx.get("배달이", [])))
        row = dict(n=n, build_ms=round(build_ms), scan_substr_ms=round(scan_ms, 2), scan_token_ms=round(tok_scan_ms, 2),
                   index_lookup_ms=round(idx_ms, 4), and_ms=round(and_ms, 3),
                   substr_hits=len(scan_hits), exact_token_hits=len(idx_hits), terms=len(idx))
        print(row); results.append(row)
    json.dump(results, open("results_toy.json", "w"), ensure_ascii=False, indent=1)
