(() => {
    const root = document.querySelector(".log-workspace");
    if (!root) return;
    const endpoint = root.dataset.endpoint || "/admin/logs";
    const $ = id => document.getElementById(id);
    const form = $("log-query");
    const selected = new Map();
    const names = {
        SESSION_OPENED: "发起会话", SESSION_ACTIVE: "连接成功", SESSION_CLOSED: "会话关闭",
        SESSION_CONNECT_FAILED: "连接失败"
    };
    const time = new Intl.DateTimeFormat("zh-CN", {dateStyle: "short", timeStyle: "medium"});
    let dataset = "connection", cursor, activeQuery, range, charts, showNodes = false;
    let request, userRequest, userTimer;

    function element(tag, text, className) {
        const node = document.createElement(tag);
        if (text !== undefined) node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function button(text, run, className = "ghost") {
        const node = element("button", text, className);
        node.type = "button";
        node.addEventListener("click", run);
        return node;
    }

    function renderSelection() {
        const container = $("log-selection");
        container.replaceChildren(...Array.from(selected, ([id, name]) => button(name + " ×", () => {
            selected.delete(id);
            renderSelection();
        })));
        container.hidden = selected.size === 0;
    }

    function hideOptions() { $("log-user-options").hidden = true; }

    async function lookup(query) {
        userRequest?.abort();
        userRequest = new AbortController();
        try {
            const response = await fetch(endpoint + "/users?q=" + encodeURIComponent(query), {signal: userRequest.signal});
            if (!response.ok || response.redirected) return;
            const users = await response.json();
            const list = $("log-user-options");
            list.replaceChildren(...users.map(user => {
                const item = element("li");
                const label = user.displayName ? `${user.username} · ${user.displayName}` : user.username;
                item.append(button(label, () => {
                    if (selected.size < 10 || selected.has(user.subject)) selected.set(user.subject, label);
                    form.elements.unknown.checked = false;
                    $("log-user").value = "";
                    hideOptions();
                    renderSelection();
                    $("log-user").focus();
                }));
                return item;
            }));
            if (users.length === 0) list.append(element("li", "没有匹配用户", "hint"));
            list.hidden = false;
        } catch (error) {
            if (error.name !== "AbortError") hideOptions();
        }
    }

    function currentQuery() {
        const data = new FormData(form);
        const to = data.get("range") === "custom" ? Date.parse(data.get("to")) : Date.now();
        const from = data.get("range") === "custom" ? Date.parse(data.get("from")) : to - Number(data.get("range")) * 3600000;
        const query = {
            dataset, from: range?.from ?? from, to: range?.to ?? to, subjects: [...selected.keys()],
            unknown: data.has("unknown"), node: data.get("node"), device: data.get("device"), session: data.get("session")
        };
        if (dataset === "connection") {
            for (const key of ["target", "transport", "outcome", "connection", "stream"]) query[key] = data.get(key);
            query.subdomains = data.has("subdomains");
            query.port = data.get("port") ? Number(data.get("port")) : null;
        } else {
            for (const key of ["text", "mode", "level", "trace", "logger", "exceptionType"]) query[key] = data.get(key);
            query.relevance = data.has("relevance");
        }
        return query;
    }

    async function search(query, signal) {
        const reply = await fetch(endpoint + "/search", {
            method: "POST", headers: {"Content-Type": "application/json", [root.dataset.csrfHeader]: root.dataset.csrf},
            body: JSON.stringify(query), signal
        });
        if (!reply.ok || reply.redirected) {
            const messages = {
                400: "请检查筛选条件。拼写容错和布尔查询最多查询 24 小时。",
                401: "登录已过期，请重新登录。", 403: "日志查询权限已失效。",
                410: "翻页已超时，请重新查询。", 429: "查询较多，请稍后重试。",
                503: "日志存储暂不可用，请稍后重试。", 504: "查询超时，请缩小时间范围。"
            };
            throw new Error(reply.redirected ? messages[401] : (messages[reply.status] || "查询未完成，请稍后重试。"));
        }
        return reply.json();
    }

    function number(value) { return Number(value ?? 0).toLocaleString("zh-CN"); }

    function bytes(value) {
        if (!value) return "0 B";
        const index = Math.min(4, Math.floor(Math.log(value) / Math.log(1024)));
        return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${["B", "KiB", "MiB", "GiB", "TiB"][index]}`;
    }

    function status(text, error = false) {
        $("log-status").textContent = text;
        $("log-status").toggleAttribute("data-error", error);
    }

    function metrics() {
        const container = $("log-metrics");
        if (!charts) { container.hidden = true; return; }
        const total = charts.dial?.doc_count ?? charts.trend.buckets.reduce((sum, item) => sum + item.doc_count, 0);
        const fail = charts.dial?.results.buckets.fail.doc_count ?? 0;
        const values = dataset === "application" ? [["匹配日志", number(total)]] : [
            ["拨号结果", number(total)], ["连接失败", number(fail)], ["失败率", total ? (100 * fail / total).toFixed(1) + "%" : "—"],
            ["已结束会话上行", bytes(charts.closed.up.value)], ["已结束会话下行", bytes(charts.closed.down.value)]
        ];
        container.replaceChildren(...values.map(([label, count]) => {
            const item = element("div", undefined, "log-metric");
            item.append(element("span", label), element("strong", count));
            return item;
        }));
        container.hidden = false;
    }

    function trend() {
        const bars = $("log-trend");
        const slots = charts.trend.buckets;
        const segments = slots.map(slot => slot.results ?
            [slot.results.buckets.ok.doc_count, slot.results.buckets.fail.doc_count] : [slot.doc_count, 0]);
        const max = Math.max(1, ...segments.map(([ok, fail]) => ok + fail));
        const span = slots.length > 1 ? slots[1].key - slots[0].key : activeQuery.to - activeQuery.from;
        bars.replaceChildren(...slots.map((slot, i) => {
            const [ok, fail] = segments[i];
            const select = button(undefined, () => {
                range = {from: Math.max(activeQuery.from, slot.key), to: Math.min(activeQuery.to, slot.key + span)};
                run();
            });
            const title = dataset === "connection" ? `${time.format(slot.key)}，成功 ${ok}，失败 ${fail}` : `${time.format(slot.key)}，日志 ${ok}`;
            select.title = title;
            select.setAttribute("aria-label", title);
            const good = element("span", undefined, "log-ok");
            const bad = element("span", undefined, "log-fail");
            good.style.height = 100 * ok / max + "%";
            bad.style.height = 100 * fail / max + "%";
            select.append(good, bad);
            return select;
        }));
        $("log-chart-key").textContent = dataset === "connection" ? "绿色 成功 · 红色 失败" : "按日志条数";
        $("log-time-labels").replaceChildren(
            element("span", time.format(activeQuery.from)), element("span", time.format(activeQuery.to)));
    }

    function leaders() {
        const source = charts.dial || charts;
        $("log-leader-title").textContent = showNodes ? "节点排行" : dataset === "connection" ? "目标域名排行" : "日志模板排行";
        $("log-show-nodes").textContent = showNodes ? "返回排行" : "节点排行";
        $("log-leaders").replaceChildren(...source[showNodes ? "nodes" : "leaders"].buckets.map(row => {
            const item = element("li");
            const bar = button(undefined, () => {
                if (showNodes) {
                    const select = form.elements.node;
                    if (![...select.options].some(option => option.value === row.key)) select.add(new Option(row.key, row.key));
                    select.value = row.key;
                } else if (dataset === "connection") {
                    form.elements.target.value = row.key;
                    form.elements.subdomains.checked = false;
                }
                run();
            }, "log-ranked");
            bar.append(element("span", row.key), element("strong", number(row.doc_count)));
            if (!showNodes && dataset === "application") bar.disabled = true;
            item.append(bar);
            return item;
        }));
    }

    function appendMessage(parent, message, query) {
        const words = query?.text?.match(/[\p{L}\p{N}_-]+/gu)?.slice(0, 20) ?? [];
        const escaped = words.map(word => word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
        const matcher = escaped.length ? new RegExp(escaped.join("|"), "giu") : null;
        if (!matcher) { parent.textContent = message; return; }
        let end = 0;
        for (const match of message.matchAll(matcher)) {
            parent.append(message.slice(end, match.index), element("mark", match[0]));
            end = match.index + match[0].length;
        }
        parent.append(message.slice(end));
    }

    function addRows(rows) {
        const columns = ["时间", "节点 / 用户归属", dataset === "connection" ? "目标 / 事件" : "级别 / 正文", "上下文"];
        const head = element("tr");
        head.append(...columns.map(label => element("th", label)));
        $("log-columns").replaceChildren(head);
        for (const row of rows) {
            const tr = element("tr");
            const owner = element("td", row.node_id);
            const account = element("small", row.account_id ? "账号 " + row.account_id.slice(0, 12) : "归属未知");
            if (row.device_id) account.title = "设备 " + row.device_id;
            owner.append(account);
            const content = element("td");
            if (row.dataset === "connection") {
                content.textContent = [row.target_host, row.target_port].filter(Boolean).join(" · ") || "—";
                content.append(element("small", [names[row.event_kind] || row.event_kind, row.transport, row.reason].filter(Boolean).join(" · ")));
            } else {
                content.append(element("small", [row.level, row.exception_type || row.logger].filter(Boolean).join(" · ")));
                const message = element("p", undefined, "log-content");
                appendMessage(message, row.message || "", activeQuery);
                content.append(message);
            }
            const action = element("td");
            if (row.proxy_session_id) action.append(button("会话", () => detail(row), "ghost log-compact"));
            tr.append(element("td", time.format(row["@timestamp"])), owner, content, action);
            $("log-rows").append(tr);
        }
    }

    async function detail(row) {
        const modal = $("log-session");
        const area = $("log-session-content");
        $("log-session-id").textContent = row.proxy_session_id;
        area.textContent = "正在查询会话…";
        modal.showModal();
        const at = row["@timestamp"];
        const query = {dataset: "all", from: Math.max(0, at - 7 * 86400000), to: Math.min(Date.now(), at + 7 * 86400000),
            node: row.node_id, session: row.proxy_session_id, limit: 100};
        const load = async () => {
            try {
                const result = await search(query);
                if (!modal.open || $("log-session-id").textContent !== row.proxy_session_id) return;
                if (!query.cursor) area.replaceChildren();
                for (const event of result.rows) {
                    const item = element("article", undefined, "log-session-event");
                    item.append(element("small", time.format(event["@timestamp"])),
                        element("strong", names[event.event_kind] || event.level || "日志"));
                    if (event.message) item.append(element("p", event.message));
                    if (event.reason) item.append(element("p", event.reason));
                    if (event.event_kind === "SESSION_CLOSED") item.append(element("p", `上行 ${bytes(event.uplink_bytes)} · 下行 ${bytes(event.downlink_bytes)}`));
                    area.append(item);
                }
                if (!result.rows.length && !query.cursor) area.textContent = "未找到其他已索引事件。";
                query.cursor = result.cursor;
                if (result.cursor) area.append(button("加载更多事件", event => {
                    event.currentTarget.remove();
                    load();
                }));
            } catch (error) { area.textContent = error.message; }
        };
        await load();
    }

    async function run(more = false) {
        if (more && !cursor) return;
        request?.abort();
        const sending = new AbortController();
        request = sending;
        $("log-more").disabled = true;
        status("正在查询…");
        if (!more) {
            cursor = null;
            charts = null;
            $("log-empty").hidden = true;
            $("log-charts").hidden = true;
            $("log-metrics").hidden = true;
            $("log-more").hidden = true;
            $("log-rows").replaceChildren();
            activeQuery = currentQuery();
        }
        $("log-clear-time").hidden = !range;
        try {
            const reply = await search({...activeQuery, cursor: more ? cursor : null}, sending.signal);
            if (sending !== request) return;
            cursor = reply.cursor;
            if (!more) {
                charts = reply.charts;
                $("log-charts").hidden = !charts;
                if (charts) { metrics(); trend(); leaders(); }
                $("log-empty").hidden = reply.rows.length > 0;
            }
            addRows(reply.rows);
            status(`已显示 ${number($("log-rows").rows.length)} 条 · 时间按浏览器时区`);
        } catch (error) {
            if (error.name !== "AbortError" && sending === request) status(error.message, true);
        } finally {
            if (sending === request) {
                $("log-more").hidden = !cursor;
                $("log-more").disabled = false;
            }
        }
    }

    root.querySelectorAll("[data-dataset]").forEach(control => control.addEventListener("click", () => {
        dataset = control.dataset.dataset;
        showNodes = false;
        root.querySelectorAll("[data-dataset]").forEach(mode => mode.setAttribute("aria-pressed", String(mode === control)));
        form.querySelectorAll("[data-view]").forEach(field => field.hidden = field.dataset.view !== dataset);
        run();
    }));
    $("log-user").addEventListener("input", event => {
        clearTimeout(userTimer);
        userRequest?.abort();
        hideOptions();
        const value = event.target.value.trim();
        if (value) userTimer = setTimeout(() => lookup(value), 250);
    });
    root.addEventListener("keydown", event => { if (event.key === "Escape") hideOptions(); });
    document.addEventListener("click", event => {
        if (!event.target.closest(".log-user-filter")) hideOptions();
    });
    form.elements.unknown.addEventListener("change", () => {
        if (form.elements.unknown.checked) { selected.clear(); renderSelection(); }
    });
    form.elements.range.addEventListener("change", () => $("log-custom").hidden = form.elements.range.value !== "custom");
    form.addEventListener("submit", event => { event.preventDefault(); range = null; run(); });
    $("log-more").addEventListener("click", () => run(true));
    $("log-clear-time").addEventListener("click", () => { range = null; run(); });
    $("log-show-nodes").addEventListener("click", () => { showNodes = !showNodes; if (charts) leaders(); });
    if (root.dataset.subject) selected.set(root.dataset.subject, root.dataset.username || root.dataset.subject);
    renderSelection();
    run();
})();
