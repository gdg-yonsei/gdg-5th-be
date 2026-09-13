from contextlib import contextmanager


@contextmanager
def transaction(conn_pool):
    conn = conn_pool.getconn()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn_pool.putconn(conn)
