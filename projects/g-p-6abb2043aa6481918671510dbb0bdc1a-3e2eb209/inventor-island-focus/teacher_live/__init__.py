"""教师真实上课 / 投屏授课工作台（专属 teacher-live 模块）。

职责边界（见平台任务 E）：
  * 只负责教师授课视图与课堂会话控制；
  * 只读课程包（docs/course-repairs/），不编辑课程包、不冻结/发布课程版本；
  * 复用 backend.annual_pack 的课程包读取与教师/学生受众隔离，不重写课件/积木/编辑器；
  * 学生独立学习页只收到"当前课堂任务"的公开投影，教师提示、答案与教师专属素材不外泄。

模块不修改 server.py。HTTP 层通过 ``teacher_live.api.TeacherLiveHandler`` 继承
``server.Handler`` 并拦截 teacher-live 路径实现；集成方只需把主服务的 Handler 换成
该子类（或直接运行 ``python3 -m teacher_live.serve`` 的隔离实例）。
"""
from . import flow, session  # noqa: F401
from .api import TeacherLiveHandler  # noqa: F401

__all__ = ['flow', 'session', 'TeacherLiveHandler', 'initialize']


def initialize(con):
    """在主库连接上建好 teacher-live 的表（供集成方在 server.initialize() 之后调用）。"""
    session.ensure_schema(con)
