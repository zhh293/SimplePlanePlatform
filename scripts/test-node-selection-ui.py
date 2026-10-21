"""Headless Chrome smoke tests of the actual desktop and dashboard JavaScript (mock IPC/API only)."""
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / ".build" / "node-selection-ui"
OUTPUT.mkdir(parents=True, exist_ok=True)
CHROME = os.environ.get("CHROME_BIN") or shutil.which("google-chrome") or shutil.which("chromium")
if not CHROME:
    for candidate in [r"C:\Program Files\Google\Chrome\Application\chrome.exe", r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"]:
        if Path(candidate).exists():
            CHROME = candidate
            break
if not CHROME:
    raise SystemExit("Set CHROME_BIN to a Chrome/Chromium executable")

remote = {"host": "a.example", "port": 9090, "transport": "http3", "cipher": "aes-gcm-v2", "key": "0123456789abcdef0123456789abcdef", "http3": {"ca_file": "a-ca.pem"}}
nodes = [{**remote, "id": "a", "name": "主节点"}, {**remote, "id": "b", "host": "b.example", "name": "备用节点"}]
desktop = {"local": {"port": 1080, "http_proxy_enabled": True}, "remote": remote, "nodes": nodes, "selected_node_id": "b", "route": {"default_route": "proxy", "proxy_list": [], "direct_list": [], "rules": [{"id": "keep", "type": "DOMAIN", "value": "test.example", "action": "PROXY"}]}}
dashboard = {"localPort": 1080, "remoteServers": [{**node, "cipherKey": "", "http3": {"caFile": "a-ca.pem"}} for node in nodes], "nodeSelection": {"mode": "manual", "selectedNodeId": "b"}, "connectionsPerNode": 1, "timeoutMs": 5000, "route": {"defaultRoute": "proxy"}}
node_status = {"currentNodeId": "b", "nodeStatus": "available", "activeStreams": 2, "savedRevision": "one", "appliedRevision": "one", "error": ""}

for name, source, config in [("desktop", ROOT / "desktop/web", desktop), ("dashboard", ROOT / "dashboard/public", dashboard)]:
    fixture = f"window.fixture={json.dumps(config, ensure_ascii=False)};window.nodeStatus={json.dumps(node_status)};window.saved=null;"
    if name == "desktop":
        fixture += """
window.__TAURI__={core:{invoke:async(command,args)=>{
 if(command==='get_config')return structuredClone(window.fixture);
 if(command==='get_tun_config_raw')return '[tun]\\nname="test"';
 if(command==='get_presets')return [];
 if(command==='save_config'){window.saved=args.configData;return 'saved';}
 if(command==='import_node_certificate'){
  window.importCalls=(window.importCalls||0)+1;
  if(window.importFails)throw Error('Invalid certificate');
  if(!args.pem.startsWith('-----BEGIN CERTIFICATE-----'))throw Error('Expected public PEM');
  return 'C:/SimplePlane/certificates/node-imported.pem';
 }
 if(command==='status')return {proxy_status:'running',tun_status:'stopped',proxy_mode:'system',proxy_port:1080,http_port:1080,proxy_port_listening:true,node_status:window.nodeStatus};
 return [];
}}};
"""
    else:
        fixture += """
window.EventSource=class {addEventListener(){} close(){}};
window.fetch=async(url,options={})=>({json:async()=>{
 if(url==='/api/config/local'&&options.method==='POST'){window.saved=JSON.parse(options.body).data;return {ok:true};}
 if(url==='/api/config/local')return {ok:true,data:structuredClone(window.fixture)};
 if(url==='/api/config/remote')return {ok:true,data:{}};
 if(url==='/api/nodes/status')return {ok:true,data:window.nodeStatus};
 if(url==='/api/status')return {ok:true,services:{'proxy-local':{status:'running',uptime:1},'tun-adapter':{status:'stopped',uptime:0}}};
 if(url==='/api/config/tun')return {ok:true,raw:'[tun]'};
 if(url==='/api/platform')return {ok:true,isWindows:true,isMacOS:false};
 return {ok:true,needsSetup:false};
}});
"""
    assertions = """
window.addEventListener('DOMContentLoaded',()=>setTimeout(async()=>{
 try{
  const cards=[...document.querySelectorAll('.server-card')];
  if(cards.length!==2)throw Error('Expected two rendered nodes');
  if(!cards[1].querySelector('[data-key="selected"]').checked)throw Error('Selected node not restored');
  if(getComputedStyle(document.querySelector('#cluster').parentNode).display!=='none')throw Error('Legacy cluster selector is still visible for HTTP/3');
  document.querySelector('.nav-item[data-section="proxy-config"]').click();
  cards[0].querySelector('[data-key="selected"]').click();
  document.querySelector('#btnSave').click();
  await new Promise(resolve=>setTimeout(resolve,100));
  const saved=window.saved;
  if(!saved)throw Error('Save did not reach IPC/API');
  const selected=saved.selected_node_id||saved.nodeSelection?.selectedNodeId;
  const nodes=saved.nodes||saved.remoteServers;
  if(selected!=='a'||nodes.length!==2)throw Error('Save lost selection or nodes');
  if(!(nodes[1].http3.ca_file||nodes[1].http3.caFile))throw Error('Save lost TLS options');
  if(nodes[1].cipher!=='aes-gcm-v2')throw Error('Save changed v2 cipher suite');
  if(saved.nodes&&!saved.route.rules?.length)throw Error('Save lost advanced route rules');
  if(saved.nodes){
   document.querySelector('#btnAddServer').click();
   const fresh=[...document.querySelectorAll('.server-card')].at(-1);
   const field=key=>fresh.querySelector(`[data-key="${key}"]`);
   field('host').value='20.78.53.111';
   if(field('server_name').value||field('ca_file').value)throw Error('New node inherited TLS identity');
   const input=fresh.querySelector('input[type="file"]');
   const upload=async(file)=>{
    const transfer=new DataTransfer();transfer.items.add(file);input.files=transfer.files;
    input.dispatchEvent(new Event('change',{bubbles:true}));
    await new Promise(resolve=>setTimeout(resolve,60));
   };
   await upload(new File(['-----BEGIN CERTIFICATE-----\\nTEST\\n-----END CERTIFICATE-----'],'ca.pem'));
   if(field('ca_file').value!=='C:/SimplePlane/certificates/node-imported.pem')throw Error('Certificate import path not applied');
   field('server_name').value='proxy.example';
   document.querySelector('#btnSave').click();
   await new Promise(resolve=>setTimeout(resolve,60));
   const added=window.saved.nodes.at(-1);
   if(added.http3.server_name!=='proxy.example'||added.http3.ca_file!==field('ca_file').value)throw Error('TLS form changes not saved');
   window.importFails=true;
   await upload(new File(['-----BEGIN CERTIFICATE-----\\nBAD\\n-----END CERTIFICATE-----'],'bad.pem'));
   if(field('ca_file').value!==added.http3.ca_file)throw Error('Failed import replaced previous certificate');
   const calls=window.importCalls;
   await upload(new File(['x'.repeat(262145)],'huge.pem'));
   if(window.importCalls!==calls)throw Error('Oversized certificate sent to backend');
   fresh.querySelector('.btn-clear-ca').click();
   field('server_name').value='';
   document.querySelector('#btnSave').click();
   await new Promise(resolve=>setTimeout(resolve,60));
   if(window.saved.nodes.at(-1).http3.ca_file||window.saved.nodes.at(-1).http3.server_name)throw Error('Cleared TLS options not saved');
   fresh.querySelector('.btn-remove').click();
  }
  cards[0].querySelector('.btn-remove').click();
  if(!cards[1].querySelector('[data-key="selected"]').checked)throw Error('Deleting selected node did not choose remaining node');
  document.body.dataset.smoke='passed';
 }catch(error){document.body.dataset.smoke='failed';document.body.dataset.failure=error.message;}
},500));
"""
    html = (source / "index.html").read_text(encoding="utf8")
    html = html.replace("<head>", f'<head><base href="{source.as_uri()}/"><script>{fixture}</script>', 1)
    html = html.replace("</body>", f"<script>{assertions}</script></body>")
    path = OUTPUT / f"{name}.html"
    path.write_text(html, encoding="utf8")
    result = subprocess.run([str(CHROME), "--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
                             f"--user-data-dir={OUTPUT / (name + '-profile')}", "--window-size=1440,1100", "--virtual-time-budget=2000",
                             f"--screenshot={OUTPUT / (name + '.png')}", "--dump-dom", path.as_uri()],
                            capture_output=True, timeout=45)
    dom = result.stdout.decode("utf8", errors="replace")
    (OUTPUT / f"{name}.dom.html").write_text(dom, encoding="utf8")
    if result.returncode or 'data-smoke="passed"' not in dom:
        (OUTPUT / f"{name}.chrome.log").write_bytes(result.stderr)
        raise SystemExit(f"{name} UI smoke failed; inspect {OUTPUT}")
    print(f"{name}: passed (render two nodes, select, save, preserve TLS, delete selected)")
