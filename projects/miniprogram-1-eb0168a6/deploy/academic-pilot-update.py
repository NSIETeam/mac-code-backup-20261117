#!/usr/bin/env python3
"""Replace only the private pilot sidecar after a UI-only image rebuild.

Default: preflight without changing production services. --apply backs up private container configuration,
recreates only siyue-academic-pilot and verifies it. Failure recreates the prior
immutable image ID. No migrations, nginx changes or legacy service restarts.
If that image index is unavailable, --rollback-image-id may supply a rebuilt
image only after its /app tree and startup settings match the running sidecar.
This fallback preflight creates one short-lived, network-disabled hash container.
--account-probes additionally creates self-cleaning short-lived Sessions for
GET-only whitelist checks; it never creates academic/printing business records.
The sibling academic-pilot.py must be present.
"""
import argparse
import datetime as dt
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

spec = importlib.util.spec_from_file_location('academic_pilot_deploy', Path(__file__).with_name('academic-pilot.py'))
pilot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pilot)

HOST_FIELDS = ['NetworkMode', 'ReadonlyRootfs', 'Privileged', 'Memory', 'MemorySwap',
               'MemoryReservation', 'NanoCpus', 'CpuShares', 'CpuPeriod', 'CpuQuota',
               'CpusetCpus', 'CpusetMems', 'PidsLimit', 'SecurityOpt', 'CapAdd', 'CapDrop',
               'Tmpfs', 'RestartPolicy', 'LogConfig', 'Ulimits', 'ShmSize', 'OomKillDisable']


def image_info(reference, missing_ok=False):
    if not missing_ok:
        return json.loads(pilot.run(['docker', 'image', 'inspect', reference]))[0]
    result = subprocess.run(['docker', 'image', 'inspect', reference], capture_output=True, text=True)
    if result.returncode:
        # Never turn daemon/permission failures into permission to use a fallback.
        if re.search(r'(?i)(no such (?:image|object)|image .* not found)', result.stderr):
            return None
        raise RuntimeError('Unable to inspect the current rollback image')
    return json.loads(result.stdout)[0]


APP_TREE_HASH_SCRIPT = r'''
import hashlib, json, os, stat
root = '/app'
digest = hashlib.sha256()
if not os.path.isdir(root) or os.path.islink(root):
    raise RuntimeError('Application tree root is not a directory')
def walk_error(error):
    raise error
def entry(path):
    info = os.lstat(path)
    record = [os.path.relpath(path, root), stat.S_IMODE(info.st_mode), info.st_uid, info.st_gid]
    if stat.S_ISLNK(info.st_mode):
        record += ['link', os.readlink(path)]
    elif stat.S_ISDIR(info.st_mode):
        record += ['dir']
    elif stat.S_ISREG(info.st_mode):
        content = hashlib.sha256()
        with open(path, 'rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                content.update(chunk)
        record += ['file', info.st_size, content.hexdigest()]
    else:
        raise RuntimeError('Unsupported application tree entry')
    digest.update((json.dumps(record, ensure_ascii=True, separators=(',', ':')) + '\n').encode())
entry(root)
for base, dirs, files in os.walk(root, followlinks=False, onerror=walk_error):
    dirs.sort()
    for name in sorted(dirs + files):
        entry(os.path.join(base, name))
print(digest.hexdigest())
'''


def app_tree_hash(image_id=None):
    if image_id:
        command = ['docker', 'run', '--rm', '--read-only', '--network', 'none',
                   '--memory', '128m', '--memory-swap', '128m', '--cpus', '0.25',
                   '--pids-limit', '32', '--cap-drop', 'ALL',
                   '--security-opt', 'no-new-privileges:true', '--entrypoint', 'python3', image_id]
    else:
        command = ['docker', 'exec', pilot.CONTAINER, 'python3']
    value = pilot.run(command + ['-I', '-B', '-c', APP_TREE_HASH_SCRIPT]).strip()
    pilot.require(re.fullmatch(r'[0-9a-f]{64}', value), 'Invalid application tree hash result')
    return value


def rollback_image(old, requested):
    original = image_info(old['Image'], missing_ok=True)
    if original:
        pilot.require(not requested or requested == old['Image'], 'Fallback image is only allowed when the original image is unavailable')
        validate_supported(old, original)
        return {'rollbackImageId': old['Image'], 'fallbackUsed': False}
    pilot.require(requested, 'Original image unavailable; supply an explicit --rollback-image-id')
    replacement = image_info(requested)
    pilot.require(replacement['Id'] == requested, 'Rollback image ID did not resolve exactly')
    validate_supported(old, replacement)
    running_hash = app_tree_hash()
    pilot.require(app_tree_hash(requested) == running_hash, 'Fallback application tree differs from the running pilot')
    return {'rollbackImageId': requested, 'fallbackUsed': True, 'applicationTreeHash': running_hash}


def env_values(container):
    return dict(item.split('=', 1) for item in container['Config']['Env'] if '=' in item)


def service_facts():
    result = {}
    for name in ['siyue-miniapp-api-1', 'siyue-miniapp-worker-1']:
        c = pilot.inspect(name)
        pilot.require(c['State']['Running'], 'An original service is not running')
        result[name] = {'id': c['Id'], 'startedAt': c['State']['StartedAt']}
    return result


def config_fingerprint(c):
    return {
        'host': {key: c['HostConfig'].get(key) for key in HOST_FIELDS},
        'user': c['Config'].get('User'), 'workingDir': c['Config'].get('WorkingDir'),
        'entrypoint': c['Config'].get('Entrypoint'), 'cmd': c['Config'].get('Cmd'),
        'env': env_values(c), 'labels': c['Config'].get('Labels') or {},
        'mounts': sorted([(m['Type'], m['Source'], m['Destination'], m['RW'], m.get('Propagation', '')) for m in c['Mounts']]),
    }


def validate_supported(c, target_image):
    h = c['HostConfig']; cfg = c['Config']; target = target_image['Config']
    pilot.require(cfg.get('Labels', {}).get('com.siyue.pilot') == 'academic-feedback', 'Unexpected pilot identity')
    pilot.require(h['NetworkMode'] == 'host' and h['ReadonlyRootfs'] and not h['Privileged'], 'Unexpected pilot isolation')
    pilot.require(not h.get('Devices') and not h.get('PortBindings') and not h.get('Links') and not h.get('VolumesFrom'), 'Unsupported sidecar host configuration')
    pilot.require(not any(h.get(k) for k in ['Dns','DnsOptions','DnsSearch','ExtraHosts','GroupAdd','Sysctls']), 'Custom host configuration needs an explicit update plan')
    pilot.require(all(m['Type'] == 'bind' and not m['RW'] for m in c['Mounts']), 'Expected read-only bind mounts only')
    pilot.require(h.get('LogConfig', {}).get('Type') in {'json-file', 'local'}, 'Unsupported logging driver')
    pilot.require(cfg.get('Entrypoint') == target.get('Entrypoint') and cfg.get('Cmd') == target.get('Cmd'), 'Image entrypoint differs; UI-only update refused')
    pilot.require(cfg.get('Healthcheck') == target.get('Healthcheck'), 'Image healthcheck differs; UI-only update refused')
    pilot.require(cfg.get('StopSignal') == target.get('StopSignal'), 'Image stop signal differs')
    pilot.require(set(target.get('Volumes') or {}) <= {m['Destination'] for m in c['Mounts']}, 'Image introduces unconfigured volumes')
    pilot.require(set(item.split('=', 1)[0] for item in target.get('Env') or []) <= set(env_values(c)), 'Image introduces unconfigured environment keys')
    pilot.require(set(target.get('Labels') or {}) <= set(cfg.get('Labels') or {}), 'Image introduces unconfigured labels')
    pilot.require(not cfg.get('Tty') and not cfg.get('OpenStdin'), 'Interactive sidecar update unsupported')
    env = env_values(c)
    pilot.require(env.get('RunWorker') == 'false' and env.get('Database__Initialize') == 'false', 'Sidecar cannot run workers or initialize schema')
    for key in ['AcademicPilot__AllowedAccounts', 'AcademicPilot__ReviewerAccounts']:
        pilot.require(env.get(key) == 'test_admin', 'Pilot must remain limited to test_admin')
    pilot.require(not env.get('AcademicPilot__AllowedStaffIds') and not env.get('AcademicPilot__ReviewerStaffIds'), 'Unexpected additional pilot identities')
    pilot.require(env.get('AcademicPilot__Enabled') == 'true', 'Pilot is not enabled')
    pilot.require('Maximum Pool Size=5' in env.get('ConnectionStrings__Database', ''), 'Sidecar connection limit missing')


def recreate_args(old, env_file, image):
    """Preserve current limits/settings; reject unsupported configurations beforehand."""
    h = old['HostConfig']; c = old['Config']
    args = ['docker', 'run', '-d', '--name', pilot.CONTAINER, '--network', h['NetworkMode'],
            '--read-only', '--env-file', str(env_file)]
    for key, flag in [('Memory','--memory'), ('MemorySwap','--memory-swap'),
                      ('MemoryReservation','--memory-reservation'), ('CpuShares','--cpu-shares'),
                      ('CpuPeriod','--cpu-period'), ('CpuQuota','--cpu-quota'), ('PidsLimit','--pids-limit'),
                      ('ShmSize','--shm-size')]:
        value = h.get(key)
        if value:
            args += [flag, str(value)]
    if h.get('NanoCpus'):
        pilot.require(not h.get('CpuPeriod') and not h.get('CpuQuota'), 'Ambiguous CPU configuration')
        args += ['--cpus', format(h['NanoCpus'] / 1_000_000_000, '.9g')]
    for key, flag in [('CpusetCpus','--cpuset-cpus'), ('CpusetMems','--cpuset-mems')]:
        if h.get(key): args += [flag, h[key]]
    for key, flag in [('SecurityOpt','--security-opt'), ('CapAdd','--cap-add'), ('CapDrop','--cap-drop')]:
        for value in h.get(key) or []: args += [flag, value]
    if h.get('OomKillDisable'): args.append('--oom-kill-disable')
    for path, options in (h.get('Tmpfs') or {}).items(): args += ['--tmpfs', path + ':' + options]
    for mount in old['Mounts']:
        pilot.require(',' not in mount['Source'] and ',' not in mount['Destination'], 'Unsupported bind path')
        value = 'type=bind,source=' + mount['Source'] + ',target=' + mount['Destination'] + ',readonly'
        if mount.get('Propagation'): value += ',bind-propagation=' + mount['Propagation']
        args += ['--mount', value]
    restart = h.get('RestartPolicy') or {}
    policy = restart.get('Name') or 'no'
    if policy == 'on-failure' and restart.get('MaximumRetryCount'): policy += ':' + str(restart['MaximumRetryCount'])
    args += ['--restart', policy]
    logging = h['LogConfig']; args += ['--log-driver', logging['Type']]
    for key, value in (logging.get('Config') or {}).items(): args += ['--log-opt', key + '=' + value]
    for item in h.get('Ulimits') or []:
        args += ['--ulimit', item['Name'] + '=' + str(item['Soft']) + ':' + str(item['Hard'])]
    for key, value in (c.get('Labels') or {}).items(): args += ['--label', key + '=' + value]
    if c.get('User'): args += ['--user', c['User']]
    if c.get('WorkingDir'): args += ['--workdir', c['WorkingDir']]
    if c.get('Hostname'): args += ['--hostname', c['Hostname']]
    if c.get('Domainname'): args += ['--domainname', c['Domainname']]
    args.append(image)
    return args


def remove_current(expected_id=None):
    names = pilot.run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
    if pilot.CONTAINER not in names: return
    current = pilot.inspect(pilot.CONTAINER)
    pilot.require(current['Config'].get('Labels', {}).get('com.siyue.pilot') == 'academic-feedback', 'Unexpected container occupies pilot name')
    if expected_id: pilot.require(current['Id'] == expected_id, 'Pilot container changed concurrently')
    if current['State']['Running']: pilot.run(['docker', 'stop', '--time', '30', pilot.CONTAINER])
    pilot.run(['docker', 'rm', pilot.CONTAINER])


def rollback_verify(old, accounts, expected_image_id, original_tree_hash=None):
    pilot.health(pilot.PORT)
    restored = pilot.inspect(pilot.CONTAINER)
    pilot.require(restored['Image'] == expected_image_id, 'Rollback did not restore the selected immutable image')
    pilot.require(config_fingerprint(restored) == config_fingerprint(old), 'Rollback configuration differs')
    pilot.require(pilot.http('https://www.siyuejn.com/miniapp/academic-pilot/')[0] == 200, 'Restored pilot shell failed')
    pilot.require(pilot.http('https://www.siyuejn.com/miniapp/api/academic-pilot/me')[0] == 401, 'Restored pilot exposed anonymous data')
    operations = pilot.contract_operations('http://127.0.0.1:5082')
    pilot.require(len(operations) == 65 and pilot.contract_operations(f'http://127.0.0.1:{pilot.PORT}') == operations, 'Restored contract differs')
    result = {'health': True, 'rollbackImageRestored': True, 'restoredImageId': expected_image_id,
              'originalImageRestored': expected_image_id == old['Image'], 'configurationPreserved': True}
    if original_tree_hash:
        pilot.require(app_tree_hash() == original_tree_hash, 'Restored application tree differs from original')
        result['applicationTreeMatchesOriginal'] = True
    if accounts: result['accountProbes'] = pilot.account_probes('https://www.siyuejn.com/miniapp')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--expected-image-id', required=True)
    parser.add_argument('--rollback-image-id', help='Full immutable image ID, used only if the original index is missing')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--account-probes', action='store_true')
    args = parser.parse_args()
    pilot.require(os.geteuid() == 0, 'Run on deployment host as root')
    pilot.require(re.fullmatch(r'sha256:[0-9a-f]{64}', args.expected_image_id), 'Supply the full expected sha256 image ID')
    pilot.require(not args.rollback_image_id or re.fullmatch(r'sha256:[0-9a-f]{64}', args.rollback_image_id), 'Supply the full rollback sha256 image ID')
    pilot.require(pilot.STATE.exists(), 'Existing pilot deployment state not found')
    state = json.loads(pilot.STATE.read_text())
    pilot.require(state.get('ok') and not state.get('rolledBack'), 'Pilot is not deployed')
    old = pilot.inspect(pilot.CONTAINER)
    target = image_info(pilot.IMAGE)
    pilot.require(target['Id'] == args.expected_image_id, 'Fixed image tag does not match the expected new image ID')
    pilot.require(old['State']['Running'], 'Current pilot is not running')
    pilot.require(old['Image'] != target['Id'], 'Target image is already running; no update required')
    validate_supported(old, target)
    recovery = rollback_image(old, args.rollback_image_id)
    old_services = service_facts(); nginx_hash = pilot.digest(pilot.SNIPPET.read_bytes())
    pilot.require(pilot.ROUTES.encode() in pilot.SNIPPET.read_bytes(), 'Expected pilot route block not found')
    pilot.health(pilot.PORT)
    pilot.require(pilot.http('http://127.0.0.1:5082/health')[0] == 200 and pilot.http('http://127.0.0.1:5083/health')[0] == 200, 'Legacy health preflight failed')
    if not args.apply:
        print(json.dumps({'preflight': True, 'oldImageId': old['Image'], 'newImageId': target['Id'],
                          **recovery,
                          'onlySidecarWillRestart': True, 'databaseMigrations': False, 'nginxChanges': False})); return
    with (pilot.STATE.parent / 'deploy.lock').open('a') as lock:
        os.chmod(lock.name, 0o600); fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        pilot.require(pilot.inspect(pilot.CONTAINER)['Id'] == old['Id'], 'Pilot changed concurrently')
        pilot.require(image_info(pilot.IMAGE)['Id'] == args.expected_image_id, 'Image tag changed concurrently')
        pilot.require(image_info(recovery['rollbackImageId'])['Id'] == recovery['rollbackImageId'], 'Rollback image changed concurrently')
        pilot.require(pilot.digest(pilot.SNIPPET.read_bytes()) == nginx_hash, 'Nginx changed concurrently')
        stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S')
        backup = pilot.ROOT / 'backups' / ('academic-pilot-ui-' + stamp)
        backup.mkdir(mode=0o700)
        env_file = backup / 'runtime.env'
        pilot.atomic(backup / 'container-private.json', json.dumps(old, ensure_ascii=False, indent=2).encode())
        pilot.atomic(backup / 'state-before.json', pilot.STATE.read_bytes())
        pilot.private_env(env_file, env_values(old))
        rollback_tag = 'siyue-miniapp:academic-pilot-ui-backup-' + stamp
        report = {'ok': False, 'backup': str(backup), 'oldImageId': old['Image'], 'newImageId': target['Id'],
                  **recovery,
                  'legacyServices': old_services, 'nginxHash': nginx_hash, 'databaseMigrations': False,
                  'accountProbesRequested': args.account_probes, 'rollbackImageTag': rollback_tag}
        replacement_started = False
        try:
            # Keep the old immutable image reachable even after its last container is removed.
            pilot.run(['docker','image','tag',recovery['rollbackImageId'],rollback_tag])
            replacement_started = True
            remove_current(old['Id'])
            # Re-check immediately before using the tag. Inspect actual Image after start as well.
            pilot.require(image_info(pilot.IMAGE)['Id'] == args.expected_image_id, 'Image tag changed during replacement')
            pilot.run(recreate_args(old, env_file, pilot.IMAGE))
            current = pilot.inspect(pilot.CONTAINER)
            pilot.require(current['Image'] == args.expected_image_id, 'Unexpected image started')
            pilot.require(config_fingerprint(current) == config_fingerprint(old), 'Sidecar configuration drifted')
            report['verification'] = pilot.verify(args.account_probes)
            pilot.require(service_facts() == old_services, 'Original API or worker changed')
            pilot.require(pilot.digest(pilot.SNIPPET.read_bytes()) == nginx_hash, 'Nginx configuration changed')
            report.update({'ok': True, 'legacyServicesUnchanged': True, 'nginxUnchanged': True,
                           'newContainerId': current['Id']})
            state['lastUiUpdate'] = {k:report[k] for k in ['backup','oldImageId','rollbackImageId','fallbackUsed','newImageId','newContainerId']}
            pilot.atomic(pilot.STATE, json.dumps(state, ensure_ascii=False, indent=2).encode())
        except Exception:
            if replacement_started:
                names = pilot.run(['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
                existing = pilot.inspect(pilot.CONTAINER) if pilot.CONTAINER in names else None
                if existing and existing['Id'] == old['Id']:
                    restored_image_id = old['Image']
                    if not existing['State']['Running']:
                        pilot.run(['docker','start',pilot.CONTAINER])
                else:
                    remove_current()
                    restored_image_id = recovery['rollbackImageId']
                    pilot.run(recreate_args(old, env_file, restored_image_id))
                report['rollback'] = rollback_verify(old, args.account_probes, restored_image_id, recovery.get('applicationTreeHash'))
                pilot.require(service_facts() == old_services, 'Original API or worker changed during rollback')
                pilot.require(pilot.digest(pilot.SNIPPET.read_bytes()) == nginx_hash, 'Nginx changed during rollback')
                report['rollback']['legacyServicesUnchanged'] = True
                report['rollback']['nginxUnchanged'] = True
            raise
        finally:
            env_file.unlink(missing_ok=True)
            pilot.atomic(backup / 'update-result.json', json.dumps(report, ensure_ascii=False, indent=2).encode())
        print(json.dumps({'ok': True, 'backup': str(backup), 'imageId': target['Id'],
                          'onlySidecarRestarted': True, 'legacyServicesUnchanged': True,
                          'nginxUnchanged': True, 'databaseMigrations': False}))


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        print('ERROR: ' + (str(e) if isinstance(e, RuntimeError) else type(e).__name__), file=sys.stderr)
        sys.exit(1)
