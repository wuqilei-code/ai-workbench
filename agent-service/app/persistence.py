"""持久化:checkpointer 与 store。

- 有 AGENT_PG_DSN 时用 PostgreSQL 做线程级 checkpoint(断线续跑/后台任务),store 用 PostgresStore(跨线程记忆)。
- checkpointer/store 共用 `psycopg_pool.ConnectionPool`(单条裸连接并发非线程安全);
  空 DSN 时退化为进程内内存版(仅开发/冒烟)。
"""

from __future__ import annotations

import logging
from typing import Protocol

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.config import AGENT_PG_DSN, AGENT_PG_POOL_MAX, AGENT_PG_POOL_MIN

logger = logging.getLogger(__name__)


class Runtime:
    """向 agent_factory 提供的统一持久化句柄。"""

    def __init__(self) -> None:
        self.checkpointer, self.store = self._build()
        self._pool = None

    def _build(self):
        if AGENT_PG_DSN:
            from langgraph.checkpoint.postgres import PostgresSaver
            from langgraph.store.postgres import PostgresStore
            from psycopg_pool import ConnectionPool

            pool = ConnectionPool(
                conninfo=AGENT_PG_DSN,
                min_size=AGENT_PG_POOL_MIN,
                max_size=AGENT_PG_POOL_MAX,
                open=True,
                kwargs={"autocommit": True},
            )
            self._pool = pool
            saver = PostgresSaver(pool)
            saver.setup()
            logger.info(
                "checkpointer: PostgresSaver(pool min=%d max=%d)",
                AGENT_PG_POOL_MIN,
                AGENT_PG_POOL_MAX,
            )
            store = PostgresStore(pool)
            store.setup()
            logger.info("store: PostgresStore(pool)")
            return saver, store
        logger.warning("checkpointer: InMemorySaver(未配置 AGENT_PG_DSN,重启即失忆)")
        return InMemorySaver(), InMemoryStore()

    def close(self) -> None:
        if self._pool is not None:
            try:
                self._pool.close()
            except Exception:  # noqa: BLE001
                pass


def get_runtime() -> Runtime:
    return Runtime()