/*
 * D14.11: the only JavaScript in the app. All DOM knowledge about "where is
 * the chat input" and "where is the newest assistant reply" lives here, so the
 * Kotlin side stays a state machine.
 *
 * Adapters are tried in order; the first whose selectors resolve wins. Adding
 * a platform means adding an entry to ADAPTERS and nothing else.
 */
(function () {
  'use strict';

  var ADAPTERS = [
    {
      name: 'chatgpt',
      input: ['#prompt-textarea', 'textarea[data-id="root"]'],
      send: ['button[data-testid="send-button"]'],
      reply: ['div[data-message-author-role="assistant"]'],
      ready: ['#prompt-textarea']
    },
    {
      name: 'gemini',
      input: ['div.ql-editor[contenteditable="true"]', 'rich-textarea .ql-editor'],
      send: ['button.send-button', 'button[aria-label*="Send"]'],
      reply: ['.model-response-text', 'message-content model-response'],
      ready: ['div.ql-editor[contenteditable="true"]']
    },
    {
      name: 'generic',
      input: ['textarea', 'div[contenteditable="true"]'],
      send: ['button[type="submit"]'],
      reply: ['[data-role="assistant"]', '.assistant', '.message.bot'],
      ready: ['textarea', 'div[contenteditable="true"]']
    }
  ];

  function first(selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var el = document.querySelector(selectors[i]);
      if (el) return el;
    }
    return null;
  }

  function adapter() {
    for (var i = 0; i < ADAPTERS.length; i++) {
      if (first(ADAPTERS[i].ready)) return ADAPTERS[i];
    }
    return null;
  }

  function lastReply() {
    var a = adapter();
    if (!a) return null;
    var nodes = document.querySelectorAll(a.reply.join(','));
    if (!nodes.length) return null;
    var text = (nodes[nodes.length - 1].innerText || '').trim();
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
    inject: function (text) {
      var a = adapter();
      if (!a) return false;
      var input = first(a.input);
      if (!input) return false;
      setInput(input, text);
      var send = first(a.send);
      if (send && !send.disabled) { send.click(); return true; }
      // Fallback: Enter, which every supported chat UI submits.
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
      return true;
    },
    newConversation: function () {
      // Only clears local bookkeeping; a platform without a "new chat" button
      // starts fresh anyway once inject() sends the next prompt.
      window.halum._baseline = lastReply();
    }
  };

  window.halum.newConversation();
})();
