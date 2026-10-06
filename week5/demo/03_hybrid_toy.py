"""하이브리드 검색 장난감: BM25 + 벡터 → RRF.  ※ 벡터는 실제 임베딩 모델이 아니라 손으로 만든 3차원 값 (개념 시연용)"""
import math
from collections import Counter
docs = ["환불 규정은 3페이지 제5조에 있습니다", "결제 취소와 대금 반환 절차 안내", "배송 지연 시 보상 기준",
        "회원 탈퇴 방법", "제3조 개인정보 보관 기간"]
vecs = [(0.9,0.1,0.1),(0.9,0.2,0.1),(0.1,0.9,0.1),(0.1,0.1,0.9),(0.3,0.1,0.8)]   # (결제·환불, 배송, 회원·개인정보)
qvec = {"환불 받는 방법": (0.9,0.1,0.2)}   # 손으로 정한 값. 숫자/고유어 약점은 장난감으로 재현 못 함 → 실제 프로젝트 경험으로 설명

def bm25(q, docs, k1=1.2, b=0.75):
    toks = [d.split() for d in docs]; N = len(docs); avg = sum(map(len, toks))/N
    df = Counter(t for ts in toks for t in set(ts)); out = []
    for ts in toks:
        c = Counter(ts); s = 0
        for t in q.split():
            if t in c:
                idf = math.log(1 + (N-df[t]+0.5)/(df[t]+0.5))
                s += idf * c[t]*(k1+1)/(c[t] + k1*(1-b+b*len(ts)/avg))
        out.append(s)
    return out
def cos(a, b): return sum(x*y for x,y in zip(a,b))/math.sqrt(sum(x*x for x in a)*sum(y*y for y in b))
def rank(scores): return [i for i in sorted(range(len(scores)), key=lambda i: -scores[i]) if scores[i] > 0]
def rrf(rankings, k=60):
    s = Counter()
    for r in rankings:
        for pos, d in enumerate(r, 1): s[d] += 1/(k+pos)
    return [d for d,_ in s.most_common()]
for q in qvec:
    bm = rank(bm25(q, docs)); vr = rank([cos(qvec[q], v) for v in vecs]); hy = rrf([bm, vr])
    print(f"\n질의: {q!r}")
    for name, r in (("BM25 ", bm), ("벡터  ", vr), ("RRF   ", hy)):
        print(f"  {name} top3:", [docs[i] for i in r[:3]])
