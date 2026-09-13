from psycopg2.extras import RealDictCursor

from models import Course


class CourseRepository:
    def find_by_id(self, conn, course_id: int) -> Course | None:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(
                "SELECT id, name, left_seats FROM course WHERE id = %s",
                (course_id,),
            )
            row = cur.fetchone()
            return Course(**row) if row else None

    def save(self, conn, course: Course) -> None:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE course SET left_seats = %s WHERE id = %s",
                (course.left_seats, course.id),
            )

    def create(self, conn, name: str, left_seats: int) -> Course:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO course (name, left_seats) VALUES (%s, %s) RETURNING id",
                (name, left_seats),
            )
            course_id = cur.fetchone()[0]
        return Course(course_id, name, left_seats)
