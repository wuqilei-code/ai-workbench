"""pytest 根配置:确保 `import app.*` 可解析。

CI 用 `pytest` 控制台脚本运行(不会像 `python -m pytest` 那样把 CWD 加入 sys.path);
tests/ 无 __init__.py 时 pytest 默认只把 tests/ 加入 sys.path,导致 `from app import ...`
报 ModuleNotFoundError。根目录存在 conftest.py 后,pytest 会把本目录(agent-service/)加入
sys.path,从而可导入 app 包。
"""
