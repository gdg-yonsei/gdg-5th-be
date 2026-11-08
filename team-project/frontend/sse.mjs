// Incremental UTF-8 + SSE parsing, including CRLF and split network frames.
export async function readSSE(stream, onEvent) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  function drain(final = false) {
    let match;
    while ((match = /\r\n|\r(?!$)|\n/.exec(buffer))) {
      const line = buffer.slice(0, match.index);
      buffer = buffer.slice(match.index + match[0].length);
      consume(line);
    }
    if (final) {
      if (buffer.endsWith('\r')) buffer = buffer.slice(0, -1);
      if (buffer) consume(buffer);
      buffer = '';
      consume('');
    }
  }
  let event = 'message', data = [];
  function consume(line) {
    if (line === '') {
      if (data.length) onEvent(event, JSON.parse(data.join('\n')));
      event = 'message'; data = [];
    } else if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
  }
  try {
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, {stream: true}); drain();
    }
    buffer += decoder.decode(); drain(true);
  } finally { reader.releaseLock(); }
}
