import React from 'react';
import {AbsoluteFill, Composition, Html5Audio, Img, Sequence, interpolate, spring, staticFile, useCurrentFrame} from 'remotion';

const BLUE = '#1268ff';
const INK = '#09254b';
const MUTED = '#63809f';
const CYAN = '#53e5f2';
const FPS = 30;
const TOTAL = 1350;
const font = '"Microsoft YaHei", "Noto Sans CJK SC", sans-serif';
const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
const prog = (f: number, a: number, b: number) => interpolate(f, [a,b], [0,1], clamp);
const pop = (f: number, delay = 0) => spring({frame: f-delay, fps: FPS, config: {damping: 20, stiffness: 95, mass: .85}});

function Rise({children, delay = 0, style = {}}: {children: React.ReactNode; delay?: number; style?: React.CSSProperties}) {
  const f = useCurrentFrame();
  const p = pop(f, delay);
  return <div style={{opacity: prog(f,delay,delay+15), transform: `translateY(${(1-p)*48}px)`, ...style}}>{children}</div>;
}

function Icon({kind, size=48, color='currentColor'}: {kind: string; size?: number; color?: string}) {
  const paths: Record<string, React.ReactNode> = {
    search: <><circle cx="10" cy="10" r="6.5"/><path d="m15 15 6 6"/></>,
    check: <path d="m5 12 4 4 10-10"/>,
    down: <><path d="M12 3v12m-5-5 5 5 5-5M4 17v4h16v-4"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 2"/></>,
    shield: <><path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6z"/><path d="m8 12 3 3 5-6"/></>,
    grid: <><rect x="3" y="3" width="6" height="6" rx="1.4"/><rect x="15" y="3" width="6" height="6" rx="1.4"/><rect x="3" y="15" width="6" height="6" rx="1.4"/><rect x="15" y="15" width="6" height="6" rx="1.4"/></>,
    sparkle: <><path d="m12 2 2.6 7.4L22 12l-7.4 2.6L12 22l-2.6-7.4L2 12l7.4-2.6z"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[kind] ?? paths.grid}</svg>;
}

function Ocean({dark = false}: {dark?: boolean}) {
  const f = useCurrentFrame();
  return <AbsoluteFill style={{background: dark ? '#041a3c' : '#f4f8ff', overflow: 'hidden'}}>
    <div style={{position:'absolute',width:1450,height:1450,left:-160,top:350,borderRadius:'50%',background: dark ? 'radial-gradient(circle, #0a4f9e88 0%, transparent 66%)' : 'radial-gradient(circle, #bcd9ff66 0%, transparent 66%)', transform:`translate(${Math.sin(f/100)*40}px,${Math.cos(f/130)*50}px)`}}/>
    <svg style={{position:'absolute',bottom:0,left:0,opacity:dark?.52:.68}} width="1080" height="600" viewBox="0 0 1080 600">
      {[0,1,2,3].map(i=><path key={i} d={`M-80 ${240+i*80} C${240+Math.sin(f/90+i)*60} ${20+i*80} 650 ${540+i*25} 1160 ${130+i*80} L1160 650 L-80 650Z`} fill={dark ? ['#0a3773','#095397','#087bae','#0798b5'][i] : ['#d3e7ff','#bfdeff','#acd5ff','#a4d8f6'][i]}/>) }
    </svg>
    {[340,640,940].map((r,i)=><div key={r} style={{position:'absolute',width:r,height:r,borderRadius:'50%',border:`1px solid ${dark?'#54c8ff17':'#1675e413'}`,top:770-r/2,left:540-r/2,transform:`scale(${1+Math.sin(f/90+i)*.02})`}}/>)}
  </AbsoluteFill>;
}

function Brand({dark=false}: {dark?:boolean}) {
  return <div style={{display:'flex',alignItems:'center',gap:17,fontSize:29,fontWeight:700,color:dark?'#fff':INK}}><Img src={staticFile('trove.svg')} style={{width:53,height:53,borderRadius:13}}/>Trove</div>;
}

function Layout({children, chapter, dark=false, duration}: {children:React.ReactNode;chapter:string;dark?:boolean;duration:number}) {
  const f=useCurrentFrame();
  // Keep the outgoing scene opaque while the next one fades over it.
  const opacity=prog(f,0,12);
  return <AbsoluteFill style={{opacity,fontFamily:font,color:dark?'#fff':INK}}>
    <Ocean dark={dark}/>
    <div style={{position:'absolute',left:78,right:78,top:125,display:'flex',justifyContent:'space-between',alignItems:'center'}}><Brand dark={dark}/><span style={{fontSize:24,letterSpacing:3,color:dark?'#8db9e2':MUTED}}>{chapter}</span></div>
    {children}
  </AbsoluteFill>;
}

function Heading({eyebrow, lines, sub, dark=false}: {eyebrow:string;lines:string[];sub:string;dark?:boolean}) {
  return <div style={{position:'absolute',top:270,left:78,right:78}}>
    <Rise delay={4} style={{fontSize:28,letterSpacing:4,color:dark?CYAN:BLUE,fontWeight:700,marginBottom:25}}>{eyebrow}</Rise>
    {lines.map((line,i)=><Rise key={line} delay={8+i*6} style={{fontSize:94,fontWeight:700,lineHeight:1.26,letterSpacing:-3,color:i===1?(dark?CYAN:BLUE):undefined}}>{line}</Rise>)}
    <Rise delay={22} style={{fontSize:33,lineHeight:1.7,color:dark?'#a9c5e4':MUTED,marginTop:25}}>{sub}</Rise>
  </div>;
}

function Footnote({children,dark=false}: {children:React.ReactNode;dark?:boolean}) {
  return <div style={{position:'absolute',left:78,right:78,bottom:210,fontSize:24,lineHeight:1.65,color:dark?'#86aacb':MUTED}}>{children}</div>;
}

function Intro() {
  const f=useCurrentFrame();
  const p=pop(f,8);
  return <Layout chapter="你的应用港湾" dark duration={150}>
    <Rise delay={7} style={{position:'absolute',top:305,left:78,fontSize:90,fontWeight:700,lineHeight:1.28,letterSpacing:-3}}>找应用，<br/><span style={{color:CYAN}}>少一点折腾。</span></Rise>
    <div style={{position:'absolute',top:770,left:330,width:420,height:420,transform:`scale(${.68+p*.32}) rotate(${(1-p)*-10}deg)`,opacity:prog(f,8,26)}}>
      <div style={{position:'absolute',inset:-70,borderRadius:'50%',background:'radial-gradient(circle,#17a2ff55,transparent 70%)'}}/>
      <Img src={staticFile('trove.svg')} style={{width:420,height:420,borderRadius:100,boxShadow:'0 40px 100px #0005',position:'relative'}}/>
    </div>
    <Rise delay={28} style={{position:'absolute',top:1280,left:78,right:78,textAlign:'center',fontSize:82,fontWeight:700,letterSpacing:-2}}>Trove</Rise>
    <Rise delay={40} style={{position:'absolute',top:1410,left:78,right:78,textAlign:'center',fontSize:35,color:'#b5d7f2'}}>让好应用，汇聚一处。</Rise>
    <Footnote dark>多源应用商店 · Android / Desktop</Footnote>
  </Layout>;
}

const sources=[['小米','#ff741c'],['vivo','#427bff'],['OPPO','#04a76b'],['华为','#d7444d'],['荣耀','#34314e'],['三星','#3156c1'],['TapTap','#14b9b1'],['豌豆荚','#65b540'],['好游快爆','#f19c2c'],['F-Droid','#4c96c7'],['应用宝','#429dff'],['酷安','#13aa63'],['应用汇','#0aa3e0'],['7723 游戏盒','#ff6b35'],['LSPosed','#f5a623'],['GitHub','#24292f'],['GitLab','#e24329'],['IzzyOnDroid','#3f8fef'],['F-Droid 清华','#5c6bc0'],['360 手机助手','#19b955'],['手机乐园','#ff7a3d'],['HAIS','#7c5cff']];

const SOURCE_COLS = 4;
const SOURCE_GRID_TOP = 770;
const SOURCE_GRID_BOTTOM = 1560;
const SOURCE_GAP = 14;

function Sources() {
  const f=useCurrentFrame();
  const rows=Math.ceil(sources.length/SOURCE_COLS);
  const tileH=Math.min(160, Math.floor((SOURCE_GRID_BOTTOM-SOURCE_GRID_TOP-SOURCE_GAP*(rows-1))/rows));
  const fontSize=tileH<130?25:29;
  return <Layout chapter="01 / 多源" duration={210}>
    <Heading eyebrow="更多选择，更少切换" lines={[`${sources.length} 个应用源，`,'由你来选。']} sub="搜索、更新、今日精选，来源独立设置。"/>
    <div style={{position:'absolute',top:SOURCE_GRID_TOP,left:78,right:78,display:'grid',gridTemplateColumns:`repeat(${SOURCE_COLS},1fr)`,gap:SOURCE_GAP}}>
      {sources.map(([name,color],i)=>{
        const p=pop(f,24+i*2);
        return <div key={name} style={{background:'#fff',border:'1px solid #e2ebf7',borderRadius:24,height:tileH,display:'flex',alignItems:'center',justifyContent:'center',flexDirection:'column',gap:11,transform:`translateY(${(1-p)*40}px) scale(${.9+p*.1})`,opacity:prog(f,24+i*2,34+i*2),boxShadow:'0 10px 30px #224e7e05'}}>
          <span style={{width:12,height:12,borderRadius:'50%',background:color,boxShadow:`0 0 0 8px ${color}13`}}/>
          <span style={{fontSize,fontWeight:700,textAlign:'center',lineHeight:1.2}}>{name}</span>
        </div>;
      })}
    </div>
    <Footnote>各来源提供的功能不同，以应用内支持情况为准。</Footnote>
  </Layout>;
}

function AppTile({letter,color}: {letter:string;color:string}) {
  return <div style={{width:80,height:80,borderRadius:22,background:`linear-gradient(140deg,${color},${color}bb)`,color:'#fff',display:'flex',justifyContent:'center',alignItems:'center',fontSize:35,fontWeight:700,flexShrink:0}}>{letter}</div>;
}

function CleanSearch() {
  const f=useCurrentFrame();
  const q='效率工具'.slice(0, Math.floor(interpolate(f,[20,52],[0,4],clamp)));
  return <Layout chapter="02 / 纯净" duration={240}>
    <Heading eyebrow="让搜索回归搜索" lines={['想要的应用，','一眼就找到。']} sub="支持过滤推广、快应用与预约结果。"/>
    <Rise delay={22} style={{position:'absolute',left:120,right:120,top:780,height:720,borderRadius:52,background:'#fff',boxShadow:'0 30px 70px #21467416',padding:42}}>
      <div style={{display:'flex',justifyContent:'space-between',fontSize:24,color:MUTED,marginBottom:30}}><span>搜索</span><span>功能示意</span></div>
      <div style={{background:'#f2f6fc',borderRadius:24,height:87,display:'flex',alignItems:'center',gap:20,padding:'0 25px',fontSize:32}}><Icon kind="search" size={33} color={MUTED}/>{q}<span style={{opacity:Math.floor(f/15)%2===0?1:0,color:BLUE}}>|</span></div>
      <div style={{display:'flex',gap:12,marginTop:24,marginBottom:30}}>{['过滤推广','过滤快应用','过滤预约'].map((x,i)=><div key={x} style={{fontSize:21,borderRadius:30,padding:'11px 16px',background:'#e8f1ff',color:BLUE,opacity:prog(f,58+i*7,72+i*7)}}>✓ {x}</div>)}</div>
      {[['待办清单','规划每一天','待','#5489f8'],['轻笔记','留住每个灵感','记','#17b9ab'],['文件管家','整理井井有条','文','#f4a44b']].map(([name,desc,letter,color],i)=><Rise key={name} delay={83+i*9} style={{display:'flex',alignItems:'center',gap:23,padding:'20px 0',borderBottom:i<2?'1px solid #edf2f8':undefined}}><AppTile letter={letter} color={color}/><div style={{flex:1}}><div style={{fontSize:30,fontWeight:700}}>{name}</div><div style={{fontSize:22,color:MUTED,marginTop:7}}>{desc}</div></div><Icon kind="down" size={29} color={BLUE}/></Rise>)}
    </Rise>
    <Footnote>净化能力取决于来源；界面与应用条目为功能示意。</Footnote>
  </Layout>;
}

function Updates() {
  const f=useCurrentFrame();
  const p=prog(f,65,150);
  return <Layout chapter="03 / 更新" dark duration={270}>
    <Heading dark eyebrow="只下载变化的部分" lines={['更新应用，','也能更轻。']} sub="支持增量更新，补丁在本地合成。"/>
    <Rise delay={25} style={{position:'absolute',left:78,right:78,top:810,borderRadius:44,border:'1px solid #80c5ff35',background:'#0b2c53',padding:46}}>
      <div style={{display:'flex',justifyContent:'space-between',fontSize:27,color:'#a9c5e4'}}><span>增量更新</span><span>流程示意</span></div>
      <div style={{display:'flex',alignItems:'center',gap:24,marginTop:42}}><AppTile letter="更" color="#2078ff"/><div><div style={{fontSize:38,fontWeight:700}}>下载补丁包</div><div style={{fontSize:27,color:'#a9c5e4',marginTop:10}}>原版 APK + 差分补丁</div></div></div>
      <div style={{marginTop:52,height:17,borderRadius:20,background:'#ffffff16',overflow:'hidden'}}><div style={{height:'100%',width:`${p*100}%`,background:'linear-gradient(90deg,#2781ff,#53e5f2)',borderRadius:20}}/></div>
      <div style={{marginTop:23,display:'flex',justifyContent:'space-between',fontSize:27,color:'#a9c5e4'}}><span>{p<1?'正在下载补丁…':'补丁下载完成'}</span><span style={{color:CYAN}}>{Math.round(p*100)}%</span></div>
      <div style={{marginTop:45,display:'flex',gap:14,alignItems:'center',fontSize:31,color:f>158?CYAN:'#a9c5e4'}}><Icon kind={f>158?'check':'shield'} size={35}/>{f>158?'本地合成完成':'在你的设备上完成合成'}</div>
    </Rise>
    <Rise delay={44} style={{position:'absolute',left:78,right:78,top:1450,display:'flex',gap:20}}>{['来源直连','纯客户端'].map(x=><div key={x} style={{padding:'17px 30px',border:'1px solid #a1d8ff40',borderRadius:60,fontSize:30,color:'#c9e7ff'}}>{x}</div>)}</Rise>
    <Footnote dark>增量更新在来源提供兼容补丁时可用；否则下载完整包。<br/>应用安装与已装应用更新功能适用于 Android。</Footnote>
  </Layout>;
}

function Control() {
  const f=useCurrentFrame();
  const selected=f>70;
  return <Layout chapter="04 / 自由" duration={240}>
    <Heading eyebrow="版本与安装，自己掌握" lines={['多一种选择，','多一份自由。']} sub="历史版本下载，多种安装方式。"/>
    <Rise delay={24} style={{position:'absolute',left:78,right:78,top:810,background:'#fff',border:'1px solid #e2ebf7',borderRadius:38,padding:38,boxShadow:'0 20px 50px #21467409'}}>
      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:24}}><div style={{display:'flex',alignItems:'center',gap:17,fontSize:35,fontWeight:700}}><Icon kind="clock" color={BLUE} size={38}/>历史版本</div><span style={{fontSize:23,color:MUTED}}>功能示意</span></div>
      {['当前版本','上一版本','更早版本'].map((x,i)=><div key={x} style={{display:'flex',alignItems:'center',justifyContent:'space-between',padding:'22px 25px',marginTop:12,borderRadius:20,background:i===1&&selected?'#edf4ff':'#f6f8fb',border:`1px solid ${i===1&&selected?'#abcfff':'transparent'}`,fontSize:30}}><span>{x}</span><span style={{fontSize:25,color:BLUE}}>{i===1&&selected?'已选择 ✓':'下载 ↓'}</span></div>)}
    </Rise>
    <Rise delay={58} style={{position:'absolute',left:78,right:78,top:1350,display:'grid',gridTemplateColumns:'1fr 1fr',gap:17}}>{[['系统安装','开箱即用'],['Shizuku / Root','静默安装']].map(([x,y])=><div key={x} style={{padding:'27px 28px',borderRadius:26,background:'#e7f0ff',minHeight:128}}><div style={{fontSize:30,fontWeight:700,color:BLUE}}>{x}</div><div style={{fontSize:24,color:MUTED,marginTop:11}}>{y}</div></div>)}</Rise>
    <Footnote>历史版本由豌豆荚 / F-Droid 等支持来源提供。<br/>静默安装需相应服务或权限；另支持第三方安装器。</Footnote>
  </Layout>;
}

function Finale() {
  const f=useCurrentFrame();
  return <Layout chapter="让好应用靠岸" dark duration={300}>
    <Rise delay={5} style={{position:'absolute',top:310,left:78,fontSize:87,fontWeight:700,lineHeight:1.28,letterSpacing:-3}}>手机到桌面，<br/><span style={{color:CYAN}}>好应用随行。</span></Rise>
    <Rise delay={17} style={{position:'absolute',top:695,left:78,right:78,height:385,borderRadius:30,background:'#122f51',border:'2px solid #759eca',boxShadow:'0 30px 80px #0003',padding:28}}>
      <div style={{display:'flex',gap:10,marginBottom:28}}>{['#ff8b83','#ffd26d','#83e5b3'].map(c=><span key={c} style={{width:12,height:12,borderRadius:'50%',background:c}}/>)}<span style={{marginLeft:15,color:'#9dbad7',fontSize:20}}>Trove · Desktop · 功能示意</span></div>
      <div style={{display:'flex',gap:22}}><div style={{width:140,color:'#a4c8e9',fontSize:22,lineHeight:2.4}}>今日<br/>搜索<br/>设置</div><div style={{flex:1}}><div style={{height:43,borderRadius:12,background:'#ffffff0d',marginBottom:20,display:'flex',alignItems:'center',gap:12,paddingLeft:17,color:'#89a8c7',fontSize:20}}><Icon kind="search" size={20}/>发现好应用</div>{[0,1,2].map(i=><div key={i} style={{display:'flex',alignItems:'center',gap:18,marginTop:15}}><div style={{width:43,height:43,borderRadius:12,background:['#468aff','#19b9b4','#f1a759'][i]}}/><div style={{width:215-i*27,height:10,borderRadius:4,background:'#9cbed645'}}/></div>)}</div></div>
    </Rise>
    <Rise delay={28} style={{position:'absolute',top:960,left:700,width:220,height:365,borderRadius:35,background:'#eef5ff',border:'8px solid #39668c',boxShadow:'0 25px 65px #0005',color:INK,padding:'23px 15px'}}><div style={{width:60,height:10,borderRadius:10,background:'#173658',margin:'0 auto 35px'}}/><Img src={staticFile('trove.svg')} style={{width:72,height:72,borderRadius:17,display:'block',margin:'auto'}}/><div style={{fontSize:19,fontWeight:700,textAlign:'center',marginTop:17}}>Trove</div>{[0,1,2].map(i=><div key={i} style={{height:29,marginTop:15,borderRadius:10,background:'#d9e6f8'}}/>)}<div style={{fontSize:12,textAlign:'center',marginTop:17,color:MUTED}}>功能示意</div></Rise>
    <Rise delay={40} style={{position:'absolute',top:1390,left:78,right:78,fontSize:61,fontWeight:700}}>你的应用港湾。</Rise>
    <Rise delay={50} style={{position:'absolute',top:1490,left:78,right:78,fontSize:29,color:'#a9c5e4'}}>Android · Windows · macOS · Linux</Rise>
    <Rise delay={65} style={{position:'absolute',top:1580,left:78,right:78,borderRadius:22,padding:'22px 28px',background:'#1473ff',color:'#fff',fontSize:31,display:'flex',justifyContent:'space-between'}}><span>GitHub · BeiChen-CN / Trove</span><span>↗</span></Rise>
    <div style={{position:'absolute',bottom:150,left:78,right:78,fontSize:21,color:'#89aacb'}}>开源项目 · 仅供技术研究学习及交流</div>
    <div style={{position:'absolute',top:1210,left:155,fontSize:23,color:'#a9c5e4',opacity:prog(f,44,65)}}>Compose Multiplatform · miuix</div>
  </Layout>;
}

export function Promo() {
  const f=useCurrentFrame();
  // Six scenes crossfade for 12 frames; total remains exactly 45 seconds.
  return <AbsoluteFill style={{background:'#041a3c',fontFamily:font}}>
    <Html5Audio src={staticFile('trove-original.wav')} volume={.83}/>
    <Sequence from={0} durationInFrames={150}><Intro/></Sequence>
    <Sequence from={138} durationInFrames={210}><Sources/></Sequence>
    <Sequence from={336} durationInFrames={240}><CleanSearch/></Sequence>
    <Sequence from={564} durationInFrames={270}><Updates/></Sequence>
    <Sequence from={822} durationInFrames={240}><Control/></Sequence>
    <Sequence from={1050} durationInFrames={300}><Finale/></Sequence>
    <div style={{position:'absolute',bottom:88,left:78,right:78,height:4,background:'#94c9ff24',borderRadius:4}}><div style={{width:`${f/(TOTAL-1)*100}%`,height:4,borderRadius:4,background:f>=138&&f<564||f>=822&&f<1050?BLUE:CYAN}}/></div>
    <AbsoluteFill style={{background:'#041a3c',opacity:prog(f,TOTAL-12,TOTAL-1)}}/>
  </AbsoluteFill>;
}

export const Root = () => <Composition id="TroveVertical" component={Promo} durationInFrames={TOTAL} fps={FPS} width={1080} height={1920}/>;
