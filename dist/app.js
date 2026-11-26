import {
  stages, outcomes, priorities, reminders, categories, uuid, timezone, workspace, customer, followUp,
  event, day, dateText, dateField, addDays, orderFollow, dayGroup, load, save, visible, report, encrypt, decrypt, validPhone,
  periods, currencyCodes, entryOutcomes, pendingOutcomes, stageForOutcome, withOutcome, outcomeLabel, completeLinkedFollowUp, resolvePending,
  greeting, journeySnapshot, remindersFor, reminderDays, profileReview, fonts, validPhoto
} from './data.js';

const view = document.querySelector('#view');
const modal = document.querySelector('#modal');
const toast = document.querySelector('#toast');
const query = selector => document.querySelector(selector);
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const titles = {
  journey: 'Customer Journey', agenda: 'Follow-up Agenda', customers: 'Customers', detail: 'Customer Detail',
  edit: 'Customer', follow: 'Follow-up Detail', wizard: 'Add Follow-up', session: 'Start Call Session',
  call: 'Post-Call Reflection', pending: 'Pending Calls', 'pending-result': 'Update Pending Call', performance: 'Performance Coach', settings: 'Settings', reminders: 'Reminders'
};
const nav = [['journey','Journey','home'],['customers','Customers','person'],['agenda','Agenda','calendar_month'],['performance','Performance','bar_chart']];
const purposes = ['Discuss proposal','Confirm interest','Share product info','Address concern / issue','Agree next step','Other'];
const themeMedia = matchMedia('(prefers-color-scheme: dark)');
let initializationError = false;
let data;
try { data = load(localStorage); }
catch { data = workspace(); initializationError = true; }
let page = 'journey';
let selected = '';
let tab = 'Overview';
let demo = false;
let wizard;
let call;
let installPrompt;
let downloads = false;
let undo;
let toastTimer;
let pendingRestore;
let pendingSafety;
let period = 'today';
let reminderDay = 'today';
let initialProfile = false;
let profileChoice = '';
let roadIntroPlayed = false;
let pendingDraft;
let limit = 40;
const filters = { q: '', status: 'OPEN', range: 'All dates', date: day(), priority: 'ANY', archive: 'active' };
const button = (text, action, id = '', kind = 'outline') =>
  `<button type="button" class="${kind}" data-action="${action}"${id ? ` data-id="${escape(id)}"` : ''}>${escape(text)}</button>`;
const icon = name => `<span class="neon-icon" aria-hidden="true">${name}</span>`;
const iconButton = (name, label, action, className = '') =>
  `<button type="button" class="icon-button ${className}" aria-label="${escape(label)}" data-action="${action}">${icon(name)}</button>`;
const dots = (label, menu) => `<details class="overflow-menu">
  <summary class="icon-button" aria-label="${escape(label)}">${icon('more_vert')}</summary><div class="menu-items">${menu}</div></details>`;
const chip = (text, style = '') => `<span class="chip ${style}">${escape(text)}</span>`;
const metric = (name, value, icon) => `<div class="metric"><span class="glyph" aria-hidden="true">${icon}</span><b>${value}</b><span>${name}</span></div>`;
const list = (rows, empty) => rows.length ? `<ul class="plain">${rows.map(row => `<li>${row}</li>`).join('')}</ul>` : `<p class="note">${empty}</p>`;
const initials = name => name.trim().split(/\s+/).slice(0,2).map(word => [...word][0]?.toUpperCase()).join('');
const owner = id => data.customers.find(c => c.id === id);
const follow = id => data.followUps.find(f => f.id === id);
const options = (map, current) => Object.entries(map).map(([id,name]) => `<option value="${escape(id)}" ${id === current ? 'selected' : ''}>${escape(name)}</option>`).join('');
const select = (label, name, map, value, attribute = '') => `<label>${label}<select name="${name}" ${attribute}>${options(map,value)}</select></label>`;
const input = (label, name, value = '', extra = '') => `<label>${label}<input name="${name}" value="${escape(value)}" ${extra}></label>`;
const area = (label, name, value, max) => `<label>${label}<textarea name="${name}" maxlength="${max}">${escape(value)}</textarea></label>`;
const prefs = value => Object.fromEntries(value.map(key => [key,key]));
const profileAvatar = (settings, large = false) => {
  const photo = validPhoto(settings.photo) ? `<img alt="Profile photo" src="data:image/jpeg;base64,${escape(settings.photo)}">` :
    `<span aria-hidden="true">${escape(initials(settings.operatorName || settings.displayName) || 'UY')}</span>`;
  return `<span class="profile-avatar${large ? ' profile-avatar-large' : ''}">${photo}</span>`;
};
const avatar = name => `<span class="avatar" aria-hidden="true">${escape(initials(name))}</span>`;
const row = (name, sub, action, id, end = '›', before = '') =>
  `<button type="button" class="list-link" data-action="${action}" data-id="${escape(id)}">${before}<span class="grow"><strong>${escape(name)}</strong><small>${escape(sub)}</small></span>${end}</button>`;

function tell(message, undoAction) {
  clearTimeout(toastTimer);
  undo = undoAction;
  toast.replaceChildren(document.createTextNode(message));
  if (undo) toast.insertAdjacentHTML('beforeend', button('Undo','undo','','link'));
  toast.hidden = false;
  toastTimer = setTimeout(() => { toast.hidden = true; undo = null; }, undo ? 10000 : 4500);
}
function write(update, message) {
  if (initializationError) throw new Error('Stored records could not be opened. Export or recover your browser data before editing.');
  const next = structuredClone(load(localStorage));
  update(next);
  save(localStorage, next);
  data = next;
  if (message) tell(message);
}
function changeStatus(id, status) {
  write(next => {
    const item = next.followUps.find(f => f.id === id);
    if (!item) throw new Error('Follow-up is unavailable.');
    item.status = status;
    item.updatedAt = Date.now();
    event(next, item.customerId, { OPEN:'Follow-up reopened',DONE:'Follow-up completed',DELETED:'Follow-up deleted' }[status], id, item.purpose);
  }, status === 'DONE' ? 'Follow-up completed' : status === 'OPEN' ? 'Follow-up reopened' : '');
}
function go(next, id = '', replace = false) {
  page = next;
  selected = id;
  limit = 40;
  if (next !== 'detail') tab = 'Overview';
  history[replace ? 'replaceState' : 'pushState']({ page: next, id }, '', '#' + next);
  render();
  window.scrollTo({ top: 0, behavior: 'instant' });
}
function handleBack() {
  if (page === 'wizard' && wizard?.step > 0) { wizard.step--; render(); return; }
  if (history.state?.page !== 'journey') history.back();
  else go('journey','',true);
}
function startWizard(customerId = '', previous = null, sourceCall = '', returnPage = page) {
  const current = visible(data,demo).customers.filter(c => !c.archived);
  wizard = { step: 0, sourceCall, returnPage, returnId: selected, previous,
    draft: previous ? { ...previous } : followUp({ customerId: current.find(c => c.id === customerId)?.id ?? current[0]?.id ?? '' }),
    custom: Boolean(previous && !purposes.includes(previous.purpose)) };
  go('wizard', previous?.id);
}
function startCall(customerId, followId = '') {
  call = { id: uuid(), customerId, followId, started: 0, ended: 0, outcome: '', issue: categories[0], note: '', reflection: '', amount: '', more: false };
  go('call',customerId);
}
function applyTheme() {
  const s = data.settings;
  document.documentElement.dataset.theme = (s.themeMode === 'NEON' || s.themeMode === 'DARK' || (s.themeMode === 'SYSTEM' && themeMedia.matches)) ? 'dark' : 'light';
  document.documentElement.dataset.palette = s.selectedPalette;
  document.documentElement.dataset.font = s.font.toLowerCase();
}
function header(w, time) {
  const title = data.settings.displayName || 'Customer Journey';
  query('#sidebar-title').textContent = title;
  document.title = title;
  const menu = dots('More options','<button type="button" data-nav="settings">Settings</button><button type="button" disabled>Help · Coming soon</button>');
  const tag = demo ? '<span class="chip">Demo</span>' : '';
  if (page === 'journey') {
    const date = new Intl.DateTimeFormat('en-GB',{ weekday:'short', month:'short', day:'numeric' }).format(time);
    const count = remindersFor(w,'today',time).length;
    return `<div class="top-left">${profileAvatar(data.settings)}<div class="grow title-group">
      <h1>${escape(title)}</h1><time datetime="${day(time)}">${date}</time></div></div>
      <div class="top-right">${tag}<button type="button" class="icon-button reminder-button" data-action="reminders" aria-label="Reminders, ${count} today">
        ${icon('notifications')}${count ? `<span class="reminder-badge" aria-hidden="true">${count}</span>` : ''}</button>${menu}</div>`;
  }
  const screen = page === 'edit' ? (selected ? 'Edit Customer' : 'Add Customer') : titles[page];
  const right = page === 'reminders' ? iconButton('more_vert','Select reminder day','reminder-period') :
    nav.some(([route]) => route === page) ? menu : '';
  return `<div class="top-left">${iconButton('arrow_back','Go back','back')}<h1>${screen}</h1></div><div class="top-right">${tag}${right}</div>`;
}

function render() {
  applyTheme();
  const w = visible(data,demo);
  const time = Date.now();
  for (const id of ['side-nav','bottom-nav']) {
    query('#'+id).innerHTML = nav.map(([route,label,glyph]) => `<button class="subtle" data-nav="${route}" ${page === route ? 'aria-current="page"' : ''}>
      <span class="nav-icon">${icon(glyph)}</span>${label}</button>`).join('');
  }
  query('#app-header').innerHTML = header(w,time);
  const screens = {
    journey: () => journey(w,time), reminders: () => reminderScreen(w,time), agenda: () => agenda(w), customers: () => customers(w), session: () => customers(w,true),
    edit: () => customerForm(owner(selected)), detail: () => detail(owner(selected),w), follow: () => followDetail(follow(selected)),
    wizard: () => wizardScreen(w), call: callScreen, pending: () => pendingList(w), 'pending-result': pendingScreen, performance: () => performance(w), settings
  };
  view.innerHTML = (initializationError ? '<p class="card error">Saved records could not be loaded. Editing is paused to protect your data.</p>' : '') + screens[page]();
  requestAnimationFrame(() => { drawTrend(w); updateTimer(); });
}

function journey(w, time) {
  const snapshot = journeySnapshot(w,period,time);
  const calls = w.calls.filter(c => day(c.endedAt) === day(time));
  const active = new Set(w.customers.filter(c => !c.archived).map(c => c.id));
  const due = w.followUps.filter(f => active.has(f.customerId) && f.status === 'OPEN' && day(f.dueAt) === day(time)).length;
  const review = calls.filter(c => c.outcome === 'ISSUE').length;
  const pending = w.calls.filter(c => c.outcome === 'PENDING').length;
  const checkpoint = (text,count,glyph,place) => `<div class="checkpoint checkpoint-${place}">
    <div class="checkpoint-label">${icon(glyph)}<div><span>${text}</span><b>${count}</b></div></div><i class="checkpoint-dot" aria-hidden="true"></i></div>`;
  const amounts = Object.entries(snapshot.deals.amounts);
  const hello = [greeting(time),w.settings.operatorName].filter(Boolean).join(', ');
  const animate = !roadIntroPlayed && w.settings.profileReview >= profileReview;
  if (animate) roadIntroPlayed = true;
  const next = w.followUps.filter(f => active.has(f.customerId) && f.status === 'OPEN').sort(orderFollow)[0];
  const tiles = [['Calls',calls.length,'call','today-calls'],['Follow-ups',due,'calendar_month','today-agenda'],['Needs review',review,'flag','today-calls']];
  const progress = Math.min(100,100 * calls.length / w.settings.goal);
  return `<div class="neon-dashboard"><section class="hero" aria-label="Customer journey, ${periods[period]}">
    <div class="hero-copy"><h2>${escape(hello)}!</h2><p class="hero-period">${periods[period]}</p></div>
    <div class="hero-map${animate ? ' hero-animate' : ''}">
      ${checkpoint('New',snapshot.newCustomers,'person_add','new')}${checkpoint('Contacted',snapshot.contacted,'call','contacted')}
      ${checkpoint('Follow-up',snapshot.followUps,'calendar_month','follow')}${checkpoint('Successful',snapshot.deals.successful,'check_circle','resolved')}
      ${animate ? ['new','contacted','follow','resolved'].map(place => `<span aria-hidden="true" class="road-token road-token-${place}"></span>`).join('') : ''}
      ${iconButton('more_vert','Select journey period','journey-period','road-more')}
    </div></section>
    <div class="stack neon-summary">
      <section class="mission-strip">
        <div class="strip-deal"><h2>Deal amount</h2><div class="payment-values">${amounts.length ? amounts.map(([currency,amount]) =>
          `<strong>${escape(currency)} ${escape(amount)}</strong>`).join('') : `<strong>${escape(w.settings.currency)} 0</strong>`}</div></div>
        <div class="strip-mission"><span class="mission-ring" style="--progress:${progress}%" aria-hidden="true"></span>
          <div><h2>Today’s mission</h2><strong>${calls.length} / ${w.settings.goal} calls</strong></div>
          ${dots('Mission options', `<button type="button" data-action="session">Start call session</button><button type="button" data-action="pending-list">Pending calls (${pending})</button><button type="button" data-nav="settings">Change call goal</button>`)}
        </div>
      </section>
      <div class="stats">${tiles.map(([label,count,glyph,action]) => `<button type="button" class="metric neon-metric" data-action="${action}">${icon(glyph)}<b>${count}</b><span>${label}</span></button>`).join('')}</div>
      <details class="card neon-activity"><summary>Activity and next action</summary>
        <h2>Next Best Action</h2><p>${next ? escape((owner(next.customerId)?.displayName ?? '') + ' · ' + next.purpose) :
          'Add a customer and schedule the next call.'}</p>
        ${next ? button('Open Follow-up','open-follow',next.id) : button('Add Follow-up','add-follow')}
        <h2>Recent Activity</h2>${list(w.timeline.slice(0,5).map(e =>
          row(e.eventType,(owner(e.customerId)?.displayName ?? '') + ' · ' + dateText(e.eventTime),'open-customer',e.customerId)),
          'Saved activity will appear here.')}</details>
    </div></div>`;
}

function reminderScreen(w, time) {
  const rows = remindersFor(w,reminderDay,time);
  return `<section class="card narrow"><h2>${reminderDays[reminderDay]} · ${rows.length} reminders</h2>
    ${list(rows.slice(0,limit).map(({followUp:f,at}) => row(f.purpose,
      [owner(f.customerId)?.displayName,'Reminder · '+dateText(at),'Follow-up due · '+dateText(f.dueAt)].filter(Boolean).join(' · '),
      'open-follow',f.id,'›',icon('notifications'))),'No reminders scheduled for this day.')}
    ${rows.length > limit ? button('Show more','more') : ''}</section>`;
}
function canvas(id) {
  const el = query(id);
  if (!el) return null;
  const rect = el.getBoundingClientRect();
  const ratio = devicePixelRatio || 1;
  el.width = Math.round(rect.width * ratio);
  el.height = Math.round(rect.height * ratio);
  const ctx = el.getContext('2d');
  ctx.scale(ratio,ratio);
  return [ctx, rect.width, rect.height];
}
function customers(w, session = false) {
  const term = filters.q.toLowerCase().trim();
  const items = w.customers.filter(c => c.archived === (!session && filters.archive === 'archived') &&
    [c.displayName,c.phoneNumber,c.company,c.tags].some(t => t.toLowerCase().includes(term)));
  return `<div class="narrow stack"><div class="card stack"><div class="fields">${input('Search customers','q',filters.q,'type="search" data-filter')}
    ${session ? '' : select('Show','archive',{ active:'Active', archived:'Archived' },filters.archive,'data-filter')}</div>
    ${session ? '<p class="note">Select the customer for this call.</p>' : button('+ Add Customer','edit-customer','','button wide')}</div>
    <section class="card" id="listing"><h2>${session ? 'Choose Customer' : 'Customer List'}</h2>
    ${list(items.slice(0,limit).map(c => row(c.displayName,c.company || c.phoneNumber || priorities[c.priority],session ? 'start-call' : 'open-customer',c.id,
      chip(stages[c.stage],c.stage === 'RESOLVED' ? 'good' : ''),avatar(c.displayName))),'No customers match this view.')}
    ${items.length > limit ? button('Show more','more') : ''}</section></div>`;
}
function customerForm(c) {
  return `<form class="card stack narrow" data-form="customer"><div class="fields">
    ${input('Name','displayName',c?.displayName,'required maxlength="100"')}${input('Phone','phoneNumber',c?.phoneNumber,'type="tel" maxlength="40"')}
    ${input('Company','company',c?.company,'maxlength="150"')}${input('Email','email',c?.email,'type="email" maxlength="160"')}
    ${input('Tags','tags',c?.tags,'maxlength="200"')}${select('Priority','priority',priorities,c?.priority ?? 'NORMAL')}</div>
    ${area('Notes','notes',c?.notes,3000)}<button class="button">Save Customer</button></form>`;
}
function timeline(entries) {
  return entries.length ? `<ol class="timeline">${entries.map(e => `<li><strong>${escape(e.eventType)}</strong><p class="note">${dateText(e.eventTime)}</p>
    <p>${escape(e.displayPayload)}</p></li>`).join('')}</ol>` : '<p class="note">No interactions yet.</p>';
}
function detail(c,w) {
  if (!c) return '<p class="card">Customer unavailable.</p>';
  const history = w.timeline.filter(e => e.customerId === c.id);
  const open = w.followUps.filter(f => f.customerId === c.id && f.status === 'OPEN').sort(orderFollow);
  const last = w.calls.filter(call => call.customerId === c.id).sort((a,b) => b.endedAt-a.endedAt)[0];
  const tabs = ['Overview','History','Notes'].map(name => `<button data-action="tab" data-id="${name}" ${tab === name ? 'aria-current="true"' : ''}>${name}</button>`).join('');
  const summary = `<section class="card"><h2>Customer Overview</h2>${select('Status','stage',stages,c.stage,'data-stage')}
    <progress max="4" value="${Object.keys(stages).indexOf(c.stage)+1}" aria-label="Customer stage"></progress>
    <p class="note">Last contact · ${last ? dateText(last.endedAt) : 'None yet'}</p>
    <p class="note">Next action · ${escape(open[0]?.purpose || 'None scheduled')}</p>
    ${c.phoneNumber ? `<p class="note">Phone · ${escape(c.phoneNumber)}</p>` : ''}${c.email ? `<p class="note">Email · ${escape(c.email)}</p>` : ''}
    ${c.tags ? `<p class="note">Tags · ${escape(c.tags)}</p>` : ''}</section>`;
  return `<div class="narrow stack"><section class="card"><div class="line">${avatar(c.displayName)}<div class="grow">
    <h2>${escape(c.displayName)}</h2><p class="note">${escape(c.company)}</p>${chip(c.archived ? 'Archived' : priorities[c.priority],c.priority === 'NORMAL' ? '' : 'urgent')}</div>
    ${c.archived ? '' : button('Call','start-call',c.id,'button')}</div></section><div class="tabs" aria-label="Customer detail tabs">${tabs}</div>
    ${tab === 'Overview' ? summary + `<section class="card"><h2>Interaction History (${history.length})</h2>${timeline(history.slice(0,5))}</section>` :
      tab === 'History' ? `<section class="card"><h2>Interaction History (${history.length})</h2>${timeline(history.slice(0,limit))}
        ${history.length > limit ? button('Show more','more') : ''}</section>` : `<section class="card"><h2>Customer Notes</h2><p class="note">${escape(c.notes || 'No customer notes yet.')}</p></section>`}
    ${open.length && tab === 'Overview' ? `<section class="card"><h2>Scheduled Follow-ups</h2>${list(open.slice(0,limit).map(f => row(f.purpose,dateText(f.dueAt),'open-follow',f.id)), '')}</section>` : ''}
    <div class="actions">${c.archived ? '' : button('+ Add Follow-up','add-follow',c.id,'button')}${button('Edit Customer','edit-customer',c.id)}
    ${button(c.archived ? 'Restore Customer' : 'Archive Customer','archive',c.id)}</div></div>`;
}
function agenda(w) {
  const term = filters.q.trim().toLowerCase();
  const last = day(addDays(new Date(filters.date + 'T12:00').getTime(), filters.range === 'Week' ? 6 : 0));
  const results = w.followUps.filter(f => owner(f.customerId)?.archived === false && f.status === filters.status &&
    (filters.priority === 'ANY' || f.priority === filters.priority) &&
    (filters.range === 'All dates' || (day(f.dueAt) >= filters.date && day(f.dueAt) <= last)) &&
    [f.purpose,f.note,owner(f.customerId)?.displayName ?? ''].some(t => t.toLowerCase().includes(term))).sort(orderFollow);
  const groups = {};
  for (const followUp of results.slice(0,limit)) {
    (groups[dayGroup(followUp)] ??= []).push(followUp);
  }
  return `<div class="narrow stack"><section class="card"><div class="fields">
    ${input('Search','q',filters.q,'type="search" data-filter')}${select('Range','range',prefs(['All dates','Day','Week']),filters.range,'data-filter')}
    ${select('Status','status',{OPEN:'Open',DONE:'Completed',DELETED:'Deleted'},filters.status,'data-filter')}
    ${select('Priority','priority',{ANY:'Any',...priorities},filters.priority,'data-filter')}</div>
    ${filters.range === 'All dates' ? '' : `<div class="actions">${button('‹ Previous','range-prev')}${input('Start date','date',filters.date,'type="date" data-filter')}${button('Next ›','range-next')}</div>`}
    ${button('+ Add Follow-up','add-follow','','button wide')}</section><div id="listing" class="stack">
    ${Object.entries(groups).map(([title,items]) => `<section class="card"><h2>${title}</h2>${list(items.map(f => row(f.purpose,
      (owner(f.customerId)?.displayName || '') + ' · ' + dateText(f.dueAt),'open-follow',f.id,chip(priorities[f.priority],f.priority === 'URGENT' ? 'urgent' : ''))),'')}</section>`).join('')}
    ${results.length ? '' : '<p class="card note">No follow-ups match this view.</p>'}
    ${results.length > limit ? button('Show more','more') : ''}</div></div>`;
}
function followDetail(f) {
  if (!f) return '<p class="card">Follow-up unavailable.</p>';
  const c = owner(f.customerId);
  return `<div class="narrow stack"><section class="card"><h2>${escape(f.purpose)}</h2><p>${escape(c?.displayName)}</p>
    <p class="note">${dateText(f.dueAt)} · ${escape(f.timezoneId)}</p>${chip(dayGroup(f),f.status === 'DONE' ? 'good' : '')}
    <p class="note">Reminder preference · ${reminders[f.reminderPolicy]}</p>${f.note ? `<p class="note">${escape(f.note)}</p>` : ''}</section>
    <div class="actions">${button('Open Customer','open-customer',f.customerId)}${f.status === 'DELETED' ? button('Restore Follow-up','reopen',f.id,'button') :
      c?.archived ? '' : button(f.status === 'DONE' ? 'Reopen' : 'Mark Done',f.status === 'DONE' ? 'reopen' : 'done',f.id,'button')}</div>
    ${f.status === 'DELETED' || c?.archived ? '' : `<div class="actions">${button('Log a Call','start-follow-call',f.id)}${button('Edit Follow-up','edit-follow',f.id)}
       ${button('Delete Follow-up','delete-follow',f.id,'outline danger')}</div>`}</div>`;
}
function wizardScreen(w) {
  const available = w.customers.filter(c => !c.archived);
  if (!wizard || !available.length) return `<div class="card narrow"><p>Add a customer before you schedule a follow-up.</p>${button('+ Add Customer','edit-customer','','button')}</div>`;
  const {step,draft,custom,previous} = wizard;
  const stepBar = ['Purpose','Schedule','Note'].map((label,i) => `<span ${i === step ? 'aria-current="step"' : ''}><b>${i+1}</b>${label}</span>`).join('');
  let section;
  if (step === 0) section = `<div><h2>Purpose of Follow-up</h2><p class="note">What’s the main goal of this follow-up?</p></div>
    ${previous ? `<p>${escape(owner(draft.customerId)?.displayName)}</p>` : select('Customer','customerId',Object.fromEntries(available.map(c => [c.id,c.displayName])),draft.customerId)}
    ${purposes.map(p => `<label class="radio"><input type="radio" name="purposeChoice" value="${escape(p)}" required ${(custom ? p === 'Other' : draft.purpose === p) ? 'checked' : ''}>${escape(p)}</label>`).join('')}
    ${custom ? input('Purpose','customPurpose',draft.purpose,'required maxlength="120"') : ''}`;
  else if (step === 1) section = `<h2>Schedule Follow-up</h2>
    ${input('Due date and time','dueAt',dateField(draft.dueAt),'type="datetime-local" required')}
    ${select('Reminder preference','reminderPolicy',reminders,draft.reminderPolicy)}
    ${draft.reminderPolicy === 'CUSTOM' ? input('Custom reminder time','customReminderAt',dateField(draft.customReminderAt ?? draft.dueAt - 3600000),'type="datetime-local" required') : ''}
    <p class="note">Use the Android app for background reminders. Your saved reminder preference is included in backup.</p>`;
  else section = `<h2>Add a Note</h2>${area('Notes (optional)','note',draft.note,2000)}${select('Priority','priority',priorities,draft.priority)}
    <p class="note">Due ${dateText(draft.dueAt)}</p>`;
  return `<div class="narrow stack"><div class="stepbar">${stepBar}</div><form class="card stack" data-form="wizard">${section}
    <button class="button">${step === 2 ? 'Save Follow-up' : 'Next ›'}</button></form></div>`;
}
function outcomeOptions(ids, selected, name) {
  return `<div class="result">${ids.map(id => `<label class="radio"><input type="radio" name="${name}" value="${id}"
    required ${id === selected ? 'checked' : ''}>${outcomes[id]}</label>`).join('')}</div>`;
}
function amountField(amount) {
  return input(`Deal amount (${escape(data.settings.currency)})`, 'amount', amount,
    'inputmode="decimal" required placeholder="1250.50" autocomplete="off"');
}
function pendingList(w) {
  const items = w.calls.filter(c => c.outcome === 'PENDING').sort((a,b) => b.endedAt - a.endedAt);
  return `<section class="card narrow"><h2>Pending Calls (${items.length})</h2>
    ${list(items.slice(0,limit).map(c => row(owner(c.customerId)?.displayName ?? '', 'Call made · '+dateText(c.endedAt),
      'open-pending',c.id)), 'No pending calls. Calls saved as Pending will appear here.')}
    ${items.length > limit ? button('Show more','more') : ''}</section>`;
}
function pendingScreen() {
  const saved = data.calls.find(c => c.id === selected && c.outcome === 'PENDING');
  if (!saved) return '<p class="card">This call no longer needs a result.</p>';
  if (pendingDraft?.id !== selected) pendingDraft = { id:selected, outcome:'', amount:'' };
  return `<div class="stack narrow"><section class="card"><h2>${escape(owner(saved.customerId)?.displayName ?? '')}</h2>
    <p class="note">Call made · ${dateText(saved.endedAt)}</p><p class="note">${escape(saved.summaryNote)}</p></section>
    <form class="card stack" data-form="pending"><h2>Final Outcome</h2>
    ${outcomeOptions(pendingOutcomes,pendingDraft.outcome,'pendingOutcome')}
    ${pendingDraft.outcome === 'RESOLVED' ? amountField(pendingDraft.amount) : ''}
    <button class="button">Save Result</button></form></div>`;
}
function callScreen() {
  const c = owner(call?.customerId);
  if (!c) return '<p class="card">Choose a customer to log a call.</p>';
  const prior = follow(call.followId)?.note || c.notes;
  return `<div class="narrow stack"><section class="card"><h2>Call with ${escape(c.displayName)}</h2><p class="note">${escape(c.phoneNumber || 'No phone number saved')}</p>
    ${prior ? `<p class="note">Prior note · ${escape(prior)}</p>` : ''}
    <div class="actions">${c.phoneNumber ? `<a class="button" href="tel:${escape(c.phoneNumber.replace(/[^+\d]/g,''))}">Open Phone Dialer</a>` : ''}
      ${button(call.started && !call.ended ? 'Stop Timer' : 'Start Timer','timer')}<span id="timer" class="chip" aria-label="Call timer">00:00</span></div></section>
    <form class="card stack" data-form="call"><div><h2>Call Outcome</h2><p class="note">What was the result of this call?</p></div>
    ${outcomeOptions(entryOutcomes.slice(0,2),call.outcome,'outcome')}
    ${button(call.more ? 'Fewer outcomes' : 'More outcomes','more-outcomes','','link')}
    ${call.more ? outcomeOptions(entryOutcomes.slice(2),call.outcome,'outcome') : ''}
    ${call.outcome === 'RESOLVED' ? amountField(call.amount) : ''}
    ${call.outcome === 'ISSUE' ? select('Issue category','issue',prefs(categories),call.issue) : ''}
    ${area('What went well?','note',call.note,200)}${area('What can I improve next time?','reflection',call.reflection,200)}
    <button class="button">${call.outcome === 'PENDING' ? 'Save as Pending' : 'Save Call Result'}</button></form></div>`;
}
function updateTimer() {
  const item = query('#timer');
  if (!item || !call?.started) return;
  const seconds = Math.max(0,Math.floor(((call.ended || Date.now()) - call.started)/1000));
  item.textContent = [Math.floor(seconds/60),seconds%60].map(n => String(n).padStart(2,'0')).join(':');
}
function periodPicker() {
  return `<div class="periods" role="group" aria-label="Report period">${Object.entries(periods).map(([id,label]) =>
    `<button class="outline" data-action="period" data-id="${id}" aria-pressed="${period === id}">${label}</button>`).join('')}</div>`;
}
function dealSummary(totals) {
  const items = Object.entries(totals.amounts);
  return `<p>${totals.successful} successful deals</p>${items.length ? items.map(([currency,amount]) =>
    `<p class="deal-value">${escape(currency)} ${escape(amount)}</p>`).join('') : '<p class="note">No deal amounts recorded.</p>'}`;
}
function performance(w) {
  const r = report(w,period);
  const totalIssues = r.issues.reduce((sum, [,n]) => sum+n,0);
  return `<div class="columns"><div class="stack"><section class="card">${periodPicker()}
    <h2>Your Performance</h2><div class="stats">${metric('Calls',r.calls,'☎')}${metric('Follow-ups',r.scheduled,'↗')}${metric('Resolved',r.counts.RESOLVED,'✓')}</div>
    <p>${r.success}% success rate · ${r.completion}% follow-up completion rate</p>
    <p class="note">Best calling hour · ${r.best == null ? 'Need successful calls' : String(r.best).padStart(2,'0')+':00'}</p>
    <p class="note">${Object.entries(outcomes).filter(([id]) => id !== 'RESOLVED').map(([id,label]) => escape(label)+': '+r.counts[id]).join(' · ')}</p></section>
    <section class="card"><h2>Journey Trend</h2><canvas id="trend" role="img" aria-label="${escape(r.trend.map(([d,n]) => d+': '+n).join(', '))}"></canvas>
    <p class="note">${r.trend.at(0)[0]} – ${r.trend.at(-1)[0]} · ${period === '24h' ? 'Calls per six-hour interval' : 'Calls per day'}</p></section></div>
    <div class="stack"><section class="card"><h2>Deal Summary</h2>${dealSummary(r.deals)}</section><section class="card"><h2>Issue Clusters</h2>${r.issues.length ? r.issues.map(([issue,n]) => `<p>${escape(issue)} · ${n}</p>
      <progress value="${n}" max="${totalIssues}" aria-label="${escape(issue)}"></progress>`).join('') : '<p class="note">No issues in this period.</p>'}</section>
    <section class="card"><h2>Coach Recommendations</h2>${r.tips.map(t => `<p>✓ ${escape(t)}</p>`).join('')}</section></div></div>`;
}
function drawTrend(w) {
  const surface = canvas('#trend');
  if (!surface) return;
  const [c,width,height] = surface;
  const points = report(w,period).trend.map(pair => pair[1]);
  const max = Math.max(1,...points);
  const pad = 8;
  const style = getComputedStyle(document.documentElement);
  c.strokeStyle = style.getPropertyValue('--line');
  for (let n=0;n<5;n++) {
    const y = pad + (height-pad*2)*n/4;
    c.beginPath(); c.moveTo(pad,y); c.lineTo(width-pad,y); c.stroke();
  }
  const plotted = points.map((n,i) => [points.length === 1 ? width/2 : pad + (width-pad*2)*i/(points.length-1),
    height-pad-(height-pad*2)*n/max]);
  c.strokeStyle = c.fillStyle = style.getPropertyValue('--primary');
  c.lineWidth = 2.5; c.beginPath(); plotted.forEach(([x,y],i) => i ? c.lineTo(x,y) : c.moveTo(x,y)); c.stroke();
  plotted.forEach(([x,y]) => { c.beginPath(); c.arc(x,y,3.2,0,Math.PI*2); c.fill(); });
}
function settings() {
  const s = data.settings;
  return `<div class="narrow stack"><section class="card"><h2>Profile</h2>
    ${profileAvatar(s)}
    <p>${escape(s.operatorName || 'Your name has not been set.')}</p>
    <p class="note">${escape(s.displayName || 'Customer Journey')}</p>
    <div class="actions">${button('Edit name and display','edit-profile')}
      ${button(s.photo ? 'Change profile photo' : 'Upload profile photo','choose-profile-photo')}
      ${s.photo ? button('View profile photo','view-profile-photo') : ''}</div>
    <input id="profile-photo-file" type="file" accept="image/*" hidden></section><section class="card"><h2>Appearance</h2><div class="fields">
    ${select('Theme','themeMode',{NEON:'Neon',SYSTEM:'System',LIGHT:'Light',DARK:'Dark'},s.themeMode,'data-setting')}
    ${select('Palette','selectedPalette',prefs(['Violet','Ocean','Forest']),s.selectedPalette,'data-setting')}
    ${select('Font','font',fonts,s.font,'data-setting')}
    ${select('Deal currency','currency',prefs([...new Set([...currencyCodes,s.currency])]),s.currency,'data-setting')}
    ${select('Daily goal','goal',prefs([...new Set([1,3,5,10,15,20,30,50,s.goal])].map(String)),String(s.goal),'data-setting')}</div>
    <p class="note">Existing deals keep the currency recorded when saved.</p><p class="note">Custom layout presets · Coming Soon</p></section>
    <section class="card"><h2>Encrypted Backup</h2><p class="note">Keep an external backup before clearing browser data or changing devices.</p>
    <p class="note">${s.lastExport ? 'Last verified export · '+dateText(s.lastExport) : 'No verified export yet.'}</p>
    <div class="actions">${button('Export Backup','export-backup','','button')}${button('Import Backup','import-backup')}</div></section>
    <section class="card"><h2>Data Retention</h2>
    ${select('Deleted follow-up retention','retentionDays',Object.fromEntries([...new Set([30,90,180,365,s.retentionDays])].map(n => [n,n+' days'])),String(s.retentionDays),'data-setting')}
    ${button('Purge older deleted follow-ups','purge','','outline danger')}</section>
    <section class="card"><h2>Demo Workspace</h2><p class="note">Sample records are separate from your work.</p>
    <div class="actions">${data.customers.some(c => c.demo) ? button(demo?'View My Workspace':'View Demo Workspace','toggle-demo') + button('Remove Demo Data','remove-demo') :
      button('Load Demo Workspace','demo')}</div></section>
    <section class="card"><h2>Install &amp; Privacy</h2><p class="note">Follow-ups stay on this device. Android includes device lock and background reminders.</p>${button('Install Options','install')}</section>
    <section class="card"><h2>Sign in &amp; Sync</h2>${chip('Coming Soon')}<p class="note">Your work is stored locally.</p></section></div>`;
}

function openModal(title, body) {
  modal.innerHTML = `<div class="stack"><h2 id="modal-title">${escape(title)}</h2>${body}</div>`;
  if (!modal.open) modal.showModal();
}
function openProfile(initial, choice = '') {
  initialProfile = initial;
  profileChoice = choice;
  const s = data.settings;
  if (initial && !choice) {
    openModal('Set up your profile',
      button('New profile','setup-new','','button') + button('Use existing profile','setup-existing') + button('Not now','skip-profile','','link'));
    return;
  }
  const useSaved = choice !== 'new';
  openModal(initial ? (useSaved ? 'Confirm your profile' : 'New profile') : 'Edit profile',`<form class="stack" data-form="profile">
    ${input('Your name','operatorName',useSaved ? s.operatorName : '', 'maxlength="60" autocomplete="given-name" ' + (initial ? 'required' : ''))}
    ${input('Dashboard display title (optional)','displayName',useSaved ? s.displayName : '', 'maxlength="60" autocomplete="off"')}
    <p data-error class="error" role="alert"></p>
    <div class="actions">${button(initial ? 'Back' : 'Cancel',initial ? 'setup-back' : 'close-modal')}<button class="button">Save</button></div>
  </form>`);
}

function saveProfile(name, display, message, replacePhoto = false) {
  write(next => {
    Object.assign(next.settings,{operatorName:name,displayName:display,profileConfigured:true,profileReview});
    if (replacePhoto) next.settings.photo='';
  },message);
  initialProfile = false;
  profileChoice = '';
  modal.close();
  render();
}

async function prepareProfilePhoto(file) {
  if (!file || file.size > 30000000) throw new Error('Choose an image smaller than 30 MB.');
  const image = await createImageBitmap(file,{imageOrientation:'from-image'});
  try {
    const surface = document.createElement('canvas');
    surface.width = surface.height = 192;
    const context = surface.getContext('2d');
    const size = Math.min(image.width,image.height);
    context.fillStyle = '#202440';
    context.fillRect(0,0,192,192);
    context.drawImage(image,(image.width-size)/2,(image.height-size)/2,size,size,0,0,192,192);
    const photo = surface.toDataURL('image/jpeg',.87).split(',')[1];
    if (!validPhoto(photo)) throw new Error('This image could not be prepared.');
    return photo;
  } finally { image.close(); }
}

function confirmAction(title, detail, action) {
  openModal(title,`<p>${escape(detail)}</p><div class="actions">${button('Cancel','close-modal')}${button('Confirm',action,'','button')}</div>`);
}
function installOptions() {
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const instructions = ios ? 'In Safari, tap Share, then Add to Home Screen.' : 'Use the install option in your browser menu.';
  openModal('Install YoU Follow ups',`<p>${instructions}</p>${installPrompt ? button('Install Website App','install-pwa','','button') : ''}
    ${downloads ? '<h3>Android APKs</h3><div class="actions"><a class="button" href="downloads/follow-up-release.apk" download>Release APK</a><a class="outline" href="downloads/follow-up-debug.apk" download>Debug APK</a></div>' : ''}
    ${button('Close','close-modal')}`);
}
function resetPassword() {
  modal.querySelectorAll('input[type=password]').forEach(el => { el.value = ''; });
}
function backupDialog(isExport) {
  openModal(isExport ? 'Export Encrypted Backup' : 'Verify Backup', `<form data-form="backup-${isExport ? 'export' : 'import'}" class="stack">
    ${isExport ? '<p>Choose a passphrase you can remember.</p>' : '<label>Backup file<input type="file" name="file" required accept=".youbackup,application/octet-stream"></label>'}
    ${input('Passphrase','password','','type="password" required minlength="10" maxlength="512" autocomplete="new-password"')}
    ${isExport ? input('Confirm passphrase','confirmation','','type="password" required minlength="10" maxlength="512" autocomplete="new-password"') : ''}
    <p class="error" role="alert" data-error></p>
    <div class="actions">${button('Cancel','close-modal')}<button class="button">${isExport ? 'Export' : 'Verify'}</button></div></form>`);
}
function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url),2000);
}
function seedDemo() {
  write(next => {
    const profiles = [
      ['Amit Verma','Acme Supplies','FOLLOW_UP','HIGH'],['Neha Singh','Northstar Studio','RESOLVED','NORMAL'],
      ['Rakesh Patel','Orbit Works','CONTACTED','NORMAL'],['Sara Khan','Cedar & Co.','NEW','NORMAL']
    ];
    for (const [index,[displayName,company,stage,priority]] of profiles.entries()) {
      const c = customer({ displayName, company, stage, priority, demo:true, tags:'Sample', notes:'Demo customer.' });
      next.customers.push(c);
      event(next,c.id,'Customer added',c.id,'Sample customer.');
      if (index === 0 || index === 2) {
        const f = followUp({ customerId:c.id, purpose: index ? 'Share product info' : 'Discuss proposal',
          dueAt:addDays(Date.now(), index ? 1 : -1),priority });
        next.followUps.push(f); event(next,c.id,'Follow-up scheduled',f.id,f.purpose);
      }
      if (index !== 3) {
        const end = addDays(Date.now(),-index)-60000;
        const outcome = ['ISSUE','RESOLVED','NO_ANSWER'][index];
        const call = { id:uuid(),customerId:c.id,startedAt:end-120000,endedAt:end,outcome,issueCategory:index ? '' : categories[0],
          summaryNote:'Sample conversation.',reflection:'',followUpId:null,createdAt:end };
        next.calls.unshift(call); event(next,c.id,'Call logged',call.id,outcomes[call.outcome],end);
      }
    }
  },'Demo workspace added.');
  demo = true;
  render();
}
function mutateFollow(f) {
  const now = Date.now();
  write(next => {
    const i = next.followUps.findIndex(row => row.id === f.id);
    const c = next.customers.find(c => c.id === f.customerId);
    if (!c || c.archived) throw new Error('Choose an active customer.');
    if (i < 0) next.followUps.push(f);
    else next.followUps[i] = f;
    if (f.status === 'OPEN' && c.stage !== 'FOLLOW_UP') {
      c.stage = 'FOLLOW_UP';
      c.updatedAt = now;
      event(next,c.id,'Status updated',c.id,stages[c.stage]);
    }
    const call = next.calls.find(log => log.id === wizard.sourceCall && log.customerId === c.id);
    if (call) call.followUpId = f.id;
    event(next,c.id,i < 0 ? 'Follow-up scheduled' : 'Follow-up updated',f.id,f.purpose);
  }, 'Follow-up saved.');
}
const actions = {
  back: handleBack,
  'edit-profile': () => openProfile(false),
  'setup-new': () => openProfile(true,'new'),
  'setup-existing': () => openProfile(true,'existing'),
  'setup-back': () => openProfile(true),
  'skip-profile': () => saveProfile(data.settings.operatorName,data.settings.displayName,''),
  'choose-profile-photo': () => query('#profile-photo-file')?.click(),
  'view-profile-photo': () => openModal('Profile photo',profileAvatar(data.settings,true)+button('Close','close-modal')),
  'journey-period': () => openModal('Journey period', periodPicker()),
  reminders: () => { reminderDay = 'today'; go('reminders'); },
  'reminder-period': () => openModal('Reminder day', `<div class="periods" role="group" aria-label="Reminder day">
    ${Object.entries(reminderDays).map(([id,label]) =>
      `<button type="button" class="outline" data-action="set-reminder-period" data-id="${id}" aria-pressed="${reminderDay === id}">${label}</button>`).join('')}</div>`),
  'set-reminder-period': id => {
    if (Object.hasOwn(reminderDays,id)) { reminderDay=id; limit=40; modal.close(); render(); }
  },
  'today-calls': () => { period='today'; go('performance'); },
  'today-agenda': () => { filters.range='Day'; filters.date=day(); filters.status='OPEN'; filters.q=''; go('agenda'); },
  'edit-customer': id => go('edit',id),
  'open-customer': id => go('detail',id),
  'open-follow': id => go('follow',id),
  'add-follow': id => startWizard(id),
  'edit-follow': id => { const f = follow(id); if (f) startWizard(f.customerId,f); },
  session: () => go('session'),
  'pending-list': () => go('pending'),
  'open-pending': id => { pendingDraft = { id, outcome:'', amount:'' }; go('pending-result',id); },
  'more-outcomes': () => {
    call.more = !call.more;
    if (!call.more && !entryOutcomes.slice(0,2).includes(call.outcome)) call.outcome = '';
    render();
  },
  period: id => { if (Object.hasOwn(periods,id)) { period=id; if (modal.open) modal.close(); render(); } },
  'start-call': id => startCall(id),
  'start-follow-call': id => { const f = follow(id); if (f) startCall(f.customerId,id); },
  tab: id => { tab = id; limit = 40; render(); },
  more: () => { limit += 40; render(); },
  done: id => { changeStatus(id,'DONE'); render(); },
  reopen: id => { changeStatus(id,'OPEN'); render(); },
  'delete-follow': id => confirmAction('Delete Follow-up?', 'You can undo this action or restore the record in Agenda.', 'confirm-delete'),
  'confirm-delete': () => {
    const id = selected;
    const prior = follow(id)?.status;
    changeStatus(id,'DELETED'); modal.close(); render();
    tell('Follow-up deleted.', () => { if (follow(id)?.status === 'DELETED') changeStatus(id,prior); render(); });
  },
  archive: () => confirmAction(owner(selected)?.archived ? 'Restore Customer?' : 'Archive Customer?',
    'Call and follow-up history will be retained.', 'confirm-archive'),
  'confirm-archive': () => {
    write(next => {
      const c = next.customers.find(c => c.id === selected);
      c.archived = !c.archived; c.updatedAt = Date.now(); event(next,c.id,c.archived ? 'Archived' : 'Restored',c.id,c.displayName);
    },'Customer updated.');
    modal.close(); render();
  },
  undo: () => { const action = undo; undo = null; action?.(); },
  'range-prev': () => { filters.date = day(addDays(new Date(filters.date+'T12:00').getTime(), filters.range === 'Week' ? -7 : -1)); render(); },
  'range-next': () => { filters.date = day(addDays(new Date(filters.date+'T12:00').getTime(), filters.range === 'Week' ? 7 : 1)); render(); },
  timer: () => {
    if (call.started && !call.ended) call.ended = Date.now();
    else { call.started = Date.now(); call.ended = 0; }
    render();
  },
  install: installOptions,
  'install-pwa': async () => {
    const prompt = installPrompt;
    installPrompt = null;
    modal.close();
    await prompt?.prompt();
  },
  'close-modal': () => { resetPassword(); modal.close(); },
  'export-backup': () => backupDialog(true),
  'import-backup': () => backupDialog(false),
  'confirm-restore': () => {
    if (!pendingRestore) return;
    if (initializationError) {
      save(localStorage,pendingRestore);
      data = pendingRestore;
      initializationError = false;
    } else write(next => Object.assign(next,pendingRestore));
    if (pendingSafety) download(pendingSafety,`YoU-Follow-ups-before-restore-${day()}.youbackup`);
    pendingRestore = null; pendingSafety = null; demo = false; modal.close(); render(); tell('Verified backup restored.');
  },
  demo: seedDemo,
  'toggle-demo': () => { demo = !demo; go('journey'); },
  'remove-demo': () => confirmAction('Remove Demo Data?', 'This only removes sample customers and their interactions.', 'confirm-remove-demo'),
  'confirm-remove-demo': () => {
    write(next => {
      const ids = new Set(next.customers.filter(c => c.demo).map(c => c.id));
      next.customers = next.customers.filter(c => !c.demo);
      for (const name of ['followUps','calls','timeline']) next[name] = next[name].filter(row => !ids.has(row.customerId));
    },'Demo data removed.');
    demo = false; modal.close(); render();
  },
  purge: () => confirmAction('Purge Deleted Follow-ups?', 'Older deleted follow-ups will be permanently removed.', 'confirm-purge'),
  'confirm-purge': () => {
    write(next => {
      const before = addDays(Date.now(),-next.settings.retentionDays);
      const ids = new Set(next.followUps.filter(f => f.status === 'DELETED' && f.updatedAt < before).map(f => f.id));
      next.followUps = next.followUps.filter(f => !ids.has(f.id));
      for (const call of next.calls) if (ids.has(call.followUpId)) call.followUpId = null;
    },'Retention cleanup complete.');
    modal.close(); render();
  }
};

const forms = {
  profile: values => {
    const name = values.operatorName.trim().slice(0,60);
    if (initialProfile && !name) throw new Error('Enter your name or choose Not now.');
    saveProfile(name,values.displayName.trim().slice(0,60),'Profile saved.',profileChoice === 'new');
  },
  customer: values => {
    const previous = owner(selected);
    const c = previous ? { ...previous } : customer({ demo });
    for (const key of ['displayName','phoneNumber','email','company','tags','notes','priority']) c[key] = values[key].trim();
    if (!c.displayName || !validPhone(c.phoneNumber)) throw new Error('Enter a name and a valid phone number.');
    c.updatedAt = Date.now();
    write(next => {
      const index = next.customers.findIndex(row => row.id === c.id);
      if (index < 0) next.customers.unshift(c);
      else next.customers[index] = c;
      event(next,c.id,index < 0 ? 'Customer added' : previous.notes !== c.notes ? 'Note updated' : 'Customer updated',
        c.id,index >= 0 && previous.notes !== c.notes ? c.notes : c.displayName);
    },'Customer saved.');
    go('detail',c.id,true);
  },
  wizard: () => {
    const { step, draft, returnPage, returnId } = wizard;
    if (step === 0 && !draft.purpose.trim()) throw new Error('Enter a follow-up purpose.');
    if (step === 1 && (!Number.isFinite(draft.dueAt) || (draft.reminderPolicy === 'CUSTOM' &&
        (!Number.isFinite(draft.customReminderAt) || draft.customReminderAt > draft.dueAt)))) throw new Error('Set a valid reminder at or before the due time.');
    if (step < 2) { wizard.step++; render(); return; }
    const f = { ...draft, purpose:draft.purpose.trim(),note:draft.note.trim(),timezoneId: wizard.previous?.timezoneId ?? timezone(),updatedAt:Date.now() };
    mutateFollow(f);
    wizard = null;
    go(returnPage === 'wizard' ? 'agenda' : returnPage, returnId, true);
  },
  call: values => {
    if (!call?.outcome) throw new Error('Choose a call outcome.');
    const { id,customerId,outcome,issue,note,reflection,followId,started } = call;
    const end = call.ended || Date.now();
    write(next => {
      const c = next.customers.find(c => c.id === customerId);
      if (!c || c.archived || next.calls.some(c => c.id === id)) throw new Error('This call is unavailable.');
      const saved = withOutcome({ id,customerId,startedAt:Math.min(started || end,end),endedAt:end,issueCategory:outcome === 'ISSUE' ? issue : '',
        summaryNote:note.trim(),reflection:reflection.trim(),followUpId:followId || null,createdAt:Date.now() },outcome,values.amount,next.settings.currency);
      next.calls.unshift(saved);
      c.stage = stageForOutcome[outcome]; c.updatedAt = Date.now();
      event(next,c.id,'Call logged',id,[outcomeLabel(saved),saved.issueCategory,saved.summaryNote,saved.reflection && 'Improve next time: '+saved.reflection].filter(Boolean).join(' · '));
      completeLinkedFollowUp(next,saved);
    },'Call result saved.');
    call = null;
    if (outcome === 'PENDING') { go('pending','',true); return; }
    go('detail',customerId,true);
    if (outcome === 'FOLLOW_UP') startWizard(customerId,null,id,'detail');
  },
  pending: values => {
    write(next => resolvePending(next,selected,pendingDraft.outcome,values.amount,next.settings.currency),'Call result updated.');
    pendingDraft = null;
    go('pending','',true);
  },
  'backup-export': async values => {
    if (values.password !== values.confirmation) throw new Error('Passphrases must match.');
    const next = structuredClone(data);
    next.settings.lastExport = Date.now();
    next.settings.exportLabel = 'External document';
    const blob = await encrypt(next,values.password);
    await decrypt(blob,values.password);
    write(current => { current.settings.lastExport = next.settings.lastExport; current.settings.exportLabel = next.settings.exportLabel; },'Encrypted backup verified.');
    download(blob,`YoU-Follow-ups-${day()}.youbackup`);
    resetPassword(); modal.close(); render();
  },
  'backup-import': async values => {
    let verified;
    try { verified = await decrypt(values.file,values.password); }
    catch { throw new Error('Verification failed. Check your passphrase or choose another file. No records were changed.'); }
    if (!initializationError && (data.customers.length || data.calls.length || data.followUps.length)) {
      pendingSafety = await encrypt(data,values.password);
    }
    pendingRestore = verified;
    resetPassword();
    openModal('Replace Current Data?',`<p>Verified ${verified.customers.length} customers, ${verified.followUps.length} follow-ups, and ${verified.calls.length} calls.</p>
      <p>Restoring replaces the records in this browser.</p><div class="actions">${button('Cancel','close-modal')}
      ${button('Replace Data','confirm-restore','','button')}</div>`);
  }
};

document.addEventListener('click', async e => {
  document.querySelectorAll('.overflow-menu[open]').forEach(menu => { if (!menu.contains(e.target) || !e.target.closest('summary')) menu.open = false; });
  const navButton = e.target.closest('[data-nav]');
  if (navButton) { e.preventDefault(); filters.q = ''; go(navButton.dataset.nav); return; }
  const control = e.target.closest('[data-action]');
  if (!control) return;
  try { await actions[control.dataset.action]?.(control.dataset.id || ''); }
  catch (error) { tell(error.message || 'Action could not be completed.'); }
});
function onFilter(el, update) {
  const focus = el === document.activeElement;
  const caret = el.type === 'search' ? el.selectionStart : null;
  update();
  render();
  if (focus) {
    const next = query(`[data-filter][name="${el.name}"]`);
    next?.focus();
    if (caret != null) next?.setSelectionRange(caret,caret);
  }
}
document.addEventListener('input', e => {
  const el = e.target;
  if (el.dataset.filter !== undefined && el.name === 'q') {
    onFilter(el,()=>{ filters.q=el.value; limit=40; });
  }
  if (page === 'wizard' && wizard) {
    const f = wizard.draft;
    if (['customerId','note','priority'].includes(el.name)) f[el.name] = el.value;
    if (el.name === 'customPurpose') f.purpose = el.value;
    if (el.name === 'dueAt') f.dueAt = new Date(el.value).getTime();
    if (el.name === 'customReminderAt') f.customReminderAt = new Date(el.value).getTime();
  }
  if (page === 'call' && call && ['note','reflection','issue','amount'].includes(el.name)) call[el.name]=el.value;
  if (page === 'pending-result' && el.name === 'amount' && pendingDraft) pendingDraft.amount=el.value;
});
document.addEventListener('change', async e => {
  const el = e.target;
  try {
    if (el.id === 'profile-photo-file') {
      const file = el.files?.[0];
      if (file) {
        const photo = await prepareProfilePhoto(file);
        write(next => { next.settings.photo = photo; },'Profile photo saved.');
        render();
      }
      return;
    }
    if (el.dataset.filter !== undefined && el.name !== 'q') onFilter(el, () => { if (el.value) filters[el.name]=el.value; limit=40; });
    if (el.dataset.stage !== undefined) {
      write(next => {
        const c=next.customers.find(c=>c.id===selected);
        c.stage=el.value; c.updatedAt=Date.now(); event(next,c.id,'Status updated',c.id,stages[c.stage]);
      },'Status updated.');
      render();
    }
    if (el.dataset.setting !== undefined) {
      write(next => { next.settings[el.name]=['goal','retentionDays'].includes(el.name) ? Number(el.value) : el.value; },'Settings saved.');
      render();
    }
    if (page === 'wizard' && el.name === 'purposeChoice') {
      wizard.custom=el.value==='Other'; wizard.draft.purpose=wizard.custom?'':el.value; render();
      if (wizard.custom) query('[name=customPurpose]').focus();
    }
    if (page === 'wizard' && el.name === 'reminderPolicy') {
      wizard.draft.reminderPolicy=el.value;
      if (el.value === 'CUSTOM' && wizard.draft.customReminderAt == null) wizard.draft.customReminderAt=wizard.draft.dueAt-3600000;
      render();
    }
    if (page === 'call' && el.name === 'outcome') { call.outcome=el.value; render(); }
    if (page === 'pending-result' && el.name === 'pendingOutcome') { pendingDraft.outcome=el.value; render(); }
  } catch (error) { tell(error.message || 'The change could not be saved.'); }
});
document.addEventListener('submit', async e => {
  const name = e.target.dataset.form;
  if (!name) return;
  e.preventDefault();
  const submit = e.submitter;
  if (submit?.disabled) return;
  if (submit) submit.disabled=true;
  try { await forms[name](Object.fromEntries(new FormData(e.target))); }
  catch (error) {
    const msg = e.target.querySelector('[data-error]');
    if (msg) msg.textContent = error.message || 'The operation could not be completed.';
    else tell(error.message || 'The change could not be saved.');
  } finally { if (submit) submit.disabled=false; }
});
modal.addEventListener('cancel', e => {
  if (initialProfile) { e.preventDefault(); actions['skip-profile'](); }
});
modal.addEventListener('close', () => { initialProfile = false; pendingRestore = null; pendingSafety = null; resetPassword(); });
window.addEventListener('popstate', e => {
  const next = e.state?.page;
  if (next && Object.hasOwn(titles,next)) { page=next; selected=e.state.id || ''; limit=40; render(); }
  else go('journey','',true);
});
window.addEventListener('beforeinstallprompt', e => { e.preventDefault(); installPrompt=e; });
window.addEventListener('appinstalled', () => { installPrompt=null; if (modal.open) modal.close(); });
window.addEventListener('resize', () => { drawTrend(visible(data,demo)); });
themeMedia.addEventListener('change',render);
setInterval(updateTimer,1000);
const refreshDashboard = () => {
  if (page === 'journey' && !modal.open && !query('.overflow-menu[open], .neon-activity[open]')) render();
};
setInterval(refreshDashboard,60000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshDashboard(); });
window.addEventListener('storage', e => {
  if (e.key !== 'you-follow-ups.v1') return;
  try { data=load(localStorage); initializationError=false; render(); }
  catch { initializationError=true; render(); }
});
fetch('downloads/android-builds.json',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(meta=>{ downloads=meta?.androidApks===true; }).catch(()=>{});
if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(()=>{});
const initial = location.hash.slice(1);
go(['journey','agenda','customers','performance','settings','pending','reminders'].includes(initial) ? initial : 'journey','',true);
if (!initializationError && data.settings.profileReview < profileReview) openProfile(true);
