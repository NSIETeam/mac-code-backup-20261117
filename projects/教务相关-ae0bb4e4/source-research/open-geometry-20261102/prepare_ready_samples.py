#!/usr/bin/env python3
import copy,json,pathlib,xml.etree.ElementTree as E
R=pathlib.Path(__file__).resolve().parent
def load(s):return json.loads((R/f'staging-{s}-siyavula.json').read_text())['records']
allq={s:load(s) for s in ['mathematics','chemistry','physics']}
def select(s,grade,number):return next(q for q in allq[s] if f'-g{grade}:' in q['id'] and q['id'].endswith(':'+str(number)))
spec=[
('mathematics',10,1738,'判断：平行四边形的两条对角线互相平分。',True,'设平行四边形两邻边向量为u、v，两条对角线的中点都是(u+v)/2，因此互相平分。'),
('mathematics',10,1742,'判断：所有正方形都是平行四边形。',True,'正方形的两组对边分别平行，满足平行四边形的定义。'),
('mathematics',12,1186,'判断：所有正方形都相似。',True,'对应角均为直角；任意两个正方形的四组对应边的比均等于其边长比。'),
('mathematics',12,1187,'判断：所有矩形都相似。',False,'反例：长宽分别为2、1和3、1的两个矩形，长宽比分别为2和3，不相似。'),
('mathematics',12,1189,'判断：所有全等多边形都相似。',True,'全等保证对应角相等、对应边相等，因此对应边成比例且比例为1。'),
('mathematics',12,1190,'判断：所有相似多边形都全等。',False,'反例：边长分别为1和2的两个正方形相似，但对应边不相等，因此不全等。'),
('mathematics',12,1191,'判断：所有全等三角形都相似。',True,'全等三角形对应角相等且对应边的比都为1，满足相似条件。'),
('mathematics',12,1193,'判断：所有等边三角形都相似。',True,'每个等边三角形的三个内角均为60°，任意两个等边三角形满足角角相似判定。'),
('chemistry',10,16,'下列哪一种物质属于混合物？','air','空气含有氮气、氧气等多种物质，属于混合物；其余选项按原题理想纯物质处理。'),
('chemistry',10,25,'分别写出下列物态变化的名称：（1）固态直接变为气态；（2）液态变为气态。',['sublimation','vaporisation'],'固态直接变为气态称为升华（sublimation）；液态变为气态称为汽化（vaporisation）。'),
('chemistry',10,38,'原子A有5个质子和5个中子，原子B有6个质子和5个中子。这两个原子属于什么关系？','atoms of different elements','元素种类由质子数决定。两原子的质子数分别为5和6，所以属于不同元素。'),
('chemistry',10,54,'原子的三种基本组成粒子是什么？','protons, neutrons, and electrons','普通原子由含质子、中子的原子核以及核外电子组成。选项中正确组合为质子、中子和电子。'),
('physics',10,124,'苍蝇的翅膀每秒往复振动200次。求振动周期。',{'value':0.005,'unit':'s'},'频率f=200 Hz，周期T=1/f=1/200 s=0.005 s。'),
('physics',10,132,'下列哪一种不是纵波？','light','中学模型中光是横向电磁波；空气中的声波、超声波是纵波。'),
('physics',10,133,'像声波这样的机械纵波不能在哪一种介质中传播？','vacuum','声音需要物质介质传播。真空没有传播声振动的物质介质。'),
('physics',10,146,'喷气式战斗机飞行速度低于声速，属于哪一种速度状态？','subsonic','subsonic意为亚声速，表示速度低于当地声速。')]
ready=[]
for s,g,n,cn,answer,why in spec:
    q=copy.deepcopy(select(s,g,n));assert not q['assets'],q['id']
    q.update({'status':'ready_reviewed_sample','questionType':'true_false' if s=='mathematics' else None,'stemZh':cn,'answer':answer,'explanationZh':why,'answerProvenance':'translated/adapted and checked in this collection; original solution retained separately','chinaGrade':None,'sampleRole':'几何基础复习/学科接入验收样本；不代表高中全知识点覆盖'})
    if s!='mathematics':
        root=E.fromstring(q['stemHtml']);ol=next((e for e in root.iter() if e.tag.endswith('}ol') or e.tag=='ol'),None)
        if ol is not None and (s,n) in {('chemistry',16),('chemistry',38),('chemistry',54),('physics',132),('physics',133),('physics',146)}:
            options=[' '.join(''.join(e.itertext()).split()) for e in ol]
            q['optionsOriginal']=options;q['questionType']='multiple_choice'
            if isinstance(answer,int):q['answer']=options[answer]
            assert q['answer'] in options,(q['id'],q['answer'],options)
    q['validation'].update({'mathematicallyVerified':s=='mathematics' or (s=='physics' and n==124),'contentReviewed':True,'formulaTranscribed':True,'eligibleForDefaultAssembly':False,'reviewMethod':'concept/theorem reasoning and explicit counterexamples; physics period checked by T*f=1' if s=='mathematics' or n==124 else 'elementary science fact review; not a formal solver proof'})
    ready.append(q)
assert abs(200*ready[12]['answer']['value']-1)<1e-12
(R/'staging-ready-reviewed-16.json').write_text(json.dumps({'schemaVersion':'question-staging/1','count':len(ready),'defaultAssemblyEnabled':False,'note':'16 small reviewed source-adapted samples. Chinese translation and explanation authored in this collection. Not the 24 generative templates. Attribution required. Original source question type kept null unless explicit true/false prompt or option list exists.','records':ready},ensure_ascii=False,indent=2)+'\n')
print('ready samples',len(ready))
