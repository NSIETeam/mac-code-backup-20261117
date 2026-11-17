import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parents[1]))
from native_contract import options,validate,cache_key
class NativeContractTests(unittest.TestCase):
 def test_crop_and_formula_ocr_options(self):
  self.assertTrue(options('pdftoppm',['-f','2','-l','2','-singlefile','-r','360','-x','20','-y','100','-W','1200','-H','650','-png']))
  self.assertTrue(options('tesseract',['-l','chi_sim+eng','--psm','6','-c','tessedit_char_whitelist=0123456789','tsv']))
 def test_no_arbitrary_config_paths_or_programs(self):
  for tool,args in [('pdftoppm',['-f','1','-l','1','-opw','password','-png','-singlefile']),('tesseract',['--tessdata-dir','../secret']),('pdftotext',['-enc','../file']),('cmd.exe',[])]:
   with self.assertRaises(ValueError):options(tool,args)
 def test_overlarge_render_and_multiple_pages_rejected(self):
  for args in [['-f','1','-l','2','-singlefile','-png'],['-f','1','-l','1','-singlefile','-r','99999','-png']]:
   with self.assertRaises(ValueError):options('pdftoppm',args)
 def test_cache_bound_to_source_and_engine(self):
  p=dict(tool='pdftoppm',inputSha='a'*64,options=['-f','1'])
  self.assertNotEqual(cache_key(p,'engine-a'),cache_key(p,'engine-b'))
  self.assertNotEqual(cache_key(p,'engine-a'),cache_key(dict(p,inputSha='b'*64),'engine-a'))
if __name__=='__main__':unittest.main()
