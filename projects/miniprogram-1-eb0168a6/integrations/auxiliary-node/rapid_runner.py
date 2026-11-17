"""Use the same pinned OCR code/models; only map its display font to the Windows bundle."""
import os,runpy,sys
from pathlib import Path
import rapidocr
original=rapidocr.RapidOCR
class LocalFontRapid(original):
    def __init__(self,*args,**kwargs):
        params=dict(kwargs.get('params',{}));params['Global.font_path']=os.environ['SIYUE_OCR_FONT'];kwargs['params']=params
        super().__init__(*args,**kwargs)
rapidocr.RapidOCR=LocalFontRapid
script=Path(sys.argv[1]);sys.argv=sys.argv[1:];runpy.run_path(str(script),run_name='__main__')
