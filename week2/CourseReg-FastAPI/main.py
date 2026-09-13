import os

import psycopg2.pool
from fastapi import FastAPI, HTTPException

from db import transaction
from repository import CourseRepository
from service import CourseService

app = FastAPI()

conn_pool = psycopg2.pool.ThreadedConnectionPool(
    1,
    20,
    dsn=os.environ.get(
        "DATABASE_URL", "postgresql://postgres:postgres@localhost:5433/coursereg"
    ),
)

course_repository = CourseRepository()
course_service = CourseService(conn_pool, course_repository)


@app.on_event("startup")
def create_table():
    with transaction(conn_pool) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS course (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    left_seats BIGINT NOT NULL
                )
                """
            )


@app.post("/courses")
def create_course(name: str, left_seats: int):
    with transaction(conn_pool) as conn:
        course = course_repository.create(conn, name, left_seats)
    return {"id": course.id, "name": course.name, "left_seats": course.left_seats}


@app.get("/courses/{course_id}")
def get_course(course_id: int):
    with transaction(conn_pool) as conn:
        course = course_repository.find_by_id(conn, course_id)
    if course is None:
        raise HTTPException(status_code=404, detail="강의를 찾을 수 없습니다.")
    return {"id": course.id, "name": course.name, "left_seats": course.left_seats}


@app.post("/courses/{course_id}/enroll")
def enroll(course_id: int):
    success = course_service.enroll(course_id)
    if not success:
        raise HTTPException(status_code=409, detail="정원이 없습니다.")
    return {"success": True}
