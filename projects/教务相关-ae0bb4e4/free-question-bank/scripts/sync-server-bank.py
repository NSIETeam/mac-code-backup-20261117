#!/usr/bin/env python3
"""Prepare a local backend catalog. Never deploys or writes business data."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
SUBJECTS = {'数学','语文','英语','物理','化学','生物','政治','历史','地理'}
STAGES = {'小学','初中','高中'}
TYPES = {'选择题','填空题','解答题','综合题'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-dir', type=Path, required=True, help='Siyue server directory containing Siyue.Server.csproj')
    parser.add_argument('--extra', type=Path, action='append', default=[], help='Additional attributed question JSON, e.g. exported custom bank')
    args = parser.parse_args()
    if not (args.server_dir/'Siyue.Server.csproj').is_file():
        parser.error('--server-dir must contain Siyue.Server.csproj')
    questions = json.loads((ROOT/'dist/data/questions.json').read_text())
    for path in args.extra:
        rows = json.loads(path.read_text())
        rows = rows if isinstance(rows,list) else rows.get('questions')
        if not isinstance(rows,list) or len(rows)>5000:
            parser.error(f'Expected up to 5000 questions: {path.name}')
        for index, q in enumerate(rows):
            if not isinstance(q,dict) or q.get('subject') not in SUBJECTS or q.get('stage') not in STAGES or q.get('type') not in TYPES:
                parser.error(f'Invalid subject/stage/type at {path.name}:{index+1}')
            if any(not isinstance(q.get(k),str) or not q[k].strip() for k in ['stem','answer','source','license']):
                parser.error(f'Missing stem/answer/source/license at {path.name}:{index+1}')
            if len(q['stem'])>40000 or not isinstance(q.get('tags',[]),list) or any(not isinstance(t,str) or len(t)>40 for t in q.get('tags',[])):
                parser.error(f'Invalid stem or tags at {path.name}:{index+1}')
            if not isinstance(q.get('issues',[]),list) or any(not isinstance(t,str) for t in q.get('issues',[])):
                parser.error(f'Invalid issues at {path.name}:{index+1}')
            # IDs are content-derived; refreshes preserve the same references.
            q={**q,'id':'custom-'+hashlib.sha256((q['subject']+'|'+q['stem']).encode()).hexdigest()[:24]}
            questions.append(q)
    if len(questions)>50000:
        parser.error('Catalog exceeds 50000 questions')
    target=args.server_dir/'QuestionBankData'
    if not (target/'knowledge-rules.json').is_file():
        parser.error('Backend knowledge-rules.json is missing; install recommendation code first')
    target.mkdir(parents=True,exist_ok=True)
    output=json.dumps(questions,ensure_ascii=False,separators=(',',':')).encode()
    pending=target/'questions.json.pending'
    pending.write_bytes(output)
    pending.replace(target/'questions.json')
    for name in ['manifest.json','GAOKAO-Bench-LICENSE.txt']:
        shutil.copy2(ROOT/'dist/data'/name,target/name)
    print(json.dumps({'questions':len(questions),'sha256':hashlib.sha256(output).hexdigest(),'extraFiles':len(args.extra),'deployed':False}))

if __name__=='__main__':
    main()
