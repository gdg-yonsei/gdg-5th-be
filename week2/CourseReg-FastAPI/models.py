class Course:
    def __init__(self, id: int, name: str, left_seats: int):
        self.id = id
        self.name = name
        self.left_seats = left_seats

    def decrease_left_seats(self):
        if self.left_seats <= 0:
            raise ValueError("정원이 없습니다.")
        self.left_seats -= 1
