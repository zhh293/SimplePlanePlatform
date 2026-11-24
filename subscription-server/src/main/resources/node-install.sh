#!/bin/sh
set -eu
umask 077
if [ "$(id -u)" != 0 ]; then echo 'Run as root on the new node.' >&2; exit 1; fi
cd "$(dirname "$0")"
for tool in java openssl systemctl python3; do command -v "$tool" >/dev/null; done
test -s simpleplane-node.jar || { echo 'Place the downloaded simpleplane-node.jar beside install.sh.' >&2; exit 1; }
python3 - <<'PY'
import hashlib,json,pathlib,re,subprocess
d=json.loads(pathlib.Path('node.json').read_text())
assert re.fullmatch('[a-z0-9][a-z0-9_-]{0,95}',d['id'])
assert hashlib.sha256(pathlib.Path('simpleplane-node.jar').read_bytes()).hexdigest()==d['jarSha256'], 'Runtime checksum mismatch'
if subprocess.check_output(['ss','-Hlnu','sport = :'+str(d['port'])]).strip():raise SystemExit('UDP port already in use; existing listeners need the shared-mode instructions.')
PY
NODE_ID=$(python3 -c "import json;print(json.load(open('node.json'))['id'])")
UNIT="simpleplane-node-$NODE_ID"
DEST="/opt/$UNIT"
for file in "$DEST" "/etc/systemd/system/$UNIT.service" "/etc/systemd/system/$UNIT-renew.service" "/etc/systemd/system/$UNIT-renew.timer"; do
    test ! -e "$file" || { echo "Already exists: $file" >&2; exit 1; }
done
WORK=$(mktemp -d "/opt/.$UNIT.XXXXXXXX")
STAGE=$WORK
COMMITTED=0
cleanup() {
    if [ "$COMMITTED" = 1 ]; then
        systemctl disable --now "$UNIT-renew.timer" "$UNIT.service" >/dev/null 2>&1 || true
        rm -f "/etc/systemd/system/$UNIT.service" "/etc/systemd/system/$UNIT-renew.service" "/etc/systemd/system/$UNIT-renew.timer"
        systemctl daemon-reload
        mv "$DEST" "$WORK"
    fi
    if [ -d "$WORK" ]; then echo "Installation interrupted; diagnostics in $WORK" >&2; fi
}
trap cleanup EXIT
install -m 644 simpleplane-node.jar "$STAGE/relay.jar"
install -m 600 device-ca.crt subscription-frame.key node.json "$STAGE/"
cd "$STAGE"
python3 - <<'PY'
import ipaddress,json,pathlib,subprocess
p=pathlib.Path
d=json.loads(p('node.json').read_text()); name=d['serverName']
try: san='IP:'+str(ipaddress.ip_address(name))
except ValueError: san='DNS:'+name
def ssl(*a):subprocess.run(['openssl',*a],check=True)
ssl('genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:prime256v1','-out','server-ca.key')
ssl('req','-new','-x509','-key','server-ca.key','-subj','/CN=SimplePlane node CA','-days','3650','-addext','basicConstraints=critical,CA:TRUE,pathlen:0','-addext','keyUsage=critical,keyCertSign,cRLSign','-out','server-ca.crt')
ssl('genpkey','-algorithm','EC','-pkeyopt','ec_paramgen_curve:prime256v1','-out','server.key')
ssl('req','-new','-key','server.key','-subj','/CN=SimplePlane relay','-out','server.csr')
p('server.ext').write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName='+san+'\n')
ssl('x509','-req','-in','server.csr','-CA','server-ca.crt','-CAkey','server-ca.key','-CAcreateserial','-days','365','-extfile','server.ext','-out','server.crt')
unit='simpleplane-node-'+d['id']
credentials='/run/credentials/'+unit+'.service/'
cfg={'host':'0.0.0.0','port':d['port'],'transport':'http3','cipher':'aes-gcm-v2','cipherKey':p('subscription-frame.key').read_text().strip(),'workerThreads':1,'bizThreads':3,'http3':{'certificateFile':credentials+'server.crt','privateKeyFile':credentials+'server.key','maxConnections':48}}
p('remote.yml').write_text(json.dumps(cfg))
PY
cat > renew.sh <<'RENEW'
#!/bin/sh
set -eu
umask 077
cd "$(dirname "$0")"
if openssl x509 -in server.crt -noout -checkend 2592000 >/dev/null; then exit 0; fi
openssl x509 -req -in server.csr -CA server-ca.crt -CAkey server-ca.key -CAserial server-ca.srl -days 365 -extfile server.ext -out server.next.crt
openssl verify -purpose sslserver -CAfile server-ca.crt server.next.crt
mv server.next.crt server.crt
systemctl restart "$(basename "$PWD").service"
RENEW
chmod 700 renew.sh
chmod 711 "$STAGE"
mv -T "$STAGE" "$DEST"
COMMITTED=1
cat >"/etc/systemd/system/$UNIT.service" <<EOF
[Unit]
Description=SimplePlane subscription node $NODE_ID
After=network-online.target
[Service]
DynamicUser=yes
LoadCredential=server.key:$DEST/server.key
LoadCredential=server.crt:$DEST/server.crt
LoadCredential=device-ca.crt:$DEST/device-ca.crt
LoadCredential=remote.yml:$DEST/remote.yml
ExecStart=$(command -v java) -XX:ActiveProcessorCount=1 -Xms16m -Xmx64m -Dsimpleplane.deviceCaFile=/run/credentials/$UNIT.service/device-ca.crt -cp /run/credentials/$UNIT.service:$DEST/relay.jar com.proxy.remote.ProxyRemoteServer
Restart=on-failure
RestartSec=5s
MemoryMax=160M
NoNewPrivileges=true
PrivateTmp=true
[Install]
WantedBy=multi-user.target
EOF
cat >"/etc/systemd/system/$UNIT-renew.service" <<EOF
[Service]
Type=oneshot
ExecStart=/bin/sh $DEST/renew.sh
EOF
cat >"/etc/systemd/system/$UNIT-renew.timer" <<EOF
[Timer]
OnCalendar=daily
RandomizedDelaySec=30m
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now "$UNIT"
port=$(python3 -c "import json;print(json.load(open('node.json'))['port'])")
ready=0
for i in $(seq 1 30); do
    pid=$(systemctl show "$UNIT" -p MainPID --value)
    if [ "$pid" -gt 1 ] && ss -Hlnup "sport = :$port" | grep -q "pid=$pid,"; then ready=1; break; fi
    sleep 1
done
[ "$ready" = 1 ] || { echo 'Listener failed to start' >&2; exit 1; }
systemctl enable --now "$UNIT-renew.timer"
trap - EXIT
echo "Upload this public certificate to the node detail page: $DEST/server-ca.crt"
cat server-ca.crt
