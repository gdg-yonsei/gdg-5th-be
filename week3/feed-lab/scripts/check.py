#!/usr/bin/env python3
"""
시연 리허설용 자동 점검. 발표 시나리오를 그대로 돌리고 "화면에서 문제가 보이는가"를 숫자로 확인한다.

    make check          (약 3분, SCALE=1 기준. 끝나면 부하를 멈추고 초기화한다)

표준 라이브러리만 쓴다.
"""
import json, sys, threading, time, urllib.error, urllib.request

APP, LG = "http://localhost:8080", "http://localhost:8081"
results = []


def call(url, method="GET", body=None, user=None, timeout=90):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if user:
        req.add_header("X-User-Id", str(user))
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return r.status, (json.loads(data) if data else None), (time.time() - t0) * 1000
    except urllib.error.HTTPError as e:
        return e.code, None, (time.time() - t0) * 1000


def load(version, rps=0, mix="uniform", wps=0, seconds=0):
    call(LG + "/control", "POST", {"version": version, "read": {"targetRps": rps, "mix": mix}, "write": {"targetRps": wps}})
    for _ in range(seconds):
        time.sleep(1)
        sys.stdout.write("."); sys.stdout.flush()
    return call(LG + "/stats")[1]


def write(version, user):
    status, data, ms = call(f"{APP}/{version}/posts", "POST", {"content": f"check {time.strftime('%X')}"}, user=user)
    return (data or {}).get("post"), ms


def arrival(version, post, observer, limit=120):
    t0 = time.time()
    while time.time() - t0 < limit:
        _, d, _ = call(f"{APP}/{version}/feed", user=observer)
        if d and any(p["id"] == post["id"] for p in d["items"]):
            return (d["serverNowMs"] - post["createdAtMs"]) / 1000
        time.sleep(0.25)
    return float("inf")


def burst_then_d(version, celebs, d_id, observer):
    posts = {}
    ts = [threading.Thread(target=lambda u=u: posts.__setitem__(u, write(version, u)[0])) for u in celebs]
    [t.start() for t in ts]
    time.sleep(1.0)
    d_post, _ = write(version, d_id)
    [t.join() for t in ts]
    out = {}
    ws = [threading.Thread(target=lambda k=k, p=p: out.__setitem__(k, arrival(version, p, observer))) for k, p in [("D", d_post), ("C", posts[celebs[0]])]]
    [t.start() for t in ws]; [t.join() for t in ws]
    return out


def check(name, value, ok, expect):
    results.append((name, value, expect, ok))
    print(f"\n  {'PASS' if ok else 'FAIL'}  {name}: {value}   (기대: {expect})")


def main():
    personas = call(APP + "/personas")[1]
    if not personas:
        sys.exit("시드가 없습니다. 먼저 make seed")
    pid = {u["name"].split(" ")[0]: u["id"] for u in personas}          # A, B, C, D, E, 셀럽
    celebs = [u["id"] for u in personas if u["followerCount"] > 10000]
    A, B, C, D, E = pid["A"], pid["B"], pid["C"], pid["D"], pid["E"]
    celebs.sort(key=lambda i: i != C)
    call(APP + "/admin/reset", "POST"); call(APP + "/config", "POST", {"fanoutBatchSize": 1, "hybridThreshold": 10000})

    print("v1 무부하: A(헤비) / B(라이트) 단건")
    for _ in range(3):
        a = call(f"{APP}/v1/feed", user=A)[2]; b = call(f"{APP}/v1/feed", user=B)[2]
    check("v1 A 단건 / B 단건", f"{a:.0f}ms / {b:.0f}ms", a >= 5 * b, "A 가 B 의 5배 이상")

    print("v1 저부하 30rps", end="")
    s = load("v1", 30, seconds=9)["read"]; co = s["byCohort"]
    check("v1 저부하 코호트 p99 (라이트/미드/헤비)", f"{co['light']['p99Ms']:.0f} / {co['mid']['p99Ms']:.0f} / {co['heavy']['p99Ms']:.0f} ms",
          co["heavy"]["p99Ms"] >= 4 * co["light"]["p99Ms"] and s["errorRate"] == 0, "헤비가 라이트의 4배 이상, 에러 0")

    print("v1 고부하 300rps", end="")
    s1 = load("v1", 300, seconds=13)["read"]
    t0 = time.time(); call(APP + "/metrics-lite"); mms = (time.time() - t0) * 1000
    check("v1 300rps 성공 rps / p99 / 에러", f"{s1['achievedRps']:.0f}/300 · {s1['p99Ms']:.0f}ms · {s1['errorRate']*100:.0f}%",
          s1["achievedRps"] <= 180 and s1["p99Ms"] >= 2000, "성공 180 이하, p99 2초 이상")
    check("v1 포화 중 코호트 p99 (라이트/헤비)", f"{s1['byCohort']['light']['p99Ms']:.0f} / {s1['byCohort']['heavy']['p99Ms']:.0f} ms",
          s1["byCohort"]["light"]["p99Ms"] >= 1500, "라이트도 같이 1.5초 이상 (같은 줄에 선다)")
    check("포화 중 /metrics-lite 응답", f"{mms:.0f}ms", mms <= 100, "100ms 이하 (상황판이 멈추지 않는다)")

    print("v1 헤비만 300rps", end="")
    sh = load("v1", 300, "heavy_only", seconds=10)["read"]
    check("v1 헤비만 성공 rps", f"{sh['achievedRps']:.0f}/300", sh["achievedRps"] < s1["achievedRps"], "균등일 때보다 낮다")

    print("v2 300rps", end="")
    s2 = load("v2", 300, seconds=12)["read"]
    check("v2 300rps 성공 rps / p99", f"{s2['achievedRps']:.0f}/300 · {s2['p99Ms']:.0f}ms", s2["achievedRps"] >= 285 and s2["p99Ms"] <= 20, "285 이상, 20ms 이하")
    print("v2 1000rps", end="")
    s3 = load("v2", 1000, seconds=9)["read"]
    check("v2 1000rps 성공 rps / p99", f"{s3['achievedRps']:.0f}/1000 · {s3['p99Ms']:.0f}ms", s3["achievedRps"] >= 950 and s3["p99Ms"] <= 30, "950 이상, 30ms 이하")

    print("v2 읽기300 + 쓰기5, 셀럽 C 글 작성", end="")
    sw = load("v2", 300, wps=5, seconds=8)["write"]
    check("v2 일반 쓰기 평균 / p99", f"{sw['avgMs']:.0f}ms / {sw['p99Ms']:.0f}ms", sw["avgMs"] >= 10, "10ms 이상 (팔로워 수만큼 느려진다)")
    post, ms = write("v2", C)
    check("v2 셀럽 글 작성 응답", f"{ms:.0f}ms", 2000 <= ms <= 9000, "2~9초")

    print("v3", end="")
    sw3 = load("v3", 300, wps=5, seconds=8)["write"]
    check("v3 일반 쓰기 평균", f"{sw3['avgMs']:.0f}ms", sw3["avgMs"] <= 10, "10ms 이하")
    post, ms = write("v3", C); arr = arrival("v3", post, E)
    check("v3 셀럽 글 작성 응답 / 도착", f"{ms:.0f}ms / {arr:.1f}초", ms <= 100 and 2 <= arr <= 10, "100ms 이하 / 2~10초")
    time.sleep(2)
    out = burst_then_d("v3", celebs, D, E)
    check("v3 셀럽 5명 뒤 D 글 도착", f"{out['D']:.1f}초", out["D"] >= 12, "12초 이상 (HOL 블로킹)")

    print("v4", end="")
    load("v4", 300, wps=5, seconds=6)
    out = burst_then_d("v4", celebs, D, E)
    check("v4 셀럽 5명 뒤 D 글 / 셀럽 글 도착", f"{out['D']:.1f}초 / {out['C']:.1f}초", out["D"] <= 3 and out["C"] <= 3, "둘 다 3초 이하")
    s4 = load("v4", 300, wps=5, seconds=6)["read"]
    check("v4 300rps 읽기 p99", f"{s4['p99Ms']:.0f}ms", s4["p99Ms"] <= 30, "30ms 이하 (v3 보다 살짝 오른다)")

    load("v1", 0); call(APP + "/admin/reset", "POST")
    failed = [r for r in results if not r[3]]
    print("\n" + "=" * 72)
    for name, value, expect, ok in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name:<40} {value}")
    print("=" * 72)
    print("전부 통과" if not failed else f"{len(failed)}개 실패: DB_CPUS, FANOUT_BATCH_SIZE 를 조정해 보세요 (README 의 '값 조정' 참고)")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
