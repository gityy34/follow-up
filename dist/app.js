'use strict';

const storageKey = 'follow-up.entries.v1';
const dialog = document.querySelector('#editor');
const form = document.querySelector('#follow-up-form');
const list = document.querySelector('#items');
const search = document.querySelector('#search');
const status = document.querySelector('#status');
const editorError = document.querySelector('#editor-error');
const install = document.querySelector('#install');
let filter = 'active';
let editingId = null;
let entries = [];

try {
  const saved = JSON.parse(localStorage.getItem(storageKey) ?? '[]');
  if (Array.isArray(saved)) entries = saved;
} catch {
  status.textContent = 'Saved follow-ups could not be loaded.';
}

function localDate() {
  const date = new Date();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function category(entry, today) {
  if (entry.done) return 'done';
  return entry.date <= today ? 'due' : 'upcoming';
}

function save(next, message) {
  try {
    localStorage.setItem(storageKey, JSON.stringify(next));
  } catch {
    const feedback = dialog.open ? editorError : status;
    feedback.textContent = 'This browser could not save the change. Check its storage settings and try again.';
    return false;
  }
  entries = next;
  status.textContent = message;
  render();
  return true;
}

function openEditor(entry) {
  editingId = entry?.id ?? null;
  form.reset();
  editorError.textContent = '';
  document.querySelector('#editor-title').textContent = entry ? 'Edit follow-up' : 'New follow-up';
  form.elements.title.value = entry?.title ?? '';
  form.elements.contact.value = entry?.contact ?? '';
  form.elements.date.value = entry?.date ?? localDate();
  form.elements.notes.value = entry?.notes ?? '';
  dialog.showModal();
  form.elements.title.focus();
}

function element(tag, text) {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}

function action(label, entry, handler) {
  const button = element('button', label);
  button.className = 'outline';
  button.setAttribute('aria-label', `${label}: ${entry.title}`);
  button.addEventListener('click', handler);
  return button;
}

function render() {
  const today = localDate();
  const totals = { due: 0, upcoming: 0, done: 0 };
  for (const entry of entries) totals[category(entry, today)]++;
  for (const [key, count] of Object.entries(totals)) {
    document.querySelector(`#${key}-count`).textContent = count;
  }

  const query = search.value.trim().toLowerCase();
  const visible = entries.filter(entry => {
    const matchesFilter = filter === 'active' ? !entry.done : category(entry, today) === filter;
    return matchesFilter && [entry.title, entry.contact, entry.notes].some(value => value.toLowerCase().includes(query));
  }).sort((first, second) => first.date.localeCompare(second.date));

  list.replaceChildren();
  for (const entry of visible) {
    const item = document.createElement('li');
    const details = document.createElement('div');
    const date = new Date(`${entry.date}T00:00:00`).toLocaleDateString(undefined, { dateStyle: 'medium' });
    details.append(element('h3', entry.title), element('p', entry.contact ? `${entry.contact} · ${date}` : date));
    if (entry.notes) details.append(element('p', entry.notes));

    const actions = document.createElement('div');
    actions.className = 'actions';
    actions.append(
      action(entry.done ? 'Reopen' : 'Complete', entry, () => {
        save(entries.map(item => item.id === entry.id ? { ...item, done: !item.done } : item),
          entry.done ? 'Follow-up reopened.' : 'Follow-up completed.');
      }),
      action('Edit', entry, () => openEditor(entry)),
      action('Delete', entry, () => {
        if (window.confirm(`Delete “${entry.title}”?`)) {
          save(entries.filter(item => item.id !== entry.id), 'Follow-up deleted.');
        }
      })
    );
    item.append(details, actions);
    list.append(item);
  }

  document.querySelector('#empty').textContent = visible.length ? '' :
    query ? 'No follow-ups match your search.' :
    entries.length ? 'No follow-ups in this view.' : 'Add a follow-up to get started.';
}

form.addEventListener('submit', event => {
  event.preventDefault();
  const fields = new FormData(form);
  const title = fields.get('title').trim();
  if (!title) {
    form.elements.title.setCustomValidity('Enter a title.');
    form.elements.title.reportValidity();
    return;
  }
  const previous = entries.find(entry => entry.id === editingId);
  const entry = {
    id: previous?.id ?? crypto.randomUUID(),
    title,
    contact: fields.get('contact').trim(),
    date: fields.get('date'),
    notes: fields.get('notes').trim(),
    done: previous?.done ?? false
  };
  const next = previous ? entries.map(item => item.id === entry.id ? entry : item) : [...entries, entry];
  if (save(next, previous ? 'Follow-up updated.' : 'Follow-up added.')) dialog.close();
});

form.elements.title.addEventListener('input', () => form.elements.title.setCustomValidity(''));
dialog.addEventListener('close', () => form.elements.title.setCustomValidity(''));
document.querySelector('#add').addEventListener('click', () => openEditor());
document.querySelector('#cancel').addEventListener('click', () => dialog.close());
search.addEventListener('input', render);
document.querySelector('.filters').addEventListener('click', event => {
  const button = event.target.closest('button[data-filter]');
  if (!button) return;
  filter = button.dataset.filter;
  for (const option of document.querySelectorAll('[data-filter]')) {
    option.setAttribute('aria-pressed', String(option === button));
  }
  render();
});
window.addEventListener('focus', render);

if (location.hostname !== 'appassets.androidplatform.net') {
  const options = document.querySelector('#install-options');
  const promptButton = document.querySelector('#browser-prompt');
  const instructions = document.querySelector('#install-instructions');
  const browserInstall = document.querySelector('#browser-install');
  const standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone;
  let deferredPrompt;

  install.hidden = !!standalone;
  browserInstall.hidden = !!standalone;
  install.addEventListener('click', () => options.showModal());

  if (/iPad|iPhone|iPod/.test(navigator.userAgent) ||
      (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)) {
    instructions.textContent = 'In Safari, tap Share, then Add to Home Screen.';
  }

  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    deferredPrompt = event;
    promptButton.hidden = false;
    instructions.hidden = true;
  });

  promptButton.addEventListener('click', async () => {
    if (!deferredPrompt) return;
    const event = deferredPrompt;
    deferredPrompt = null;
    promptButton.hidden = true;
    instructions.hidden = false;
    try {
      await event.prompt();
    } catch {
      instructions.textContent = 'Open your browser’s menu and choose its install option.';
    }
  });

  window.addEventListener('appinstalled', () => {
    browserInstall.hidden = true;
    if (document.querySelector('#android-downloads').hidden) {
      install.hidden = true;
      options.close();
    }
  });

  fetch('./downloads/android-builds.json', { cache: 'no-store' })
    .then(response => response.ok ? response.json() : null)
    .then(metadata => {
      if (metadata?.androidApks === true) {
        document.querySelector('#android-downloads').hidden = false;
        install.hidden = false;
      }
    }).catch(() => {});

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').catch(error => console.warn('Offline setup failed', error));
  }
}

render();
