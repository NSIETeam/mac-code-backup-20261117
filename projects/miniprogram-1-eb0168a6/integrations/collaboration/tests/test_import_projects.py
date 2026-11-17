import csv,importlib.util,io,json,pathlib,tempfile,unittest
spec=importlib.util.spec_from_file_location('import_projects',pathlib.Path(__file__).parents[1]/'import_projects.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class ImportTests(unittest.TestCase):
 def test_verbatim_source_unknown_identity_and_no_false_completion(self):
  with tempfile.TemporaryDirectory() as directory:
   root=pathlib.Path(directory);source=root/'source.csv';headers=['干啥','为啥','怎么干','涉及的部门','当前状态','11/12更新状态','11/12状态下的需求表单（包括物料和决策）'];row=['项目','保留逗号,换行\n和单引号\'','方案','','已提交','已实现','']
   with source.open('w',newline='',encoding='utf-8-sig') as f:w=csv.writer(f);w.writerow(headers);w.writerow(row)
   plan=[dict(match='项目',status='active',tasks=[['核验','review','核对实际成果']])]
   first=m.prepare(source,plan,'00000000-0000-0000-0000-000000000001',root/'first');second=m.prepare(source,plan,'00000000-0000-0000-0000-000000000001',root/'second')
   self.assertEqual(first,second)
   attachment=next((root/'first'/'attachments').iterdir());self.assertEqual(list(csv.reader(io.StringIO(attachment.read_text(encoding='utf-8-sig')))),[headers,row])
   sql=(root/'first'/'import.sql').read_text();self.assertIn('原表 11/12 更新（年份待补充）',sql);self.assertNotIn("'done'",sql);self.assertIn('pg_advisory_xact_lock',sql);self.assertIn('\\if :imported',sql)
   self.assertIn('NULL,FALSE,\'active\'',sql);self.assertNotIn('INSERT INTO "Acceptances"',sql);self.assertNotIn('INSERT INTO "Members"',sql)
 def test_sql_values_are_quoted_as_data(self):
  self.assertEqual(m.literal("x');DROP TABLE test;--"),"'x'');DROP TABLE test;--'")
if __name__=='__main__':unittest.main()
