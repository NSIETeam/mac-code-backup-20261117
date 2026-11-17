#!/usr/bin/env python3
import collections,hashlib,json,pathlib,posixpath,re,zipfile,xml.etree.ElementTree as E
ROOT=pathlib.Path(__file__).resolve().parent
E.register_namespace('','http://www.w3.org/1999/xhtml')
def cls(e,c):return c in e.attrib.get('class','').split()
def html(e):return E.tostring(e,encoding='unicode') if e is not None else None
def txt(e):return ' '.join(''.join(e.itertext()).split()) if e is not None else ''
def save(n,x):(ROOT/n).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
chem={10:{2,3,4,5,6,12,13,14,18,19},11:{3,4,7,8,12,13,14},12:{4,7,8,9,13,14}}
records=collections.defaultdict(list);summary=[];failures=[]
for archive in sorted((ROOT/'downloads/siyavula').glob('*.epub')):
    if not zipfile.is_zipfile(archive):
        failures.append({'file':str(archive.relative_to(ROOT)),'status':'download endpoint returned non-EPUB; excluded','bytes':archive.stat().st_size});continue
    g=int(re.search(r'grade-(\d+)',archive.name)[1]);science='physicalsciences' in archive.name
    z=zipfile.ZipFile(archive);names=set(z.namelist());counts=collections.Counter(); archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest()
    for path in sorted(names):
        if not path.endswith('.html'):continue
        root=E.fromstring(z.read(path));parents={c:p for p in root.iter() for c in p}
        if 'copyright_acknowledgements_ccby' in path:
            (ROOT/f'licenses/siyavula-{"physicalsciences" if science else "maths"}-grade-{g}-artifact-license.txt').write_text(txt(root))
        for e in root.iter():
            if not cls(e,'entry'):continue
            chain=[];p=parents.get(e)
            while p is not None:chain.append(p);p=parents.get(p)
            if any(cls(p,'entry') for p in chain):continue
            question=next((c for c in e if cls(c,'problem')),None);solution=next((c for c in e if cls(c,'solution')),None)
            if solution is not None and not txt(solution) and len(solution)==0:solution=None
            if question is None:continue
            counts['questions']+=1
            chapter=int(re.match(r'(\d+)',path.split('/')[-1])[1])
            subject=('chemistry' if chapter in chem[g] else 'physics') if science else 'mathematics'
            if science and chapter==1 and g==10:subject='science_general'
            context=[];topics=[]
            for a in reversed(chain):
                if cls(a,'problemset'):context.extend(c for c in a if cls(c,'header'))
                if cls(a,'section'):topics.extend(txt(c) for c in a if cls(c,'title'))
            assets=[]
            for base in context+[e]:
                for img in base.iter():
                    if img.tag.rsplit('}',1)[-1]!='img':continue
                    src=img.attrib.get('src','');resolved=posixpath.normpath(posixpath.join(posixpath.dirname(path),src));asset= {'archivePath':resolved,'kind':'formula_png' if 'math-' in img.attrib.get('class','') else 'diagram_or_illustration','exists':resolved in names}
                    if asset not in assets:assets.append(asset)
            mathpng=any(a['kind']=='formula_png' for a in assets)
            ident=f'siyavula-{subject}-g{g}:{path.split("/")[-1]}:{counts["questions"]}'
            records[subject].append({'id':ident,'status':'needs_formula_transcription' if mathpng else 'needs_content_review','subject':subject,'language':'en','educationSystem':'South Africa CAPS','sourceGrade':g,'chinaGrade':None,'questionType':None,'knowledgePath':topics,'stemHtml':html(question),'sharedContextHtml':[html(c) for c in context],'stemTextIncompleteWithoutAssets':txt(question),'answer':None,'sourceSolutionHtml':html(solution),'hasSourceSolution':solution is not None,'assets':assets,'source':{'publisher':'Siyavula Education','book':f'Everything {"Science" if science else "Maths"}, Grade {g}, unbranded EPUB','url':f'https://www.siyavula.com/downloads/books/{"science" if science else "maths"}/Gr{g}_{"PhysicalSciences" if science else "Mathematics"}_Learner_Eng_CC-BY.epub','localArchive':str(archive.relative_to(ROOT)),'archiveHtmlPath':path,'archiveSha256':archive_sha256,'license':'CC-BY-4.0','licenseEvidence':f'licenses/siyavula-{"physicalsciences" if science else "maths"}-grade-{g}-artifact-license.txt'},'validation':{'formatParsed':True,'assetReferencesComplete':all(a['exists'] for a in assets),'formulaTranscribed':not mathpng,'mathematicallyVerified':False,'chinaCurriculumMapped':False,'eligibleForDefaultAssembly':False},'licenseNotes':'Unbranded source; retain author/book/source attribution. No implied Siyavula endorsement. Third-party illustrations retain individual credits.'})
    summary.append({'archive':archive.name,'bytes':archive.stat().st_size,**counts})
for subject,items in records.items():save(f'staging-{subject}-siyavula.json',{'schemaVersion':'question-staging/1','count':len(items),'defaultAssemblyEnabled':False,'records':items})
save('staging-summary.json',{'archives':summary,'subjects':{k:len(v) for k,v in records.items()},'totalRecords':sum(map(len,records.values())),'downloadFailures':failures,'note':'Raw solution preserved; answer/type not invented. Source language preserved. PNG formula assets are not mathematical expressions.'})
print(json.dumps(json.loads((ROOT/'staging-summary.json').read_text())))
