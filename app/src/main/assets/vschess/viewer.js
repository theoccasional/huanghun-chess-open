// 微思象棋播放器 —— Android WebView 桥接层
// 原生侧将本地棋谱（XQF 二进制 hex 或 PGN/WXF/东萍等文本）喂给播放器；
// 并桥接“自家引擎”：读取主变着法列表、刷局面、段位导出各格式、人机自动应手。
(function () {
    "use strict";

    var chess = null;   // vschess 实例
    var ready = false;  // 播放器界面构建完成
    var pending = [];   // 就绪前暂存的任务

    // ── 对弈人机同步 ──
    var engineAuto = false;   // 引擎自动应手开关（原生侧控制）
    var pollTimer = null;
    var lastSeenLen = -1;
    var moveListOk = false;   // 能否读取着法列表（决定人机是否可用）
    var suppressCallback = false; // 原生推送局面后短暂不回传（避免自锁）

    function notify(msg) {
        try {
            if (window.Android && window.Android.onToast) window.Android.onToast(msg);
        } catch (e) { /* ignore */ }
    }

    function flushPending() {
        var it;
        while ((it = pending.shift())) {
            try { it(); } catch (e) { notify("操作失败: " + (e && e.message)); }
        }
    }

    // 走子法串判据：UCCI/ICCS 坐标着法形如 a0i9（红方底线第0排）
    var UCCI_RE = /^[a-i][0-9][a-i][0-9]$/;

    // 把微思返回的列表规整成纯 UCCI 着法数组（自动剔除开头的起始FEN、空串、多余字段）
    function toMoveArray(l) {
        if (l == null) return null;
        var arr = (typeof l === "string") ? [l] : Array.prototype.slice.call(l);
        var out = [];
        for (var i = 0; i < arr.length; i++) {
            var s = String(arr[i]);
            if (UCCI_RE.test(s)) out.push(s);
        }
        if (out.length > 0) moveListOk = true;
        return out;
    }

    // 尝试读取主对着法列表（UCCI/ICCS 串，与原生引擎 UCI 同坐标系）
    function getMoveList() {
        try {
            if (!chess) return null;
            if (typeof chess.getUCCIList === "function") {
                var m = toMoveArray(chess.getUCCIList());
                if (m) return m;
            }
            if (typeof chess.getMoveList === "function") {
                var m2 = toMoveArray(chess.getMoveList());
                if (m2) return m2;
            }
        } catch (e) { /* ignore */ }
        return null;
    }

    // 尽力跳到末步（打谱区定位到最后）
    function navigateLast() {
        try {
            if (!chess) return;
            if (typeof chess.goLast === "function") { chess.goLast(); return; }
            var guard = 400;
            while (guard-- > 0 && typeof chess.goNext === "function") {
                try { chess.goNext(); } catch (e) { break; }
            }
            if (typeof chess.setIndex === "function" && typeof chess.getIndex === "function") {
                var li = -1, c = 0;
                try { c = chess.getIndex(); } catch (e) { }
                try { li = chess.getIndex() - 1; chess.setIndex(li); } catch (e) { }
                // 回退到至少可见末步：向前尽量多步数不进则止
                var n = 0;
                try { n = (chess.getIndexCount ? chess.getIndexCount() : (typeof chess.getUCCIFenList === "function" ? (chess.getUCCIFenList() || []).length - 1 : 0)); } catch (e) { }
                if (n > 0) { try { chess.setIndex(n); } catch (e) { } }
            }
        } catch (e) { /* ignore */ }
    }

    // 加载一个局面+着法列表（用 vschess 官方 stepListToNode 建节点树后整棵载入）
    function loadGame(fen, stepList) {
        if (!chess) return false;
        try {
            var sel = (stepList && stepList.length) ? stepList : undefined;
            var node = window.vschess.stepListToNode(fen, sel);
            if (!node) return false;
            chess.loadData(node, {});
            navigateLast();
            return true;
        } catch (e) { return false; }
    }

    function startPoll() {
        if (!engineAuto || pollTimer) return;
        pollTimer = setInterval(function () {
            try {
                var list = getMoveList();
                if (!list) return;
                if (lastSeenLen < 0) { lastSeenLen = list.length; return; } // 首次仅建基线
                if (list.length !== lastSeenLen) {
                    // 步数变化（走子或悔/跳）→ 回传完整列表，由原生做精确 diff 同步，
                    // 避免增量检测在变招/换线时漏报导致“电脑不走”。
                    lastSeenLen = list.length;
                    if (window.Android && window.Android.onBoardMove && !suppressCallback) {
                        try { window.Android.onBoardMove(JSON.stringify(list)); } catch (e) { }
                    }
                }
            } catch (e) { /* ignore */ }
        }, 650);
    }
    function stopPoll() {
        if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
    }

    // 脚本与依赖就绪后创建播放器
    function boot() {
        if (typeof window.vschess === "undefined" || !window.vschess.load) return false;
        chess = new window.vschess.load(".player", {
            chessData: false,
            defaultTab: "comment",
            sound: true,
            loadFinish: function () {
                ready = true;
                flushPending();
                try {
                    if (window.Android && window.Android.onPlayerReady) window.Android.onPlayerReady();
                } catch (e) { /* ignore */ }
            }
        });
        // 探测主变着法列表是否可读
        var tmp = getMoveList();
        return true;
    }

    function startBoot() {
        var tries = 0;
        var t = setInterval(function () {
            tries++;
            if (boot()) { clearInterval(t); return; }
            if (tries > 300) clearInterval(t);
        }, 25);
    }

    function ensureReady(fn) {
        if (ready) { fn(); return; }
        pending.push(fn);
    }

    function hexToBytes(hex) {
        var out = new Uint8Array(Math.floor(hex.length / 2));
        for (var i = 0; i < out.length; ++i) {
            out[i] = parseInt(hex.substr(i * 2, 2), 16);
        }
        return out;
    }

    function loadNode(node, info, label, okMsg) {
        if (!node || !node.fen) return false;
        if (!info || typeof info !== "object") info = {};
        chess.loadData(node, info);
        navigateLast();
        return true;
    }

    window.Player = {
        isReady: function () { return ready; },

        // 二进制谱（XQF/CBR/CCM）：hex 字符串
        loadHex: function (hex, type) {
            ensureReady(function () {
                try {
                    type = type || "xqf";
                    var bytes = hexToBytes(hex);
                    var node = window.vschess.binaryToNode(bytes, type);
                    var info = window.vschess.binaryToInfo ? window.vschess.binaryToInfo(bytes, type) : null;
                    var ok = loadNode(node, info, type);
                    notify(ok ? "棋谱载入成功" : "棋谱无法识别");
                } catch (e) { notify("解析失败: " + (e && e.message)); }
            });
        },

        // 文本谱：PGN / WXF / ICCS / 东萍 / 世家 / QQ新中国 等，type 一般为 auto
        loadText: function (text, type) {
            ensureReady(function () {
                try {
                    type = type || "auto";
                    var node = window.vschess.dataToNode(text, type);
                    var info = window.vschess.dataToInfo ? window.vschess.dataToInfo(text, type) : null;
                    var ok = loadNode(node, info, type);
                    notify(ok ? "棋谱载入成功" : "棋谱无法识别");
                } catch (e) { notify("解析失败: " + (e && e.message)); }
            });
        },

        showExport: function () { if (chess && chess.showTab) chess.showTab("export"); },
        showComment: function () { if (chess && chess.showTab) chess.showTab("comment"); },
        getInfo: function () { return (chess && chess.chessInfo) ? chess.chessInfo : {}; },

        // ── ★ 以下为对弈/导出桥接能力 ──

        /** 读取当前主板对着法列表（JSON 数组），失败返回空串。 */
        getMoveList: function () {
            var list = getMoveList();
            return list ? JSON.stringify(list) : "";
        },

        /** 走子法列表是否可读（人机是否可用）。 */
        moveListOk: function () { return moveListOk ? "1" : "0"; },

        /** 用起始 FEN + 主变着法列表重载棋盘，并跳到末步。fen=="" 用默认开局。 */
        showGame: function (fen, jsonMoves) {
            ensureReady(function () {
                try {
                    var fen2 = (fen && fen.length) ? fen : window.vschess.defaultFen;
                    var stepList = null;
                    if (jsonMoves && jsonMoves.length) {
                        try { stepList = JSON.parse(jsonMoves); } catch (e) { stepList = null; }
                        if (stepList && !(stepList instanceof Array)) stepList = null;
                    }
                    suppressCallback = true;
                    loadGame(fen2, stepList || []);
                    setTimeout(function () { suppressCallback = false; }, 60);
                    notify(stepList ? "局面已同步（" + stepList.length + " 手）" : "已开局");
                } catch (e) { notify("同步失败: " + (e && e.message)); }
            });
        },

        /** 设为标准开局（清空）。 */
        resetStandard: function () {
            ensureReady(function () {
                try { suppressCallback = true; loadGame(window.vschess.defaultFen, []); setTimeout(function(){suppressCallback=false;},60); notify("新开一局"); }
                catch (e) { notify("开局失败: " + (e && e.message)); }
            });
        },

        /** 跳到第 n 手（n=0 回到起始局面）。 */
        goStep: function (n) {
            ensureReady(function () {
                try {
                    if (typeof chess.setIndex === "function") { chess.setIndex(n); return; }
                    var guard = 600;
                    while (guard-- > 0 && typeof chess.goNext === "function") { try { chess.goNext(); } catch (e) { break; } }
                } catch (e) { /* ignore */ }
            });
        },

        /** 跳到末步。 */
        goLast: function () {
            ensureReady(function () { navigateLast(); });
        },

        /** 悔一手（上一步）。 */
        goBack: function () {
            ensureReady(function () {
                try { if (typeof chess.goPrev === "function") chess.goPrev(); else if (typeof chess.setIndex === "function") try { chess.setIndex(chess.getIndex() - 1); } catch (e) { } } catch (e) { }
            });
        },

        /** 开启/关闭引擎自动应手轮询（1=开 0=关）。 */
        setEngineAuto: function (en) {
            engineAuto = !!en;
            suppressCallback = false;
            if (engineAuto) {
                lastSeenLen = -1;   // 首次轮询以上报当前为基准
                startPoll();
            } else {
                stopPoll();
            }
        },

        /** 重设走子轮询基线为当前主板步数（引擎推盘后调用，防误报/漏报）。 */
        resetBaseline: function () {
            try { var l = getMoveList(); lastSeenLen = l ? l.length : 0; } catch (e) { lastSeenLen = 0; }
        },

        /** 以当前主板主变（FEN+着法列表）生成指定格式文本。format: pgn/wxf/iccs/dhxq/pfc/qq/text */
        exportData: function (format) {
            var out = "";
            try {
                var list = getMoveList();
                var fen = window.vschess.defaultFen;
                if (chess && chess.chessInfo && chess.chessInfo.fen) fen = chess.chessInfo.fen;
                if (!list || !list.length) return "";
                switch (format) {
                    case "pgn":
                        out = window.vschess.moveListToData_PGN(list, fen) || "";
                        break;
                    case "text":
                        out = window.vschess.moveListToText(list, fen) || "";
                        break;
                    case "dhxq": {
                        var node = window.vschess.stepListToNode(fen, list);
                        out = window.vschess.nodeToData_DhtmlXQ(node, {}, false) || "";
                        break;
                    }
                    case "pfc": {
                        var n2 = window.vschess.stepListToNode(fen, list);
                        out = window.vschess.nodeToData_PengFei(n2, {}, null, false) || "";
                        break;
                    }
                    case "qq":
                        out = window.vschess.moveListToData_QQ(list, false) || "";
                        break;
                    case "wxf":
                    case "iccs":
                    case "chinese": {
                        var lines = [];
                        for (var i = 0; i < list.length; i++) {
                            var m = list[i];
                            var res;
                            if (format === "iccs") { res = { move: window.vschess.Node2ICCS_NoFen ? window.vschess.Node2ICCS_NoFen(m) : m }; }
                            else if (format === "wxf") { try { res = window.vschess.Node2WXF(m, fen); } catch (e) { res = null; } }
                            else { try { res = window.vschess.Node2Chinese(m, fen); } catch (e) { res = null; } }
                            if (res && res.move && res.move !== "None" && res.move !== "无效着法") lines.push(res.move);
                            if (res && res.movedFen) fen = res.movedFen;
                        }
                        out = lines.join(" ");
                        break;
                    }
                    default:
                        out = "";
                }
            } catch (e) { out = ""; }
            return out;
        }
    };

    window.App = {
        openFile: function () {
            try {
                if (window.Android && window.Android.openFile) window.Android.openFile();
                else notify("当前环境不支持打开文件");
            } catch (e) { /* ignore */ }
        },
        showExport: function () { window.Player.showExport(); }
    };

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", startBoot);
    } else {
        startBoot();
    }
})();