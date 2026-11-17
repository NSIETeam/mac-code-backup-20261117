"""Exercise data-return correctness and refusal to create two SQLite writers."""
import io,json,pathlib,sqlite3,tarfile,tempfile,unittest
from unittest.mock import patch
import homework_lab_common as c
import operate_homework_lab as op

class Integrity(unittest.TestCase):
    def test_digest_conversion_requires_identical_layers_and_runtime_config(self):
        import copy
        source=dict(Id='source',Architecture='amd64',Os='linux',Created='same',Config={'User':'app'},RootFS={'Layers':['a','b']})
        target=copy.deepcopy(source);target['Id']='exported-config'
        self.assertTrue(c.image_equivalence(source,target)['layersEqual'])
        for key in ('layers','config'):
            changed=copy.deepcopy(target)
            if key=='layers':changed['RootFS']['Layers'].reverse()
            else:changed['Config']['User']='root'
            with self.assertRaises(ValueError):c.image_equivalence(source,changed)

    def test_all_rows_and_pending_jobs_are_checked(self):
        with tempfile.TemporaryDirectory() as td:
            p=pathlib.Path(td)/'test.db';db=sqlite3.connect(p)
            db.execute('CREATE TABLE AcademicGradingJobs (Id TEXT, State TEXT)');db.execute('CREATE TABLE Submissions (Id TEXT, Answer TEXT)')
            db.execute("INSERT INTO AcademicGradingJobs VALUES ('g','reviewed')");db.execute("INSERT INTO Submissions VALUES ('s','before')");db.commit()
            before=c.database(p)
            db.execute("UPDATE Submissions SET Answer='after'");db.commit();self.assertNotEqual(c.database(p),before)
            db.execute("UPDATE AcademicGradingJobs SET State='queued'");db.commit()
            with self.assertRaisesRegex(ValueError,'Pending AI'):c.database(p)
            db.close()

    def test_archive_cannot_escape_data_root(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td);archive=root/'return.tar.gz'
            with tarfile.open(archive,'w:gz') as t:
                member=tarfile.TarInfo('../escape');member.size=4;t.addfile(member,io.BytesIO(b'fail'))
            with self.assertRaises(ValueError):c.extract_data(archive,root/'data')
            self.assertFalse((root/'escape').exists())

class SingleWriter(unittest.TestCase):
    def test_unknown_or_running_destination_cannot_restart_source(self):
        for answer in [RuntimeError('SSH unreachable'),dict(running=True,enabled=False,pid=123),dict(running=False,enabled=True,pid=0),dict(running=False,enabled=False,pid=123)]:
            with self.subTest(answer=answer):
                kwargs={'side_effect':answer} if isinstance(answer,Exception) else {'return_value':answer}
                with patch.object(op,'control',**kwargs),patch.object(op,'run') as run:
                    with self.assertRaises((RuntimeError,ValueError)):op.rollback()
                    run.assert_not_called()

    def test_returned_data_mismatch_keeps_both_services_stopped(self):
        with tempfile.TemporaryDirectory() as td:
            root=pathlib.Path(td);(root/'operation.json').write_text(json.dumps(dict(activeData='node')))
            with patch.object(op,'STATE',root),patch.object(op,'SOURCE',root),patch.object(op,'control',return_value=dict(running=False,enabled=False,pid=0)),patch.object(op,'source_running',return_value=False),patch.object(op,'ensure_identity'),patch.object(op,'record'),patch.object(op,'run') as run,patch.object(op,'remote_integrity',return_value={'files':{},'database':{'expected':True}}),patch.object(op,'receive_data'),patch.object(op,'extract_data'),patch.object(op,'database',return_value={'different':True}):
                with self.assertRaisesRegex(ValueError,'integrity mismatch'):op.rollback()
                self.assertFalse(any(x.args[0][:2]==['docker','start'] for x in run.call_args_list))

    def test_incomplete_handoff_cannot_be_restarted(self):
        for state in ['succeeded','transferring-data','starting-workstation','rollback-needs-inspection']:
            with self.subTest(state=state),tempfile.TemporaryDirectory() as td:
                root=pathlib.Path(td);(root/'operation.json').write_text(json.dumps({'state':state}))
                with patch.object(op,'STATE',root),patch.object(op,'ensure_identity') as identity:
                    with self.assertRaises(ValueError):op.cutover()
                    identity.assert_not_called()

if __name__=='__main__':unittest.main()
