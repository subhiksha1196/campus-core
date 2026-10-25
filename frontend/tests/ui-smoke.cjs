/* Browser smoke test with explicit API fixtures; does not claim backend integration. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {spawn}=require('node:child_process');
const assert=require('node:assert/strict');
const path=require('node:path');
const fs=require('node:fs');
const shots=process.env.SHOT_DIR || path.join(process.cwd(),'test-results');
fs.mkdirSync(shots,{recursive:true});
(async()=>{
 const server=spawn(process.execPath,['node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5178'],{stdio:'pipe'});
 let browser;
 try{
  for(let n=0;n<60;n++){try{if((await fetch('http://127.0.0.1:5178')).ok)break;}catch{}await new Promise(r=>setTimeout(r,200));}
  let options={headless:true,args:['--no-sandbox']};
  if(process.env.CHROMIUM_MODULE){const packed=require(process.env.CHROMIUM_MODULE);options={...options,args:packed.args,executablePath:process.env.CHROMIUM_EXECUTABLE || await packed.executablePath()};}
  if(process.env.CHROMIUM_EXECUTABLE){options.executablePath=process.env.CHROMIUM_EXECUTABLE;options.args.push('--no-zygote','--single-process','--disable-gpu');}
  browser=await chromium.launch(options);
  const page=await browser.newPage({viewport:{width:1512,height:1100}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  let user={id:'S1',name:'Aarav Sharma',email:'aarav@example.com',department:'CSE',year:2,role:'STUDENT'};
  let courses=[{id:'C1',code:'CS201',title:'Data Structures',credits:4,department:'CSE',semester:'2026-ODD',instructorId:'F1',instructorName:'Dr. R. Venkatesan',capacity:40,enrolled:28,seatsLeft:12,registered:true,scheduleText:'Mon, Wed · 09:00–09:50',schedule:[]},{id:'C2',code:'CS202',title:'Database Management Systems',credits:3,department:'CSE',semester:'2026-ODD',instructorId:'F1',instructorName:'Dr. S. Lakshmi Priya',capacity:40,enrolled:32,seatsLeft:8,registered:true,scheduleText:'Tue, Thu · 10:00–10:50',schedule:[]},{id:'C3',code:'CS204',title:'Computer Networks',credits:3,department:'CSE',instructorId:'F1',instructorName:'Prof. M. Karthikeyan',capacity:30,enrolled:20,seatsLeft:10,registered:false,scheduleText:'Fri · 14:00–14:50',schedule:[]}];
  const slots=[{courseId:'C1',code:'CS201',title:'Data Structures',day:new Date().toLocaleDateString('en-US',{weekday:'short'}).toUpperCase(),startTime:'09:00',endTime:'09:50',room:'A204'}];
  let notes=[{id:'N1',studentId:'S1',title:'Mid-semester timetable published',message:'Your timetable is now available.',type:'EXAM',read:false,createdAt:new Date().toISOString()}];
  const calls=[];
  await page.route('**/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method();calls.push(`${m} ${p}`);let data={};
   if(p==='/api/auth/login')data={token:'test-token',user};
   else if(p==='/api/auth/me'){if(m==='PUT')user={...user,...req.postDataJSON()};data=user;}
   else if(p==='/api/academic/courses')data={courses,departments:['CSE']};
   else if(p==='/api/academic/people/S1')data={person:user,credits:courses.filter(c=>c.registered).reduce((a,c)=>a+c.credits,0),maxCredits:28};
   else if(p==='/api/academic/timetable')data={slots,days:['MON','TUE','WED','THU','FRI'],periods:[['09:00','09:50']]};
   else if(p==='/api/academic/registrations'){courses=courses.map(c=>c.id===req.postDataJSON().courseId?{...c,registered:true}:c);data={};}
   else if(p.startsWith('/api/academic/registrations/')){courses=courses.map(c=>c.id===p.split('/').pop()?{...c,registered:false}:c);}
   else if(p==='/api/notifications/unread-count')data={unread:notes.filter(n=>!n.read).length};
   else if(p==='/api/notifications')data=notes;
   else if(p.endsWith('/read'))notes=notes.map(n=>({...n,read:true}));
   else if(p.endsWith('/attendance'))data=p.includes('/students/')?{minimum:75,courses:[]}:{date:new Date().toISOString().slice(0,10),students:[],sessions:[],scheduled:true,exists:false};
   else if(p.endsWith('/grades'))data=p.includes('/students/')?{gpa:8.5,gradedCourses:0,courses:[]}:{students:[],exams:[],distribution:{}};
   else if(p.endsWith('/roster')||p.endsWith('/exams'))data=[];
   else if(p.match(/\/courses\/C\d$/))data=courses.find(c=>c.id===p.split('/').pop());
   else if(p==='/api/academic/overview')data={counts:{students:120,courses:24},courses};
   else if(p==='/api/academic/search')data={query:url.searchParams.get('q'),results:[{id:'C1',label:'Data Structures',sub:'CS201 · CSE'}],tookMs:1.5};
   else if(p==='/api/academic/metrics')data={searchLatency:{},searchLatencyByType:{},selectionFeedback:{count:0},index:{},benchmark:null};
   else if(p==='/api/crawl/runs'||p==='/api/urls')data=[];
   else if(p==='/api/listings')data={total:0,items:[]};
   else if(p==='/api/metrics/summary')data={redirectLatency:{},cache:{hits:0,misses:0,hitRatio:0},notifications:{created:0},resources:{processCpuPercent:0,jvmHeapUsedMb:12}};
   await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(data)});
  });
  await page.goto('http://127.0.0.1:5178');await page.getByRole('button',{name:'Sign in',exact:false}).first().click();
  await page.getByLabel('Email address').fill('aarav@example.com');await page.getByLabel('Password',{exact:true}).fill('Password123!');await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await page.getByRole('heading',{name:/Make room for\s*what’s next\./}).waitFor();await page.locator('.course-tile').first().waitFor();
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor),'rgb(255, 255, 255)');
  await page.screenshot({path:shots+'/courses-desktop.png',fullPage:true});
  await page.getByRole('button',{name:'Course catalog',exact:true}).click();await page.getByRole('button',{name:'Register',exact:true}).click();await page.getByRole('status').filter({hasText:'Registered for CS204'}).waitFor();
  await page.getByRole('button',{name:'View course',exact:false}).first().click();await page.getByRole('button',{name:'Drop course',exact:true}).click();await page.getByRole('button',{name:'Confirm drop',exact:true}).click();await page.getByRole('status').filter({hasText:'Dropped CS201'}).waitFor();
  async function navigate(name){await page.locator('nav').getByRole('button',{name:new RegExp('^'+name)}).click();await page.waitForTimeout(120);}
  for(const name of ['Timetable','Attendance','Grades'])await navigate(name);
  await page.getByRole('button',{name:'Notifications',exact:true}).click();await page.waitForTimeout(120); // reached from the top-bar bell now
  await navigate('Link Shortener');
  await page.getByRole('button',{name:'Open my profile',exact:true}).click();await page.waitForTimeout(120); // name/role card in the sidebar footer
  await page.getByLabel('Full name').fill('Aarav Sharma');await page.getByRole('button',{name:'Save changes'}).click();await page.getByRole('status').filter({hasText:'Profile saved'}).waitFor();
  await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'Open menu'}).click();await navigate('Courses');await page.locator('.course-tile').first().waitFor();await page.screenshot({path:shots+'/courses-mobile.png',fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'Mobile page must not overflow horizontally');
  await page.setViewportSize({width:1512,height:1100});
  for(const role of ['FACULTY','ADMIN']){
   user={...user,role,id:'F1',name:'Dr. R. Venkatesan'};await page.reload();await page.locator('.course-tile').first().waitFor();
   await navigate('Course Manager');await page.getByRole('button',{name:'Open',exact:true}).first().click();
   for(const t of ['Attendance','Exams and marks','Grades']){await page.locator('.subtabs').getByRole('button',{name:t,exact:true}).click();await page.waitForTimeout(120);}
   await page.screenshot({path:shots+'/faculty-desktop.png',fullPage:true});
   await navigate('Opportunity Crawler');await navigate('System Metrics');
   assert.equal(await page.locator('nav').getByRole('button',{name:/^Search/}).count(),0,'Search must not be in the sidebar');
   await navigate('Courses');await page.getByRole('button',{name:'Faculty',exact:true}).click();await page.getByRole('combobox',{name:'Search faculty'}).waitFor();
  }
  assert.deepEqual(errors,[]);assert.ok(calls.includes('POST /api/academic/registrations'));assert.ok(calls.includes('DELETE /api/academic/registrations/S1/C1'));
  console.log('PASS: login, live fixture courses, registration/drop, profile save, student navigation, faculty/admin navigation, mobile layout; no browser exceptions.');
 }finally{if(browser)await browser.close();server.kill();}
})().catch(e=>{console.error(e);process.exitCode=1;});
