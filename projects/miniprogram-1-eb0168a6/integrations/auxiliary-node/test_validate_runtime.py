import pathlib,tempfile,unittest
from unittest.mock import Mock,patch
from types import SimpleNamespace
import validate_runtime as v

class RuntimeTest(unittest.TestCase):
 def test_pdf_only_node_does_not_require_unassigned_onnx_dlls(self):
  with tempfile.TemporaryDirectory() as td:
   lib=pathlib.Path(td)/'Library/bin';lib.mkdir(parents=True)
   for name in ('pdftotext','pdftoppm'):(lib/(name+'.exe')).touch()
   def load(name):
    if name!='PIL.Image':raise ImportError('OCR runtime absent on this node')
   with patch.object(v.importlib,'import_module',side_effect=load),patch.object(v.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as exe:
    self.assertEqual(set(v.validate(dict(nativeRoot=td,nativeTools=['pdftotext','pdftoppm']))),{'pdftotext','pdftoppm'})
    self.assertEqual(exe.call_count,2)
 def test_assigned_tool_failure_rejects_runtime(self):
  with tempfile.TemporaryDirectory() as td:
   lib=pathlib.Path(td)/'Library/bin';lib.mkdir(parents=True);(lib/'pdftotext.exe').touch()
   with patch.object(v.importlib,'import_module'),patch.object(v.subprocess,'run',return_value=SimpleNamespace(returncode=1)):
    with self.assertRaisesRegex(ValueError,'failed'):v.validate(dict(nativeRoot=td,nativeTools=['pdftotext']))
 def test_unknown_capability_is_rejected(self):
  with self.assertRaises(ValueError):v.validate(dict(nativeRoot='.',nativeTools=['arbitrary']))

if __name__=='__main__':unittest.main()
