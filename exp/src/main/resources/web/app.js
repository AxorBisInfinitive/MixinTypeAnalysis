"use strict";

const $ = selector => document.querySelector(selector);
const labels = { DRAFT: "草稿", CONFIRMED: "已确认", COMPLETED: "已完成", CANCELLED: "已取消" };
const reasons = { RESTOCK: "商品入库", ORDER_CONFIRMED: "订单出库", ORDER_CANCELLED: "取消返还" };
const pages = {
    orders: { title: "订单管理", list: "订单列表", create: "＋ 新建订单", placeholder: "查询订单编号或客户" },
    products: { title: "商品管理", list: "商品列表", create: "＋ 新增商品", placeholder: "查询商品编号或名称" },
    inventory: { title: "库存管理", list: "当前库存", create: "", placeholder: "查询商品编号或名称" },
    movements: { title: "库存流水", list: "变动记录", create: "", placeholder: "" }
};
const state = { page: "orders", products: [], orders: [], movements: [] };
const integer = new Intl.NumberFormat("zh-CN");
let loadSequence = 0;
let toastTimer;

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, character => ({
        "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[character]);
}

function cents(value) {
    const [whole, fraction = ""] = String(value ?? "0").split(".");
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0").slice(0, 2));
}

function moneyFromCents(value) {
    return `¥ ${integer.format(value / 100n)}.${(value % 100n).toString().padStart(2, "0")}`;
}

function money(value) {
    return moneyFromCents(cents(value));
}

function date(value) {
    return new Date(value).toLocaleString("zh-CN", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false
    });
}

function badge(status) {
    return `<span class="status status-${status.toLowerCase()}">${labels[status]}</span>`;
}

async function api(path, method = "GET", body) {
    const options = { method, headers: {} };
    if (body !== undefined) {
        options.headers["Content-Type"] = "application/json";
        options.body = JSON.stringify(body);
    }
    let response;
    try {
        response = await fetch(`/api${path}`, options);
    } catch (error) {
        throw new Error("无法连接服务，请确认应用正在运行");
    }
    const result = await response.json();
    if (!response.ok) {
        throw new Error(result.error || "操作失败，请重试");
    }
    return result;
}

function toast(message) {
    clearTimeout(toastTimer);
    $("#toast").textContent = message;
    $("#toast").hidden = false;
    toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 3500);
}

async function loadData() {
    const sequence = ++loadSequence;
    try {
        const [products, orders, movements] = await Promise.all([
            api("/products"), api("/orders"), api("/movements")
        ]);
        if (sequence !== loadSequence) return;
        Object.assign(state, { products, orders, movements });
        $("#connection-status").textContent = "本地数据 · 自动保存";
        updateProductFilter();
        renderPage();
    } catch (error) {
        if (sequence !== loadSequence) return;
        $("#connection-status").textContent = "连接失败";
        $("#content").innerHTML = `<div class="empty-state error-state"><h3>数据加载失败</h3><p>${escapeHtml(error.message)}</p></div>`;
        $("#record-count").textContent = "";
        throw error;
    }
}

function metric(label, value, isMoney = false) {
    return `<div class="metric"><p class="metric-label">${label}</p><strong class="metric-value${isMoney ? " money" : ""}">${value}</strong></div>`;
}

function table(headings, rows) {
    return `<table><thead><tr>${headings.map(heading => `<th>${heading}</th>`).join("")}</tr></thead><tbody>${rows.join("")}</tbody></table>`;
}

function empty(title, description, action) {
    let button = "";
    if (action) button = `<button class="button primary" data-action="${action}">${pages[state.page].create}</button>`;
    return `<div class="empty-state"><div class="empty-symbol" aria-hidden="true">▤</div><h3>${title}</h3><p>${description}</p>${button}</div>`;
}

function renderPage() {
    const page = pages[state.page];
    $("#page-title").textContent = page.title;
    $("#panel-title").textContent = page.list;
    $("#create-button").hidden = !page.create;
    $("#create-button").textContent = page.create;
    $("#search").placeholder = page.placeholder;
    $("#search").hidden = state.page === "movements";
    $("#status-filter").hidden = state.page !== "orders";
    $("#product-filter").hidden = state.page !== "movements";
    document.querySelectorAll("nav a").forEach(link => {
        if (link.dataset.page === state.page) link.setAttribute("aria-current", "page");
        else link.removeAttribute("aria-current");
    });
    const query = $("#search").value.trim().toLocaleLowerCase();
    if (state.page === "orders") renderOrders(query);
    else if (state.page === "movements") renderMovements();
    else renderProducts(query);
}

function renderOrders(query) {
    const status = $("#status-filter").value;
    const completed = state.orders.filter(order => order.status === "COMPLETED");
    const revenue = completed.reduce((sum, order) => sum + cents(order.total), 0n);
    $("#metrics").innerHTML = metric("订单总数", integer.format(state.orders.length))
        + metric("已确认订单", integer.format(state.orders.filter(order => order.status === "CONFIRMED").length))
        + metric("已完成金额", moneyFromCents(revenue), true);
    const orders = state.orders.filter(order => (!status || order.status === status)
        && `${order.number} ${order.customer}`.toLocaleLowerCase().includes(query));
    $("#record-count").textContent = `共 ${orders.length} 笔订单`;
    if (!orders.length) {
        $("#content").innerHTML = state.orders.length
            ? empty("没有匹配的订单", "请调整查询内容或状态筛选。")
            : empty("创建第一笔订单", "选择商品与客户，开始记录你的业务。", "create");
        return;
    }
    $("#content").innerHTML = table(["订单编号", "客户", "订单金额", "状态", "创建时间", "操作"], orders.map(order => `
        <tr><td class="strong number">${escapeHtml(order.number)}</td><td>${escapeHtml(order.customer)}</td>
        <td class="number">${money(order.total)}</td><td>${badge(order.status)}</td><td class="muted number">${date(order.createdAt)}</td>
        <td><div class="row-actions"><button class="text-button" data-action="detail" data-id="${order.id}">查看详情</button>
        ${order.status === "DRAFT" ? `<button class="text-button" data-action="edit-order" data-id="${order.id}">编辑</button>` : ""}</div></td></tr>`));
}

function renderProducts(query) {
    const inventory = state.page === "inventory";
    $("#metrics").innerHTML = metric("商品总数", integer.format(state.products.length))
        + metric("启用商品", integer.format(state.products.filter(product => product.active).length))
        + metric("库存总量", integer.format(state.products.reduce((sum, product) => sum + BigInt(product.stock), 0n)));
    const products = state.products.filter(product => `${product.sku} ${product.name}`.toLocaleLowerCase().includes(query));
    $("#record-count").textContent = `共 ${products.length} 种商品`;
    if (!products.length) {
        $("#content").innerHTML = state.products.length
            ? empty("没有匹配的商品", "请调整商品编号或名称后重新查询。")
            : empty("添加你的第一件商品", "商品创建后，可以在库存管理中办理入库。", inventory ? null : "create");
        return;
    }
    $("#content").innerHTML = table(["商品", "售价", "库存数量", "状态", "操作"], products.map(product => `
        <tr><td><span class="strong">${escapeHtml(product.name)}</span><span class="secondary number">${escapeHtml(product.sku)}</span></td>
        <td class="number">${money(product.price)}</td><td class="number ${product.stock === 0 ? "muted" : ""}">${integer.format(product.stock)}</td>
        <td><span class="status status-${product.active ? "active" : "inactive"}">${product.active ? "启用" : "停用"}</span></td>
        <td><div class="row-actions">${inventory
            ? `<button class="text-button" data-action="restock" data-id="${product.id}">＋ 入库</button><button class="text-button" data-action="movements" data-id="${product.id}">查看流水</button>`
            : `<button class="text-button" data-action="edit-product" data-id="${product.id}">编辑商品</button>`}</div></td></tr>`));
}

function renderMovements() {
    const productId = $("#product-filter").value;
    const movements = state.movements.filter(movement => !productId || String(movement.productId) === productId);
    const received = movements.filter(movement => movement.quantity > 0).reduce((sum, movement) => sum + BigInt(movement.quantity), 0n);
    const issued = movements.filter(movement => movement.quantity < 0).reduce((sum, movement) => sum - BigInt(movement.quantity), 0n);
    $("#metrics").innerHTML = metric("变动记录", integer.format(movements.length))
        + metric("累计入库与返还", integer.format(received)) + metric("累计出库", integer.format(issued));
    $("#record-count").textContent = `共 ${movements.length} 条记录`;
    if (!movements.length) {
        $("#content").innerHTML = empty("暂无库存变动", "商品入库及订单确认、取消后，会在这里留下记录。");
        return;
    }
    $("#content").innerHTML = table(["发生时间", "商品", "变动原因", "变动数量", "变动后库存", "关联订单与备注"], movements.map(movement => `
        <tr><td class="muted number">${date(movement.createdAt)}</td><td><span class="strong">${escapeHtml(movement.name)}</span><span class="secondary">${escapeHtml(movement.sku)}</span></td>
        <td>${reasons[movement.reason]}</td><td class="number ${movement.quantity > 0 ? "positive" : "negative"}">${movement.quantity > 0 ? "+" : ""}${integer.format(movement.quantity)}</td>
        <td class="number">${integer.format(movement.balance)}</td><td>${movement.orderId
            ? `<button class="text-button" data-action="detail" data-id="${movement.orderId}">SO-${movement.orderId}</button>` : "<span class='muted'>商品入库</span>"}
        ${movement.note ? `<span class="secondary">${escapeHtml(movement.note)}</span>` : ""}</td></tr>`));
}

function updateProductFilter() {
    const previous = $("#product-filter").value;
    $("#product-filter").innerHTML = '<option value="">全部商品</option>' + state.products.map(product =>
        `<option value="${product.id}">${escapeHtml(product.name)}</option>`).join("");
    $("#product-filter").value = previous;
}

function resetForm(dialog) {
    dialog.querySelector("form").reset();
    dialog.querySelector(".form-error").textContent = "";
}

function openProduct(product) {
    const dialog = $("#product-dialog");
    resetForm(dialog);
    const form = $("#product-form");
    form.elements.id.value = product?.id ?? "";
    form.elements.sku.value = product?.sku ?? "";
    form.elements.name.value = product?.name ?? "";
    form.elements.price.value = product?.price ?? "";
    form.elements.active.checked = product?.active ?? true;
    $("#product-dialog-title").textContent = product ? "编辑商品" : "新增商品";
    dialog.showModal();
}

function openStock(product) {
    resetForm($("#stock-dialog"));
    $("#stock-form").elements.id.value = product.id;
    $("#stock-product").textContent = `${product.name} · ${product.sku}　当前库存 ${integer.format(product.stock)}`;
    $("#stock-dialog").showModal();
}

async function openOrder(id) {
    await loadData();
    if (!state.products.some(product => product.active)) {
        toast("请先新增并启用商品，再创建订单");
        return;
    }
    const detail = id ? await api(`/orders/${id}`) : null;
    const dialog = $("#order-dialog");
    resetForm(dialog);
    const form = $("#order-form");
    form.elements.id.value = id ?? "";
    form.elements.customer.value = detail?.order.customer ?? "";
    $("#order-dialog-title").textContent = id ? `编辑订单 ${detail.order.number}` : "新建订单";
    $("#order-lines").replaceChildren();
    if (detail) detail.lines.forEach(line => addLine(line));
    else addLine();
    updateDraftTotal();
    dialog.showModal();
}

function addLine(line) {
    if ($("#order-lines").children.length >= 100) {
        toast("每笔订单最多包含 100 种商品");
        return;
    }
    const row = document.createElement("div");
    row.className = "order-line";
    const products = state.products.filter(product => product.active || product.id === line?.productId);
    row.innerHTML = `<select aria-label="商品" required><option value="">选择商品</option>${products.map(product =>
        `<option value="${product.id}">${escapeHtml(product.name)} · ${escapeHtml(product.sku)}</option>`).join("")}</select>
        <input type="number" min="1" max="1000000" step="1" required aria-label="数量" value="${line?.quantity ?? 1}">
        <span class="line-total number">¥ 0.00</span><button type="button" class="remove-line" aria-label="移除商品">×</button>`;
    if (line) row.querySelector("select").value = line.productId;
    row.addEventListener("input", updateDraftTotal);
    row.addEventListener("change", updateDraftTotal);
    row.querySelector("button").addEventListener("click", () => {
        row.remove();
        updateDraftTotal();
    });
    $("#order-lines").append(row);
    updateDraftTotal();
}

function updateDraftTotal() {
    let total = 0n;
    document.querySelectorAll(".order-line").forEach(row => {
        const product = state.products.find(product => String(product.id) === row.querySelector("select").value);
        const quantity = Number(row.querySelector("input").value);
        const subtotal = product && Number.isInteger(quantity) && quantity > 0 && quantity <= 1000000
            ? cents(product.price) * BigInt(quantity) : 0n;
        row.querySelector(".line-total").textContent = moneyFromCents(subtotal);
        total += subtotal;
    });
    $("#draft-total").textContent = moneyFromCents(total);
}

async function openDetail(id) {
    const detail = await api(`/orders/${id}`);
    const { order, lines } = detail;
    $("#detail-title").textContent = order.number;
    $("#detail-dialog .form-error").textContent = "";
    $("#order-detail").innerHTML = `<dl class="detail-meta"><div><dt>客户</dt><dd>${escapeHtml(order.customer)}</dd></div>
        <div><dt>订单状态</dt><dd>${badge(order.status)}</dd></div><div><dt>创建时间</dt><dd>${date(order.createdAt)}</dd></div>
        <div><dt>更新时间</dt><dd>${date(order.updatedAt)}</dd></div></dl><div class="detail-table">`
        + table(["商品", "成交单价", "数量", "小计"], lines.map(line => `<tr><td>${escapeHtml(line.name)}<span class="secondary">${escapeHtml(line.sku)}</span></td>
            <td class="number">${money(line.unitPrice)}</td><td class="number">${integer.format(line.quantity)}</td><td class="number">${money(line.subtotal)}</td></tr>`))
        + `</div><div class="detail-total"><span>订单合计</span><strong>${money(order.total)}</strong></div>`;
    const actions = [];
    if (order.status === "DRAFT") {
        actions.push('<button class="button subtle" data-order-action="edit">编辑草稿</button>');
        actions.push('<button class="button danger" data-order-action="cancel">取消订单</button>');
        actions.push('<button class="button primary" data-order-action="confirm">确认订单</button>');
    } else if (order.status === "CONFIRMED") {
        actions.push('<button class="button danger" data-order-action="cancel">取消并返还库存</button>');
        actions.push('<button class="button primary" data-order-action="complete">完成订单</button>');
    } else {
        actions.push('<button class="button subtle" data-close>关闭</button>');
    }
    $("#detail-actions").innerHTML = actions.join("");
    $("#detail-actions").dataset.orderId = id;
    if (!$("#detail-dialog").open) $("#detail-dialog").showModal();
}

function ask(title, description) {
    const dialog = $("#confirm-dialog");
    $("#confirm-title").textContent = title;
    $("#confirm-description").textContent = description;
    dialog.returnValue = "cancel";
    return new Promise(resolve => {
        dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true });
        dialog.showModal();
    });
}

async function submit(form, operation) {
    const button = form.querySelector('[type="submit"]');
    const error = form.querySelector(".form-error");
    if (button.disabled) return;
    button.disabled = true;
    error.textContent = "";
    try {
        await operation();
        form.closest("dialog").close();
        await loadData();
        toast("保存成功");
    } catch (failure) {
        if (form.closest("dialog").open) error.textContent = failure.message;
        else toast(failure.message);
    } finally {
        button.disabled = false;
    }
}

$("#product-form").addEventListener("submit", event => {
    event.preventDefault();
    const form = event.currentTarget;
    submit(form, () => {
        const id = form.elements.id.value;
        return api(`/products${id ? `/${id}` : ""}`, id ? "PUT" : "POST", {
            sku: form.elements.sku.value, name: form.elements.name.value,
            price: form.elements.price.value, active: form.elements.active.checked
        });
    });
});

$("#stock-form").addEventListener("submit", event => {
    event.preventDefault();
    const form = event.currentTarget;
    submit(form, () => api(`/products/${form.elements.id.value}/restocks`, "POST", {
        quantity: Number(form.elements.quantity.value), note: form.elements.note.value
    }));
});

$("#order-form").addEventListener("submit", event => {
    event.preventDefault();
    const form = event.currentTarget;
    submit(form, () => {
        const id = form.elements.id.value;
        const lines = [...document.querySelectorAll(".order-line")].map(row => ({
            productId: Number(row.querySelector("select").value), quantity: Number(row.querySelector("input").value)
        }));
        return api(`/orders${id ? `/${id}` : ""}`, id ? "PUT" : "POST", { customer: form.elements.customer.value, lines });
    });
});

$("#detail-actions").addEventListener("click", async event => {
    const button = event.target.closest("[data-order-action]");
    if (!button || button.disabled) return;
    const id = Number($("#detail-actions").dataset.orderId);
    const action = button.dataset.orderAction;
    if (action === "edit") {
        $("#detail-dialog").close();
        try { await openOrder(id); } catch (failure) { toast(failure.message); }
        return;
    }
    const descriptions = {
        confirm: ["确认订单", "确认后将扣减商品库存，订单内容随即确定。"],
        cancel: ["取消订单", "取消已确认的订单会返还库存，订单将保留为已取消状态。"],
        complete: ["完成订单", "订单完成后将保留成交记录。"]
    };
    button.disabled = true;
    try {
        if (!await ask(...descriptions[action])) return;
        await api(`/orders/${id}/${action}`, "POST");
        await openDetail(id);
        await loadData();
        toast("订单状态已更新");
    } catch (failure) {
        $("#detail-dialog .form-error").textContent = failure.message;
    } finally {
        button.disabled = false;
    }
});

document.addEventListener("click", async event => {
    const close = event.target.closest("[data-close]");
    if (close) close.closest("dialog").close();
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const id = Number(button.dataset.id);
    try {
        switch (button.dataset.action) {
            case "create": await create(); break;
            case "detail": await openDetail(id); break;
            case "edit-order": await openOrder(id); break;
            case "edit-product": openProduct(state.products.find(product => product.id === id)); break;
            case "restock": openStock(state.products.find(product => product.id === id)); break;
            case "movements":
                location.hash = "movements";
                $("#product-filter").value = id;
                renderPage();
                break;
        }
    } catch (failure) {
        toast(failure.message);
    }
});

async function create() {
    if (state.page === "orders") await openOrder();
    else openProduct();
}

function navigate() {
    const page = location.hash.slice(1);
    state.page = Object.hasOwn(pages, page) ? page : "orders";
    $("#search").value = "";
    $("#status-filter").value = "";
    renderPage();
}

$("#search-form").addEventListener("submit", event => { event.preventDefault(); renderPage(); });
$("#status-filter").addEventListener("change", renderPage);
$("#product-filter").addEventListener("change", renderPage);
$("#create-button").addEventListener("click", () => create().catch(failure => toast(failure.message)));
$("#refresh-button").addEventListener("click", () => loadData().catch(failure => toast(failure.message)));
window.addEventListener("hashchange", navigate);
$("#today").textContent = new Date().toLocaleDateString("zh-CN", { year: "numeric", month: "long", day: "numeric", weekday: "long" });
navigate();
loadData().catch(failure => toast(failure.message));
