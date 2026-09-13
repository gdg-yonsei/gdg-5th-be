import concurrent.futures

import psycopg2.pool
import pytest
from testcontainers.postgres import PostgresContainer

from db import transaction
from repository import CourseRepository
from service import CourseService

CAPACITY = 100
REQUEST_COUNT = 500
THREAD_POOL_SIZE = 8


@pytest.fixture(scope="module")
def conn_pool():
    with PostgresContainer("postgres:16", driver=None) as postgres:
        pool = psycopg2.pool.ThreadedConnectionPool(
            1, THREAD_POOL_SIZE, dsn=postgres.get_connection_url()
        )
        with transaction(pool) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    CREATE TABLE course (
                        id SERIAL PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        left_seats BIGINT NOT NULL
                    )
                    """
                )
        yield pool


@pytest.fixture
def course_service(conn_pool):
    return CourseService(conn_pool, CourseRepository())


@pytest.fixture
def course_id(conn_pool):
    with transaction(conn_pool) as conn:
        course = CourseRepository().create(conn, "동시성 테스트 강의", CAPACITY)
    return course.id


def test_정원_100명_강의에_500명이_동시에_신청해도_100명만_성공해야_한다(course_service, course_id):
    with concurrent.futures.ThreadPoolExecutor(max_workers=THREAD_POOL_SIZE) as executor:
        results = list(
            executor.map(lambda _: course_service.enroll(course_id), range(REQUEST_COUNT))
        )

    success_count = sum(results)
    with transaction(course_service.conn_pool) as conn:
        course = CourseRepository().find_by_id(conn, course_id)

    print(f"성공한 신청 수: {success_count}")
    print(f"남은 좌석 수: {course.left_seats}")

    assert success_count == CAPACITY
    assert course.left_seats == 0
