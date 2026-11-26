import test from 'node:test';
import assert from 'node:assert/strict';
import { addDays, customer, followUp, load, save, workspace, report, encrypt, decrypt, dayGroup, validate } from '../dist/data.js';

const storage = () => {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key,value) };
};
test('legacy data migrates once and preserves status and notes', () => {
  const disk = storage();
  const old = [{ title:'Call back', contact:'Amit', notes:'Send proposal', date:'2026-11-26', done:true }];
  disk.setItem('follow-up.entries.v1',JSON.stringify(old));
  const first = load(disk);
  const second = load(disk);
  assert.deepEqual(first,second);
  assert.equal(first.customers[0].displayName,'Amit');
  assert.equal(first.followUps[0].status,'DONE');
  assert.equal(first.followUps[0].note,'Send proposal');
});

test('repository serializes large record sets and aggregates actual outcomes', () => {
  const w = workspace();
  const person = customer({displayName:'Amit'});
  w.customers.push(person);
  const time = new Date(2026,10,26,15).getTime();
  for (let i=0;i<120;i++) w.followUps.push(followUp({ customerId:person.id, purpose:'Proposal '+i,dueAt:time,status:i%2?'OPEN':'DONE' },time));
  for (const outcome of ['RESOLVED','NO_ANSWER','ISSUE']) w.calls.push({ id:crypto.randomUUID(),customerId:person.id,startedAt:time,
    endedAt:time,outcome,issueCategory:outcome==='ISSUE'?'Price':'',followUpId:null,summaryNote:'',reflection:'',createdAt:time });
  const disk=storage();
  save(disk,w);
  const persisted=load(disk);
  const result=report(persisted,'7d',time);
  assert.equal(result.calls,3);
  assert.equal(result.success,33);
  assert.equal(result.completion,50);
  assert.equal(result.trend.at(-1)[1],3);
  assert.equal(dayGroup({ status:'OPEN',dueAt:addDays(time,1) },time),'Tomorrow');
});

test('encrypted backup rejects wrong passphrases, modified payloads, and duplicate ids', async () => {
  const w=workspace();
  w.customers.push(customer({displayName:'Example'}));
  const password='correct horse battery staple';
  const blob=await encrypt(w,password);
  assert.deepEqual(await decrypt(blob,password),w);
  await assert.rejects(decrypt(blob,'incorrect'));
  const bytes=new Uint8Array(await blob.arrayBuffer());
  bytes[bytes.length-1]^=1;
  await assert.rejects(decrypt(new Blob([bytes]),password));
  assert.throws(()=>validate({...w,customers:[w.customers[0],w.customers[0]]}),/duplicated/);
});

test('pending calls resolve once and totals use exact decimals and the resolution date', async () => {
  const { normalizeAmount, resolvePending, withOutcome } = await import('../dist/data.js');
  assert.equal(normalizeAmount('۱٬۲۵۰٫۵۰'),'1250.50');
  assert.equal(normalizeAmount('12,50'),'12.50');
  assert.equal(normalizeAmount('-10'),null);
  const data = workspace();
  const owner = customer({displayName:'Customer'});
  data.customers.push(owner);
  const now = new Date(2026,10,26,1).getTime();
  const yesterday = now - 7200000;
  const base = { customerId:owner.id, startedAt:yesterday, endedAt:yesterday, createdAt:yesterday,
    summaryNote:'First call', reflection:'', followUpId:null, issueCategory:'' };
  const pending = { ...base,id:crypto.randomUUID(),outcome:'PENDING' };
  data.calls.push(pending,
    withOutcome({ ...base,id:crypto.randomUUID() },'RESOLVED','0.000000001','PKR', yesterday),
    withOutcome({ ...base,id:crypto.randomUUID() },'RESOLVED','3.00','USD', now));
  const cancelled = { ...base,id:crypto.randomUUID(),outcome:'PENDING' };
  data.calls.push(cancelled);
  assert.throws(() => resolvePending(data,pending.id,'RESOLVED','','PKR',now),/deal amount/);
  assert.equal(data.calls[0].outcome,'PENDING');
  const saved = resolvePending(data,pending.id,'RESOLVED','9999999999999999999999.123456789','PKR',now);
  assert.equal(saved.endedAt,yesterday);
  assert.equal(saved.outcomeAt,now);
  assert.equal(saved.summaryNote,'First call');
  assert.throws(() => resolvePending(data,pending.id,'CANCELLED',null,null,now),/final result/);
  assert.equal(resolvePending(data,cancelled.id,'CANCELLED','123','PKR',now).dealAmount,null);
  const today = report(data,'today',now);
  const rolling = report(data,'24h',now);
  assert.equal(today.calls,0);
  assert.deepEqual(today.deals,{ successful:2,amounts:{ PKR:'9999999999999999999999.123456789',USD:'3.00' } });
  assert.equal(rolling.calls,4);
  assert.equal(rolling.trend.reduce((n,[,value])=>n+value,0),rolling.calls);
  assert.equal(rolling.deals.amounts.PKR,'9999999999999999999999.123456790');
  data.settings.currency = 'EUR';
  const password = 'restore deals and currency';
  assert.deepEqual(await decrypt(await encrypt(data,password),password),data);
});

test('neon dashboard dates, profile defaults, and weekend reminders', async () => {
  const { greeting, journeySnapshot, remindersFor, reminderTime } = await import('../dist/data.js');
  const local = (month,hour) => new Date(2026,month-1,29,hour).getTime();
  assert.equal(greeting(local(11,1)),'Good night');
  assert.equal(greeting(local(11,12)),'Good afternoon');
  assert.equal(greeting(local(11,17)),'Good evening');
  assert.equal(greeting(local(7,17)),'Good afternoon');
  assert.equal(greeting(local(7,21)),'Good night');
  const w = workspace();
  const sunday = local(11,12);
  const c = customer({displayName:'Amit'},addDays(sunday,-6));
  w.customers.push(c);
  w.followUps.push(...[0,1,2].map(n => followUp({customerId:c.id,purpose:'Follow-up '+n,dueAt:addDays(sunday,n),reminderPolicy:'DAY'})));
  assert.equal(remindersFor(w,'today',sunday)[0].followUp.purpose,'Follow-up 1');
  assert.equal(remindersFor(w,'yesterday',sunday)[0].followUp.purpose,'Follow-up 0');
  assert.equal(remindersFor(w,'tomorrow',sunday)[0].followUp.purpose,'Follow-up 2');
  assert.equal(reminderTime({reminderPolicy:'DAY',timezoneId:'America/New_York',dueAt:Date.parse('2026-03-08T15:00:00Z')}),
    Date.parse('2026-03-07T16:00:00Z'));
  assert.equal(journeySnapshot(w,'2d',sunday).newCustomers,0);
  assert.equal(journeySnapshot(w,'20d',sunday).newCustomers,1);
  const old = storage();
  save(old,{...w,settings:{themeMode:'SYSTEM'}});
  assert.equal(load(old).settings.themeMode,'NEON');
  assert.equal(load(old).settings.profileConfigured,false);
});

test('a pending reminder moves to the day of its actual reschedule', async () => {
  const { remindersFor } = await import('../dist/data.js');
  const w = workspace();
  const now = new Date(2026,10,29,12).getTime();
  const c = customer({displayName:'Customer'});
  w.customers.push(c);
  w.followUps.push(followUp({customerId:c.id,purpose:'Catch-up',dueAt:now+3600000,reminderScheduledAt:now+1500}));
  assert.equal(remindersFor(w,'today',now)[0].at,now+1500);
  assert.equal(remindersFor(w,'yesterday',now).length,0);
  w.followUps[0].reminderPolicy='OFF';
  assert.equal(remindersFor(w,'today',now).length,0);
});

test('profile review keeps legacy names and backups preserve font and photo', async () => {
  const { profileReview, validPhoto } = await import('../dist/data.js');
  const disk = storage();
  save(disk,{...workspace(),settings:{operatorName:'Uzair',displayName:'Callx',profileConfigured:true}});
  const w = load(disk);
  assert.equal(w.settings.operatorName,'Uzair');
  assert.equal(w.settings.displayName,'Callx');
  assert.equal(w.settings.profileReview,0);
  assert.equal(w.settings.font,'DEFAULT');
  Object.assign(w.settings,{profileReview,photo:'/9j/AA==',font:'MODERN'});
  assert.deepEqual((await decrypt(await encrypt(w,'profile review backup'),'profile review backup')).settings,w.settings);
  assert.equal(validPhoto('/9j/<svg>'),false);
  assert.equal(validPhoto('/9j/'+'A'.repeat(90000)),false);
});

test('photo crop and today’s summary use stable crop bounds and separate units', async () => {
  const { profileCrop, todayOverview } = await import('../dist/data.js');
  assert.deepEqual(profileCrop(400,200,1,0,0),{left:100,top:0,side:200});
  assert.deepEqual(profileCrop(400,200,2,1,1),{left:300,top:100,side:100});
  assert.deepEqual(profileCrop(200,400,3,-1,-1),{left:0,top:0,side:66});
  const w = workspace();
  const now = new Date(2026,10,29,12).getTime();
  const yesterday = addDays(now,-1);
  w.calls.push({endedAt:yesterday,outcomeAt:now-1,outcome:'RESOLVED',dealAmount:'1250.50',dealCurrency:'PKR'},
    {endedAt:now-2,outcome:'PENDING'},{endedAt:now-3,outcome:'ISSUE'});
  const summary = todayOverview(w,now);
  assert.equal(summary.total,2);
  assert.equal(summary.successful,0);
  assert.equal(summary.pending,1);
  assert.equal(summary.other,1);
  assert.deepEqual(summary.deals.amounts,{PKR:'1250.50'});
  Object.assign(w.settings,{photo:'/9j/AA==',photoSource:'/9j/AQ=='});
  w.calls = [];
  const restored = await decrypt(await encrypt(w,'profile editor backup'),'profile editor backup');
  assert.equal(restored.settings.photoSource,'/9j/AQ==');
});

test('payment batches, edits and date totals preserve exact values and currency', async () => {
  const { savePayments, paymentSummary, validate, visible } = await import('../dist/data.js');
  const w = workspace();
  const at = new Date('2026-12-01T00:10:00').getTime();
  const c = customer({ displayName:'Example' },at - 3600000);
  w.customers.push(c);
  w.calls.push({id:crypto.randomUUID(),customerId:c.id,startedAt:at-3600000,endedAt:at-1800000,
    createdAt:at,outcome:'RESOLVED',outcomeAt:at,dealAmount:'0.005',dealCurrency:'PKR',issueCategory:'',summaryNote:'',reflection:''});
  assert.equal(savePayments(w,'2026-12-01','9999999999999999999999.100\n\n۰٫۸۹۵','PKR','',at),2);
  savePayments(w,'2026-12-01','20.50','USD','',at);
  savePayments(w,'2026-12-02','2.125','PKR','',at);
  savePayments(w,'2026-11-30','3.00','PKR','',at);
  assert.throws(() => savePayments(w,'2026-12-01','30\nwrong','PKR','',at),/line 2/);
  assert.throws(() => savePayments(w,'2026-02-29','10','PKR'),/valid date/);
  assert.equal(w.payments.length,5);
  const day = paymentSummary(w,'2026-12-01',false,at);
  assert.deepEqual(day.total,{PKR:'10000000000000000000000.000',USD:'20.50'});
  assert.deepEqual(day.calls,{PKR:'0.005'});
  assert.equal(day.manual.PKR,'9999999999999999999999.995');
  assert.equal(paymentSummary(w,'2026-12-01',true,at).total.PKR,'10000000000000000000002.125');
  assert.equal(paymentSummary(w,'2026-11-30',true,at).total.PKR,'3.00');
  const usd = w.payments.find(row => row.currency === 'USD');
  savePayments(w,'2026-12-02','25.025','PKR',usd.id,at+1);
  assert.equal(usd.currency,'USD');
  assert.equal(paymentSummary(w,'2026-12-01',false,at).total.USD,undefined);
  assert.equal(paymentSummary(w,'2026-12-02',false,at).total.USD,'25.025');
  assert.equal(visible(w,true).payments.length,0);
  w.settings.paymentPeriod = 'MONTHLY';
  assert.deepEqual(await decrypt(await encrypt(w,'payments backup round trip'),'payments backup round trip'),w);
  assert.throws(() => validate({ ...w,payments:[usd,usd] }),/invalid or duplicated/);
  const older = structuredClone(w);
  delete older.payments;
  delete older.settings.paymentPeriod;
  assert.deepEqual(validate(older).payments,[]);
  assert.equal(validate(older).settings.paymentPeriod,'DAILY');
});

test('all journey detail lists match their marker counts', async () => {
  const { journeyRecords, journeySnapshot } = await import('../dist/data.js');
  const at = new Date('2026-11-25T12:00:00').getTime();
  const w = workspace();
  const c = customer({displayName:'Example'},at-60);
  w.customers.push(c);
  const base = {customerId:c.id,outcome:'NO_ANSWER',endedAt:at-30};
  const old = {...base,endedAt:at-90000000,outcome:'RESOLVED',outcomeAt:at-10,dealAmount:'5.50',dealCurrency:'PKR'};
  w.calls.push({...base,endedAt:at-100},old,base);
  const due = followUp({customerId:c.id,purpose:'Quote review',dueAt:at+70000});
  w.followUps.push(due,{...due,status:'DELETED'});
  const rows = journeyRecords(w,'today',at);
  assert.deepEqual(rows.new,[c]);
  assert.deepEqual(rows.contacted,[base]);
  assert.deepEqual(rows.follow,[due]);
  assert.deepEqual(rows.resolved,[old]);
  const count = journeySnapshot(w,'today',at);
  assert.deepEqual(count,{newCustomers:1,contacted:1,followUps:1,deals:{successful:1,amounts:{PKR:'5.50'}}});
  assert.deepEqual(journeyRecords(w,'24h',at).follow,[]);
});
