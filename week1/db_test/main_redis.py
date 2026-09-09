from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
import redis

r = redis.Redis(host="redis", port=6379, decode_responses=True)

app = FastAPI()


@app.get("/", response_class=HTMLResponse)
def index():
    msgs = r.lrange("chat", 0, 49)          # 최근 50개
    msgs.reverse()                          # 오래된 것부터 표시

    items = "".join(f"<li>{m}</li>" for m in msgs)
    return f"""
    <h2>채팅</h2>
    <ul>{items}</ul>
    <form method="post" action="/send">
      <input name="message" autofocus autocomplete="off">
      <button>보내기</button>
    </form>
    """


@app.post("/send")
def send(message: str = Form(...)):
    r.lpush("chat", message)                # 앞쪽에 추가
    r.ltrim("chat", 0, 49)                  # 50개만 유지, 나머지는 버림
    return RedirectResponse("/", status_code=303)