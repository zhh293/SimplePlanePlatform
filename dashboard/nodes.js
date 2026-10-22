const fs = require('fs');
const crypto = require('crypto');

function validateManualNodes(config) {
  const nodes = config.remoteServers || [];
  if (!Array.isArray(nodes) || !nodes.length) throw new Error('至少需要配置一个节点');
  if (!config.nodeSelection) return;
  if (!['manual', 'auto'].includes(config.nodeSelection.mode)) throw new Error('请选择手动或自动模式');
  if (config.connectionsPerNode !== 1) throw new Error('HTTP/3 每个节点只支持一条连接，请设置 connectionsPerNode=1');
  if (!nodes.length || nodes.some(node => node.transport !== 'http3')) throw new Error('手动节点选择仅支持 HTTP/3 节点');
  const ids = new Set();
  for (const node of nodes) {
    if (typeof node.id !== 'string' || !node.id.trim() || ids.has(node.id)) throw new Error('节点 ID 不能为空或重复');
    if (!node.host || !Number.isInteger(node.port) || node.port < 1 || node.port > 65535) throw new Error('节点地址或端口无效');
    ids.add(node.id);
  }
  if (!ids.has(config.nodeSelection.selectedNodeId)) throw new Error('请选择一个存在的节点');
  if (config.nodeSelection.mode === 'auto' && !ids.has(config.nodeSelection.preferredNodeId)) throw new Error('请选择一个存在的首选节点');
}

function atomicWrite(file, text) {
  const temporary = `${file}.${crypto.randomUUID()}.tmp`;
  try {
    fs.writeFileSync(temporary, text, { encoding: 'utf8', mode: 0o600 });
    fs.renameSync(temporary, file);
  } finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
}

function readNodeStatus(configPath) {
  try {
    const status = JSON.parse(fs.readFileSync(`${configPath}.status.json`, 'utf8'));
    if (!status.running || Date.now() - status.updatedAt > 10000) return { ok: false, error: '代理未运行或状态已过期' };
    return { ok: true, data: status };
  } catch { return { ok: false, error: '运行时尚未确认节点配置' }; }
}

function withNodeBypass(text, addresses) {
  const section = /^\s*\[bypass\]\s*(?:#.*)?$/m.exec(text);
  if (!section) return `${text.trimEnd()}\n\n[bypass]\nproxy_remote_ips = ${JSON.stringify([...new Set(addresses)])}\n`;
  const start = section.index + section[0].length;
  const nextSection = /^\s*\[[A-Za-z0-9_.-]+\]\s*(?:#.*)?$/m.exec(text.slice(start));
  const end = nextSection ? start + nextSection.index : text.length;
  let body = text.slice(start, end);
  const current = /^[ \t]*proxy_remote_ips[ \t]*=[ \t]*(\[[\s\S]*?\])[ \t]*(?:#.*)?$/m.exec(body);
  if (!current && /proxy_remote_ips\s*=/.test(body)) throw new Error('请检查 TUN proxy_remote_ips 数组格式');
  const existing = current ? [...current[1].matchAll(/["']([^"']+)["']/g)].map(match => match[1]) : [];
  const line = `proxy_remote_ips = ${JSON.stringify([...new Set([...existing, ...addresses])])}`;
  body = current ? body.replace(current[0], line) : `\n${line}\n${body}`;
  return text.slice(0, start) + body + text.slice(end);
}

async function prepareTunNodeConfig(config, tunPath, lookup = require('dns').promises.lookup) {
  const addresses = [];
  for (const node of config.remoteServers || []) {
    let timer;
    try {
      const resolved = await Promise.race([
        lookup(node.host, { all: true }),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('解析节点地址超时')), 5000); }),
      ]);
      addresses.push(...resolved.map(item => item.address));
    } finally { clearTimeout(timer); }
  }
  const runtime = `${tunPath}.nodes.toml`;
  atomicWrite(runtime, withNodeBypass(fs.readFileSync(tunPath, 'utf8'), addresses));
  return runtime;
}

module.exports = { validateManualNodes, atomicWrite, readNodeStatus, withNodeBypass, prepareTunNodeConfig };
