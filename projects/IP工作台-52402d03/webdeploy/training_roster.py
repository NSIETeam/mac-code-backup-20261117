"""Export only active printing staff names and campuses for the training page."""

import json
import os
import pwd
from pathlib import Path
import subprocess
import time

DESTINATION = Path("/var/lib/siyue-ip-workbench/training-roster.json")


def main():
    container = json.loads(
        subprocess.check_output(
            ["docker", "inspect", "siyue-miniapp-api-1"], timeout=10
        )
    )[0]
    environment = dict(v.split("=", 1) for v in container["Config"]["Env"])
    config = {
        k.lower(): v
        for k, v in (
            p.split("=", 1)
            for p in environment["ConnectionStrings__Database"].split(";")
            if "=" in p
        )
    }
    env = {**os.environ, "PGPASSWORD": config["password"], "PGCONNECT_TIMEOUT": "5"}
    query = """
    BEGIN READ ONLY;
    SET LOCAL statement_timeout='10s';
    COPY (
      SELECT json_build_object('id',s."Id",'name',s."Name",'role',s."Role",
        'code',COALESCE(s."Code",''),
        'campuses',COALESCE((
          SELECT json_agg(names.name ORDER BY names.name) FROM (
            SELECT DISTINCT ca."Name" AS name
            FROM "Classes" cl JOIN "Campuses" ca ON ca."Id"=cl."CampusId"
            WHERE NOT cl."Archived" AND NOT ca."Archived" AND
             (cl."OwnerStaffId"=s."Id" OR EXISTS (
                SELECT 1 FROM "Assignments" a WHERE a."ClassId"=cl."Id" AND a."StaffId"=s."Id"))
          ) names
        ),'[]'::json))
      FROM "Staff" s WHERE s."Enabled" AND NOT s."Archived" ORDER BY s."Name",s."Id"
    ) TO STDOUT;
    ROLLBACK;
    """
    result = subprocess.run(
        [
            "psql",
            "-X",
            "-q",
            "-h",
            config["host"],
            "-p",
            config.get("port", "5432"),
            "-U",
            config.get("username", "postgres"),
            "-d",
            config["database"],
            "-v",
            "ON_ERROR_STOP=1",
            "-c",
            query,
        ],
        env=env,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if result.returncode:
        raise RuntimeError("错题打印名单读取失败，上一份名单已保留")
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if not rows or any(not r["id"] or not r["name"] for r in rows):
        raise RuntimeError("名单校验未通过，上一份名单已保留")
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    temporary = DESTINATION.with_suffix(".next")
    with temporary.open("w", encoding="utf-8") as out:
        os.fchmod(out.fileno(), 0o640)
        os.fchown(out.fileno(), 0, pwd.getpwnam("siyue-ip-web").pw_gid)
        json.dump({"checked_at": time.time(), "items": rows}, out, ensure_ascii=False)
    temporary.replace(DESTINATION)
    print("已同步在用人员", len(rows))


if __name__ == "__main__":
    main()
