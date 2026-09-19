# feed-lab
# SCALE: 1 = 발표용(유저 10만, DB 약 4.5GB). 파트원 노트북은 0.5 권장 (최소 0.4: 셀럽 팔로워가 임계값 1만을 넘어야 함)
SCALE      ?= 1
FEED_DAYS  ?= 3
DB_CPUS    ?= 2
SEED_CPUS  ?= 6
PSQL        = docker exec -i feedlab-postgres psql -U postgres -d feedlab
USERS       = $(shell awk 'BEGIN{printf "%d", $(SCALE)*100000}')
POSTS       = $(shell awk 'BEGIN{printf "%d", $(SCALE)*3000000}')

.PHONY: up seed reset warm explain check stats logs down nuke

up:            ## 빌드 + 기동 (앱이 Flyway 마이그레이션을 자동 실행)
	DB_CPUS=$(DB_CPUS) docker compose up -d --build
	@if [ "$$($(PSQL) -Atc 'SELECT count(*) FROM seed_meta' 2>/dev/null)" = "1" ]; then echo "캐시 올리는 중 (make warm)..."; $(PSQL) -q -f /seed/warm.sql > /dev/null; fi
	@echo "→ http://localhost:8080   (처음이면 make seed, 발표 전에는 make check)"

seed:          ## 시드 (약 2~3분). 이미 있으면 중단. 다시 하려면 FORCE=1
	@until $(PSQL) -Atc "SELECT 1 FROM information_schema.tables WHERE table_name='fanout_jobs'" 2>/dev/null | grep -q 1; do echo "앱 마이그레이션 대기..."; sleep 2; done
	@if [ "$(FORCE)" != "1" ] && [ "$$($(PSQL) -Atc 'SELECT count(*) FROM seed_meta')" != "0" ]; then echo "이미 시드되어 있습니다. 다시 하려면: make seed FORCE=1"; exit 1; fi
	docker update --cpus $(SEED_CPUS) feedlab-postgres > /dev/null
	@echo "users=$(USERS) posts=$(POSTS) feed_days=$(FEED_DAYS)"
	time $(PSQL) -v users=$(USERS) -v posts=$(POSTS) -v feed_days=$(FEED_DAYS) -f /seed/seed.sql || { docker update --cpus $(DB_CPUS) feedlab-postgres > /dev/null; exit 1; }
	$(PSQL) -q -f /seed/warm.sql
	docker update --cpus $(DB_CPUS) feedlab-postgres > /dev/null
	docker compose restart app loadgen

reset:         ## 시드 이후에 쓴 글/feeds/잡만 삭제 (리허설 반복용)
	curl -s -X POST localhost:8080/admin/reset && echo

warm:          ## 재부팅/재기동 뒤에 캐시 다시 올리기
	$(PSQL) -f /seed/warm.sql

explain:       ## v1 쿼리를 A(헤비)/B(라이트) 로 EXPLAIN ANALYZE
	$(PSQL) -f /seed/explain.sql

check:         ## 시연 시나리오를 자동으로 돌려 수치 점검 (약 3분)
	python3 scripts/check.py

stats:
	docker stats --no-stream feedlab-postgres feedlab-app feedlab-loadgen

logs:
	docker compose logs -f --tail=50 app loadgen

down:
	docker compose down

nuke:          ## 볼륨(시드 데이터)까지 삭제
	docker compose down -v
