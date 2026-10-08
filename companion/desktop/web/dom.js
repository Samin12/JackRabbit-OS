// Tiny DOM builder. Every piece of conversation content goes through textContent (never innerHTML).

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value == null || value === false) continue;
      if (key === 'class') el.className = value;
      else if (key === 'style') el.style.cssText = value;
      else if (key === 'dataset') Object.assign(el.dataset, value);
      else if (key === 'text') el.textContent = value;
      else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
      else if (value === true) el.setAttribute(key, '');
      else el.setAttribute(key, String(value));
    }
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false || child === '') continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

export function clear(el) {
  while (el.firstChild) el.firstChild.remove();
  return el;
}

/** Paragraphs and line breaks from plain text, as DOM (no markup is interpreted). */
export function textBlocks(text, className = 'p') {
  const out = [];
  for (const para of String(text ?? '').split(/\n{2,}/)) {
    if (!para.trim()) continue;
    const p = h('p', { class: className });
    para.split('\n').forEach((line, index) => {
      if (index) p.append(document.createElement('br'));
      p.append(document.createTextNode(line));
    });
    out.push(p);
  }
  return out;
}
