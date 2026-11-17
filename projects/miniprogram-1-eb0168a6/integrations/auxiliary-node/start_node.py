"""Keep startup failures observable when Windows launches pythonw without a console."""
import json,runpy,sys,traceback
from pathlib import Path
root=Path(__file__).resolve().parent
try:
    sys.argv=[str(root/'node.py'),str(root/'config.json')]
    runpy.run_path(sys.argv[0],run_name='__main__')
except BaseException:
    cfg=json.loads((root/'config.json').read_text(encoding='utf-8-sig'))
    message=traceback.format_exc().replace(cfg.get('token','\0'),'<redacted>')
    (Path(cfg['data'])/'startup-error.txt').write_text(message,encoding='utf-8')
    raise
