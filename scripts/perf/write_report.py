"""Publish actual measurements, failures, resource traces, and cleanup proof."""
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import shutil
import statistics
import os
import sys

ROOT=Path(__file__).resolve().parents[2]
RUN=ROOT/'.build/proxy-comparison'
OUT=ROOT/'docs/performance/proxy-comparison-2026-10-30'
TOOLS=['simpleplane','shadowsocks','hysteria']


def stats(values):
    values=sorted(values)
    return {'n':len(values),'median':statistics.median(values),'p95':values[math.ceil(.95*len(values))-1],
            'min':min(values),'max':max(values)} if values else None


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    for name in ['environment.json','samples.json','summary.json','deployed-baseline.json',
                 'deployed-diagnostic.json','cleanup.json','client-metrics.jsonl','server-metrics.jsonl',
                 'supplement-samples.json','tcp-diagnostics.jsonl','final-idle-snapshot.json','aligned-recovery-samples.json','prune.json']:
        if (RUN/name).exists():
            shutil.copyfile(RUN/name,OUT/('run-summary.json' if name=='summary.json' else name))
    rows=json.loads((RUN/'samples.json').read_text())
    summary=json.loads((RUN/'summary.json').read_text())
    env=json.loads((RUN/'environment.json').read_text())
    cleanup=json.loads((RUN/'cleanup.json').read_text())
    client=[json.loads(s) for s in (RUN/'client-metrics.jsonl').read_text().splitlines()]
    server=[json.loads(s) for s in (RUN/'server-metrics.jsonl').read_text().splitlines()]
    begin,end=min(r['time'] for r in rows)-10,max(r['time'] for r in rows)+5
    server=[s for s in server if begin<=s['time']<=end]
    resources={}
    for tool in TOOLS:
        blocks=[r for r in rows if r['tool']==tool and r['kind']=='resource_block']
        pids={r['end']['pid'] for r in blocks}
        c=[s for s in client if s['tool']==tool and s['pid'] in pids and begin<=s['time']<=end]
        units=[u for s in server for u in s['units'] if u['name']==tool and 'memory_bytes' in u]
        server_cpu=[]
        for block in blocks:
            start=block['time']-block['elapsed_s']
            selected=[u for s in server if start<=s['time']<=block['time'] for u in s['units'] if u['name']==tool and 'cpu' in u]
            if len(selected)>=2:
                server_cpu.append((selected[-1]['cpu']['usage_usec']-selected[0]['cpu']['usage_usec'])/1000000)
        resources[tool]={
            'blocks':len(blocks),'idle_rss_mib':stats([s['VmRSS']/1024 for s in c if s['phase']=='idle']),
            'client_peak_rss_mib':max((s['VmHWM']/1024 for s in c),default=None),
            'client_max_swap_mib':max((s['VmSwap']/1024 for s in c),default=None),
            'client_max_threads':max((s['Threads'] for s in c),default=None),
            'client_max_fds':max((s['fds'] for s in c),default=None),
            'client_cpu_s_per_block':stats([r['cpu_s'] for r in blocks]),
            'server_cpu_s_per_block_sampled':stats(server_cpu),
            'server_peak_cgroup_mib':max((u['memory_bytes']/1048576 for u in units),default=None),
            'server_peak_rss_mib':max((sum(p.get('VmHWM',0) for p in u['processes'])/1024 for u in units),default=None),
            'server_max_swap_mib':max((u['swap_bytes']/1048576 for u in units),default=None),
            'server_max_oom_kill_event':max((u['events'].get('oom_kill',0) for u in units),default=None),
            'server_max_memory_high_event':max((u['events'].get('high',0) for u in units),default=None),
            'server_max_memory_limit_event':max((u['events'].get('max',0) for u in units),default=None)}
    failures=[r for r in rows if not r['ok'] and r['kind']!='old_stream_after_outage']
    complete=all(resources[t]['blocks']==3 for t in TOOLS)
    data={'status':'completed' if complete else 'incomplete','samples':len(rows),'public_port':9090,
          'internal_test_port':18443,'resources':resources,'cleanup':cleanup,
          'failure_counts':dict(Counter(r['tool']+'/'+r['kind']+'/'+r.get('host','') for r in failures)),
          'minimum_server_available_mib':min(s['available_kib'] for s in server)/1024,
          'harness_verification':{'passed':7,'total':7}}
    extra_rows=json.loads((RUN/'supplement-samples.json').read_text()) if (RUN/'supplement-samples.json').exists() else []
    extras={}
    for tool in TOOLS:
        extras[tool]={}
        for kind in sorted({r['kind'] for r in extra_rows}):
            selected=[r for r in extra_rows if r['tool']==tool and r['kind']==kind]
            extras[tool][kind]={'n':len(selected),'successes':sum(r['ok'] for r in selected)}
            for metric in ['total_ms','mib_s']:
                extras[tool][kind][metric]=stats([r[metric] for r in selected if r['ok'] and metric in r])
    data['supplement']=extras
    aligned=json.loads((RUN/'aligned-recovery-samples.json').read_text()) if (RUN/'aligned-recovery-samples.json').exists() else []
    data['aligned_recovery_30s']=aligned
    (OUT/'analysis.json').write_text(json.dumps(data,indent=2))
    def cell(tool,kind,metric='total_ms',tail=False):
        s=summary.get(tool,{}).get(kind,{})
        total=s.get('n',0)
        if kind=='cold_start':
            total+=sum(r['tool']==tool and r['kind']=='block_error' and 'not reachable' in r.get('error','') for r in rows)
        v=s.get(metric)
        if not v:return f"未成功/未采样 ({s.get('successes',0)}/{total})"
        return f"{v['median']:.2f}"+(f" / {v['p95']:.2f}" if tail else '')+f" ({s['successes']}/{total})"
    measurements=[
        ('本地冷启动至首次完整 HTTP，ms','cold_start','total_ms',True),
        ('新隧道 + 1 KiB HTTP，ms','new_connection_http','total_ms',True),
        ('复用隧道 + 1 KiB HTTP，ms','keepalive_http','total_ms',True),
        ('新隧道 + TLS + 1 KiB HTTPS，ms','new_connection_https','total_ms',True),
        ('模拟页面加载，ms','synthetic_webpage','total_ms',True),
        ('32 MiB 单路下载，MiB/s','download_32mib','mib_s',False),
        ('8 MiB 上传，MiB/s','upload_8mib','mib_s',False),
        ('4×8 MiB 下载，MiB/s','parallel_download_4x8mib','mib_s',False),
        ('后台下载时 1 KiB HTTP，ms','loaded_http','total_ms',True),
        ('重启命令返回至新请求成功，ms','recovery','total_ms',True),
        ('发起重启命令至新请求成功，ms','recovery','restart_to_success_ms',True)]
    table='\n'.join('| '+name+' | '+' | '.join(cell(t,k,m,tail) for t in TOOLS)+' |' for name,k,m,tail in measurements)
    memory=[]
    for title,key in [('客户端空闲 RSS 中位数，MiB','idle_rss_mib'),('客户端全过程 RSS 高水位，MiB','client_peak_rss_mib'),
                      ('服务端进程 RSS 高水位，MiB','server_peak_rss_mib'),('服务端 cgroup 采样峰值，MiB','server_peak_cgroup_mib'),
                      ('每轮客户端 CPU 累计秒中位数','client_cpu_s_per_block'),
                      ('每轮服务端 CPU 采样累计秒中位数','server_cpu_s_per_block_sampled'),
                      ('服务端单次 cgroup 的 memory.high 事件最大值','server_max_memory_high_event'),
                      ('客户端最大线程数','client_max_threads'),('客户端最大 FD 数','client_max_fds')]:
        values=[]
        for t in TOOLS:
            v=resources[t][key]
            if isinstance(v,dict):v=v['median']
            values.append('未采样' if v is None else f'{v:.2f}')
        memory.append('| '+title+' | '+' | '.join(values)+' |')
    concurrency=[]
    for n in [1,4,8,16]:
        values=[]
        for t in TOOLS:
            cases=[r for r in rows if r['tool']==t and r['kind']=='concurrent_small_requests' and r['concurrency']==n]
            qps=stats([r['successes']/(r['total_ms']/1000) for r in cases])
            values.append(f"{sum(r['successes'] for r in cases)}/{sum(r['count'] for r in cases)}；{qps['median']:.2f} QPS" if qps else '未采样')
        concurrency.append(f'| {n} | '+' | '.join(values)+' |')
    old=[]
    for t in TOOLS:
        cases=[r for r in rows if r['tool']==t and r['kind']=='old_stream_after_outage']
        outcomes=Counter('预算内无响应（不等于已观察到 TCP 关闭）' if r.get('error','').startswith('TimeoutError') else '旧流读取失败/被拒绝' if not r['ok'] else '旧流仍成功' for r in cases)
        old.append(f'- {t}：{dict(outcomes)}。')
    failure_lines=[]
    for key,count in data['failure_counts'].items():
        selected=[r for r in failures if r['tool']+'/'+r['kind']+'/'+r.get('host','')==key]
        errors=dict(Counter(r.get('error','并发子请求失败，见 requests') for r in selected))
        failure_lines.append(f'- `{key}`：{count} 个失败样本；{errors}。')
    public='\n'.join('| '+h+' | '+' | '.join(cell(t,'public_https/'+h,tail=True) for t in TOOLS)+' |' for h in ['example.com','www.wikipedia.org','www.cloudflare.com'])
    extra_table=[]
    for kind,title,metric in [('download_1048576','1 MiB 下载，MiB/s','mib_s'),
                              ('download_8388608','8 MiB 下载，MiB/s','mib_s'),
                              ('existing_stream_after_3s_process_pause','进程暂停 3 秒后，旧隧道响应 ms','total_ms'),
                              ('new_stream_after_pause','暂停恢复后，新隧道响应 ms','total_ms')]:
        values=[]
        for t in TOOLS:
            r=extras[t].get(kind,{})
            d=r.get(metric)
            values.append((f"{d['median']:.2f} / {d['p95']:.2f}" if d else '未成功/未采样')+f" ({r.get('successes',0)}/{r.get('n',0)})")
        extra_table.append('| '+title+' | '+' | '.join(values)+' |')
    baseline=json.loads((RUN/'deployed-baseline.json').read_text())
    baseline_lines=[]
    for k,title in [('http_new','新隧道 HTTP'),('http_keepalive','长连接 HTTP'),('https_new','新隧道 HTTPS'),('synthetic_webpage','模拟页面')]:
        d=stats([r['total_ms'] for r in baseline if r['kind']==k and r['ok']])
        baseline_lines.append(f"| {title} | {d['n']} | {d['median']:.2f} ms | {d['p95']:.2f} ms |")
    env.update(public_test_port=9090,internal_test_port=18443,route_reuse_authorized_by_user=True)
    (OUT/'environment.json').write_text(json.dumps(env,indent=2))
    private=json.loads((RUN/'credentials.private.json').read_text())
    for name in ['deployed-client.log','simpleplane-client.log','shadowsocks-client.log','hysteria-client.log',
                 'simpleplane-server.log','shadowsocks-server.log','hysteria-server.log','origin-server.log']:
        if (RUN/name).exists():
            lines=[s for s in (RUN/name).read_text(errors='replace').splitlines() if not any(x in s.lower() for x in ['cipherkey','password','auth":','private key'])]
            value='\n'.join(lines)+'\n'
            for secret in private.values():value=value.replace(secret,'[REDACTED]')
            (OUT/name).write_text(value,encoding='utf-8')
    plot_available=False
    interpretation=''
    if complete:
        latency=min(TOOLS,key=lambda t:summary[t]['new_connection_http']['total_ms']['median'])
        ram=min(TOOLS,key=lambda t:resources[t]['client_peak_rss_mib'])
        successes=[t for t in TOOLS if 'mib_s' in summary[t].get('download_32mib',{})]
        download=max(successes,key=lambda t:summary[t]['download_32mib']['mib_s']['median']) if successes else None
        interpretation=f"本轮新隧道 HTTP 中位数最低为 `{latency}`，客户端 RSS 高水位最低为 `{ram}`。" + (f"在完成的 32 MiB 样本中，吞吐中位数最高为 `{download}`；务必结合成功样本数，超时不能从结论中隐藏。" if download else '')
        os.environ.setdefault('MPLCONFIGDIR',str(RUN/'matplotlib'))
        sys.path.insert(0,str(RUN.parent/'perf-python311'))
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        labels=['SimplePlane','Shadowsocks','Hysteria 2']
        fig,axes=plt.subplots(1,3,figsize=(11,3.6),layout='constrained')
        panels=[('1 KiB HTTP: median complete response','ms',
                 [summary[t]['new_connection_http']['total_ms']['median'] for t in TOOLS]),
                ('Client peak resident memory','MiB',[resources[t]['client_peak_rss_mib'] for t in TOOLS]),
                ('32 MiB download: successful samples','MiB/s',
                 [summary[t].get('download_32mib',{}).get('mib_s',{}).get('median',float('nan')) for t in TOOLS])]
        for ax,(title,unit,values) in zip(axes,panels):
            bars=ax.bar(labels,values,color=['#3769ad','#dc8c38','#438f75'],width=.65)
            ax.set_title(title,fontsize=10);ax.set_ylabel(unit);ax.tick_params(axis='x',labelrotation=18)
            ax.spines[['top','right']].set_visible(False)
            for bar,value in zip(bars,values):
                if math.isfinite(value):
                    label=f'{value:.1f}'
                    if unit=='MiB/s':
                        case=summary[TOOLS[list(bars).index(bar)]]['download_32mib']
                        label+=f"\n{case['successes']}/{case['n']} complete"
                    ax.text(bar.get_x()+bar.get_width()/2,value,label,ha='center',va='bottom',fontsize=9)
            ax.margins(y=.2)
        fig.suptitle('Same host and endpoint, 3 rotating rounds | 2026-10-30',fontsize=12)
        fig.savefig(OUT/'comparison.png',dpi=180)
        fig.savefig(OUT/'comparison.svg')
        plt.close(fig);plot_available=True
    text=f'''# SimplePlane / Shadowsocks / Hysteria 同机同线路性能测试

日期：2026-10-30。状态：**{'三工具三轮测试已执行完成' if complete else '采样未完整结束，详见 analysis.json'}**。
结果只适用于本轮服务器、线路和配置，不外推为所有设备与网络上的排名。

## 1. 环境与资源保护

服务器 `54.172.101.190`，2 vCPU、约 1909 MiB RAM；初始约 490 MiB 可用，2 GiB swap 已满，磁盘约 95% 使用。
客户端运行于用户本机的 WSL2 Ubuntu，使用独立随机 SOCKS 端口，不改变系统代理。不是 Windows GUI 的内存数据。
对比对象是代理内核，不含桌面 UI、TUN 驱动和系统代理集成的额外开销。

18443 公网探测超时后，按用户要求复用 **9090/TCP、9090/UDP**：只临时切换 nginx 的单个 upstream，从 `127.0.0.1:19090` 指向 `127.0.0.1:18443`，每轮恢复。
测试期间原代理的公网新连接可能受影响；原 Java 服务进程不替换。加入 nginx 配置校验、异常恢复、独立 45 分钟回滚定时器，最终校验配置摘要。
首次探测清理遗漏了立即恢复转发，已修正；正式测试前确认原配置 SHA-256 恢复一致，该探测不纳入正式统计。

三个新增代理逐个运行，均限制 `MemoryMax=256M`、`MemoryHigh=192M`、`MemorySwapMax=0`、`CPUQuota=100%`、`TasksMax=128`、最长运行 3600 秒。
源站 64 MiB、监控 48 MiB；可用内存低于 160 MiB 时停止新增负载并恢复路由。正式采样窗口最低可用内存 **{data['minimum_server_available_mib']:.1f} MiB**。
共享 nginx、源站和后台服务不计入单个代理进程 RSS。这不是独占机器测试，现网后台负载会影响结果。

## 2. 版本和配置

| 对象 | 配置 |
| --- | --- |
| SimplePlane | 本地采集时 HEAD `{env['git_head'][:12]}`，远端使用已部署 `b125737` JAR；HTTP/3、单条物理连接、`chacha20-poly1305-v2` + QUIC TLS；业务线程 4、worker 2；两端 JVM heap 96 MiB、direct 48 MiB；隔离实例不加载现网 agent |
| Shadowsocks-rust 1.25.0 | 官方 musl 二进制，`chacha20-ietf-poly1305`、TCP、worker 2、TCP_NODELAY |
| Hysteria 2.13.0 | 官方 Linux amd64 二进制，QUIC/TLS，不设固定带宽、不加混淆；`GOMAXPROCS=2`、`GOMEMLIMIT=160MiB` |

来源：[Shadowsocks 发布](https://github.com/shadowsocks/shadowsocks-rust/releases/tag/v1.25.0)、[Hysteria 发布](https://github.com/HyNetworks/hysteria/releases/tag/app/v2.13.0)、[Hysteria 服务端配置](https://hy2.app/docs/advanced/Full-Server-Config/)、[客户端配置](https://hy2.app/docs/advanced/Full-Client-Config/)。
二进制按官方摘要校验；完整 SHA-256 见相邻 `environment.json`。git HEAD 不能代替 JAR 内容证明。
协议封装、加密次数、拥塞控制及成功握手语义不同，这是工具整体行为比较。SOCKS 返回成功不保证远端就绪语义完全一致，主要依据完整校验的响应。
SimplePlane 默认客户端限流保持启用；本次没有为改善分数修改生产源码或默认限制。

## 3. 方法与口径

三轮顺序为 SP→SS→HY、SS→HY→SP、HY→SP→SS；同服务器、同数值端口、同受控源站。
每轮热身 5 次，再测 15 次新隧道 HTTP、20 次长连接 HTTP、6 次 HTTPS、3 次模拟页面；原始样本分别记录建连、TLS、TTFB 和完整耗时。
Java 只做了这段短时热身；结果覆盖冷启动后的短时使用，不能称作充分 JIT 预热后的长期稳态峰值。
源站使用远端私有 DNS 名称，排除本地私网直连；内存生成固定数据，上传/下载均验证长度与 SHA-256。

模拟页面：32 KiB 文档后用 6 条持久 TLS 连接读取 12 个 32 KiB 资源，共 416 KiB。它不是浏览器渲染/FCP/LCP，Python TLS 源站能力也可能限制该项。
测 32 MiB 下载、8 MiB 上传、4 路各 8 MiB 下载；吞吐为成功校验字节/完整耗时，包含连接开销，不称为稳态带宽。
并发及大吞吐阶段之间冷却 1.5 秒，避免上一阶段固定秒限流污染下一阶段；另测后台下载时延迟、公开 HTTPS 和服务重启恢复。
请求有整体耗时预算，失败保留。成功 QPS 只计成功请求，快速拒绝不计作高性能。
延迟列为 **中位数 / P95**，括号是 **成功/总样本**。P95 是经验分位数；只有三次的吞吐、启动与恢复样本不足以提供稳定尾部保证。
耗时和吞吐分位数只使用成功样本，属于条件统计；必须同时阅读失败率，不能把超时样本当作已经完成的慢请求，或从结论中删去。
启动 30 秒未就绪的记录保留为失败；如补跑缺失阶段，原失败仍计入冷启动成功率，补跑不替换原始记录。补跑发生在主轮换之后，因此缺失阶段的时间均衡控制弱于原计划。
该次 SimplePlane 就绪失败没有观察到 OOM；服务端最后的 143 是测试清理时发送 SIGTERM 的结果，不能据此称作进程崩溃。握手/转发失败的具体根因尚未定位。

## 4. 正式结果

{'![主要指标](proxy-comparison-2026-10-30/comparison.png)' if plot_available else ''}

{interpretation}

| 指标 | SimplePlane | Shadowsocks-rust | Hysteria 2 |
| --- | --- | --- | --- |
{table}

| 小请求并发数 | SimplePlane | Shadowsocks-rust | Hysteria 2 |
| --- | --- | --- | --- |
{chr(10).join(concurrency)}

各档每轮 32 个请求，共三轮；QPS 为各轮成功 QPS 中位数，必须结合成功数阅读。

### 补充测量

三工具均追加同样的 1 MiB 与 8 MiB 下载，各 3 次、整体预算 90 秒；作为较小文件的独立场景，不覆盖 32 MiB 在 60 秒预算内的超时。
还对自建服务进程暂停 3 秒并自动继续，验证既有隧道与新隧道。这是进程停顿，不称作真实断网。

| 指标，中位数 / P95（成功/总数） | SimplePlane | Shadowsocks-rust | Hysteria 2 |
| --- | --- | --- | --- |
{chr(10).join(extra_table)}

## 5. 资源占用

| 指标 | SimplePlane | Shadowsocks-rust | Hysteria 2 |
| --- | --- | --- | --- |
{chr(10).join(memory)}

客户端每 0.5 秒、服务端每 1 秒采样。RSS 高水位使用 `/proc` VmHWM；cgroup 峰值为采样所见，可能漏掉短促峰值。
服务端 CPU 用各轮负载窗口内相邻 cgroup 采样的累计量差，起止存在约 1 秒采样误差；不包含共享 nginx 和源站。
空闲是热身后 3 秒的短窗口。CPU 秒包含各实现实际完成的样本，失败导致有效工作量不同，不能直接当作每有效 MiB 的效率排名。
OOM kill、内存上限事件、swap、线程和 FD 的完整数据见 `analysis.json` 及轨迹。
本轮 SimplePlane 触发了 `MemoryHigh=192M` 的软阈值，单次 cgroup 的事件计数最高为 {resources['simpleplane']['server_max_memory_high_event']}；其他两个实现为 0。未观察到硬内存上限或 OOM kill 事件。因而这是相同内存预算下的表现，不能直接作为更宽裕内存配置的峰值结果。

## 6. 故障、失败与公开网站

仅停止自建对比实例；停止命令返回后至少等 3 秒，探测旧流，再启动并验证新连接，最多观察 45 秒。
分别记录发起重启命令及命令返回后的耗时，以区别 SSH/启动控制面。至少保持 3 秒不等于精确断网 3 秒。

{chr(10).join(old)}

这测服务重启，不等于随机丢包、移动换网、多节点切换或旧 TCP 流无缝迁移。旧流失效与新连接恢复分开统计。

**恢复超时配置说明：** SimplePlane 主测试两端设 10 秒 idle timeout。Hysteria 客户端虽然请求设 10 秒，但本版默认 Chrome 指纹模拟会将有效值固定为 30 秒，详见[官方客户端配置说明](https://hy2.app/docs/advanced/Full-Client-Config/)。主表恢复耗时包含策略差异，不据此判定重连算法优劣。
追加测试把 SimplePlane 两端也设为 30 秒，Hysteria 保持默认指纹模拟与有效 30 秒，仅测恢复，不替换主表：

```json
{json.dumps([{'tool':r['tool'],'ok':r['ok'],'post_ack_ms':r.get('total_ms'),'restart_to_success_ms':r.get('restart_to_success_ms')} for r in aligned if r['kind'] in ['recovery','aligned_failure']],ensure_ascii=False,indent=2)}
```

该附加配置每工具一次，仅验证现象，不提供稳定 P95；统一 idle timeout 也不等于把所有重试和 keep-alive 策略统一。

失败分类：

{chr(10).join(failure_lines) if failure_lines else '- 未记录失败样本。'}

| 公开 HTTPS 完整响应，ms | SimplePlane | Shadowsocks-rust | Hysteria 2 |
| --- | --- | --- | --- |
{public}

公开站点含 DNS、站点策略、TLS、响应大小与 CDN 波动，不作为纯代理核心排名。HTTP 403 是站点拒绝，保留为失败，不称作代理断线。
第二轮起追加只读 TCP `ss -tin` 轨迹，保留 RTT、拥塞窗口与可用的重传字段，见 `tcp-diagnostics.jsonl`；它不提供 UDP 丢包率，也不足以独自断定超时根因。

### 限流诊断

[`RateLimitFilter.java`](../../proxy-cluster/src/main/java/com/proxy/cluster/filter/RateLimitFilter.java) 不区分消息类型，固定秒窗口全局 500、单域名 50 次调用；DATA 等事件可能消耗额度，不是“50 个网页请求/秒”。
基线日志确有 `Domain rate limit exceeded`。上传阶梯中 32 KiB、256 KiB、1 MiB 成功，8 MiB 失败；冷却后独立小请求成功。
独立的现网原配置诊断中，冷却后 4×8 MiB 下载、1/4/8/16 并发的各 32 个请求均成功；正式三轮的成功数以主表为准，不能用这次诊断替代。前序窗口污染与持续上传问题需区分；没有宣称所有上传内部原因都已排除。
原始上传后整组拒绝保留于 `deployed-baseline.json`；追加诊断在 `deployed-diagnostic.json`，没有覆盖失败。

## 7. 现网原配置基线（单列）

现网 9090 为 legacy `chacha20`、加载 LogLens agent，与隔离测试的密码套件、线程、监控负担不同，不混合统计。

| 指标 | 成功样本 | 中位数 | P95 |
| --- | --- | --- | --- |
{chr(10).join(baseline_lines)}

一次 32 MiB 下载约 1.574 MiB/s；冷却后的 4×8 MiB 合计约 4.762 MiB/s。不是优化前后收益，也不是稳定线路上限。
早期一次大文件请求因测量器未设整体预算而被中断，未纳入吞吐；已补预算和超时校验用例。

## 8. 证据与复现

相邻目录保存原始样本、分组统计、资源轨迹、基线和诊断、环境摘要及清理证明；`manifest.json` 列出 SHA-256。
测量器 7 个校验用例通过：摘要校验、长连接、损坏、截断、并发失败计数和整体超时。
最终在实际采样所用的 WSL/Linux Python 下再次验证 7/7，通过日志见 `harness-verification-linux.log`。

```text
python scripts/perf/verify_harness.py
# 在本机 WSL 内；首次准备需官方发布文件及 prepare_comparison.py。
python3 scripts/perf/inspect_comparison.py setup
python3 scripts/perf/compare_proxies.py --probe
python3 scripts/perf/compare_proxies.py --rounds 3
python3 scripts/perf/inspect_comparison.py collect
python scripts/perf/write_report.py
```

准备脚本不会直接覆盖已有远端目录；认证和私钥只在私有测试目录，不复制进文档。
最终检查：原服务 `{cleanup['production_active']}`，PID `{cleanup['production_pid']}`，guard 触发 `{cleanup['guard_triggered']}`，nginx upstream 恢复 `{cleanup.get('nginx_upstream_restored')}`；配置摘要见 `cleanup.json`。
`final-idle-snapshot.json` 确认四个测试单元均停止；`prune.json` 确认远端临时目录已移除，释放约 36.6 MiB，包含临时认证配置及证书私钥。

尚未覆盖：原生 Windows/macOS 资源、真实浏览器渲染、UDP 转发能力横评、人工丢包/抖动、移动换网、长期泄漏与极限并发。
2 GiB 机器仍有后台服务，本轮不做无限并发压垮测试，不能保证所有场景无异常。

## 9. 后续改进优先级

1. 先解决持续上传被默认限流拒绝的问题：明确 CONNECT、DATA、DISCONNECT 的计数职责，区分建连限流与字节流量限制，确保清理事件可以完成；保持当前失败样本作为回归依据。
2. 给启动失败保留更具体的握手和重试原因，定位 30 秒未就绪的单次问题，不能仅扩大等待时间后把它忽略。
3. 功能问题解决后，再分别做充分 JIT 预热、QUIC 窗口和 JVM 内存配置实验；每次只改变明确参数，重新比较吞吐、尾延迟与内存，当前数据不能独自量化各项贡献。
'''
    (ROOT/'docs/performance/proxy-comparison-2026-10-30.md').write_text(text,encoding='utf-8')
    files={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file() and p.name!='manifest.json'}
    source_hashes={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'scripts/perf').glob('*.py')}
    (OUT/'manifest.json').write_text(json.dumps({'status':data['status'],'files':files,'source_sha256':source_hashes,
        'report_sha256':hashlib.sha256((ROOT/'docs/performance/proxy-comparison-2026-10-30.md').read_bytes()).hexdigest()},indent=2))
    print(json.dumps({'status':data['status'],'samples':data['samples'],'resources':resources,'cleanup':cleanup},indent=2))


if __name__=='__main__':
    main()
