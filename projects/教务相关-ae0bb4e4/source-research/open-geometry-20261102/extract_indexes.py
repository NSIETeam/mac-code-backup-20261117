#!/usr/bin/env python3
"""Preserves licensed source content; does not assert mathematical correctness."""
import collections, hashlib, json, pathlib, posixpath, re, zipfile, xml.etree.ElementTree as E
ROOT=pathlib.Path(__file__).resolve().parent
def save(name,x): (ROOT/name).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def cls(e,c): return c in e.attrib.get('class','').split()
def html(e): return E.tostring(e,encoding='unicode') if e is not None else None
def text(e): return ' '.join(''.join(e.itertext()).split()) if e is not None else ''
stats=[]; indexes=[]; geometry=[]
for grade in [10,11,12]:
    archive=ROOT/f'downloads/siyavula/grade-{grade}-unbranded-CC-BY.epub'
    z=zipfile.ZipFile(archive); archive_names=set(z.namelist()); counts=collections.Counter(); missing=[]
    for path in sorted(z.namelist()):
        if not path.endswith('.html'):continue
        doc=E.fromstring(z.read(path)); parents={c:p for p in doc.iter() for c in p}
        if 'copyright_acknowledgements_ccby' in path:
            (ROOT/f'licenses/siyavula-grade-{grade}-artifact-license.txt').write_text(text(doc))
        for entry in doc.iter():
            if not cls(entry,'entry'):continue
            counts['all_entry_nodes']+=1
            chain=[];p=parents.get(entry)
            while p is not None:chain.append(p);p=parents.get(p)
            if any(cls(p,'entry') for p in chain):counts['nested_entry_nodes']+=1;continue
            counts['top_level_question_groups']+=1
            problem=next((e for e in entry if cls(e,'problem')),None)
            solution=next((e for e in entry if cls(e,'solution')),None)
            if problem is None:counts['missing_problem']+=1;continue
            ident=f'siyavula-g{grade}:{path.split("/")[-1]}:{counts["top_level_question_groups"]}'
            headers=[];section_titles=[]
            for parent in reversed(chain):
                if cls(parent,'problemset'):
                    for e in parent:
                        if cls(e,'header'):headers.append(html(e))
                if cls(parent,'section'):
                    section_titles.extend(text(e) for e in parent if cls(e,'title'))
            imgs=[]
            for el in list(entry.iter()):
                if el.tag.rsplit('}',1)[-1]=='img':
                    src=el.attrib.get('src','');resolved=posixpath.normpath(posixpath.join(posixpath.dirname(path),src))
                    imgs.append({'src':src,'epub_path':resolved,'kind':'formula_png' if any(c in el.attrib.get('class','') for c in ['math-inline','math-block']) else 'diagram_or_illustration','exists':resolved in archive_names})
            missing.extend(i['epub_path'] for i in imgs if not i['exists'])
            topic=next((k for k in ['analytical-geometry','euclidean-geometry','trigonometry','measurement'] if k in path),None)
            item={'id':ident,'grade_south_africa':grade,'topic_from_chapter_filename':topic,'section_titles':section_titles,'source_archive':str(archive.relative_to(ROOT)),'archive_html_path':path,'license':'CC-BY-4.0 (artifact copyright notice)','problem_text_preview_incomplete_without_images':text(problem)[:300],'has_solution':solution is not None,'nested_subquestion_count':sum(cls(e,'entry') for e in entry.iter())-1,'image_count':len(imgs),'formula_png_count':sum(i['kind']=='formula_png' for i in imgs),'review_status':'unreviewed-source; requires formula transcription, China-curriculum tagging and answer validation'}
            indexes.append(item)
            if topic:
                counts['geometry_top_level_question_groups']+=1
                if solution is not None:counts['geometry_groups_with_solution']+=1
                geometry.append(dict(item,shared_problemset_context_html=headers,problem_html=html(problem),solution_html=html(solution),images=imgs))
    stats.append({'grade':grade,'archive_bytes':archive.stat().st_size,**counts,'missing_image_references':len(missing)})
save('siyavula-all-question-index.json',{'counts':stats,'items':indexes})
save('siyavula-geometry-source-records.json',{'schema':'source-candidate/v1','not_validated_templates':True,'count':len(geometry),'source_license':'CC-BY-4.0; retain original author and book attribution; no trademark endorsement','records':geometry})

formal=[]
for project in ['formalgeo7k','formalgeo-imo']:
    for f in sorted((ROOT/f'samples/{project}/problems').glob('*.json'),key=lambda x:int(x.stem)):
        a=json.loads(f.read_text()); formal.append({'id':f'{project}:{a["problem_id"]}','source_file':str(f.relative_to(ROOT)),'source_upstream':a.get('source'),'problem_text_cn':a.get('problem_text_cn'),'entities_and_conditions':{'construction':a.get('construction_cdl',[]),'text':a.get('text_cdl',[]),'diagram_labels':a.get('image_cdl',[])},'goal':a.get('goal_cdl'),'answer':a.get('problem_answer'),'theorem_sequence':a.get('theorem_seqs'),'diagram_png':str((ROOT/f'samples/{project}/diagrams/{f.stem}.png').relative_to(ROOT)) if (ROOT/f'samples/{project}/diagrams/{f.stem}.png').exists() else None,'geogebra_construction':str((ROOT/f'samples/{project}/diagrams/{f.stem}.ggb').relative_to(ROOT)) if (ROOT/f'samples/{project}/diagrams/{f.stem}.ggb').exists() else None,'license':'MIT (pinned dataset repository project LICENSE)','review_status':'not re-solved locally; derivative source-rights and age-level review pending'})
save('formalgeo-normalized-samples.json',{'count':len(formal),'records':formal})
save('extraction-stats.json',{'siyavula':stats,'siyavula_top_level_question_groups':len(indexes),'siyavula_geometry_top_level_question_groups':len(geometry),'formalgeo_normalized_samples':len(formal),'note':'Question groups retain nested subquestions; equation PNGs remain as source assets, not parsed formulae. No generated template or solver validation is claimed.'})
print(json.dumps(json.loads((ROOT/'extraction-stats.json').read_text()),ensure_ascii=False))
