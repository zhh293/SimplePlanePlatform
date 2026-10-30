"""Read bounded fixture diagnostics, without reading private configurations."""
import sys
import json
from prepare_comparison import ROOT, RUN, REMOTE, ssh, remote

if len(sys.argv) > 1 and sys.argv[1] == 'setup':
    print(remote('setup').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'update':
    ssh('cat > '+REMOTE+'/remote_fixture.next && mv '+REMOTE+'/remote_fixture.next '+REMOTE+'/remote_fixture.py', (ROOT/'scripts/perf/remote_fixture.py').read_bytes())
    print('Updated owned fixture controller')
elif len(sys.argv) > 1 and sys.argv[1] == 'arm':
    print(remote('arm').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'snapshot':
    print(remote('snapshot').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'prune':
    value=json.loads(remote('prune'))
    (RUN/'prune.json').write_text(json.dumps(value,indent=2))
    print(json.dumps(value))
elif len(sys.argv) > 1 and sys.argv[1] == 'sp-log':
    print(ssh('sudo -n tail -45 '+REMOTE+'/simpleplane.log; '
              'sudo -n systemctl show sp-perf-20261030-simpleplane -p Result -p ExecMainStatus; '
              'sudo -n journalctl -u sp-perf-20261030-simpleplane --no-pager -n 12').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'start':
    print(remote('start', sys.argv[2]).decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'cleanup':
    print(remote('cleanup').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'network':
    print(ssh('command -v aws; command -v nft; command -v tcpdump; '
              'sudo -n ss -lntup | head -25; '
              'sudo -n tail -8 '+REMOTE+'/shadowsocks.log; '
              'sudo -n head -55 /etc/nginx/nginx.conf; true').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'stream':
    print(ssh('sudo -n tail -75 /etc/nginx/nginx.conf; '
              'sudo -n find /etc/nginx -maxdepth 3 -type f -iname "*stream*"; '
              'sudo -n grep -R -n -E "19090|listen.*9090|proxy_pass" /etc/nginx/stream*; true').decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'aws':
    print(ssh('AWS_METADATA_SERVICE_TIMEOUT=2 AWS_METADATA_SERVICE_NUM_ATTEMPTS=1 '
              'aws --region us-east-1 --cli-connect-timeout 5 --cli-read-timeout 8 '
              'ec2 describe-instances --filters Name=ip-address,Values=54.172.101.190 '
              '--query "Reservations[].Instances[].{Id:InstanceId,Groups:SecurityGroups}" --output json', timeout=30).decode())
elif len(sys.argv) > 1 and sys.argv[1] == 'collect':
    (RUN/'server-metrics.jsonl').write_bytes(ssh('sudo -n cat '+REMOTE+'/server-metrics.jsonl'))
    for name in ['simpleplane','shadowsocks','origin','hysteria']:
        (RUN/(name+'-server.log')).write_bytes(ssh('sudo -n sh -c '+__import__('shlex').quote('test ! -f '+REMOTE+'/'+name+'.log || tail -100 '+REMOTE+'/'+name+'.log')))
    result=json.loads(remote('cleanup'))
    (RUN/'cleanup.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result))
    expected=json.loads((RUN/'environment.json').read_text())
    assert result['production_active']=='active'
    assert result['production_pid']==expected['production_pid']
    assert result['nginx_upstream_restored']
    assert result['nginx_sha256']==result['nginx_original_sha256']
    snapshot=json.loads(remote('snapshot'))
    assert all(u.get('inactive') or u.get('processes')==[] for u in snapshot['units']), snapshot
    (RUN/'final-idle-snapshot.json').write_text(json.dumps(snapshot,indent=2))
    print('Verified original service PID, exact nginx hash, and all owned test units stopped')
else:
    print(ssh('sudo -n tail -35 '+REMOTE+'/simpleplane.log; '
              'sudo -n tail -15 '+REMOTE+'/origin.log; '
              'sudo -n systemctl show sp-perf-20261030-simpleplane -p Result -p ExecMainStatus; '
              'sudo -n ss -lnup; sudo -n iptables -S INPUT; '
              'true').decode())
