"""Fail-closed checks for the candidate's single-writer handoff."""
import json,pathlib,tempfile,unittest
from unittest.mock import patch
import operate_crm_candidate as operator

class SingleWriter(unittest.TestCase):
    def test_source_never_starts_when_node_stop_cannot_be_confirmed(self):
        for outcome in (RuntimeError('SSH unavailable'),{'running':True,'enabled':False},{'running':False,'enabled':True}):
            with self.subTest(outcome=outcome),tempfile.TemporaryDirectory() as td:
                root=pathlib.Path(td);(root/'prepared.json').write_text(json.dumps({'source':{'unitFileState':'disabled'}}))
                options={'side_effect':outcome} if isinstance(outcome,Exception) else {'return_value':outcome}
                with patch.object(operator,'STATE',root),patch.object(operator,'control',**options),patch.object(operator,'run') as run:
                    with self.assertRaises((RuntimeError,ValueError)):operator.rollback()
                    run.assert_not_called()

    def test_accepted_or_uncertain_operations_are_not_restarted(self):
        for state in ('succeeded','starting-workstation','rollback-needs-inspection'):
            with self.subTest(state=state),tempfile.TemporaryDirectory() as td:
                root=pathlib.Path(td);(root/'operation.json').write_text(json.dumps({'state':state}))
                with patch.object(operator,'STATE',root),patch.object(operator,'run') as run,patch.object(operator,'control') as control:
                    with self.assertRaises(ValueError):operator.cutover()
                    run.assert_not_called();control.assert_not_called()

if __name__=='__main__':unittest.main()
