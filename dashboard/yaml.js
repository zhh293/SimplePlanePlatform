// Minimal YAML parser/serializer for proxy config files
// Handles: key-value, nested objects, lists of strings, lists of objects, comments

function parseYaml(text) {
  const lines = text.split('\n');
  const result = {};
  // Stack: { obj, indent }
  const stack = [{ obj: result, indent: -2 }];

  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    if (raw.trim() === '' || raw.trim().startsWith('#')) continue;

    const indent = raw.search(/\S/);
    const line = raw.trim();

    // Pop stack: remove entries at deeper or equal indentation
    while (stack.length > 1) {
      const top = stack[stack.length - 1];
      if (top.indent >= indent) {
        stack.pop();
      } else {
        break;
      }
    }

    const parent = stack[stack.length - 1].obj;

    // List item
    if (line.startsWith('- ')) {
      const value = line.substring(2).trim();

      // Find target array: if parent is array, use it; otherwise find last array value in parent
      let targetArray = null;
      if (Array.isArray(parent)) {
        targetArray = parent;
      } else {
        const keys = Object.keys(parent);
        for (let k = keys.length - 1; k >= 0; k--) {
          if (Array.isArray(parent[keys[k]])) {
            targetArray = parent[keys[k]];
            break;
          }
        }
      }

      if (!targetArray) continue;

      if (value.includes(':') && !value.startsWith('"') && !value.startsWith("'")) {
        const obj = {};
        parseInlineObject(value, obj);
        targetArray.push(obj);
        // Push with the list's indent so the pop logic works correctly
        stack.push({ obj: obj, indent: indent });
      } else {
        targetArray.push(parseScalar(value));
      }
      continue;
    }

    // Key-value
    const colonIdx = findColon(line);
    if (colonIdx > 0) {
      const key = line.substring(0, colonIdx).trim();
      const valuePart = line.substring(colonIdx + 1).trim();

      if (valuePart === '') {
        const nextLine = findNextContent(lines, i + 1);
        if (nextLine && nextLine.trim().startsWith('- ')) {
          const arr = [];
          parent[key] = arr;
          // Push array as sentinel; list items will find it via parent lookup
          stack.push({ obj: arr, indent: indent });
        } else {
          const nested = {};
          parent[key] = nested;
          stack.push({ obj: nested, indent: indent });
        }
      } else {
        parent[key] = parseScalar(valuePart);
      }
    }
  }

  return result;
}

function findColon(line) {
  let inQuote = false;
  let quoteChar = '';
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuote) {
      if (ch === quoteChar && line[i - 1] !== '\\') inQuote = false;
    } else {
      if (ch === '"' || ch === "'") { inQuote = true; quoteChar = ch; }
      else if (ch === ':') return i;
    }
  }
  return -1;
}

function parseInlineObject(str, obj) {
  const parts = splitOutsideQuotes(str, ',');
  for (const part of parts) {
    const idx = part.indexOf(':');
    if (idx > 0) {
      const k = part.substring(0, idx).trim();
      const v = part.substring(idx + 1).trim();
      obj[k] = parseScalar(v);
    }
  }
}

function splitOutsideQuotes(str, delimiter) {
  const parts = [];
  let current = '';
  let inQuote = false;
  let quoteChar = '';
  for (let i = 0; i < str.length; i++) {
    const ch = str[i];
    if (inQuote) {
      if (ch === quoteChar && str[i - 1] !== '\\') inQuote = false;
      current += ch;
    } else {
      if (ch === '"' || ch === "'") { inQuote = true; quoteChar = ch; current += ch; }
      else if (ch === delimiter) { parts.push(current); current = ''; }
      else current += ch;
    }
  }
  if (current) parts.push(current);
  return parts;
}

function parseScalar(value) {
  if (value === 'true') return true;
  if (value === 'false') return false;
  if (value === 'null' || value === '~' || value === '') return null;
  if (/^-?\d+$/.test(value)) return parseInt(value, 10);
  if (/^-?\d+\.\d+$/.test(value)) return parseFloat(value);
  if ((value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))) {
    return value.slice(1, -1);
  }
  return value;
}

function findNextContent(lines, start) {
  for (let i = start; i < lines.length; i++) {
    const t = lines[i].trim();
    if (t !== '' && !t.startsWith('#')) return lines[i];
  }
  return null;
}

function toYaml(obj, indent = 0) {
  const lines = [];
  const prefix = '  '.repeat(indent);

  for (const [key, value] of Object.entries(obj)) {
    if (value === null || value === undefined) {
      lines.push(`${prefix}${key}: null`);
    } else if (Array.isArray(value)) {
      if (value.length === 0) {
        lines.push(`${prefix}${key}: []`);
      } else {
        lines.push(`${prefix}${key}:`);
        for (const item of value) {
          if (typeof item === 'object' && item !== null) {
            const entries = Object.entries(item);
            if (entries.length > 0) {
              const [firstKey, firstVal] = entries[0];
              let line = `${prefix}  - ${firstKey}: ${formatValue(firstVal)}`;
              for (let i = 1; i < entries.length; i++) {
                const [k, v] = entries[i];
                line += `, ${k}: ${formatValue(v)}`;
              }
              lines.push(line);
            }
          } else {
            lines.push(`${prefix}  - ${formatValue(item)}`);
          }
        }
      }
    } else if (typeof value === 'object') {
      lines.push(`${prefix}${key}:`);
      lines.push(toYaml(value, indent + 1));
    } else {
      lines.push(`${prefix}${key}: ${formatValue(value)}`);
    }
  }

  return lines.join('\n');
}

function formatValue(v) {
  if (typeof v === 'string') {
    if (v.includes(':') || v.includes('#') || v.includes('*') ||
        v.includes('"') || v.includes("'") || v === '' ||
        v.startsWith(' ') || v.endsWith(' ') ||
        v === 'true' || v === 'false' || v === 'null' ||
        /^\d+$/.test(v)) {
      return `"${v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
    }
    return v;
  }
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (v === null) return 'null';
  return String(v);
}

module.exports = { parseYaml, toYaml };
