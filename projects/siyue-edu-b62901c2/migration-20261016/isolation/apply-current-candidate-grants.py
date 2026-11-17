"""Retired one-off candidate grant applier: always refuse before reading credentials or connecting.

Use mysql-runtime-grants.py with an independently reviewed BASE TABLE + views inventory,
review its output, then perform a separately authorized DBA change. The persistent
java-cutover-preflight.py guard must recheck the complete boundary before any cutover.
"""
import json
import sys


def main():
    """Emit a safe, explicit retirement result; no inputs, SQL or rollback are executed."""
    print(json.dumps({'status': 'refused', 'code': 'HISTORICAL_GRANT_APPLIER_RETIRED',
                      'next': 'Review mysql-runtime-grants.py --inventory output; authorize DBA changes separately; '
                              'recheck with java-cutover-preflight.py',
                      'databaseChanged': False, 'routeActivated': False}))
    return 2


if __name__ == '__main__':
    sys.exit(main())
