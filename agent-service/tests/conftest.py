"""pytest 全局初始化。

app.config 在导入时强校验 INTERNAL_TOKEN(服务启动安全要求),单测不需要真实令牌,
因此必须在任何 app.* 模块被导入之前注入一个测试值。
"""

import os

os.environ.setdefault("INTERNAL_TOKEN", "test-internal-token")
