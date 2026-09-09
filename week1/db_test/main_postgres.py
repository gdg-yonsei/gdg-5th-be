from fastapi import FastAPI, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from sqlalchemy import create_engine, text

DB_URL = "postgresql+psycopg2://postgres:postgres@localhost:5433/devdb"
engine = create_engine(DB_URL)

app = FastAPI()


@app.on_event("startup")
def init_db():
    with engine.begin() as conn:
        conn.execute(text("""
            CREATE TABLE IF NOT EXISTS chats (
                id SERIAL PRIMARY KEY,
                message TEXT NOT NULL
            )
        """))


@app.get("/", response_class=HTMLResponse)
def index():
    with engine.connect() as conn:
        rows = conn.execute(text("SELECT message FROM chats ORDER BY id")).all()

    items = "".join(f"<li>{r.message}</li>" for r in rows)
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
    with engine.begin() as conn:
        conn.execute(text("INSERT INTO chats (message) VALUES (:m)"), {"m": message})
    return RedirectResponse("/", status_code=303)