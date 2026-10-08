/* SamRabbit widget bridge (runs inside the sandboxed widget document).
 *
 * Host protocol (window.parent.postMessage, same message types as OpenGenerativeUI's MCP renderer):
 *   {type: "widget-resize", height}   content height in CSS px (ResizeObserver, load, 200 ms for 15 s)
 *   {type: "send-prompt", text}       the widget asks the assistant something on the user's behalf
 *   {type: "open-link", url}          https links only
 *   {type: "widget-ready", height, errors}  after jsExpressions ran, charts were drawn and fonts loaded
 * Generated code may call SamRabbit.sendPrompt/openLink, window.sendPrompt/openLink, or the
 * OpenGenerativeUI form Websandbox.connection.remote.sendPrompt({text}) / openLink({url}).
 * send-prompt and open-link need a real user gesture (navigator.userActivation), so a widget, or text
 * smuggled into its data, cannot speak to the assistant or open links on its own.
 * window.__SR_STATIC__ (set by the assembler for the R1 preview render) turns animations off.
 */
(function () {
  "use strict";
  var w = window;
  var doc = document;
  var STATIC = !!w.__SR_STATIC__;
  var CHART_UMD = "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js";
  var CHART_ESM = "https://esm.sh/chart.js@4.4.1/auto";
  var COLORS = ["#5ca2ff", "#7c6cff", "#ff5ca8", "#ffc45c", "#4fd1a5", "#ff8a5c", "#a0c7ff", "#8c98ac"];
  var errors = [];
  var pending = [];
  var helperCharts = 0;
  var chartPromise = null;
  var ready = false;

  function post(message) {
    try { if (w.parent && w.parent !== w) w.parent.postMessage(message, "*"); } catch (e) { /* host gone */ }
  }
  function note(err) {
    if (errors.length >= 8) return;
    var text = err && (err.message || (err.reason && err.reason.message));
    errors.push(String(text || err || "error").slice(0, 240));
  }
  w.addEventListener("error", function (event) { note(event.error || event.message); });
  w.addEventListener("unhandledrejection", function (event) { note(event.reason); });
  if (STATIC) doc.documentElement.classList.add("sr-static");

  function contentHeight() {
    var body = doc.body;
    if (!body) return 0;
    var style = w.getComputedStyle(body);
    return Math.ceil(body.getBoundingClientRect().height + (parseFloat(style.marginTop) || 0) +
                     (parseFloat(style.marginBottom) || 0));
  }
  var lastHeight = -1;
  function reportHeight() {
    var height = contentHeight();
    if (height !== lastHeight) {
      lastHeight = height;
      post({ type: "widget-resize", height: height });
    }
  }

  function alpha(hex, a) {
    var value = /^#([0-9a-f]{6})$/i.exec(String(hex));
    if (!value) return hex;
    var n = parseInt(value[1], 16);
    return "rgba(" + (n >> 16 & 255) + "," + (n >> 8 & 255) + "," + (n & 255) + "," + a + ")";
  }

  /* Value labels for static images (the R1 has no tooltips): numbers on bars and line points. */
  var valuePlugin = {
    id: "srValues",
    afterDatasetsDraw: function (chart, _args, options) {
      if (!options || options.display === false) return;
      var type = chart.config.type;
      if (type !== "bar" && type !== "line") return;
      var ctx = chart.ctx;
      var horizontal = chart.options.indexAxis === "y";
      var visible = chart.data.datasets.filter(function (_d, i) { return chart.isDatasetVisible(i); }).length;
      if (visible === 0 || visible > 2) return;
      ctx.save();
      ctx.font = "600 12px " + (w.getComputedStyle(doc.body).getPropertyValue("--font-sans") || "system-ui");
      ctx.fillStyle = options.color || "#f5f8ff";
      ctx.strokeStyle = options.halo || "rgba(18,22,32,0.92)";  // keeps labels legible over lines and grid
      ctx.lineWidth = 4;
      ctx.lineJoin = "round";
      chart.data.datasets.forEach(function (dataset, index) {
        if (!chart.isDatasetVisible(index)) return;
        var meta = chart.getDatasetMeta(index);
        if (!meta || meta.data.length > (type === "bar" ? 16 : 10)) return;
        meta.data.forEach(function (element, i) {
          var raw = dataset.data[i];
          var value = raw !== null && typeof raw === "object" ? (horizontal ? raw.x : raw.y) : raw;
          if (typeof value !== "number" || !isFinite(value)) return;
          var text = options.format ? options.format(value) :
            (Math.round(value * 100) / 100).toLocaleString();
          var point = element.tooltipPosition ? element.tooltipPosition() : element;
          var radius = type === "line" && element.options && typeof element.options.radius === "number" ?
            element.options.radius : 0;
          var x = point.x, y = point.y;
          if (horizontal) {
            ctx.textAlign = "left"; ctx.textBaseline = "middle";
            x += radius + 6;
          } else {
            ctx.textAlign = "center"; ctx.textBaseline = "bottom";
            y -= radius + 6;
          }
          ctx.strokeText(text, x, y);
          ctx.fillText(text, x, y);
        });
      });
      ctx.restore();
    }
  };

  function themeChart(Chart) {
    if (!Chart || Chart.__samrabbit) return Chart;
    Chart.__samrabbit = true;
    var d = Chart.defaults;
    var font = w.getComputedStyle(doc.documentElement).getPropertyValue("--font-sans").trim() || "system-ui";
    d.color = "#aeb8cb";
    d.borderColor = "rgba(190,210,255,0.10)";
    d.font.family = font;
    d.font.size = 13;
    d.responsive = true;
    d.maintainAspectRatio = false;
    if (STATIC) d.animation = false;
    if (d.plugins && d.plugins.legend) {
      d.plugins.legend.labels.boxWidth = 10;
      d.plugins.legend.labels.boxHeight = 10;
      d.plugins.legend.labels.usePointStyle = true;
      d.plugins.legend.labels.color = "#c9d2e3";
    }
    if (d.plugins && d.plugins.tooltip) {
      var t = d.plugins.tooltip;
      t.backgroundColor = "#181d2a"; t.titleColor = "#f5f8ff"; t.bodyColor = "#c9d2e3";
      t.borderColor = "rgba(190,210,255,0.24)"; t.borderWidth = 1; t.padding = 10; t.cornerRadius = 8;
    }
    if (d.elements) {
      if (d.elements.bar) { d.elements.bar.borderRadius = 6; d.elements.bar.borderSkipped = "start"; }
      if (d.elements.line) { d.elements.line.tension = 0.35; d.elements.line.borderWidth = 2.5; }
      if (d.elements.point) { d.elements.point.radius = 3; d.elements.point.hoverRadius = 5; }
      if (d.elements.arc) { d.elements.arc.borderColor = "#090b10"; d.elements.arc.borderWidth = 2; }
    }
    try { Chart.register(valuePlugin); } catch (e) { note(e); }
    return Chart;
  }

  function loadChart() {
    if (w.Chart && w.Chart.register) return Promise.resolve(themeChart(w.Chart));
    if (!chartPromise) {
      chartPromise = new Promise(function (resolve, reject) {
        var script = doc.createElement("script");
        script.src = CHART_UMD;
        script.onload = function () { if (w.Chart) resolve(w.Chart); else reject(new Error("Chart.js did not load")); };
        script.onerror = function () {
          import(CHART_ESM).then(function (mod) { resolve(mod.default || mod.Chart); },
                                 function () { reject(new Error("Chart.js could not be loaded")); });
        };
        (doc.head || doc.documentElement).appendChild(script);
      }).then(themeChart);
    }
    return chartPromise;
  }

  function colorize(config) {
    var data = config.data || {};
    var type = config.type;
    (data.datasets || []).forEach(function (dataset, index) {
      var kind = dataset.type || type;
      var color = COLORS[index % COLORS.length];
      if (kind === "pie" || kind === "doughnut" || kind === "polarArea") {
        if (!dataset.backgroundColor) {
          var n = (dataset.data || []).length;
          dataset.backgroundColor = COLORS.slice(0, Math.max(1, n));
        }
        return;
      }
      if (!dataset.borderColor) dataset.borderColor = color;
      if (!dataset.backgroundColor) dataset.backgroundColor = kind === "line" || kind === "radar" ? alpha(color, 0.16) : color;
      if (kind === "line" && dataset.pointBackgroundColor === undefined) dataset.pointBackgroundColor = dataset.borderColor;
    });
  }

  function chart(target, config) {
    var promise = loadChart().then(function (Chart) {
      var element = typeof target === "string" ? (doc.getElementById(target) || doc.querySelector(target)) : target;
      if (!element) throw new Error("SamRabbit.chart: no element " + target);
      if (element.tagName !== "CANVAS") {
        var canvas = doc.createElement("canvas");
        element.appendChild(canvas);
        element = canvas;
      }
      config = config || {};
      config.options = config.options || {};
      config.options.plugins = config.options.plugins || {};
      if (config.options.plugins.srValues === undefined || config.options.plugins.srValues === true) {
        config.options.plugins.srValues = { display: true };
      }
      var labels = config.options.plugins.srValues && config.options.plugins.srValues.display !== false;
      if (labels && (config.type === "bar" || config.type === "line") && config.options.layout === undefined) {
        // Room for the value labels above the tallest bar (or right of the longest horizontal bar).
        config.options.layout = { padding: config.options.indexAxis === "y" ? { right: 40 } : { top: 22 } };
      }
      if (STATIC) config.options.animation = false;
      colorize(config);
      helperCharts += 1;
      return new Chart(element, config);
    });
    pending.push(promise.catch(note));
    return promise;
  }

  function userGesture() {
    var activation = w.navigator && w.navigator.userActivation;
    return !activation || activation.isActive;
  }
  function sendPrompt(text) {
    text = String(text == null ? "" : text).trim().slice(0, 4000);
    if (text && userGesture()) post({ type: "send-prompt", text: text });
  }
  function openLink(url) {
    url = String(url == null ? "" : url);
    if (/^https:\/\//i.test(url) && userGesture()) post({ type: "open-link", url: url });
  }

  function timeout(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  function frames() {
    return new Promise(function (resolve) {
      requestAnimationFrame(function () { requestAnimationFrame(function () { resolve(); }); });
    });
  }
  function loaded() {
    if (doc.readyState === "complete") return Promise.resolve();
    return new Promise(function (resolve) { w.addEventListener("load", function () { resolve(); }, { once: true }); });
  }

  function settle() {
    return Promise.race([Promise.all(pending.concat([loaded()])), timeout(10000)])
      .then(function () { return doc.fonts && doc.fonts.ready ? Promise.race([doc.fonts.ready, timeout(2000)]) : null; })
      .then(frames)
      .then(function () {
        // Charts drawn without the helper may still be animating: give them a moment.
        var canvases = doc.querySelectorAll("canvas").length;
        return canvases > helperCharts ? timeout(STATIC ? 1100 : 0) : null;
      })
      .then(function () {
        ready = true;
        w.__srReady = true;
        w.__srErrors = errors.slice();
        reportHeight();
        post({ type: "widget-ready", height: contentHeight(), errors: errors.slice() });
      });
  }

  function run(expressions) {
    var chain = Promise.resolve();
    (expressions || []).forEach(function (code) {
      chain = chain.then(function () {
        var result;
        try {
          result = (0, eval)(String(code));
        } catch (e) {
          note(e);
          return null;
        }
        if (result && typeof result.then === "function") {
          return Promise.race([Promise.resolve(result).catch(note), timeout(8000)]);
        }
        return null;
      });
    });
    return chain.then(settle, settle);
  }

  var api = {
    static: STATIC,
    colors: COLORS.slice(),
    palette: { blue: "#5ca2ff", violet: "#7c6cff", pink: "#ff5ca8", amber: "#ffc45c", mint: "#4fd1a5",
               coral: "#ff8a5c", sky: "#a0c7ff", gray: "#8c98ac", red: "#ff6363", ink: "#f5f8ff", muted: "#9aa6bb" },
    alpha: alpha,
    loadChart: loadChart,
    chart: chart,
    track: function (promise) { pending.push(Promise.resolve(promise).catch(note)); return promise; },
    sendPrompt: sendPrompt,
    openLink: openLink,
    isReady: function () { return ready; },
    __run: run
  };
  w.SamRabbit = api;
  w.sendPrompt = sendPrompt;
  w.openLink = openLink;
  w.Websandbox = { connection: { remote: {
    sendPrompt: function (args) { sendPrompt(args && args.text); return Promise.resolve(); },
    openLink: function (args) { openLink(args && args.url); return Promise.resolve(); }
  } } };

  doc.addEventListener("click", function (event) {
    var link = event.target && event.target.closest ? event.target.closest("a[href]") : null;
    if (link && /^https?:/i.test(link.href)) {
      event.preventDefault();
      openLink(link.href);
    }
  });
  function startMeasuring() {
    reportHeight();
    if (w.ResizeObserver && doc.body) new ResizeObserver(reportHeight).observe(doc.body);
    w.addEventListener("load", reportHeight);
    if (!STATIC) {
      var interval = setInterval(reportHeight, 200);
      setTimeout(function () { clearInterval(interval); }, 15000);
    }
  }
  if (doc.readyState === "loading") doc.addEventListener("DOMContentLoaded", startMeasuring);
  else startMeasuring();
})();
