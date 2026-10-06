"""ES + Nori 데모: standard vs nori 분석기 비교. 사전: docker compose up -d --build, pip install requests"""
import sys, json, requests
sys.path.insert(0, "..")
from gen_data import make_reviews
ES = "http://localhost:9200"
def p(title, r): print(f"\n=== {title} ===\n{json.dumps(r, ensure_ascii=False, indent=1)[:900]}")

# 1) 분석기 비교 (_analyze)
text = "후라이드치킨을 시켰는데 맛있어요"
p("standard", [t["token"] for t in requests.post(f"{ES}/_analyze", json={"analyzer": "standard", "text": text}).json()["tokens"]])
p("nori(mixed)", [t["token"] for t in requests.post(f"{ES}/_analyze", json={
    "tokenizer": {"type": "nori_tokenizer", "decompound_mode": "mixed"},
    "filter": ["nori_part_of_speech"], "text": text}).json()["tokens"]])

# 2) 인덱스 2개: standard vs nori
nori = {"settings": {"number_of_shards": 3, "number_of_replicas": 1, "analysis": {
    "tokenizer": {"nori_mixed": {"type": "nori_tokenizer", "decompound_mode": "mixed"}},
    "analyzer": {"ko": {"type": "custom", "tokenizer": "nori_mixed", "filter": ["nori_part_of_speech", "lowercase"]}}}},
    "mappings": {"properties": {"body": {"type": "text", "analyzer": "ko"}}}}
std = {"settings": {"number_of_shards": 3}, "mappings": {"properties": {"body": {"type": "text"}}}}
for name, body in (("reviews_std", std), ("reviews_nori", nori)):
    requests.delete(f"{ES}/{name}"); requests.put(f"{ES}/{name}", json=body).raise_for_status()
    lines = []
    for i, d in enumerate(make_reviews(20000)):
        lines.append(json.dumps({"index": {"_index": name, "_id": i}})); lines.append(json.dumps({"body": d}, ensure_ascii=False))
    requests.post(f"{ES}/_bulk?refresh=true", data="\n".join(lines) + "\n", headers={"Content-Type": "application/x-ndjson"}).raise_for_status()

# 3) 같은 질의, 다른 결과
for name in ("reviews_std", "reviews_nori"):
    r = requests.post(f"{ES}/{name}/_search", json={"query": {"match": {"body": "치킨"}}, "size": 3, "track_total_hits": True}).json()
    print(f"\n[{name}] hits={r['hits']['total']['value']} took={r['took']}ms")
    for h in r["hits"]["hits"]: print(round(h["_score"], 2), h["_source"]["body"])

# 4) BM25 점수 설명
r = requests.post(f"{ES}/reviews_nori/_search", json={"query": {"match": {"body": "치킨"}}, "size": 1, "explain": True}).json()
p("explain", r["hits"]["hits"][0]["_explanation"])
# 5) 샤드 분산 확인
print(requests.get(f"{ES}/_cat/shards/reviews_nori?v").text)
