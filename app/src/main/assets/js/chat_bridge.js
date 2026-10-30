/*
 * D14.11 / Q1: the only JavaScript in the app. All DOM knowledge about "where
 * is the chat input", "how do I submit" and "where is the newest reply" lives
 * here, so the Kotlin side stays a state machine.
 *
 * Adapters are selected by hostname, then fall back to the generic one. Every
 * adapter exposes the same shape:
 *   input   []  selectors for the text box
 *   submit  []  selectors for the send button
 *   stop    []  selectors for the stop-generation button
 *   assistant [] selectors for assistant turns
 *   strip   []  selectors for buttons inside a turn that are not the text
 *
 * Nothing here throws to the caller: a missing input or submit returns a
 * result object so Kotlin can log BRIDGE_FAIL and toast instead of crashing.
 */
(function () {
  'use strict';

  var ADAPTERS = [
    {
      name: 'chatgpt',
      hosts: ['chatgpt.com', 'chat.openai.com'],
      input: ['textarea#prompt-textarea', '#prompt-textarea', 'textarea[data-id="root"]'],
      submit: ['button[data-testid="send-button"]'],
      stop: ['button[data-testid="stop-button"]'],
      assistant: ['div[data-message-author-role="assistant"]'],
      strip: ['button[data-testid="copy-turn-action-button"]', 'button[data-testid="regenerate-button"]']
    },
    {
      name: 'claude',
      hosts: ['claude.ai'],
      input: ['div[contenteditable="true"]'],
      submit: ['button[aria-label="Send message"]'],
      stop: ['button[aria-label="Stop response"]'],
      assistant: ['div.font-claude-message'],
      strip: ['button[aria-label="Copy"]', 'button[aria-label="Retry"]']
    },
    {
      name: 'gemini',
      hosts: ['gemini.google.com'],
      input: ['textarea[aria-label="Enter a prompt here"]', 'div.ql-editor[contenteditable="true"]'],
      submit: ['button[aria-label="Send message"]', 'button.send-button'],
      stop: ['button[aria-label="Stop response"]'],
      assistant: ['message-content', '.model-response-text'],
      strip: ['button[aria-label="Copy"]', 'button[aria-label="Retry"]']
    },
    {
      name: 'generic',
      hosts: [],
      input: ['textarea', 'div[contenteditable="true"]'],
      submit: [],
      stop: [],
      assistant: ['[data-role="assistant"]', '.assistant', '.message.bot'],
      strip: []
    }
  ];

  function first(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) return el;
    }
    return null;
  }

  function isVisible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && el.offsetParent !== null;
  }

  function byHost() {
    var host = location.hostname;
    for (var i = 0; i < ADAPTERS.length; i++) {
      var a = ADAPTERS[i];
      for (var j = 0; j < a.hosts.length; j++) {
        if (host.indexOf(a.hosts[j]) !== -1) return a;
      }
    }
    return null;
  }

  function usable(a) {
    return a && first(a.input);
  }

  function adapter() {
    var h = byHost();
    if (usable(h)) return h;
    for (var i = 0; i < ADAPTERS.length; i++) {
      if (usable(ADAPTERS[i])) return ADAPTERS[i];
    }
    return null;
  }

  function genericSubmit() {
    var buttons = document.querySelectorAll('button');
    var re = /send|submit|→|›/i;
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      if (!isVisible(b)) continue;
      var label = (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
      if (re.test(label)) return b;
    }
    return null;
  }

  function findSubmit(a) {
    var btn = first(a.submit);
    if (btn) return btn;
    return genericSubmit();
  }

  function assistantNodes(a) {
    var list = [];
    for (var i = 0; i < a.assistant.length; i++) {
      var n = document.querySelectorAll(a.assistant[i]);
      for (var k = 0; k < n.length; k++) list.push(n[k]);
    }
    return list;
  }

  function textOf(node, a) {
    var clone = node.cloneNode(true);
    var strip = a.strip || [];
    for (var i = 0; i < strip.length; i++) {
      var bad = clone.querySelectorAll(strip[i]);
      for (var k = 0; k < bad.length; k++) bad[k].remove();
    }
    var t = clone.textContent || '';
    return t.replace(/\s+\n/g, '\n').trim();
  }

  function lastReply() {
    var a = adapter();
    if (!a) return null;
    var nodes = assistantNodes(a);
    if (!nodes.length) return null;
    var text = textOf(nodes[nodes.length - 1], a);
    return text || null;
  }

  function setInput(el, text) {
    el.focus();
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT') {
      // React-style value setter: a plain .value assignment is ignored by the
      // framework's own state, and the send button stays disabled.
      var proto = Object.getPrototypeOf(el);
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(el, text); else el.value = text;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      el.textContent = text;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text }));
    }
  }

  window.halum = {
    ready: function () { return !!adapter(); },
    adapter: function () { var a = adapter(); return a ? a.name : null; },
    lastReply: lastReply,
    /** Returns {ok, inputFound, submitFound, adapter}. Never throws. */
    inject: function (text) {
      var a = adapter();
      if (!a) {
        return { ok: false, inputFound: false, submitFound: false, adapter: null, reason: 'input_not_found' };
      }
      var input = first(a.input);
      if (!input) {
        return { ok: false, inputFound: false, submitFound: false, adapter: a.name, reason: 'input_not_found' };
      }
      setInput(input, text);
      var send = findSubmit(a);
      if (send && !send.disabled) {
        send.click();
        return { ok: true, inputFound: true, submitFound: true, adapter: a.name };
      }
      // Fallback: Enter, which every supported chat UI submits.
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
      return { ok: true, inputFound: true, submitFound: false, adapter: a.name };
    },
    newConversation: function () {
      // Only clears local bookkeeping; a platform without a "new chat" button
      // starts fresh anyway once inject() sends the next prompt.
      window.halum._baseline = lastReply();
    }
  };

  window.halum.newConversation();
})();
