import threading
import time

counter = 0


def increment():
    global counter

    current = counter
    time.sleep(0.01) # 0.01초 쉬었을 뿐인데
    counter = current + 1


threads = []

for _ in range(100):
    t = threading.Thread(target=increment)
    threads.append(t)
    t.start()

for t in threads:
    t.join()

print(counter)