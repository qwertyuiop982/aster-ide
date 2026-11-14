from pathlib import Path
from html import escape
R=Path(__file__).resolve().parent / 'assets/screens'
# Faithful, resolution-independent redraws of the two supplied Aster screenshots.
# Text and icons are typeset from visible source content, not upsampled or AI-filled.
FONT='Arial, Noto Sans CJK SC, Noto Sans SC, Microsoft YaHei, sans-serif'
def rect(x,y,w,h,fill,rx=0,stroke='none',sw=1):
 return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'
def text(x,y,value,size=30,color='#252b2c',weight=400,anchor='start',family=FONT):
 return f'<text x="{x}" y="{y}" fill="{color}" font-family="{family}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}">{escape(value)}</text>'
def path(d,color='#252b2c',width=3):
 return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>'
def circle(x,y,r,fill,stroke='none',sw=1):
 return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'
def white_top(count):
 return (path('M58 112h27 M58 123h27 M58 134h27')+text(146,136,'gpt-6.1-sol',34)+path('M349 124l7 7 7-7','#7c8586',2)
 +path('M479 115h21 M479 127h21 M479 139h21 M486 111v8 M495 123v8 M487 135v8','#7c8586',2)+text(516,136,'Ultra',27,'#7c8586')
 +path('M639 113h-5v29h26v-13 M648 118l14-14 6 6-14 14-9 3z')
 +rect(574,208,112,136,'#fff',56,'#e7eaea',2)+path('M612 248l16-8 17 8-17 9z M612 258l16 8 17-8 M612 267l16 8 17-8','#14787f',3)
 +text(628,313,'Agent',27,'#14787f',400,'middle')+circle(670,220,21,'#087e85')+text(670,228,str(count),25,'#fff',400,'middle'))
def inputbar():
 return rect(34,1460,652,108,'#fff',54,'#f0f2f2',2)+path('M78 1514h24 M90 1502v24')+text(144,1526,'询问 Codex...',34,'#939a9c')+circle(626,1515,43,'#ddb3f5')+path('M626 1531v-32 M614 1511l12-12 12 12','#fff',3)
def dark_top(title,subtitle):
 return rect(0,0,720,1612,'#050505')+rect(0,65,720,130,'#0d0d0d')+text(42,36,'16:14',24,'#eee',600)+text(675,36,'4G  ▰ 22%',23,'#ddd',500,'end')+path('M42 120h27 M42 130h27 M42 140h27','#aaa',3)+text(116,124,title,39,'#f5f5f5',600)+text(116,161,subtitle,27,'#aaa')+path('M512 116h19l7 7v21h-26z M518 116v11h12v-11 M519 135h12v9h-12','#888',3)+path('M591 119l15 10-15 10z','#f2f2f2',3)+circle(667,119,3,'#999')+circle(667,130,3,'#999')+circle(667,141,3,'#999')
def dark_bottom(language):
 return path('M16 1340h688','#333',2)+text(42,1395,'↶   ↷   ⇥   {   }   (   )   [   ]   ;',34,'#aaa',400,family='monospace')+rect(0,1443,720,102,'#0d0d0d')+rect(328,1453,64,5,'#777',3)+text(42,1517,'⚒ 构建    ▤ 产物    {} 符号    ▣ 日志',29,'#eee',500)+path('M0 1554h720','#333',2)+text(22,1593,'▣ Termux   ⓘ 0  △ 0',24,'#ddd')+text(699,1593,'1:1  '+language,24,'#bbb',400,'end')
white=rect(0,0,720,1612,'#fff')+white_top(0)+text(360,808,'我们该构建什么？',54,'#242a2b',650,'middle')+text(360,898,'未绑定工作区',29,'#858d8f',400,'middle')+inputbar()
pixel=dark_top('mini-agent','Android Code Studio 样式')+rect(16,207,688,1214,'#0d0d0d',24)+path('M345 680l-20 20 20 20 M375 680l20 20-20 20','#8d8d8d',8)+text(360,818,'mini-agent',46,'#eee',650,'middle')
pixel+=path('M279 899h13l7 7v23h-20z M289 908v13 M283 915h12','#eee',3)+text(323,929,'新建文件',32,'#eee',600)+path('M277 996h12l5 5h9v21h-26z','#eee',3)+text(323,1024,'打开项目',32,'#eee',600)+dark_bottom('text')
for name,body in [('xiaomi',white),('pixel',pixel)]:
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="3224" viewBox="0 0 720 1612">{body}</svg>'
 (R/(name+'.svg')).write_text(svg)
 print(name, len(svg.encode()))
