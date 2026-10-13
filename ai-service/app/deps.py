"""共享单例：main.py 与各路由都从这里取 store / knowledge。

若路由模块直接 `from app.main import store`，而 main.py 又要 include_router 这些路由，
就会形成循环 import。共享状态集中在此，两侧只依赖 deps。
"""

from app.config import settings
from app.knowledge import KnowledgeBase
from app.vector_store import VectorStore

store = VectorStore(settings.index_dir)
knowledge = KnowledgeBase(store)
