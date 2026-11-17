"""Validate the tools assigned to this node, without loading unassigned OCR engines."""
import importlib,json,os,pathlib,subprocess,sys

def validate(config):
 tools=config.get('nativeTools',['pdftoppm','pdftotext','rapid'])
 if not isinstance(tools,list) or not tools or any(t not in ('pdftoppm','pdftotext','rapid','tesseract') for t in tools):raise ValueError('Unsupported native capability')
 # Layout/audit operations also need Pillow's compiled image decoder.
 importlib.import_module('PIL.Image')
 library=pathlib.Path(config['nativeRoot'])/'Library/bin'
 env=os.environ.copy();env['PATH']=str(library)+os.pathsep+env.get('PATH','')
 results={}
 for tool in tools:
  if tool=='rapid':
   importlib.import_module('onnxruntime');importlib.import_module('rapidocr');results[tool]='imported'
  else:
   exe=library/(tool+'.exe')
   if not exe.is_file():raise ValueError('Missing assigned native executable: '+tool)
   p=subprocess.run([str(exe),'--version' if tool=='tesseract' else '-v'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,timeout=15,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
   if p.returncode:raise ValueError('Assigned native executable failed: '+tool)
   results[tool]='executed'
 return results

if __name__=='__main__':
 c=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
 print(json.dumps(dict(validated=validate(c))))
