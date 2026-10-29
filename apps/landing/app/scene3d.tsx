"use client";

import { useEffect, useRef } from "react";
import * as THREE from "three";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";

export type SceneProps = { quality: "high" | "low"; reduced: boolean; onReady: () => void; onFail: () => void };

const BG = "#f2f9fb";
const FOV = 38;
const CAM_Z = 9;
const clamp = (v: number, a = 0, b = 1) => Math.min(b, Math.max(a, v));
const smooth = (a: number, b: number, v: number) => {
  const t = clamp((v - a) / (b - a));
  return t * t * (3 - 2 * t);
};
const mix = THREE.MathUtils.lerp;

function rng(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function shieldPath<T extends THREE.Path>(shape: T, inset: number): T {
  const w = 1.0 - inset;
  const top = 1.28 - inset;
  const shoulder = 0.98 - inset;
  const bottom = -1.5 + inset * 1.15;
  shape.moveTo(0, top);
  shape.bezierCurveTo(0.35 * w, top - 0.05, 0.75 * w, shoulder + 0.12, w, shoulder);
  shape.lineTo(w, 0.12);
  shape.bezierCurveTo(w, -0.78, 0.45 * w, -1.18, 0, bottom);
  shape.bezierCurveTo(-0.45 * w, -1.18, -w, -0.78, -w, 0.12);
  shape.lineTo(-w, shoulder);
  shape.bezierCurveTo(-0.75 * w, shoulder + 0.12, -0.35 * w, top - 0.05, 0, top);
  return shape;
}

function bubbleShape(w = 1.9, h = 1.05, r = 0.38) {
  const s = new THREE.Shape();
  const x = -w / 2;
  const y = -h / 2;
  s.moveTo(x + r, y);
  s.lineTo(x + 0.3, y);
  s.lineTo(x + 0.1, y - 0.3);
  s.lineTo(x + 0.66, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

function qrMatrix(n = 21) {
  const r = rng(42);
  const m: boolean[][] = Array.from({ length: n }, () => Array<boolean>(n).fill(false));
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) m[y][x] = r() > 0.52;
  const clear = (ox: number, oy: number) => {
    for (let y = 0; y < 8; y++) for (let x = 0; x < 8; x++) if (oy + y >= 0 && oy + y < n && ox + x >= 0 && ox + x < n) m[oy + y][ox + x] = false;
  };
  clear(0, 0);
  clear(n - 8, 0);
  clear(0, n - 8);
  const finder = (ox: number, oy: number) => {
    for (let y = 0; y < 7; y++)
      for (let x = 0; x < 7; x++) {
        const edge = x === 0 || y === 0 || x === 6 || y === 6;
        const core = x >= 2 && x <= 4 && y >= 2 && y <= 4;
        m[oy + y][ox + x] = edge || core;
      }
  };
  finder(0, 0);
  finder(n - 7, 0);
  finder(0, n - 7);
  for (let i = 8; i < n - 8; i++) {
    m[6][i] = i % 2 === 0;
    m[i][6] = i % 2 === 0;
  }
  return m;
}

function radialTexture(inner: string, outer: string) {
  const c = document.createElement("canvas");
  c.width = c.height = 256;
  const g = c.getContext("2d")!;
  const grad = g.createRadialGradient(128, 128, 0, 128, 128, 128);
  grad.addColorStop(0, inner);
  grad.addColorStop(1, outer);
  g.fillStyle = grad;
  g.fillRect(0, 0, 256, 256);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

type Anchor = { x: number; y: number; z: number; s: number };
const SAT_W: Anchor = { x: 0, y: 2.7, z: -4, s: 0.5 };
const SAT_N: Anchor = { x: 0, y: 3.2, z: -4, s: 0.42 };
const ANCHORS_WIDE: Anchor[] = [
  { x: 2.7, y: -0.1, z: 0, s: 1.0 }, SAT_W, SAT_W, SAT_W, SAT_W,
  { x: 2.6, y: 0, z: 0, s: 1.1 }, { x: 6, y: 3, z: -6, s: 0.3 }, { x: 3.0, y: -0.2, z: -0.5, s: 1.0 },
];
const ANCHORS_NARROW: Anchor[] = [
  { x: 0, y: -1.9, z: 0, s: 0.8 }, SAT_N, SAT_N, SAT_N, SAT_N,
  { x: 0, y: 1.7, z: 0, s: 0.8 }, { x: 0, y: 3.6, z: -6, s: 0.3 }, { x: 0, y: 1.9, z: -0.5, s: 0.8 },
];

export default function Scene3D({ quality, reduced, onReady, onFail }: SceneProps) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    let renderer: THREE.WebGLRenderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas, antialias: quality === "high", powerPreference: "high-performance" });
    } catch {
      onFail();
      return;
    }
    const high = quality === "high";
    let disposed = false;
    let raf = 0;
    const disposables: { dispose: () => void }[] = [];
    const own = <T extends { dispose: () => void }>(o: T) => {
      disposables.push(o);
      return o;
    };

    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, high ? 1.75 : 1.25));
    renderer.setClearColor(new THREE.Color(BG), 1);
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 80);
    camera.position.set(0, 0, CAM_Z);

    const pmrem = new THREE.PMREMGenerator(renderer);
    const envScene = new RoomEnvironment();
    const envTex = pmrem.fromScene(envScene, 0.04).texture;
    scene.environment = envTex;
    scene.environmentIntensity = 0.9;
    disposables.push({ dispose: () => envTex.dispose() });
    pmrem.dispose();

    const key = new THREE.DirectionalLight(0xffffff, 1.6);
    key.position.set(3, 5, 6);
    scene.add(key);
    const glow = new THREE.PointLight(0x4fe3cf, 18, 14, 1.6);
    glow.position.set(0, 0, 2.4);
    scene.add(glow);

    /* ---------- materials ---------- */
    const glassMat = own(new THREE.MeshPhysicalMaterial({
      color: 0xd7f3f1, roughness: 0.16, metalness: 0, ior: 1.4, clearcoat: 1, clearcoatRoughness: 0.1,
      transmission: high ? 0.9 : 0, thickness: 1.4, attenuationColor: new THREE.Color("#2fb8ae"), attenuationDistance: 1.1,
      transparent: !high, opacity: high ? 1 : 0.9, envMapIntensity: 1.6, sheen: 1, sheenColor: new THREE.Color("#7ff0dc"),
    }));
    const frameMat = own(new THREE.MeshPhysicalMaterial({ color: 0x4fb9b2, metalness: 0.55, roughness: 0.2, clearcoat: 1, clearcoatRoughness: 0.08, envMapIntensity: 1.8 }));
    const mintGlow = own(new THREE.MeshStandardMaterial({ color: 0x0fbfa3, emissive: new THREE.Color("#10d9b6"), emissiveIntensity: 1.7, roughness: 0.28 }));
    const inkMat = own(new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.32, metalness: 0.12 }));
    const coralMat = own(new THREE.MeshStandardMaterial({ color: 0xff6a55, emissive: new THREE.Color("#ff5a43"), emissiveIntensity: 1.2, roughness: 0.35 }));
    const bubbleMat = own(new THREE.MeshPhysicalMaterial({
      color: 0xe8fbf8, roughness: 0.12, clearcoat: 1, transmission: high ? 0.55 : 0, thickness: 0.35, ior: 1.25,
      transparent: !high, opacity: high ? 1 : 0.86, envMapIntensity: 1.5, attenuationColor: new THREE.Color("#5fd9c8"), attenuationDistance: 0.9, emissive: new THREE.Color("#bff5ec"), emissiveIntensity: 0.22,
    }));
    const transmissive = [glassMat, bubbleMat];

    /* ---------- backdrop (the Gemini lake render) ---------- */
    const backdropUniforms = { map: { value: null as THREE.Texture | null }, bg: { value: new THREE.Color(BG) }, lift: { value: 0.1 } };
    const backdropMat = own(new THREE.ShaderMaterial({
      uniforms: backdropUniforms,
      vertexShader: "varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }",
      fragmentShader: `uniform sampler2D map; uniform vec3 bg; uniform float lift; varying vec2 vUv;
        void main(){
          vec3 c = texture2D(map, vUv).rgb;
          c = mix(c, bg, lift);
          c = mix(bg, c, smoothstep(0.0, 0.45, vUv.y));
          gl_FragColor = vec4(c, 1.0);
          #include <colorspace_fragment>
        }`,
    }));
    const backdropGeo = own(new THREE.PlaneGeometry(1, 1));
    const backdrop = new THREE.Mesh(backdropGeo, backdropMat);
    backdrop.position.z = -12;
    backdrop.visible = false;
    scene.add(backdrop);
    let backdropAspect = 2.36;
    new THREE.TextureLoader().load("/gemini/lake-panorama.webp", (tex) => {
      if (disposed) return tex.dispose();
      tex.colorSpace = THREE.SRGBColorSpace;
      tex.anisotropy = 4;
      disposables.push(tex);
      backdropUniforms.map.value = tex;
      backdropAspect = tex.image.width / tex.image.height;
      backdrop.visible = true;
      resize();
    });

    /* ---------- shield ---------- */
    const shield = new THREE.Group();
    scene.add(shield);
    const outer = shieldPath(new THREE.Shape(), 0);
    outer.holes.push(shieldPath(new THREE.Path(), 0.17));
    const frameGeo = own(new THREE.ExtrudeGeometry(outer, { depth: 0.26, bevelEnabled: true, bevelThickness: 0.1, bevelSize: 0.07, bevelSegments: 8, curveSegments: 56 }));
    frameGeo.translate(0, 0, -0.13);
    const frameMesh = new THREE.Mesh(frameGeo, frameMat);
    shield.add(frameMesh);
    const bodyGeo = own(new THREE.ExtrudeGeometry(shieldPath(new THREE.Shape(), 0.2), { depth: 0.2, bevelEnabled: true, bevelThickness: 0.12, bevelSize: 0.08, bevelSegments: 10, curveSegments: 56 }));
    bodyGeo.translate(0, 0, -0.1);
    const body = new THREE.Mesh(bodyGeo, glassMat);
    shield.add(body);

    // check mark: short stroke down-right, long stroke up-right, joined at the elbow
    const tickGeo = own(new RoundedBoxGeometry(1, 0.26, 0.26, 4, 0.12));
    const elbow = new THREE.Vector2(-0.12, -0.5);
    const barA = new THREE.Mesh(tickGeo, mintGlow);
    barA.scale.x = 0.62;
    barA.rotation.z = -Math.PI / 4;
    barA.position.set(elbow.x - 0.2, elbow.y + 0.2, 0.34);
    const barB = new THREE.Mesh(tickGeo, mintGlow);
    barB.scale.x = 1.26;
    barB.rotation.z = Math.PI / 4;
    barB.position.set(elbow.x + 0.46, elbow.y + 0.46, 0.34);
    shield.add(barA, barB);

    const shardGeo = own(new THREE.OctahedronGeometry(0.1, 0));
    const outlinePts = shieldPath(new THREE.Shape(), -0.03).getPoints(90);
    const SHARDS = 64;
    const shards = new THREE.InstancedMesh(shardGeo, glassMat, SHARDS);
    const rs = rng(7);
    const dummy = new THREE.Object3D();
    for (let i = 0; i < SHARDS; i++) {
      const p = outlinePts[Math.floor(rs() * outlinePts.length)];
      dummy.position.set(p.x * (1 + rs() * 0.05), p.y, (rs() - 0.5) * 0.3);
      dummy.rotation.set(rs() * 3, rs() * 3, rs() * 3);
      dummy.scale.set(0.5 + rs() * 1.2, 0.5 + rs() * 2.0, 0.5 + rs() * 1.2);
      dummy.updateMatrix();
      shards.setMatrixAt(i, dummy.matrix);
    }
    shield.add(shards);

    // The Gemini render is the front face; a solid teal slab behind it gives real thickness when the shield turns.
    frameMesh.visible = false;
    body.visible = false;
    barA.visible = false;
    barB.visible = false;
    shards.visible = false;
    const slabGeo = own(new THREE.ExtrudeGeometry(shieldPath(new THREE.Shape(), 0.03), { depth: 0.46, bevelEnabled: true, bevelThickness: 0.08, bevelSize: 0.06, bevelSegments: 6, curveSegments: 56 }));
    slabGeo.translate(0, 0, -0.78);
    const slabMat = own(new THREE.MeshPhysicalMaterial({ color: 0x2b8f8a, metalness: 0.5, roughness: 0.28, clearcoat: 1, clearcoatRoughness: 0.15, envMapIntensity: 1.5 }));
    const slab = new THREE.Mesh(slabGeo, slabMat);
    slab.scale.set(1.12, 1.03, 1);
    slab.position.y = -0.04;
    shield.add(slab);
    const artGeo = own(new THREE.PlaneGeometry(2.5, 2.95));
    const artMat = own(new THREE.MeshBasicMaterial({ transparent: true, alphaTest: 0.02, toneMapped: false, side: THREE.DoubleSide, opacity: 0 }));
    const art = new THREE.Mesh(artGeo, artMat);
    art.position.set(0, -0.06, -0.17);
    shield.add(art);
    new THREE.TextureLoader().load("/gemini/shield-cutout.webp", (tex) => {
      if (disposed) return tex.dispose();
      tex.colorSpace = THREE.SRGBColorSpace;
      tex.anisotropy = 8;
      disposables.push(tex);
      artMat.map = tex;
      artMat.opacity = 1;
      artMat.needsUpdate = true;
    });

    const haloTex = radialTexture("rgba(120,240,222,0.55)", "rgba(120,240,222,0)");
    disposables.push(haloTex);
    const haloMat = own(new THREE.SpriteMaterial({ map: haloTex, transparent: true, depthWrite: false, opacity: 0.9 }));
    const halo = new THREE.Sprite(haloMat);
    halo.scale.set(6.2, 6.2, 1);
    halo.position.z = -0.6;
    shield.add(halo);

    /* ---------- chapter 1: message bubbles ---------- */
    const bubbles = new THREE.Group();
    scene.add(bubbles);
    const bubbleGeo = own(new THREE.ExtrudeGeometry(bubbleShape(), { depth: 0.1, bevelEnabled: true, bevelThickness: 0.05, bevelSize: 0.05, bevelSegments: 6, curveSegments: 24 }));
    const lineGeo = own(new RoundedBoxGeometry(1, 0.1, 0.06, 3, 0.03));
    const bubbleDefs = [
      { p: [0.1, 1.5, 0], r: -0.08, s: 1.15, flag: 1 }, { p: [-1.5, 0.15, 0.7], r: 0.1, s: 0.95, flag: -1 }, { p: [1.5, -0.35, 0.2], r: 0.07, s: 1.0, flag: 0 },
      { p: [-0.5, -1.55, 0.9], r: -0.05, s: 0.85, flag: -1 }, { p: [1.7, 1.2, -0.9], r: 0.12, s: 0.7, flag: -1 },
    ];
    const bubbleMeshes: THREE.Group[] = [];
    bubbleDefs.forEach((d, i) => {
      const g = new THREE.Group();
      g.add(new THREE.Mesh(bubbleGeo, bubbleMat));
      [0.9, 0.62, 0.42].forEach((w, k) => {
        const flagged = d.flag > 0 && k === 1;
        const line = new THREE.Mesh(lineGeo, flagged ? coralMat : mintGlow);
        line.scale.set(w, 1, 1);
        line.position.set(-0.78 + w / 2, 0.22 - k * 0.22, 0.12);
        g.add(line);
      });
      g.position.set(d.p[0], d.p[1], d.p[2]);
      g.rotation.z = d.r;
      g.scale.setScalar(d.s);
      g.userData = { base: d.p, phase: i * 1.7 };
      bubbles.add(g);
      bubbleMeshes.push(g);
    });

    /* ---------- chapter 2: QR that assembles ---------- */
    const qr = new THREE.Group();
    scene.add(qr);
    const matrix = qrMatrix(21);
    const cells: { x: number; y: number }[] = [];
    matrix.forEach((row, y) => row.forEach((on, x) => on && cells.push({ x, y })));
    const cubeGeo = own(new RoundedBoxGeometry(0.17, 0.17, 0.17, 2, 0.045));
    const qrMesh = new THREE.InstancedMesh(cubeGeo, inkMat, cells.length);
    qr.add(qrMesh);
    const cell = 0.2;
    const rq = rng(99);
    const scatter = cells.map(() => new THREE.Vector3((rq() - 0.5) * 11, (rq() - 0.5) * 8, (rq() - 0.5) * 6 - 1));
    const tamper = cells.map((c) => Math.abs(c.x - 10) <= 2 && Math.abs(c.y - 10) <= 1);
    const inkCol = new THREE.Color("#12384a");
    const coralCol = new THREE.Color("#ff5a43");
    let tamperOn = false;
    cells.forEach((_, i) => qrMesh.setColorAt(i, inkCol));
    const scan = new THREE.Mesh(own(new RoundedBoxGeometry(4.5, 0.06, 0.14, 2, 0.03)), mintGlow);
    qr.add(scan);

    /* ---------- chapter 3: call wave ring ---------- */
    const call = new THREE.Group();
    scene.add(call);
    const BARS = 72;
    const barGeo = own(new RoundedBoxGeometry(0.07, 1, 0.07, 2, 0.03));
    const waveMesh = new THREE.InstancedMesh(barGeo, inkMat, BARS);
    const tealA = new THREE.Color("#1fd6b8");
    const tealB = new THREE.Color("#0b7a73");
    for (let i = 0; i < BARS; i++) waveMesh.setColorAt(i, tealA.clone().lerp(tealB, (Math.sin((i / BARS) * Math.PI * 2) + 1) / 2));
    call.add(waveMesh);
    const mic = new THREE.Mesh(own(new THREE.CapsuleGeometry(0.3, 0.62, 8, 24)), glassMat);
    call.add(mic);
    const micRing = new THREE.Mesh(own(new THREE.TorusGeometry(0.58, 0.035, 12, 48, Math.PI)), mintGlow);
    micRing.rotation.z = Math.PI;
    micRing.position.y = -0.1;
    call.add(micRing);

    /* ---------- chapter 4: flood ---------- */
    const flood = new THREE.Group();
    scene.add(flood);
    const FLOOD = 240;
    const floodMat = own(new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.4, metalness: 0.05 }));
    const floodMesh = new THREE.InstancedMesh(own(new THREE.IcosahedronGeometry(0.1, 1)), floodMat, FLOOD);
    flood.add(floodMesh);
    const rf = rng(5);
    const floodSeed = Array.from({ length: FLOOD }, () => ({ a: rf() * Math.PI * 2, ph: rf(), z: (rf() - 0.5) * 1.6, sp: 0.18 + rf() * 0.22, calm: rf() }));
    for (let i = 0; i < FLOOD; i++) floodMesh.setColorAt(i, new THREE.Color("#f0a22e"));
    const barrier = new THREE.Mesh(own(new THREE.TorusGeometry(1.45, 0.035, 16, 96)), mintGlow);
    flood.add(barrier);
    const barrierDiscTex = radialTexture("rgba(80,230,208,0.28)", "rgba(80,230,208,0)");
    disposables.push(barrierDiscTex);
    const disc = new THREE.Sprite(own(new THREE.SpriteMaterial({ map: barrierDiscTex, transparent: true, depthWrite: false })));
    disc.scale.set(3.6, 3.6, 1);
    flood.add(disc);
    const amber = new THREE.Color("#f0a22e");
    const coral = new THREE.Color("#ff5a43");
    const mint = new THREE.Color("#1fd6b8");
    const tmpCol = new THREE.Color();

    /* ---------- chapter 5: lock rings ---------- */
    const lock = new THREE.Group();
    scene.add(lock);
    const ringA = new THREE.Mesh(own(new THREE.TorusGeometry(1.95, 0.03, 12, 128)), mintGlow);
    const ringB = new THREE.Mesh(own(new THREE.TorusGeometry(2.25, 0.02, 12, 128)), frameMat);
    lock.add(ringA, ringB);
    const lockBody = new THREE.Mesh(own(new RoundedBoxGeometry(0.62, 0.52, 0.22, 4, 0.1)), frameMat);
    const shackle = new THREE.Mesh(own(new THREE.TorusGeometry(0.19, 0.055, 12, 32, Math.PI)), frameMat);
    shackle.position.y = 0.26;
    const badge = new THREE.Group();
    badge.add(lockBody, shackle);
    badge.position.set(1.0, -1.15, 0.55);
    lock.add(badge);

    /* ---------- snow / ice particles ---------- */
    const COUNT = high ? 520 : 260;
    const pr = rng(2026);
    const pos = new Float32Array(COUNT * 3);
    const seeds = new Float32Array(COUNT);
    const sizes = new Float32Array(COUNT);
    for (let i = 0; i < COUNT; i++) {
      pos[i * 3] = (pr() - 0.5) * 20;
      pos[i * 3 + 1] = (pr() - 0.5) * 12;
      pos[i * 3 + 2] = pr() * 13 - 9;
      seeds[i] = pr();
      sizes[i] = 5 + pr() * 11;
    }
    const pGeo = own(new THREE.BufferGeometry());
    pGeo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    pGeo.setAttribute("aSeed", new THREE.BufferAttribute(seeds, 1));
    pGeo.setAttribute("aSize", new THREE.BufferAttribute(sizes, 1));
    const pUniforms = { uTime: { value: 0 }, uScroll: { value: 0 }, uPx: { value: renderer.getPixelRatio() }, uColor: { value: new THREE.Color("#8fdcd3") } };
    const pMat = own(new THREE.ShaderMaterial({
      uniforms: pUniforms, transparent: true, depthWrite: false,
      vertexShader: `attribute float aSeed; attribute float aSize; uniform float uTime; uniform float uScroll; uniform float uPx; varying float vA;
        void main(){
          vec3 p = position;
          float t = uTime * 0.15;
          p.x += sin(t * 3.1 + aSeed * 20.0) * 0.35;
          p.z += sin(t * 2.7 + aSeed * 11.0) * 0.35;
          p.y = mod(p.y - uScroll * (0.25 + aSeed * 0.35) - uTime * (0.05 + aSeed * 0.07) + 6.0, 12.0) - 6.0 + cos(t * 2.3 + aSeed * 31.0) * 0.3;
          vec4 mv = modelViewMatrix * vec4(p, 1.0);
          gl_Position = projectionMatrix * mv;
          gl_PointSize = aSize * uPx * (9.0 / -mv.z);
          vA = 0.25 + 0.6 * fract(aSeed * 13.7);
        }`,
      fragmentShader: `uniform vec3 uColor; varying float vA;
        void main(){ float d = length(gl_PointCoord - 0.5); float a = smoothstep(0.5, 0.05, d) * vA; if (a < 0.01) discard; gl_FragColor = vec4(uColor, a); }`,
    }));
    const points = new THREE.Points(pGeo, pMat);
    points.frustumCulled = false;
    scene.add(points);

    /* ---------- layout + scroll bookkeeping ---------- */
    let narrow = false;
    let vw = 1;
    let vh = 1;
    let unitsPerPx = 0.01;
    let centers: number[] = [];
    let tops: number[] = [];
    let heights: number[] = [];
    const visibleH = (z: number) => 2 * (CAM_Z - z) * Math.tan(THREE.MathUtils.degToRad(FOV / 2));

    function measure() {
      const els = Array.from(document.querySelectorAll<HTMLElement>("[data-chapter]")).sort((a, b) => Number(a.dataset.chapter) - Number(b.dataset.chapter));
      tops = els.map((e) => e.getBoundingClientRect().top + window.scrollY);
      heights = els.map((e) => e.offsetHeight);
      centers = els.map((e, i) => tops[i] + heights[i] / 2);
    }

    function resize() {
      vw = window.innerWidth;
      vh = window.innerHeight;
      renderer.setSize(vw, vh, false);
      camera.aspect = vw / vh;
      camera.updateProjectionMatrix();
      narrow = camera.aspect < 1.05;
      unitsPerPx = visibleH(0) / vh;
      const dist = visibleH(backdrop.position.z);
      const hNeeded = dist * 1.18;
      const wNeeded = dist * camera.aspect * 1.08;
      const h = Math.max(hNeeded, wNeeded / backdropAspect);
      backdrop.scale.set(h * backdropAspect, h, 1);
      measure();
      dirty = true;
    }
    let dirty = true;
    const ro = new ResizeObserver(() => {
      if (Math.abs(window.innerWidth - vw) > 1 || Math.abs(window.innerHeight - vh) > 120) resize();
      else measure();
    });
    ro.observe(document.body);
    window.addEventListener("resize", resize);

    const pointer = { x: 0, y: 0, tx: 0, ty: 0 };
    const onMove = (e: PointerEvent) => {
      if (e.pointerType === "touch") return;
      pointer.tx = (e.clientX / window.innerWidth) * 2 - 1;
      pointer.ty = (e.clientY / window.innerHeight) * 2 - 1;
    };
    if (!reduced) window.addEventListener("pointermove", onMove, { passive: true });

    function degrade() {
      glassMat.transmission = 0;
      glassMat.transparent = true;
      glassMat.opacity = 0.82;
      bubbleMat.transmission = 0;
      bubbleMat.transparent = true;
      bubbleMat.opacity = 0.72;
      transmissive.forEach((m) => (m.needsUpdate = true));
      renderer.setPixelRatio(1);
      pUniforms.uPx.value = 1;
      renderer.setSize(vw, vh, false);
    }

    /* ---------- frame ---------- */
    let lastT = performance.now();
    let time = 0;
    let frames = 0;
    let acc = 0;
    let degraded = !high;
    let readyFired = false;
    let lastY = -1;
    const wts = new Array(8).fill(0);

    function frame() {
      raf = requestAnimationFrame(frame);
      if (document.hidden) return;
      const nowT = performance.now();
      const dt = Math.min((nowT - lastT) / 1000, 0.05);
      lastT = nowT;
      const sy = window.scrollY;
      if (reduced && !dirty && sy === lastY) return;
      lastY = sy;
      dirty = false;
      if (!reduced) time += dt;

      const y = sy + vh * 0.5;
      const n = centers.length;
      let cf = 0;
      if (n > 1) {
        if (y <= centers[0]) cf = 0;
        else if (y >= centers[n - 1]) cf = n - 1;
        else for (let i = 0; i < n - 1; i++) if (y < centers[i + 1]) { cf = i + (y - centers[i]) / (centers[i + 1] - centers[i]); break; }
      }
      for (let i = 0; i < 8; i++) wts[i] = smooth(1, 0.2, Math.abs(cf - i));
      const q = (i: number) => (heights[i] ? clamp((y - tops[i]) / heights[i]) : 0);

      pointer.x += (pointer.tx - pointer.x) * 0.06;
      pointer.y += (pointer.ty - pointer.y) * 0.06;

      // shield anchor
      const A = narrow ? ANCHORS_NARROW : ANCHORS_WIDE;
      const i0 = Math.max(0, Math.min(Math.floor(cf), A.length - 2));
      const tt = smooth(0.3, 0.7, cf - i0);
      const faqFade = 1 - smooth(5.4, 6.1, cf);
      const a0 = A[i0];
      const a1 = A[Math.min(i0 + 1, A.length - 1)];
      const ax = mix(a0.x, a1.x, tt);
      const ay = mix(a0.y, a1.y, tt);
      const az = mix(a0.z, a1.z, tt);
      const as = mix(a0.s, a1.s, tt);
      shield.position.set(ax + pointer.x * 0.18, ay + Math.sin(time * 0.8) * 0.08 - pointer.y * 0.1, az);
      shield.scale.setScalar(as * Math.max(0.001, faqFade));
      shield.visible = faqFade > 0.01;
      shield.rotation.y = Math.sin(time * 0.45) * 0.28 + pointer.x * 0.42 + Math.sin(cf * 0.9) * 0.35;
      shield.rotation.x = -pointer.y * 0.2 + Math.sin(time * 0.6) * 0.04;
      glow.position.set(shield.position.x, shield.position.y, 2.6);
      glow.intensity = 10 + 12 * as;

      // backdrop scrolls away with the hero
      backdrop.position.y = sy * unitsPerPx * 0.55 * (visibleH(-12) / visibleH(0));
      backdrop.visible = !!backdropUniforms.map.value && sy < vh * 1.4;
      camera.position.x = pointer.x * 0.25;
      camera.position.y = -pointer.y * 0.15;
      camera.lookAt(0, 0, 0);

      const vx = narrow ? 0 : 2.55;
      const vy = narrow ? 2.3 : -0.1;
      const vs = narrow ? 0.5 : 0.78;

      // bubbles
      const wb = wts[1];
      bubbles.visible = wb > 0.01;
      if (bubbles.visible) {
        bubbles.position.set(vx, vy, 0);
        bubbles.scale.setScalar(vs * (0.55 + 0.45 * wb));
        bubbles.rotation.y = Math.sin(time * 0.3) * 0.15 + pointer.x * 0.25;
        bubbleMeshes.forEach((g) => {
          const b = g.userData.base as number[];
          g.position.y = b[1] + Math.sin(time * 0.9 + g.userData.phase) * 0.12;
          g.position.x = b[0] + Math.cos(time * 0.6 + g.userData.phase) * 0.06;
        });
      }

      // QR
      const wq = wts[2];
      qr.visible = wq > 0.01;
      if (qr.visible) {
        qr.position.set(-vx, vy, 0);
        qr.scale.setScalar(vs * (0.7 + 0.3 * wq));
        qr.rotation.y = pointer.x * 0.3 + Math.sin(time * 0.35) * 0.08;
        qr.rotation.x = -pointer.y * 0.18;
        const p = q(2);
        const assemble = smooth(0.05, 0.62, p);
        for (let i = 0; i < cells.length; i++) {
          const c = cells[i];
          const delay = (c.x * 0.4 + c.y) / 30 * 0.35;
          const k = smooth(delay, delay + 0.65, assemble);
          const sx = (c.x - 10) * cell;
          const sy2 = (10 - c.y) * cell;
          dummy.position.set(mix(scatter[i].x, sx, k), mix(scatter[i].y, sy2, k), mix(scatter[i].z, 0, k) + Math.sin(time * 1.4 + i) * 0.015 * k);
          dummy.rotation.set((1 - k) * 2.4, (1 - k) * 3.1, 0);
          dummy.scale.setScalar(0.3 + 0.7 * k);
          dummy.updateMatrix();
          qrMesh.setMatrixAt(i, dummy.matrix);
        }
        qrMesh.instanceMatrix.needsUpdate = true;
        const wantTamper = p > 0.72;
        if (wantTamper !== tamperOn) {
          tamperOn = wantTamper;
          tamper.forEach((t, i) => t && qrMesh.setColorAt(i, tamperOn ? coralCol : inkCol));
          if (qrMesh.instanceColor) qrMesh.instanceColor.needsUpdate = true;
        }
        scan.visible = assemble > 0.9;
        scan.position.set(0, mix(-2.2, 2.2, (time * 0.32) % 1), 0.2);
      }

      // call wave
      const wc = wts[3];
      call.visible = wc > 0.01;
      if (call.visible) {
        call.position.set(vx, vy, 0);
        call.scale.setScalar(vs * (0.6 + 0.4 * wc));
        call.rotation.y = pointer.x * 0.3;
        call.rotation.x = -pointer.y * 0.2;
        const talk = 0.45 + 0.55 * smooth(0.1, 0.5, q(3));
        for (let i = 0; i < BARS; i++) {
          const ang = (i / BARS) * Math.PI * 2;
          const amp = 0.18 + talk * (0.55 * Math.abs(Math.sin(time * 2.1 + i * 0.55) * Math.cos(time * 1.3 + i * 0.21)) + 0.25 * Math.abs(Math.sin(time * 3.7 + i * 0.9)));
          dummy.position.set(Math.cos(ang) * 1.7, Math.sin(ang) * 1.7, 0);
          dummy.rotation.set(0, 0, ang - Math.PI / 2);
          dummy.scale.set(1, amp * 2, 1);
          dummy.updateMatrix();
          waveMesh.setMatrixAt(i, dummy.matrix);
        }
        waveMesh.instanceMatrix.needsUpdate = true;
        mic.position.y = Math.sin(time * 1.2) * 0.05;
      }

      // flood
      const wf = wts[4];
      flood.visible = wf > 0.01;
      if (flood.visible) {
        flood.position.set(-vx, vy, 0);
        flood.scale.setScalar(vs * (0.6 + 0.4 * wf));
        const calmGlobal = smooth(0.5, 0.8, q(4));
        for (let i = 0; i < FLOOD; i++) {
          const s = floodSeed[i];
          const calmK = calmGlobal * (0.55 + 0.45 * s.calm);
          const u = (s.ph + time * s.sp * (1 - 0.88 * calmK)) % 1;
          const r = mix(4.6, 1.62 + s.calm * 0.12 * calmK, smooth(0, 1, u));
          const ang = s.a + u * 0.6 * (1 - calmK);
          dummy.position.set(Math.cos(ang) * r, Math.sin(ang) * r, s.z * (1 - 0.5 * u));
          dummy.rotation.set(0, 0, 0);
          dummy.scale.setScalar((0.6 + 0.8 * (1 - u)) * (1 - 0.35 * calmK));
          dummy.updateMatrix();
          floodMesh.setMatrixAt(i, dummy.matrix);
          tmpCol.copy(amber).lerp(coral, smooth(0.3, 1, u)).lerp(mint, calmK);
          floodMesh.setColorAt(i, tmpCol);
        }
        floodMesh.instanceMatrix.needsUpdate = true;
        if (floodMesh.instanceColor) floodMesh.instanceColor.needsUpdate = true;
        barrier.rotation.z = time * 0.25;
        barrier.scale.setScalar(1 + 0.03 * Math.sin(time * 2));
      }

      // lock rings follow the shield
      const wl = wts[5];
      lock.visible = wl > 0.01 && faqFade > 0.01;
      if (lock.visible) {
        lock.position.copy(shield.position);
        lock.scale.setScalar(as * (0.6 + 0.4 * wl));
        ringA.rotation.set(Math.PI / 2.4, time * 0.4, 0);
        ringB.rotation.set(Math.PI / 2.1, -time * 0.3, 0.4);
        badge.rotation.y = Math.sin(time * 0.9) * 0.3;
      }

      pUniforms.uTime.value = time;
      pUniforms.uScroll.value = sy * unitsPerPx;

      renderer.render(scene, camera);

      if (!readyFired && frames >= 2) {
        readyFired = true;
        onReady();
      }
      frames++;
      if (!reduced && !degraded && frames > 40 && frames <= 130) {
        acc += dt;
        if (frames === 130 && acc / 90 > 0.034) {
          degraded = true;
          degrade();
        }
      }
    }

    resize();
    raf = requestAnimationFrame(frame);
    const late = window.setTimeout(measure, 1200);

    return () => {
      disposed = true;
      cancelAnimationFrame(raf);
      window.clearTimeout(late);
      ro.disconnect();
      window.removeEventListener("resize", resize);
      window.removeEventListener("pointermove", onMove);
      disposables.forEach((d) => d.dispose());
      renderer.dispose();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <canvas ref={ref} className="ts-canvas" aria-hidden="true" />;
}
