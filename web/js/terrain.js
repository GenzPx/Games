import * as THREE from "three";
import { WORLD } from "./generated.js";

export const VISUAL_H = 520;
export const WORLD_M = WORLD.worldM;

function hash2(ix, iy) {
  let n = (Math.imul(ix | 0, 374761393) + Math.imul(iy | 0, 668265263)) >>> 0;
  n = Math.imul(n ^ (n >>> 13), 1274126177) >>> 0;
  return (n & 0xffffff) / 16777215;
}
function noise2(x, y) {
  const x0 = Math.floor(x), y0 = Math.floor(y);
  const fx = x - x0, fy = y - y0;
  const u = fx * fx * (3 - 2 * fx);
  const v = fy * fy * (3 - 2 * fy);
  const a = hash2(x0, y0), b = hash2(x0 + 1, y0);
  const c = hash2(x0, y0 + 1), d = hash2(x0 + 1, y0 + 1);
  return a + (b - a) * u + ((c + (d - c) * u) - (a + (b - a) * u)) * v;
}
function fbm(x, y, oct = 5) {
  let a = 1, f = 1, t = 0, n = 0;
  for (let i = 0; i < oct; i++) {
    t += noise2(x * f, y * f) * a;
    n += a; a *= 0.5; f *= 2.05;
  }
  return t / n;
}
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const smoothstep = (e0, e1, x) => {
  const t = clamp((x - e0) / (e1 - e0), 0, 1);
  return t * t * (3 - 2 * t);
};

export function heightNorm(u, v) {
  const dx = (u - 0.5) * 2;
  const dy = (v - 0.56) * 2;
  const r = Math.hypot(dx * 2.05, dy * 1.25);
  let horn = Math.pow(Math.max(0, 1 - r), 1.22);
  const rx = dx + 0.045 * Math.sin(v * 16);
  const ridge = Math.exp(-(rx * rx) * 70) * smoothstep(0.1, 0.56, v) * (1 - smoothstep(0.78, 1, v));
  let h = Math.max(horn * 0.96, ridge * 0.9);
  h += Math.exp(-((u - 0.31) ** 2 * 90 + (v - 0.42) ** 2 * 55)) * 0.28;
  const n = fbm(u * 7, v * 7, 5);
  h += (n - 0.5) * 0.045;
  // cliff shelves — vertical walls between flats
  const shelves = [0.12, 0.22, 0.34, 0.46, 0.58, 0.7, 0.82];
  let terr = h;
  for (const s of shelves) {
    if (h > s && h < s + 0.055) terr = s + (h - s) * 0.12;
  }
  h = terr * 0.78 + h * 0.22;
  h = Math.max(h, 0.012 + n * 0.02);
  h += 0.07 * Math.exp(-(dx * dx + dy * dy) * 110);
  return clamp(h, 0, 1);
}

export function xzToUv(x, z) {
  return { u: x / WORLD_M + 0.5, v: z / WORLD_M + 0.5 };
}
export function sampleNorm(x, z) {
  const { u, v } = xzToUv(x, z);
  return heightNorm(u, v);
}
export function heightY(x, z) {
  return sampleNorm(x, z) * VISUAL_H;
}
export function altitudeOf(x, z) {
  return WORLD.baseAlt + sampleNorm(x, z) * (WORLD.summitAlt - WORLD.baseAlt);
}
export function surfaceNormal(x, z, eps = 1.6) {
  const dx = heightY(x + eps, z) - heightY(x - eps, z);
  const dz = heightY(x, z + eps) - heightY(x, z - eps);
  return new THREE.Vector3(-dx, 2 * eps, -dz).normalize();
}
export function slopeDeg(x, z) {
  return (Math.acos(clamp(surfaceNormal(x, z).y, -1, 1)) * 180) / Math.PI;
}

function biomeColor(h, steep) {
  const snow = clamp(h * 1.35 - (steep - 32) / 55, 0, 1);
  let r, g, b;
  if (h < 0.16) {
    r = 0.28; g = 0.42; b = 0.22;
  } else if (h < 0.34) {
    r = 0.45; g = 0.4; b = 0.3;
  } else if (h < 0.55) {
    r = 0.52; g = 0.48; b = 0.42;
  } else {
    r = 0.78; g = 0.8; b = 0.84;
  }
  r = r * (1 - snow) + 0.93 * snow;
  g = g * (1 - snow) + 0.95 * snow;
  b = b * (1 - snow) + 0.98 * snow;
  if (steep > 55) {
    r *= 0.72; g *= 0.74; b *= 0.8;
  }
  return [r, g, b, snow];
}

export function buildTerrain() {
  const segs = 220;
  const geo = new THREE.PlaneGeometry(WORLD_M, WORLD_M, segs, segs);
  geo.rotateX(-Math.PI / 2);
  const pos = geo.attributes.position;
  const colors = new Float32Array(pos.count * 3);
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i);
    const z = pos.getZ(i);
    const y = heightY(x, z);
    pos.setY(i, y);
    const h = y / VISUAL_H;
    const steep = slopeDeg(x, z);
    const [r, g, b] = biomeColor(h, steep);
    colors[i * 3] = r;
    colors[i * 3 + 1] = g;
    colors[i * 3 + 2] = b;
  }
  geo.setAttribute("color", new THREE.BufferAttribute(colors, 3));
  geo.computeVertexNormals();
  const mat = new THREE.MeshStandardMaterial({
    vertexColors: true,
    roughness: 0.88,
    metalness: 0.02,
    flatShading: false,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.receiveShadow = true;
  mesh.castShadow = true;
  mesh.name = "terrain";
  return mesh;
}

export function buildSky() {
  const geo = new THREE.SphereGeometry(3800, 32, 20);
  const mat = new THREE.ShaderMaterial({
    side: THREE.BackSide,
    depthWrite: false,
    uniforms: {
      uSun: { value: new THREE.Vector3(0.4, 0.35, 0.3).normalize() },
      uTop: { value: new THREE.Color(0x1a3a78) },
      uHor: { value: new THREE.Color(0xf0b07a) },
      uNight: { value: 0 },
    },
    vertexShader: `
      varying vec3 vDir;
      void main() {
        vDir = position;
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }
    `,
    fragmentShader: `
      uniform vec3 uSun, uTop, uHor;
      uniform float uNight;
      varying vec3 vDir;
      void main() {
        vec3 d = normalize(vDir);
        float h = d.y;
        vec3 day = mix(uHor, uTop, smoothstep(-0.08, 0.55, h));
        vec3 night = mix(vec3(0.02,0.03,0.07), vec3(0.01,0.02,0.06), smoothstep(0.0, 0.6, h));
        vec3 col = mix(day, night, uNight);
        float sun = pow(max(0.0, dot(d, normalize(uSun))), 420.0);
        float glow = pow(max(0.0, dot(d, normalize(uSun))), 8.0);
        col += vec3(1.0, 0.86, 0.55) * sun * 2.4 * (1.0 - uNight);
        col += vec3(1.0, 0.55, 0.25) * glow * 0.35 * (1.0 - uNight);
        float stars = step(0.996, fract(sin(dot(d.xy, vec2(12.9898,78.233))) * 43758.5453));
        col += vec3(stars) * uNight * 0.8;
        gl_FragColor = vec4(col, 1.0);
      }
    `,
  });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.name = "sky";
  mesh.frustumCulled = false;
  return mesh;
}

export function buildCloudSea() {
  const c = document.createElement("canvas");
  c.width = c.height = 512;
  const ctx = c.getContext("2d");
  const img = ctx.createImageData(512, 512);
  for (let y = 0; y < 512; y++) {
    for (let x = 0; x < 512; x++) {
      const n = fbm(x / 70, y / 70, 5);
      const a = clamp((n - 0.42) * 3.2, 0, 1);
      const i = (y * 512 + x) * 4;
      img.data[i] = 255;
      img.data[i + 1] = 255;
      img.data[i + 2] = 255;
      img.data[i + 3] = a * 210;
    }
  }
  ctx.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(3, 3);
  const mat = new THREE.MeshBasicMaterial({
    map: tex,
    transparent: true,
    depthWrite: false,
    side: THREE.DoubleSide,
    opacity: 0.85,
  });
  const mesh = new THREE.Mesh(new THREE.PlaneGeometry(4200, 4200), mat);
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.y = 52;
  mesh.name = "cloudsea";
  return mesh;
}

export function buildLake() {
  const mat = new THREE.MeshStandardMaterial({
    color: 0x2a5a78,
    roughness: 0.15,
    metalness: 0.35,
    transparent: true,
    opacity: 0.85,
  });
  const mesh = new THREE.Mesh(new THREE.CircleGeometry(280, 40), mat);
  mesh.rotation.x = -Math.PI / 2;
  mesh.position.set(-80, 8.2, -620);
  return mesh;
}

export function buildClimbRoute() {
  const group = new THREE.Group();
  group.name = "route";
  const platforms = [];
  const rockMat = new THREE.MeshStandardMaterial({ color: 0x6b6258, roughness: 0.92, flatShading: true });
  const snowMat = new THREE.MeshStandardMaterial({ color: 0xe8eef4, roughness: 0.7, flatShading: true });
  const iceMat = new THREE.MeshStandardMaterial({
    color: 0x9fd4e6,
    roughness: 0.22,
    metalness: 0.25,
    flatShading: true,
  });
  const woodMat = new THREE.MeshStandardMaterial({ color: 0x7a4a28, roughness: 0.85 });
  const restMat = new THREE.MeshStandardMaterial({ color: 0xc9a227, roughness: 0.55, emissive: 0x3a2a08, emissiveIntensity: 0.35 });

  const start = WORLD.route.find((p) => p.id === "bc") || WORLD.route[1];
  const summit = WORLD.route.find((p) => p.id === "summit") || WORLD.route[WORLD.route.length - 1];

  const steps = 110;
  for (let i = 0; i < steps; i++) {
    const t = i / (steps - 1);
    const x = start.x + (summit.x - start.x) * t + Math.sin(t * 22) * (11 - t * 6);
    const z = start.z + (summit.z - start.z) * t;
    const gy = heightY(x, z);
    const w = (i < 12 ? 5.2 : 3.2) - t * 1.1 + (i % 4 === 0 ? 2.8 : 0);
    const d = 2.8 - t * 0.7;
    const thick = 0.6;
    const y = gy + 0.35 + (i % 3) * 0.12;
    const ice = t > 0.62;
    const snow = t > 0.4 && !ice;
    const mat = ice ? iceMat : snow ? snowMat : rockMat;
    const geo = new THREE.BoxGeometry(w, thick, d);
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(x, y, z);
    mesh.rotation.y = Math.sin(t * 9) * 0.3;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    mesh.userData.climb = true;
    mesh.userData.ice = ice;
    group.add(mesh);
    platforms.push({
      mesh,
      x, y, z,
      w, d, thick,
      ice,
      rest: isRest,
    });

    // vertical hold wall between ledges
    if (i < steps - 1) {
      const holds = 3;
      for (let k = 0; k < holds; k++) {
        const hx = x + (k - 1) * 0.95 + Math.sin(i * 1.7 + k) * 0.35;
        const hz = z + 0.75;
        const hy = y + 0.9 + k * 0.7;
        const hm = new THREE.Mesh(new THREE.BoxGeometry(0.85, 0.38, 0.55), mat);
        hm.position.set(hx, hy, hz);
        hm.rotation.z = (k - 1) * 0.15;
        hm.castShadow = true;
        hm.userData.climb = true;
        hm.userData.ice = ice;
        group.add(hm);
        platforms.push({ mesh: hm, x: hx, y: hy, z: hz, w: 0.85, d: 0.55, thick: 0.38, ice, hold: true });
      }
    }
  }

  // camp terraces
  for (const camp of WORLD.camps) {
    const y = heightY(camp.x, camp.z);
    const deck = new THREE.Mesh(new THREE.BoxGeometry(16, 0.5, 12), woodMat);
    deck.position.set(camp.x, y + 0.25, camp.z);
    deck.receiveShadow = true;
    deck.userData.climb = false;
    group.add(deck);
    platforms.push({
      mesh: deck,
      x: camp.x, y: y + 0.25, z: camp.z,
      w: 16, d: 12, thick: 0.5,
      ice: false, rest: true, camp: camp.id,
    });
  }

  return { group, platforms };
}

export function addDistantPeaks(scene) {
  const g = new THREE.Group();
  const mat = new THREE.MeshStandardMaterial({
    color: 0xc5cdd6,
    roughness: 0.95,
    flatShading: true,
  });
  const specs = [
    [2100, -500, 620, 1.6],
    [-2300, 140, 540, 1.3],
    [700, -2100, 480, 1.8],
    [-1600, -1800, 400, 1.4],
    [2500, 900, 360, 1.1],
  ];
  for (const [x, z, h, s] of specs) {
    const mesh = new THREE.Mesh(new THREE.ConeGeometry(200 * s, h, 6), mat);
    mesh.position.set(x, h * 0.38, z);
    mesh.rotation.y = x * 0.01;
    g.add(mesh);
  }
  scene.add(g);
  return g;
}
