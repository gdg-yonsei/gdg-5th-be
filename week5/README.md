# 검색 발표 데모
## 바로 실행 (Docker 불필요)
    python3 01_toy_index.py     # 장난감 역색인 (약 1분)
    python3 02_sqlite_fts.py    # SQLite LIKE vs FTS5 (100만 건)
    python3 03_hybrid_toy.py    # BM25 + 벡터 + RRF (손으로 만든 벡터)
## ES + Nori (발표 전 꼭 한 번 실행해 볼 것 — 작성자 환경에서는 미실행)
    cd es && docker compose up -d --build   # 1~2분, ES 기동 대기
    curl localhost:9200                     # 응답 오면
    pip install requests && python3 es_demo.py
    docker compose down
막히면 슬라이드 16번은 캡처 화면으로 대체.
