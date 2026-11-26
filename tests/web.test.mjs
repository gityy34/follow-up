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
  const result=report(persisted,7,time);
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
