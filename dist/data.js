export const stages = { NEW: 'New', CONTACTED: 'Contacted', FOLLOW_UP: 'Follow-up', RESOLVED: 'Resolved' };
export const outcomes = { RESOLVED: 'Successful / Resolved', FOLLOW_UP: 'Follow-up Needed', HANG_UP: 'Hang-up', NO_ANSWER: 'No Answer', ISSUE: 'Issue / Escalation', PENDING: 'Pending', CANCELLED: 'Cancelled', OTHER: 'Other' };
export const priorities = { NORMAL: 'Normal', HIGH: 'High priority', URGENT: 'Urgent' };
export const reminders = { DEFAULT: 'Default', DAY: 'One day before', SAME_DAY: 'Same day at 9 AM', HOUR: 'One hour before', SHORT: '15 minutes before', CUSTOM: 'Custom date and time', OFF: 'Off' };
export const categories = ['Waiting for customer decision', 'Need more information', 'Price / budget concerns', 'Technical issue', 'Other'];
export const defaults = { themeMode: 'SYSTEM', selectedPalette: 'Violet', goal: 5, retentionDays: 90, lastExport: 0, exportLabel: '', onboardingDone: true, currency: 'PKR' };
const key = 'you-follow-ups.v1';
export const uuid = () => crypto.randomUUID();
export const timezone = () => Intl.DateTimeFormat().resolvedOptions().timeZone;
export const workspace = () => ({ customers: [], followUps: [], calls: [], timeline: [], settings: { ...defaults } });
export function customer(input = {}, time = Date.now()) {
  return { id: uuid(), displayName: '', phoneNumber: '', email: '', company: '', tags: '', notes: '', stage: 'NEW',
    priority: 'NORMAL', archived: false, demo: false, createdAt: time, updatedAt: time, ...input };
}
export function followUp(input, time = Date.now()) {
  return { id: uuid(), purpose: '', dueAt: time + 86400000, timezoneId: timezone(), reminderPolicy: 'DEFAULT',
    customReminderAt: null, timeCritical: false, status: 'OPEN', priority: 'NORMAL', note: '', createdAt: time,
    updatedAt: time, ...input };
}
export function event(data, customerId, eventType, referenceId, displayPayload, eventTime = Date.now()) {
  data.timeline.unshift({ id: uuid(), customerId, eventType, referenceId, eventTime, displayPayload });
}
export const day = (time = Date.now()) => {
  const d = new Date(time);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
};
export const dateText = time => new Intl.DateTimeFormat('en', { dateStyle: 'medium', timeStyle: 'short' }).format(Number(time));
export const dateField = time => {
  const d = new Date(time);
  return `${day(time)}T${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;
};
export function addDays(time, count) {
  const d = new Date(time);
  d.setDate(d.getDate() + count);
  return d.getTime();
}
export const priorityWeight = row => Object.keys(priorities).indexOf(row.priority);
export const orderFollow = (a, b) => a.dueAt - b.dueAt || priorityWeight(b) - priorityWeight(a);
export function dayGroup(row, time = Date.now()) {
  if (row.status === 'DONE') return 'Completed';
  if (row.status === 'DELETED') return 'Deleted';
  if (row.dueAt < time) return 'Overdue';
  if (day(row.dueAt) === day(time)) return 'Today';
  if (day(row.dueAt) === day(addDays(time, 1))) return 'Tomorrow';
  return 'Upcoming';
}
export function save(storage, data) {
  storage.setItem(key, JSON.stringify(data));
}
export function load(storage) {
  const current = storage.getItem(key);
  if (current != null) return validate(JSON.parse(current));
  const source = storage.getItem('follow-up.entries.v1');
  if (source == null) return workspace();
  const result = workspace();
  const owners = new Map();
  for (const row of JSON.parse(source)) {
    const name = (row.contact || 'General follow-ups').trim().slice(0, 100) || 'General follow-ups';
    if (!owners.has(name)) {
      const owner = customer({ displayName: name, stage: 'FOLLOW_UP' });
      owners.set(name, owner);
      result.customers.push(owner);
    }
    const owner = owners.get(name);
    const purpose = String(row.title || 'Follow-up').slice(0, 120);
    const dueAt = new Date(`${row.date}T12:00:00`).getTime() || Date.now();
    const item = followUp({ customerId: owner.id, purpose, dueAt, status: row.done ? 'DONE' : 'OPEN', note: String(row.notes || '') });
    result.followUps.push(item);
    event(result, owner.id, 'Follow-up imported', item.id, purpose);
  }
  save(storage, result);
  return result;
}

const validId = value => typeof value === 'string' && /^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.test(value);
const text = (value, maximum) => typeof value === 'string' && value.length <= maximum;
const time = value => Number.isSafeInteger(value) && value > 0 && value <= 8640000000000000;
export const periods = { today: 'Today', '24h': 'Last 24 Hours', '7d': 'Last 7 Days', '30d': 'Last 30 Days' };
export const pendingOutcomes = ['RESOLVED', 'CANCELLED', 'OTHER'];
export const entryOutcomes = ['RESOLVED', 'PENDING', 'FOLLOW_UP', 'HANG_UP', 'NO_ANSWER', 'ISSUE', 'CANCELLED', 'OTHER'];
export const stageForOutcome = {
  RESOLVED: 'RESOLVED', FOLLOW_UP: 'FOLLOW_UP', ISSUE: 'FOLLOW_UP', PENDING: 'FOLLOW_UP',
  HANG_UP: 'CONTACTED', NO_ANSWER: 'CONTACTED', CANCELLED: 'CONTACTED', OTHER: 'CONTACTED'
};
export const validCurrency = code => typeof code === 'string' && /^[A-Z]{3}$/.test(code);
export const currencyCodes = [...new Set([
  'PKR', 'USD', 'EUR', 'GBP', 'AED', 'SAR', 'CAD', 'AUD', 'INR', 'JPY', 'CNY',
  ...(Intl.supportedValuesOf?.('currency') ?? [])
])];

export function normalizeAmount(raw) {
  const normalized = raw.trim().replace(/[٠-٩۰-۹０-９]/g, digit => {
    const code = digit.charCodeAt(0);
    return String(code - (code >= 0xff10 ? 0xff10 : code >= 0x6f0 ? 0x6f0 : 0x660));
  }).replaceAll('٫','.').replaceAll('٬',',');
  let decimal;
  if (/^(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)$/.test(normalized)) decimal = normalized;
  else if (/^[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]*)?$/.test(normalized)) decimal = normalized.replaceAll(',','');
  else if (/^[0-9]*,[0-9]+$/.test(normalized)) decimal = normalized.replace(',','.');
  else return null;
  const [whole, fraction = ''] = decimal.split('.');
  return (whole.replace(/^0+/, '') || '0') + (fraction ? '.' + fraction : '');
}

function addAmounts(left, right) {
  const [leftWhole, leftFraction = ''] = left.split('.');
  const [rightWhole, rightFraction = ''] = right.split('.');
  const scale = Math.max(leftFraction.length, rightFraction.length);
  const total = BigInt(leftWhole + leftFraction.padEnd(scale, '0')) + BigInt(rightWhole + rightFraction.padEnd(scale, '0'));
  const digits = total.toString().padStart(scale + 1, '0');
  return scale ? digits.slice(0, -scale) + '.' + digits.slice(-scale) : digits;
}

export function withOutcome(call, outcome, amount, currency, now = Date.now()) {
  if (!Object.hasOwn(outcomes, outcome)) throw new Error('Choose a valid call outcome.');
  const dealAmount = outcome === 'RESOLVED' ? normalizeAmount(amount ?? '') : null;
  if (outcome === 'RESOLVED' && dealAmount == null) throw new Error('Enter a valid deal amount.');
  if (dealAmount != null && !validCurrency(currency)) throw new Error('Choose a valid currency.');
  return { ...call, outcome, dealAmount, dealCurrency: dealAmount == null ? null : currency, outcomeAt: Math.max(now, call.endedAt) };
}

export function outcomeLabel(call) {
  return call.dealAmount == null ? outcomes[call.outcome] : `${outcomes[call.outcome]} · ${call.dealCurrency} ${call.dealAmount}`;
}

export function resolvePending(data, id, outcome, amount, currency, now = Date.now()) {
  const index = data.calls.findIndex(c => c.id === id && c.outcome === 'PENDING');
  if (index < 0) throw new Error('This call already has a final result.');
  if (!pendingOutcomes.includes(outcome)) throw new Error('Choose a final outcome.');
  const saved = withOutcome(data.calls[index], outcome, amount, currency, now);
  const customer = data.customers.find(c => c.id === saved.customerId);
  if (!customer) throw new Error('Customer is unavailable.');
  data.calls[index] = saved;
  const latest = data.calls.filter(c => c.customerId === customer.id).sort((a,b) =>
    b.endedAt - a.endedAt || b.createdAt - a.createdAt || b.id.localeCompare(a.id))[0];
  if (latest?.id === id) {
    customer.stage = stageForOutcome[outcome];
    customer.updatedAt = now;
  }
  event(data, customer.id, 'Pending call updated', id, outcomeLabel(saved), now);
  completeLinkedFollowUp(data,saved,now);
  return saved;
}

export function completeLinkedFollowUp(data, call, now = Date.now()) {
  if (call.outcome !== 'RESOLVED') return;
  const followUp = data.followUps.find(f => f.id === call.followUpId && f.status === 'OPEN');
  if (!followUp) return;
  followUp.status = 'DONE';
  followUp.updatedAt = now;
  event(data,call.customerId,'Follow-up completed',followUp.id,followUp.purpose,now);
}

export function dealTotals(calls, start, end) {
  let successful = 0;
  const amounts = {};
  for (const call of calls) {
    const at = call.outcomeAt ?? call.endedAt;
    if (call.outcome !== 'RESOLVED' || at < start || at >= end) continue;
    successful++;
    if (call.dealAmount != null && validCurrency(call.dealCurrency)) {
      amounts[call.dealCurrency] = addAmounts(amounts[call.dealCurrency] ?? '0', call.dealAmount);
    }
  }
  return { successful, amounts: Object.fromEntries(Object.entries(amounts).sort(([a],[b]) => a.localeCompare(b))) };
}

export const validPhone = value => value === '' || (value.length <= 40 && /^[+()\d .#-]+$/.test(value) && (value.match(/\d/g)?.length ?? 0) >= 3);
function validZone(id) {
  if (typeof id !== 'string') return false;
  try { return Boolean(new Intl.DateTimeFormat('en', { timeZone: id })); } catch { return false; }
}
function unique(records, valid) {
  if (!Array.isArray(records)) throw new Error('Backup structure is invalid.');
  const rows = new Map();
  for (const row of records) {
    if (!row || !validId(row.id) || rows.has(row.id) || !valid(row)) throw new Error('A record is invalid or duplicated.');
    rows.set(row.id, row);
  }
  return rows;
}
export function validate(data) {
  const records = unique(data.customers, c => text(c.displayName, 100) && c.displayName.trim().length > 0 &&
    text(c.phoneNumber, 40) && validPhone(c.phoneNumber) && text(c.email, 160) && text(c.company, 150) && text(c.tags, 200) &&
    text(c.notes, 3000) && Object.hasOwn(stages,c.stage) && Object.hasOwn(priorities,c.priority) &&
    typeof c.archived === 'boolean' && typeof c.demo === 'boolean' && time(c.createdAt) && time(c.updatedAt));
  const follows = unique(data.followUps, f => records.has(f.customerId) && text(f.purpose,120) && f.purpose.trim() &&
    time(f.dueAt) && validZone(f.timezoneId) && Object.hasOwn(reminders,f.reminderPolicy) && ['OPEN','DONE','DELETED'].includes(f.status) &&
    (f.reminderPolicy !== 'CUSTOM' || (time(f.customReminderAt) && f.customReminderAt <= f.dueAt)) &&
    Object.hasOwn(priorities,f.priority) && text(f.note,2000) && time(f.createdAt) && time(f.updatedAt));
  unique(data.calls, c => records.has(c.customerId) && time(c.startedAt) && time(c.endedAt) && c.startedAt <= c.endedAt &&
    Object.hasOwn(outcomes,c.outcome) && text(c.issueCategory,120) && text(c.summaryNote,200) && text(c.reflection,200) &&
    (c.followUpId == null || follows.get(c.followUpId)?.customerId === c.customerId) && time(c.createdAt) &&
    (c.outcomeAt == null || (time(c.outcomeAt) && c.outcomeAt >= c.endedAt)) &&
    (c.dealAmount == null ? c.dealCurrency == null :
      c.outcome === 'RESOLVED' && typeof c.dealAmount === 'string' && normalizeAmount(c.dealAmount) === c.dealAmount && validCurrency(c.dealCurrency)));
  unique(data.timeline, e => records.has(e.customerId) && text(e.eventType,120) && text(e.referenceId,120) && typeof e.displayPayload === "string" && time(e.eventTime));
  const settings = { ...defaults, ...data.settings };
  if (!['SYSTEM','LIGHT','DARK'].includes(settings.themeMode) || !['Violet','Ocean','Forest'].includes(settings.selectedPalette) ||
      !Number.isInteger(settings.goal) || settings.goal < 1 || settings.goal > 500 || !Number.isInteger(settings.retentionDays) ||
      settings.retentionDays < 1 || settings.retentionDays > 3650 || !validCurrency(settings.currency)) throw new Error('Settings are invalid.');
  return { ...data, settings };
}
export function visible(data, showDemo) {
  const customers = data.customers.filter(c => c.demo === showDemo);
  const ids = new Set(customers.map(c => c.id));
  return { ...data, customers, followUps: data.followUps.filter(f => ids.has(f.customerId)),
    calls: data.calls.filter(c => ids.has(c.customerId)), timeline: data.timeline.filter(e => ids.has(e.customerId)) };
}
export function report(data, period, now = Date.now()) {
  const days = period === '30d' ? 30 : period === '7d' ? 7 : 1;
  const first = day(addDays(now, -(days - 1)));
  const last = day(now);
  const start = period === '24h' ? now - 86400000 : new Date(first + 'T00:00:00').getTime();
  const inPeriod = time => period === '24h' ? time >= start && time <= now : day(time) >= first && day(time) <= last;
  const calls = data.calls.filter(c => inPeriod(c.endedAt) && c.endedAt <= now);
  const due = data.followUps.filter(f => inPeriod(f.dueAt) && f.status !== 'DELETED');
  const counts = Object.fromEntries(Object.keys(outcomes).map(o => [o, 0]));
  const issues = new Map();
  const hours = new Map();
  const daily = new Map();
  for (const call of calls) {
    counts[call.outcome]++;
    const date = day(call.endedAt);
    daily.set(date, (daily.get(date) ?? 0) + 1);
    if (call.outcome === 'ISSUE') issues.set(call.issueCategory || 'Uncategorized', (issues.get(call.issueCategory || 'Uncategorized') ?? 0) + 1);
    if (call.outcome === 'RESOLVED') {
      const hour = new Date(call.startedAt).getHours();
      hours.set(hour, (hours.get(hour) ?? 0) + 1);
    }
  }
  const best = [...hours].sort((a,b) => b[1] - a[1] || a[0] - b[0])[0]?.[0];
  const topIssues = [...issues].sort((a,b) => b[1] - a[1]);
  const tips = [];
  if (due.some(f => f.status === 'OPEN' && f.dueAt < now)) tips.push('Start with overdue follow-ups to clear the oldest commitments.');
  if (counts.NO_ANSWER + counts.HANG_UP > calls.length / 3) tips.push('Try a different time window for customers you could not reach.');
  if (topIssues.length) tips.push(`Prepare a short response for the most common issue: ${topIssues[0][0]}.`);
  if (best != null) tips.push('Use your strongest calling window for high-priority customers.');
  if (!tips.length) tips.push('Log each outcome and write one clear next action after every call.');
  const trend = period === '24h' ? Array.from({ length: 4 }, (_, i) => {
    const startOfSlot = start + i * 21600000;
    const label = new Intl.DateTimeFormat('en', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(startOfSlot);
    return [label, calls.filter(c => c.endedAt >= startOfSlot && (i === 3 || c.endedAt < startOfSlot + 21600000)).length];
  }) : Array.from({ length: days }, (_, i) => {
    const time = addDays(now, i - days + 1);
    return [new Intl.DateTimeFormat('en', days <= 7 ? { weekday: 'short' } : { month: 'short', day: 'numeric' }).format(time), daily.get(day(time)) ?? 0];
  });
  return { counts, issues: topIssues, best, tips: tips.slice(0, 3), trend, calls: calls.length, scheduled: due.length,
    deals: dealTotals(data.calls,start,now+1),
    success: calls.length ? Math.floor(counts.RESOLVED * 100 / calls.length) : 0,
    completion: due.length ? Math.floor(due.filter(f => f.status === 'DONE').length * 100 / due.length) : 0 };
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const magic = encoder.encode('YOUFUP1');
const checksum = async raw => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(raw))), byte => byte.toString(16).padStart(2,'0')).join('');
async function backupKey(password, salt) {
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', salt, iterations: 600000, hash: 'SHA-256' }, material, { name: 'AES-GCM', length: 256 }, false, ['encrypt','decrypt']);
}
export async function encrypt(data, password) {
  if (password.length < 10) throw new Error('Choose a passphrase with at least ten characters.');
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const raw = JSON.stringify(data);
  const manifest = { formatVersion: 2, createdAt: Date.now(), checksum: await checksum(raw), encrypted: true, appVersion: '1.2.0' };
  const payload = encoder.encode(JSON.stringify({ manifest, data: raw }));
  if (payload.length > 100 * 1024 * 1024) throw new Error('Backup is too large.');
  const encrypted = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: magic }, await backupKey(password, salt), payload);
  return new Blob([magic, salt, iv, encrypted], { type: 'application/octet-stream' });
}
export async function decrypt(blob, password) {
  if (blob.size < 51 || blob.size > 100 * 1024 * 1024 + 51) throw new Error('Backup size is invalid.');
  const buffer = new Uint8Array(await blob.arrayBuffer());
  if (decoder.decode(buffer.subarray(0, 7)) !== 'YOUFUP1') throw new Error('Choose a YoU Follow ups backup.');
  const iv = buffer.slice(23,35);
  const salt = buffer.slice(7,23);
  const raw = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: magic }, await backupKey(password,salt), buffer.subarray(35));
  const packet = JSON.parse(decoder.decode(raw));
  if (![1,2].includes(packet.manifest?.formatVersion) || !packet.manifest.encrypted || !time(packet.manifest.createdAt) ||
      !packet.manifest.appVersion || packet.manifest.checksum !== await checksum(packet.data)) throw new Error('Backup integrity could not be verified.');
  return validate(JSON.parse(packet.data));
}
