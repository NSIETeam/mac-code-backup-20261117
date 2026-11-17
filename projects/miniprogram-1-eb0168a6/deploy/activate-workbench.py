"""Run on the server after copying the workbench static release and nginx snippet."""
import pathlib,subprocess,os,sys,time,shutil,json
release=pathlib.Path(sys.argv[1]).resolve()
assert release.parent==pathlib.Path('/opt/siyue-workbench/releases')
assert all((release/n).is_file() for n in ['index.html','app.js','core.mjs','style.css','LICENSE-icons.txt'])
root=release.parent.parent
current=root/'current';old=os.readlink(current) if current.is_symlink() else None
site=pathlib.Path('/etc/nginx/sites-enabled/siyue-crm.conf').resolve()
snippet=pathlib.Path('/etc/nginx/snippets/siyue-workbench.conf')
backup=root/'backups'/str(int(time.time()));backup.mkdir(parents=True)
shutil.copy2(site,backup/'site.conf')
old_snippet=snippet.read_bytes() if snippet.exists() else None
if old_snippet is not None:(backup/'snippet.conf').write_bytes(old_snippet)
line=' include /etc/nginx/snippets/siyue-workbench.conf;\n'
text=site.read_text()
assert 'include /etc/nginx/snippets/siyue-miniapp.conf;' in text
try:
 snippet.write_bytes(pathlib.Path(sys.argv[2]).read_bytes())
 if line.strip() not in text:site.write_text(text.replace(' include /etc/nginx/snippets/siyue-miniapp.conf;',line+' include /etc/nginx/snippets/siyue-miniapp.conf;'))
 temp=root/'current.new';temp.unlink(missing_ok=True);temp.symlink_to(release);os.replace(temp,current)
 subprocess.run(['nginx','-t'],check=True)
 subprocess.run(['systemctl','reload','nginx'],check=True)
except:
 site.write_bytes((backup/'site.conf').read_bytes())
 if old_snippet is None:snippet.unlink(missing_ok=True)
 else:snippet.write_bytes(old_snippet)
 if old:current.unlink(missing_ok=True);current.symlink_to(old)
 else:current.unlink(missing_ok=True)
 subprocess.run(['nginx','-t'],check=True);subprocess.run(['systemctl','reload','nginx'],check=True)
 raise
print(json.dumps({'release':str(release),'previous':old,'backup':str(backup),'url':'https://www.siyuejn.com/miniapp/workbench/'}))
