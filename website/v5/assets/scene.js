/* Independently drawn concept devices, not official manufacturer assets.
 * Three.js r187 is self-hosted; see vendor/THREE-LICENSE.txt.
 */
import * as THREE from './vendor/three.module.js';

const PI = Math.PI;
const clamp = THREE.MathUtils.clamp;
const geometryCache = new Map();
const allTextures = new Set();

function roundedShape(w, h, r) {
  r = Math.max(.001, Math.min(r, w / 2 - .001, h / 2 - .001));
  const s = new THREE.Shape(), x = -w / 2, y = -h / 2;
  s.moveTo(x + r, y); s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r); s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y);
  return s;
}
function bodyGeometry(w, h, d, r, bevel = .025) {
  const key = [w, h, d, r, bevel].join('/');
  if (!geometryCache.has(key)) {
    const g = new THREE.ExtrudeGeometry(roundedShape(w - bevel * 2, h - bevel * 2, r), {
      depth: Math.max(.008, d - bevel * 2), bevelEnabled: true, bevelSegments: 4,
      steps: 1, bevelSize: bevel, bevelThickness: bevel, curveSegments: 16,
    });
    g.translate(0, 0, -d / 2 + bevel);
    geometryCache.set(key, g);
  }
  return geometryCache.get(key);
}
function slab(group, w, h, d, r, material, x = 0, y = 0, z = 0, bevel = .025) {
  const m = new THREE.Mesh(bodyGeometry(w, h, d, r, bevel), material);
  m.position.set(x, y, z); group.add(m); return m;
}
function rectFace(group, w, h, r, material, x, y, z, back = false) {
  const key = ['face', w, h, r].join('/');
  if (!geometryCache.has(key)) {
    const g = new THREE.ShapeGeometry(roundedShape(w, h, r), 12);
    const pos = g.getAttribute('position'), uv = g.getAttribute('uv');
    for (let i = 0; i < pos.count; i++) uv.setXY(i, pos.getX(i) / w + .5, pos.getY(i) / h + .5);
    geometryCache.set(key, g);
  }
  const m = new THREE.Mesh(geometryCache.get(key), material);
  m.position.set(x, y, z); if (back) m.rotation.y = PI; group.add(m); return m;
}
function disk(group, radius, depth, mat, x, y, z, sides = 48) {
  const key = ['disk', radius, depth, sides].join('/');
  if (!geometryCache.has(key)) geometryCache.set(key, new THREE.CylinderGeometry(radius, radius, depth, sides));
  const m = new THREE.Mesh(geometryCache.get(key), mat); m.rotation.x = PI / 2;
  m.position.set(x, y, z); group.add(m); return m;
}
function physical(color, roughness, metalness, extras = {}) {
  return new THREE.MeshPhysicalMaterial({ color, roughness, metalness, ...extras });
}
function noiseTexture() {
  const c = document.createElement('canvas'); c.width = c.height = 256;
  const ctx = c.getContext('2d'), image = ctx.createImageData(256, 256);
  let seed = 4207;
  for (let i = 0; i < image.data.length; i += 4) {
    seed = (1664525 * seed + 1013904223) >>> 0;
    const value = 95 + (seed >>> 25);
    image.data[i] = image.data[i + 1] = image.data[i + 2] = value; image.data[i + 3] = 255;
  }
  ctx.putImageData(image, 0, 0);
  const t = new THREE.CanvasTexture(c); t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(2, 4); t.anisotropy = 8; allTextures.add(t); return t;
}
function studioEnvironment(renderer) {
  // Softboxes appear in device reflections; the transparent canvas leaves the CSS falloff visible.
  const c = document.createElement('canvas'); c.width = 1024; c.height = 512;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#131416'; ctx.fillRect(0, 0, 1024, 512);
  for (const [x, y, w, h, color] of [[68, 95, 90, 295, '#e7ebea'], [390, 50, 255, 105, '#f5f5f2'], [815, 65, 105, 310, '#ff7048']]) {
    ctx.shadowColor = color; ctx.shadowBlur = 34; ctx.fillStyle = color;
    ctx.fillRect(x, y, w, h);
  }
  const texture = new THREE.CanvasTexture(c); texture.mapping = THREE.EquirectangularReflectionMapping;
  texture.colorSpace = THREE.SRGBColorSpace;
  const pmrem = new THREE.PMREMGenerator(renderer);
  const target = pmrem.fromEquirectangular(texture);
  texture.dispose(); pmrem.dispose(); return target;
}
function opticalRing(group, radius, tube, material, x, y, z) {
  const key = ['torus', radius, tube].join('/');
  if (!geometryCache.has(key)) geometryCache.set(key, new THREE.TorusGeometry(radius, tube, 10, 64));
  const mesh = new THREE.Mesh(geometryCache.get(key), material);
  mesh.position.set(x, y, z); group.add(mesh);
}
function lensGlass(group, radius, material, x, y, z) {
  const key = ['glass-dome', radius].join('/');
  if (!geometryCache.has(key)) {
    const geometry = new THREE.SphereGeometry(radius, 40, 20);
    geometry.scale(1, 1, .13); geometryCache.set(key, geometry);
  }
  const mesh = new THREE.Mesh(geometryCache.get(key), material);
  mesh.position.set(x, y, z); group.add(mesh);
}
function octagonalPlate(group, radius, depth, material, x, y, z) {
  const key = ['octagon', radius, depth].join('/');
  if (!geometryCache.has(key)) {
    const shape = new THREE.Shape();
    const vertices = Array.from({ length: 8 }, (_, i) => new THREE.Vector2(
      Math.cos(i * PI / 4 + PI / 8) * radius, Math.sin(i * PI / 4 + PI / 8) * radius));
    vertices.forEach((v, i) => {
      const prev = vertices[(i + 7) % 8], next = vertices[(i + 1) % 8];
      const a = v.clone().lerp(prev, .10), b = v.clone().lerp(next, .10);
      if (i === 0) shape.moveTo(a.x, a.y); else shape.lineTo(a.x, a.y);
      shape.quadraticCurveTo(v.x, v.y, b.x, b.y);
    });
    shape.closePath();
    const geometry = new THREE.ExtrudeGeometry(shape, { depth: depth - .04, bevelEnabled: true,
      bevelSize: .02, bevelThickness: .02, bevelSegments: 4, steps: 1, curveSegments: 8 });
    geometry.translate(0, 0, -depth / 2 + .02); geometryCache.set(key, geometry);
  }
  const mesh = new THREE.Mesh(geometryCache.get(key), material);
  mesh.position.set(x, y, z); group.add(mesh);
}
function lens(group, x, y, z, radius, mats) {
  // Recessed optical stack, rounded metal lip and curved coated cover glass.
  // The lip stands proud of the glass instead of seven flat painted circles.
  disk(group, radius + .030, .10, mats.ring, x, y, z - .012);
  disk(group, radius - .004, .108, mats.black, x, y, z - .018);
  opticalRing(group, radius + .008, .017, mats.edge, x, y, z - .077);
  disk(group, radius * .79, .012, mats.iris, x, y, z - .076);
  opticalRing(group, radius * .60, .006, mats.glass, x, y, z - .087);
  disk(group, radius * .37, .010, mats.pupil, x, y, z - .088);
  lensGlass(group, radius * .81, mats.cover, x, y, z - .083);

}
function addHardware(group, w, h, depth, mats) {
  slab(group, .055, .64, .10, .025, mats.rail, w / 2 + .015, .83, 0, .012);
  slab(group, .055, .36, .10, .025, mats.rail, w / 2 + .015, -.05, 0, .012);
  slab(group, .017, h - .42, .027, .007, mats.edge, w / 2 + .008, 0, depth * .13, .004);
  slab(group, .017, h - .42, .027, .007, mats.edge, -w / 2 - .008, 0, depth * .13, .004);
  slab(group, .018, .55, depth * .52, .008, mats.antenna, -w / 2 - .008, -.84, 0, .004);
  const trayPin = disk(group, .014, .01, mats.black, -w / 2 - .019, -1.03, 0, 16);
  trayPin.rotation.set(0, 0, PI / 2);
  for (const x of [-w / 2, w / 2]) for (const y of [-h * .34, h * .34]) {
    slab(group, .032, .035, depth + .008, .01, mats.antenna, x, y, 0, .005);
  }
  // USB-C opening and speaker perforations are visible when the group rotates.
  slab(group, .36, .014, .11, .035, mats.black, 0, -h / 2 - .017, 0, .006);
  for (let i = 0; i < 5; i++) {
    slab(group, .035, .018, .035, .014, mats.black, .48 + i * .105, -h / 2 - .017, 0, .004);
  }
  for (const x of [-.27, .27]) {
    const microphone = disk(group, .018, .012, mats.black, x, -h / 2 - .008, 0, 16);
    microphone.rotation.x = 0;
  }
}
function mergeStaticDevice(group) {
  // A material batch per device keeps mobile draw calls low without an addon.
  group.updateMatrixWorld(true);
  const batches = new Map();
  for (const mesh of group.children) {
    if (!mesh.isMesh) continue;
    const geometry = mesh.geometry.index ? mesh.geometry.toNonIndexed() : mesh.geometry.clone();
    geometry.applyMatrix4(mesh.matrix);
    if (!batches.has(mesh.material)) batches.set(mesh.material, []);
    batches.get(mesh.material).push(geometry);
  }
  group.clear();
  for (const [material, geometries] of batches) {
    const combined = new THREE.BufferGeometry();
    for (const name of ['position', 'normal', 'uv']) {
      const size = name === 'uv' ? 2 : 3;
      const length = geometries.reduce((n, g) => n + g.getAttribute(name).array.length, 0);
      const array = new Float32Array(length); let offset = 0;
      for (const g of geometries) { const part = g.getAttribute(name).array; array.set(part, offset); offset += part.length; }
      combined.setAttribute(name, new THREE.BufferAttribute(array, size));
    }
    combined.computeBoundingSphere();
    group.add(new THREE.Mesh(combined, material));
    geometries.forEach(g => g.dispose());
  }
  return group;
}

function makeDevice(kind, screen, leather) {
  const g = new THREE.Group(); g.name = kind;
  const spec = {
    xiaomi: { w: 2.68, h: 5.80, d: .29, r: .27, rail: 0x8b9991, back: 0x899b8b },
    huawei: { w: 2.70, h: 5.93, d: .47, r: .23, rail: 0xb4a17a, back: 0x783a33 },
    pixel: { w: 2.80, h: 5.61, d: .41, r: .35, rail: 0xa9b4b1, back: 0xc6cebf },
    samsung: { w: 2.81, h: 6.03, d: .29, r: .21, rail: 0x9298a9, back: 0x9294aa },
  }[kind];
  const { w, h, d, r } = spec;
  const mats = {
    rail: physical(spec.rail, .24, .94), ring: physical(spec.rail, .15, .98),
    edge: physical(0xcad2cf, .19, .87),
    back: physical(spec.back, kind === 'huawei' ? .59 : .30, kind === 'huawei' ? .03 : .42,
      kind === 'huawei' ? { bumpMap: leather, bumpScale: .018 } : { clearcoat: .8, clearcoatRoughness: .13 }),
    black: physical(0x07090b, .25, .25), glass: physical(0x163645, .10, .62, { clearcoat: 1 }),
    cover: physical(0x9ebacb, .06, .12, { transparent: true, opacity: .22, clearcoat: 1, clearcoatRoughness: .035, depthWrite: false, envMapIntensity: 2.8 }),
    iris: physical(0x173544, .17, .65), pupil: physical(0x020408, .12, .15),
    antenna: physical(0x66695f, .55, .08), flash: physical(0xe2dac0, .48, .1),
  };
  slab(g, w, h, d, r, mats.rail);
  slab(g, w - .055, h - .055, .045, r - .015, mats.back, 0, 0, -d / 2 - .007, .012);
  slab(g, w - .055, h - .055, .013, r - .015, mats.edge, 0, 0, d / 2 + .006, .006);
  if (kind === 'huawei' || kind === 'pixel') {
    const layers = kind === 'huawei' ? 3 : 2;
    for (let i = 1; i < layers; i++) {
      const z = -d / 2 + d * i / layers;
      slab(g, w + .006, h + .006, .014, r, mats.black, 0, 0, z, .005);
    }
    const hinge = slab(g, .12, h - .30, d * .91, .055, mats.ring, -w / 2 + .015, 0, 0, .02);
    hinge.name = 'closed-hinge';
    // Fine hinge ribs; all three panels remain closed.
    for (let i = -2; i <= 2; i++) slab(g, .125, .016, d * .94, .01, mats.rail, -w / 2 + .015, i * .83, 0, .004);
  }
  slab(g, w - .08, h - .08, .035, r - .025, mats.black, 0, 0, d / 2 + .011, .01);
  const screenMat = new THREE.MeshBasicMaterial({ map: screen || null, color: screen ? 0xffffff : 0x172122, toneMapped: false });
  rectFace(g, w - .16, h - .17, r - .045, screenMat, 0, 0, d / 2 + .034);
  disk(g, .044, .013, mats.pupil, 0, h / 2 - .17, d / 2 + .047, 20);
  slab(g, .35, .026, .01, .01, mats.black, 0, h / 2 - .055, d / 2 + .04, .003);
  addHardware(g, w, h, d, mats);
  const bz = -d / 2 - .105;
  if (kind === 'xiaomi') {
    slab(g, w - .24, 1.63, .16, .22, mats.rail, 0, 1.80, bz);
    slab(g, w - .31, 1.55, .13, .20, mats.black, 0, 1.80, bz - .06);
    lens(g, -.72, 2.17, bz - .11, .265, mats);
    lens(g, -.72, 1.42, bz - .11, .265, mats);
    lens(g, -.05, 1.42, bz - .11, .22, mats);
    const c = document.createElement('canvas'); c.width = 128; c.height = 160;
    const ctx = c.getContext('2d'), grad = ctx.createLinearGradient(0, 0, 128, 160);
    grad.addColorStop(0, '#233e35'); grad.addColorStop(.55, '#8ba38a'); grad.addColorStop(1, '#253b34');
    ctx.fillStyle = grad; ctx.fillRect(0, 0, 128, 160);
    ctx.fillStyle = '#eef1e5'; ctx.font = '500 30px sans-serif'; ctx.textAlign = 'center'; ctx.fillText('Aster', 64, 93);
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; allTextures.add(t);
    rectFace(g, 1.05, 1.35, .15, new THREE.MeshBasicMaterial({ map: t, toneMapped: false }), .51, 1.80, bz - .13, true);
    disk(g, .057, .04, mats.flash, -.04, 2.20, bz - .16, 20);
    for (const x of [-.92, .92]) for (const y of [1.10, 2.48]) disk(g, .023, .016, mats.antenna, x, y, bz - .161, 12);
  } else if (kind === 'huawei') {
    octagonalPlate(g, .98, .16, mats.ring, 0, 1.72, bz);
    octagonalPlate(g, .86, .12, mats.black, 0, 1.72, bz - .064);
    for (const [x, y] of [[-.36, 2.05], [.36, 2.05], [-.36, 1.35]]) lens(g, x, y, bz - .115, .235, mats);
    disk(g, .105, .08, mats.flash, .36, 1.35, bz - .16, 24);
    for (const y of [-1.45, -.65, .15, .95, 2.65]) slab(g, .022, .028, .018, .007, mats.edge, -w / 2 - .055, y, bz + .08, .004);
  } else if (kind === 'pixel') {
    slab(g, w - .24, 1.15, .18, .36, mats.ring, 0, 1.65, bz);
    slab(g, 1.67, .76, .15, .34, mats.black, -.27, 1.65, bz - .09);
    lens(g, -.70, 1.65, bz - .16, .23, mats); lens(g, .05, 1.65, bz - .16, .23, mats);
    disk(g, .11, .08, mats.flash, .94, 1.78, bz - .13, 24);
    disk(g, .065, .06, mats.black, .94, 1.43, bz - .13, 20);
    slab(g, 1.42, .014, .012, .005, mats.edge, -.24, 1.20, bz - .181, .003);
  } else {
    slab(g, .86, 2.50, .09, .36, mats.back, -.76, 1.50, bz + .015);
    for (const y of [2.32, 1.50, .68]) lens(g, -.76, y, bz - .07, .32, mats);
    lens(g, .15, 1.90, bz - .05, .17, mats); lens(g, .15, 1.10, bz - .05, .17, mats);
    disk(g, .075, .04, mats.flash, .16, 2.45, bz - .09, 24);
    for (const y of [2.75, 1.89, 1.06, .23]) disk(g, .022, .02, mats.edge, -.76, y, bz - .12, 12);
  }
  return mergeStaticDevice(g);
}

export async function startScene(stage) {
  const lifetime = new AbortController();
  const on = (target, type, listener, options = {}) => target.addEventListener(type, listener, { ...options, signal: lifetime.signal });
  const canvas = document.createElement('canvas'); canvas.setAttribute('aria-hidden', 'true');
  const context = canvas.getContext('webgl2', { alpha: true, antialias: true, powerPreference: 'high-performance' });
  if (!context) throw new Error('WebGL2 unavailable');
  const renderer = new THREE.WebGLRenderer({ canvas, context, alpha: true, antialias: true });
  renderer.setClearColor(0x000000, 0); renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.12;
  const env = studioEnvironment(renderer), scene = new THREE.Scene(); scene.environment = env.texture;
  scene.environmentIntensity = 1.4;
  scene.add(new THREE.HemisphereLight(0xdbe5eb, 0x171c26, .50));
  const key = new THREE.DirectionalLight(0xe9f0f2, 1.0); key.position.set(-6, 7, 10); scene.add(key);
  // Finite-distance sources create real falloff across the back panels.
  const warm = new THREE.SpotLight(0xff663c, 165, 0, .72, .75, 2);
  warm.position.set(7, 8, 7); warm.target.position.set(0, 0, 0); scene.add(warm, warm.target);
  const fill = new THREE.PointLight(0xdbe7fa, 44, 0, 2); fill.position.set(-5, 2, 6); scene.add(fill);
  const rim = new THREE.PointLight(0xff512b, 95, 0, 2); rim.position.set(5, 5, -6); scene.add(rim);
  const camera = new THREE.PerspectiveCamera(33, 1, .1, 120); camera.position.set(0, 0, 24);
  const cluster = new THREE.Group(); cluster.name = 'four-phones'; scene.add(cluster);
  const loader = new THREE.TextureLoader();
  const textures = await Promise.all(['xiaomi', 'huawei', 'pixel', 'samsung'].map(async name => {
    try {
      const vector = name === 'xiaomi' || name === 'pixel';
      const source = new URL('./screens/' + name + (vector ? '.svg' : '.jpg'), import.meta.url);
      const t = await loader.loadAsync(source.href).catch(() => loader.loadAsync(new URL('./screens/' + name + '.jpg', import.meta.url).href));
      t.colorSpace = THREE.SRGBColorSpace; t.anisotropy = Math.min(renderer.capabilities.getMaxAnisotropy(), 16);
      t.minFilter = THREE.LinearMipmapLinearFilter;
      // Remove the source phone's camera cutout; each model has its own geometry.
      if (!vector) t.repeat.y = .974;
      allTextures.add(t); return t;
    } catch (_) { return null; }
  }));
  const leather = noiseTexture();
  const phones = ['xiaomi', 'huawei', 'pixel', 'samsung'].map((kind, i) => makeDevice(kind, textures[i], leather));
  phones.forEach(p => cluster.add(p));
  stage.dataset.screenSizes = textures.map(t => t ? `${t.image.width}x${t.image.height}` : 'fallback').join(',');
  const motionQuery = matchMedia('(prefers-reduced-motion: reduce)');
  let paused = motionQuery.matches || Boolean(navigator.connection && navigator.connection.saveData);
  let inspecting = false, restoreQualityTimer = 0;
  let rotationY = 0, rotationX = -.025, zoom = 1, autoAngle = 0, active = true, lost = false, disposed = false;
  let width = 0, height = 0, dirty = true, raf = 0, previous = 0, lastRender = 0, elapsed = 0;
  let quality = Math.min(2, Math.max(1.8, devicePixelRatio || 1));
  let slowFrames = 0, frames = 0, frameTime = 0;
  let activeQuality = quality;
  function setResolution(detail = inspecting) {
    const detailRatio = Math.min(3, Math.max(2.5, devicePixelRatio || 1), Math.sqrt(10000000 / Math.max(1, width * height)));
    activeQuality = detail ? Math.max(quality, detailRatio) : quality;
    renderer.setPixelRatio(activeQuality); renderer.setSize(width, height, false);
  }
  const motion = document.getElementById('motion-toggle'), reset = document.getElementById('scene-reset');
  const pointers = new Map(); let holdTimer = 0, pinchDistance = 0;
  function setPaused(value) {
    paused = value; motion.setAttribute('aria-pressed', String(paused));
    const label = paused ? motion.dataset.playLabel : motion.dataset.pauseLabel;
    motion.setAttribute('aria-label', label); motion.title = label; dirty = true; requestFrame();
  }
  function resize() {
    width = stage.clientWidth; height = stage.clientHeight;
    setResolution();
    camera.aspect = width / height; camera.updateProjectionMatrix();
    const mobile = width <= 600 && height > width;
    // Real clearance between all four devices. The entire group shares one rigid
    // transform, so rotation and zoom cannot create physical intersections.
    const layouts = mobile ? [
      [-1.88, 2.05, 1.35, .10, -.26, -.12], [-1.88, -1.80, -1.35, -.08, PI + .24, -.10],
      [1.88, 1.85, -1.35, -.07, -.20, .10], [1.88, -2.05, 1.35, .10, PI - .25, .12],
    ] : [
      [-3.30, .05, 1.40, .10, -.30, -.22], [-1.20, .80, -1.40, -.09, PI + .25, -.12],
      [3.30, .50, -1.40, -.10, -.28, .22], [1.20, -.60, 1.40, .09, PI - .24, .13],
    ];
    phones.forEach((p, i) => {
      const [x, y, z, rx, ry, rz] = layouts[i]; p.position.set(x, y, z); p.rotation.set(rx, ry, rz);
    });
    // Conservative bounds in cluster-local space, including buttons/camera bumps.
    // Do not use world AABBs: their overlap at oblique angles is not a collision.
    const bounds = phones.map(p => {
      const box = new THREE.Box3();
      p.updateMatrix();
      for (const mesh of p.children) {
        if (!mesh.geometry.boundingBox) mesh.geometry.computeBoundingBox();
        box.union(mesh.geometry.boundingBox.clone().applyMatrix4(p.matrix));
      }
      return box;
    });
    let collisions = 0, clearance = Infinity;
    for (let i = 0; i < bounds.length; i++) for (let j = i + 1; j < bounds.length; j++) {
      if (bounds[i].intersectsBox(bounds[j])) collisions++;
      const gap = ['x', 'y', 'z'].map(axis => Math.max(0,
        bounds[i].min[axis] - bounds[j].max[axis], bounds[j].min[axis] - bounds[i].max[axis]));
      clearance = Math.min(clearance, Math.hypot(...gap));
    }
    stage.dataset.collisions = String(collisions);
    stage.dataset.clearance = clearance.toFixed(3);
    const viewHeight = mobile ? Math.max(16.2, 8.8 / camera.aspect) : Math.max(11.8, 13.8 / camera.aspect);
    camera.position.z = viewHeight / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)));
    dirty = true; requestFrame();
  }
  function updateState() {
    // Minimal read-only diagnostics for QA without an extra rendering dependency.
    stage.dataset.rotation = (rotationY + autoAngle).toFixed(4); stage.dataset.zoom = zoom.toFixed(3);
    stage.dataset.paused = String(paused); stage.dataset.pixelRatio = activeQuality.toFixed(2); stage.dataset.inspecting = String(inspecting);
  }
  function requestFrame() { if (!raf && active && !lost && !disposed) raf = requestAnimationFrame(frame); }
  function frame(now) {
    raf = 0; if (!active || lost || disposed) return;
    const dt = previous ? Math.min((now - previous) / 1000, .09) : 0; previous = now;
    const moving = !paused && !inspecting && pointers.size === 0;
    if (moving) frameTime += dt;
    if (now - lastRender > 1000 / 30 - 2 && (moving || dirty)) {
      if (moving) { autoAngle += frameTime * .105; elapsed += frameTime; }
      cluster.rotation.set(rotationX + (motionQuery.matches ? 0 : Math.sin(elapsed * .23) * .035), rotationY + autoAngle, 0);
      cluster.scale.setScalar(zoom);
      const started = performance.now(); renderer.render(scene, camera);
      const cost = performance.now() - started; frames++; if (cost > 42) slowFrames++;
      if (frames === 90 && slowFrames > 40 && quality > 1.8) {
        // A single modest reduction, never a blurry 1x canvas on high-DPI phones.
        quality = Math.max(1.8, quality * .90);
        setResolution();
      }
      frameTime = 0;
      stage.dataset.drawCalls = String(renderer.info.render.calls);
      stage.dataset.triangles = String(renderer.info.render.triangles);
      updateState(); lastRender = now; dirty = false;
    }
    if ((!paused && !inspecting) || dirty) requestFrame();
  }
  function inspect(value) {
    if (inspecting === value) return;
    inspecting = value; clearTimeout(restoreQualityTimer);
    document.body.classList.toggle('is-inspecting', value);
    if (value) setResolution(true);
    else restoreQualityTimer = setTimeout(() => {
      if (!disposed) { setResolution(false); dirty = true; requestFrame(); }
    }, motionQuery.matches ? 0 : 480);
    frameTime = 0; dirty = true; updateState(); requestFrame();
  }
  function cancelHold() { clearTimeout(holdTimer); holdTimer = 0; }
  function pointerDown(e) {
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY });
    try { stage.setPointerCapture(e.pointerId); } catch (_) { /* Pointer may already be released. */ }
    cancelHold();
    if (pointers.size === 1) holdTimer = setTimeout(() => { if (pointers.size === 1) inspect(true); }, 440);
    if (pointers.size === 2) { const [a, b] = [...pointers.values()]; pinchDistance = Math.hypot(a.x - b.x, a.y - b.y); }
  }
  function pointerMove(e) {
    const p = pointers.get(e.pointerId); if (!p) return;
    const dx = e.clientX - p.x, dy = e.clientY - p.y;
    if (Math.hypot(e.clientX - p.startX, e.clientY - p.startY) > 9) cancelHold();
    p.x = e.clientX; p.y = e.clientY;
    if (pointers.size >= 2) {
      const [a, b] = [...pointers.values()], distance = Math.hypot(a.x - b.x, a.y - b.y);
      if (pinchDistance > 0) zoom = clamp(zoom * distance / pinchDistance, .58, 1.8);
      pinchDistance = distance; rotationY += dx * .0012;
    } else { rotationY += dx * .006; rotationX = clamp(rotationX + dy * .004, -.85, .85); }
    dirty = true; requestFrame();
  }
  function pointerUp(e) { pointers.delete(e.pointerId); cancelHold(); inspect(false); pinchDistance = 0; dirty = true; requestFrame(); }
  function clearInteraction() { pointers.clear(); cancelHold(); inspect(false); }
  function resetView() { rotationY = 0; rotationX = -.025; autoAngle = 0; zoom = 1; elapsed = 0; clearInteraction(); dirty = true; requestFrame(); }
  function wheel(e) { e.preventDefault(); zoom = clamp(zoom * Math.exp(-e.deltaY * .001), .58, 1.8); dirty = true; requestFrame(); }
  function keydown(e) {
    const actions = {
      ArrowLeft: () => { rotationY -= .15; }, ArrowRight: () => { rotationY += .15; },
      ArrowUp: () => { rotationX = clamp(rotationX - .12, -.85, .85); },
      ArrowDown: () => { rotationX = clamp(rotationX + .12, -.85, .85); },
      '+': () => { zoom = clamp(zoom + .1, .58, 1.8); }, '=': () => { zoom = clamp(zoom + .1, .58, 1.8); },
      '-': () => { zoom = clamp(zoom - .1, .58, 1.8); }, ' ': () => setPaused(!paused),
      r: resetView, R: resetView, i: () => inspect(!inspecting), I: () => inspect(!inspecting), Escape: clearInteraction,
    };
    if (!actions[e.key]) return; e.preventDefault(); actions[e.key](); dirty = true; requestFrame();
  }
  on(stage, 'pointerdown', pointerDown); on(stage, 'pointermove', pointerMove);
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) on(stage, type, pointerUp);
  on(stage, 'wheel', wheel, { passive: false }); on(stage, 'keydown', keydown);
  on(stage, 'contextmenu', e => e.preventDefault());
  on(motion, 'click', () => setPaused(!paused)); on(reset, 'click', resetView);
  on(window, 'blur', clearInteraction);
  on(window, 'keydown', e => { if (e.key === 'Escape') clearInteraction(); });
  on(document, 'visibilitychange', () => {
    active = !document.hidden; previous = 0; frameTime = 0; clearInteraction();
    if (!active && raf) { cancelAnimationFrame(raf); raf = 0; } else { dirty = true; requestFrame(); }
  });
  on(motionQuery, 'change', e => setPaused(e.matches));
  const resizeObserver = new ResizeObserver(resize); resizeObserver.observe(stage);
  function disposeScene() {
    if (disposed) return;
    disposed = true; clearInteraction(); clearTimeout(restoreQualityTimer); lifetime.abort();
    if (raf) cancelAnimationFrame(raf); resizeObserver.disconnect();
    const materials = new Set(), geometries = new Set(geometryCache.values());
    scene.traverse(o => { if (o.material) materials.add(o.material); if (o.geometry) geometries.add(o.geometry); });
    geometries.forEach(g => g.dispose()); materials.forEach(m => m.dispose());
    allTextures.forEach(t => t.dispose()); geometryCache.clear(); allTextures.clear();
    env.dispose(); renderer.dispose();
    if (!lost) renderer.forceContextLoss();
  }
  on(canvas, 'webglcontextlost', e => {
    e.preventDefault(); lost = true;
    stage.dataset.scene = 'fallback'; motion.disabled = reset.disabled = true;
    // Dispose while the context is lost. Reusing GPU-only PMREM targets and old
    // buffer listeners after restoration would produce stale reflections/errors.
    disposeScene();
    canvas.addEventListener('webglcontextrestored', () => {
      canvas.remove();
      if (!stage.isConnected) return;
      startScene(stage).then(() => { motion.disabled = reset.disabled = false; }).catch(() => {
        stage.dataset.scene = 'fallback'; stage.querySelectorAll('canvas').forEach(c => c.remove());
        motion.disabled = reset.disabled = true;
      });
    }, { once: true });
  });
  on(window, 'pagehide', e => { clearInteraction(); if (!e.persisted) disposeScene(); });
  on(window, 'pageshow', () => {
    if (!disposed) { active = true; dirty = true; previous = 0; frameTime = 0; requestFrame(); }
  });
  stage.appendChild(canvas); resize(); setPaused(paused);
  renderer.render(scene, camera); stage.dataset.scene = 'ready'; updateState(); requestFrame();
}
