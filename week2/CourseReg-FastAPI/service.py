import threading

from db import transaction
from repository import CourseRepository


class CourseService:
    def __init__(self, conn_pool, course_repository: CourseRepository):
        self.conn_pool = conn_pool
        self.course_repository = course_repository
        self.locks: dict[int, threading.Lock] = {}

    def _get_lock(self, course_id: int) -> threading.Lock:
        return self.locks.setdefault(course_id, threading.Lock())

    def enroll(self, course_id: int) -> bool:
        with self._get_lock(course_id):
            with transaction(self.conn_pool) as conn:
                course = self.course_repository.find_by_id(conn, course_id)
                if course.left_seats == 0:
                    return False
                course.decrease_left_seats()
                self.course_repository.save(conn, course)
                return True
