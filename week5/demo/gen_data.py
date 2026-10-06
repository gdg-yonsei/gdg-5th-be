import random
KEYWORD_SENTENCES = ["치킨을 시켰는데 정말 맛있어요","치킨이 너무 짜서 별로였어요","치킨은 바삭한데 배달이 늦었어요",
 "후라이드치킨이 기대 이상이었어요","양념치킨을 먹었는데 소스가 달아요","치킨 최고 또 시킬게요","치킨도 좋고 사이드도 좋아요"]
OTHER_SENTENCES = ["피자가 식어서 왔어요","배달이 생각보다 빨랐어요","떡볶이 국물이 진해서 좋았어요","사장님이 친절하세요",
 "포장이 꼼꼼해서 만족합니다","양이 너무 적어요","환불 요청했는데 답변이 느려요","가격 대비 괜찮은 편이에요",
 "재주문 의사 있습니다","국밥이 따뜻하고 든든했어요","샐러드가 신선했어요","면이 불어서 도착했어요"]
def make_reviews(n, keyword_ratio=0.05, seed=42):
    r = random.Random(seed); out = []
    for i in range(n):
        base = r.choice(KEYWORD_SENTENCES) if r.random() < keyword_ratio else r.choice(OTHER_SENTENCES)
        extra = [r.choice(OTHER_SENTENCES) for _ in range(r.randint(0, 2))]
        out.append(" ".join([base] + extra))
    return out
