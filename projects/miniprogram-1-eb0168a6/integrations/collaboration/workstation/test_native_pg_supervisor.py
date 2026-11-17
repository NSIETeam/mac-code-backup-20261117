import unittest
from native_pg_supervisor import can_start, RETURNS

class PrimaryFenceTests(unittest.TestCase):
    def test_only_matching_explicit_primary_can_start_services(self):
        self.assertTrue(can_start(True,'primary','prod','prod','f'))
        for args in [(False,'primary','prod','prod','f'),(True,'standby','prod','prod','t'),
                     (True,'primary','prod','prod','t'),(True,'primary','prod','clone','f'),
                     (True,'unknown','prod','prod','f'),(True,'primary','','','f')]:
            with self.subTest(args=args): self.assertFalse(can_start(*args))

    def test_distinct_returns_do_not_overlap_local_service_ports(self):
        self.assertEqual(len(RETURNS),len(set(RETURNS.values())))
        self.assertFalse(set(RETURNS)&set(RETURNS.values()))

if __name__=='__main__': unittest.main()
