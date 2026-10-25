import {el,icon} from './dom.js';

const drawings={
  codex:[
    'M12 19H29L40 30M12 53H29L40 42M120 19H103L92 30M120 53H103L92 42',
    'M49 14H83Q87 14 87 18V54Q87 58 83 58H49Q45 58 45 54V18Q45 14 49 14Z',
    'M57 6V14M75 6V14M57 58V66M75 58V66M4 36H26M106 36H128'
  ],
  gemini:[
    'M66 7L95 36L66 65L37 36Z',
    'M26 18L37 7M95 65L106 54M8 36H25M107 36H124',
    'M17 54H30V67M102 5V18H115M50 6L39 17M93 55L82 66'
  ],
  openclaude:[
    'M18 18H35M14 27H33M18 36H30M102 36H118M99 45H122M97 54H116',
    'M44 13H82L91 22V56H44ZM82 13V22H91',
    'M38 23V62H80M50 7H86L97 18V49'
  ],
  pi:[
    'M31 17H14V55H31M101 17H118V55H101',
    'M42 14H90M42 58H90M33 8H99M33 64H99',
    'M4 30H14M4 42H14M118 30H128M118 42H128M45 5V9M87 63V67'
  ]
};

/** Small, local linework around the existing Lucide engine mark. */
export function engineArtwork(profile) {
  const frame=el('div','engine-art');frame.setAttribute('aria-hidden','true');
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
  for(const [name,value] of Object.entries({viewBox:'0 0 132 72',fill:'none',class:'engine-pattern',focusable:'false','stroke-width':'1.2','stroke-linecap':'round','stroke-linejoin':'round'}))svg.setAttribute(name,value);
  for(const [index,d] of drawings[profile.id].entries()){
    const path=document.createElementNS(svg.namespaceURI,'path');
    path.setAttribute('d',d);path.setAttribute('pathLength','1');path.setAttribute('class','pattern-stroke pattern-'+index);svg.append(path);
  }
  frame.append(svg,icon(profile.mark));return frame;
}
