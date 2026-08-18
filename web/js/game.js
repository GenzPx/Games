import * as THREE from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { WORLD } from "./generated.js";
import { createState, tick as tickBody, airTempC } from "./physiology.js";
import {
  createExpedition,
  nearestCamp,
  tickClock,
  tickWeather,
  shouldTurnAround,
  endingCopy,
  causeToEnding,
  ENDING,
} from "./expedition.js";
import { Soundscape } from "./audio.js";
import {
  WORLD_M, VISUAL_H, heightY, altitudeOf, slopeDeg, surfaceNormal,
  buildTerrain, buildSky, buildCloudSea, buildLake, buildClimbRoute, addDistantPeaks,
} from "./terrain.js";
import { createClimber, poseClimber } from "./climber.js";

const TIME_SCALE = 12;
const $ = (id) => document.getElementById(id);
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

const I18N = {
  id: {
    start: "Tap / klik untuk mulai",
    continue: "Naik gunung",
    briefingTitle: "Cara main",
    briefing:
      "Ini climbing, bukan jalan-jalan.\n\nNempel ke batu — tahan PANJAT. Stamina habis = jatuh. Makin tinggi, stamina makin cepat habis. Jatuh jauh = balik ke tenda terakhir.\n\nHP: joystick kiri, geser kanan buat kamera, tahan PANJAT, LARI, TENDA.\nPC: WASD · mouse · SPASI panjat · Shift lari · E tenda · F makan · O oksigen\n\nPuncak opsional. Turunan wajib.",
    turnaround: "JAM PUTAR BALIK — turun sekarang",
    deathzone: "ZONA MATI",
    tent: "TENDA — checkpoint",
    leave: "Keluar tenda",
    summit: "PUNCAK. Turun. Jangan tinggal.",
    nofood: "Makanan habis",
    nowater: "Air habis",
    noo2: "O₂ habis",
    o2on: "Oksigen ON",
    ate: "Makan. Stamina pulih.",
    drank: "Minum.",
    paused: "JEDA",
    resume: "Lanjut",
    died: "JATUH / GAGAL",
    survived: "KAMU HIDUP",
    again: "Ulangi",
    lang: "EN",
    day: "Hari",
    grab: "Tahan PANJAT",
  },
  en: {
    start: "Click to start the climb",
    continue: "Climb",
    briefingTitle: "How to play",
    briefing:
      "This is climbing, not hiking.\n\nHold CLIMB to stick to rock. Empty stamina = you fall. Higher up, stamina dies faster. A long fall sends you back to the last tent.\n\nPhone: left stick, drag right to look, hold CLIMB, RUN, TENT.\nPC: WASD · mouse · SPACE climb · Shift run · E tent · F eat · O oxygen\n\nSummit is optional. Descent is not.",
    turnaround: "TURNAROUND — go down",
    deathzone: "DEATH ZONE",
    tent: "TENT — checkpoint",
    leave: "Leave tent",
    summit: "SUMMIT. Go down. Do not stay.",
    nofood: "No food",
    nowater: "No water",
    noo2: "No O₂",
    o2on: "Oxygen ON",
    ate: "Food. Stamina back.",
    drank: "Water.",
    paused: "PAUSED",
    resume: "Resume",
    died: "FELL / FAILED",
    survived: "YOU LIVED",
    again: "Again",
    lang: "ID",
    day: "Day",
    grab: "SPACE — climb",
  },
};

export class ThinAir {
  constructor() {
    this.lang = "id";
    this.mode = "boot";
    this.body = createState();
    this.ex = createExpedition();
    this.sound = new Soundscape();
    this.keys = {};
    this.yaw = 0.05;
    this.pitch = 0.18;
    this.pos = new THREE.Vector3();
    this.vy = 0;
    this.climbing = false;
    this.grounded = true;
    this.inTent = false;
    this.platforms = [];
    this.lastCamp = "bc";
    this.climbTarget = null;
    this.clock = new THREE.Clock();
    this.time = 0;
    this.radioCd = 0;
    this.warnCd = 0;
    this._locked = false;
    this.speedNow = 0;
    this.anim = { time: 0, speed: 0, grounded: true, climbing: false, vy: 0, slope: 0 };
    this.mobile = matchMedia("(pointer: coarse)").matches || "ontouchstart" in window;
    this.stick = { active: false, x: 0, y: 0, id: null };
    this.lookId = null;
    this.lookLast = { x: 0, y: 0 };
  }

  t(k) { return I18N[this.lang][k]; }

  async start() {
    this.bindUi();
    this.show("screen-splash");
    this.syncCopy();
    await new Promise((r) => setTimeout(r, 1800));
    $("screen-splash").classList.add("out");
    await new Promise((r) => setTimeout(r, 600));
    this.show("screen-title");
    this.mode = "title";
  }

  bindUi() {
    $("btn-lang").onclick = () => { this.lang = this.lang === "id" ? "en" : "id"; this.syncCopy(); };
    $("btn-begin").onclick = () => { this.show("screen-brief"); this.mode = "brief"; };
    $("btn-climb").onclick = () => this.enterWorld();
    $("btn-resume").onclick = () => this.resume();
    $("btn-again").onclick = () => location.reload();
    $("btn-menu-die").onclick = () => location.reload();
    window.addEventListener("keydown", (e) => this.onKey(e, true));
    window.addEventListener("keyup", (e) => this.onKey(e, false));
    window.addEventListener("blur", () => { this.keys = {}; this.stick.x = 0; this.stick.y = 0; });
    this.bindTouch();
  }

  bindTouch() {
    const hold = (id, key) => {
      const el = $(id);
      if (!el) return;
      const down = (e) => { e.preventDefault(); e.stopPropagation(); this.keys[key] = true; el.classList.add("active"); };
      const up = (e) => { e.preventDefault(); this.keys[key] = false; el.classList.remove("active"); };
      el.addEventListener("pointerdown", down);
      el.addEventListener("pointerup", up);
      el.addEventListener("pointercancel", up);
      el.addEventListener("pointerleave", up);
    };
    hold("btn-climb-hold", " ");
    hold("btn-run-hold", "shift");
    $("btn-tent")?.addEventListener("click", (e) => { e.preventDefault(); this.toggleTent(); });
    $("btn-eat")?.addEventListener("click", (e) => { e.preventDefault(); this.eat(); });
    $("btn-o2")?.addEventListener("click", (e) => { e.preventDefault(); this.oxygen(); });
    $("btn-pause")?.addEventListener("click", (e) => { e.preventDefault(); this.pause(); });

    window.addEventListener("pointerdown", (e) => this.onPointerDown(e));
    window.addEventListener("pointermove", (e) => this.onPointerMove(e));
    window.addEventListener("pointerup", (e) => this.onPointerUp(e));
    window.addEventListener("pointercancel", (e) => this.onPointerUp(e));
    document.addEventListener("gesturestart", (e) => e.preventDefault());
    document.addEventListener("contextmenu", (e) => { if (this.mobile) e.preventDefault(); });
  }

  onPointerDown(e) {
    if (this.mode !== "play" || e.pointerType === "mouse") return;
    if (e.target.closest && e.target.closest(".tbtn, button, .panel")) return;
    const x = e.clientX, y = e.clientY;
    const left = x < innerWidth * 0.46 && y > innerHeight * 0.38;
    if (left && this.stick.id == null) {
      this.stick.active = true;
      this.stick.id = e.pointerId;
      this.stick.ox = x;
      this.stick.oy = y;
      this.updateStick(0, 0);
    } else if (this.lookId == null) {
      this.lookId = e.pointerId;
      this.lookLast.x = x;
      this.lookLast.y = y;
    }
  }

  onPointerMove(e) {
    if (this.mode !== "play") return;
    if (e.pointerId === this.stick.id) {
      const dx = e.clientX - this.stick.ox;
      const dy = e.clientY - this.stick.oy;
      const max = 52;
      const len = Math.hypot(dx, dy) || 1;
      const k = Math.min(1, len / max);
      this.stick.x = (dx / len) * k;
      this.stick.y = (dy / len) * k;
      this.updateStick(this.stick.x * max, this.stick.y * max);
    } else if (e.pointerId === this.lookId) {
      const dx = e.clientX - this.lookLast.x;
      const dy = e.clientY - this.lookLast.y;
      this.lookLast.x = e.clientX;
      this.lookLast.y = e.clientY;
      this.yaw -= dx * 0.0055;
      this.pitch += dy * 0.0045;
      this.pitch = clamp(this.pitch, -0.15, 1.25);
    }
  }

  onPointerUp(e) {
    if (e.pointerId === this.stick.id) {
      this.stick.active = false;
      this.stick.id = null;
      this.stick.x = 0;
      this.stick.y = 0;
      this.updateStick(0, 0);
    }
    if (e.pointerId === this.lookId) this.lookId = null;
  }

  updateStick(px, py) {
    const knob = $("stick-knob");
    if (knob) knob.style.transform = `translate(${px}px, ${py}px)`;
  }

  syncCopy() {
    $("btn-lang").textContent = this.t("lang");
    $("copy-start").textContent = this.t("start");
    $("brief-title").textContent = this.t("briefingTitle");
    $("brief-body").textContent = this.t("briefing");
    $("btn-climb").textContent = this.t("continue");
    $("pause-title").textContent = this.t("paused");
    $("btn-resume").textContent = this.t("resume");
    $("btn-again").textContent = this.t("again");
    $("btn-menu-die").textContent = this.t("again");
  }

  show(id) {
    for (const el of document.querySelectorAll(".screen")) el.classList.remove("on");
    $(id)?.classList.add("on");
  }

  async enterWorld() {
    this.show("screen-load");
    $("load-bar").style.width = "12%";
    try { await this.sound.init(); } catch {}
    $("load-bar").style.width = "28%";
    await this.initThree();
    $("load-bar").style.width = "100%";
    await new Promise((r) => setTimeout(r, 200));
    this.show("screen-hud");
    $("hud").classList.add("on");
    if (this.mobile) {
      const ui = $("touch-ui");
      if (ui) ui.hidden = false;
    }
    this.mode = "play";
    this.clock.start();
    if (!this.mobile) this.canvas.requestPointerLock?.();
    this.logRadio(this.lang === "id"
      ? "Ikuti pijakan. Tahan PANJAT di dinding. Jangan habisin stamina di udara tipis."
      : "Follow the ledges. Hold CLIMB on walls. Don't dump stamina in thin air.");
    this.loop();
  }

  async initThree() {
    const canvas = $("view");
    this.canvas = canvas;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: "high-performance" });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, this.mobile ? 1.25 : 1.6));
    this.renderer.setSize(innerWidth, innerHeight);
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.05;
    this.renderer.shadowMap.enabled = !this.mobile;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.scene.fog = new THREE.FogExp2(0x87a0b8, 0.00042);

    this.camera = new THREE.PerspectiveCamera(62, innerWidth / innerHeight, 0.15, 7000);

    this.sky = buildSky();
    this.scene.add(this.sky);
    this.scene.add(buildCloudSea());
    this.scene.add(buildLake());

    this.hemi = new THREE.HemisphereLight(0x9ec4ff, 0x3d3428, 0.7);
    this.scene.add(this.hemi);
    this.sun = new THREE.DirectionalLight(0xffe2b0, 2.1);
    this.sun.castShadow = !this.mobile;
    this.sun.shadow.mapSize.set(2048, 2048);
    this.sun.shadow.camera.near = 4;
    this.sun.shadow.camera.far = 1800;
    const s = 420;
    this.sun.shadow.camera.left = this.sun.shadow.camera.bottom = -s;
    this.sun.shadow.camera.right = this.sun.shadow.camera.top = s;
    this.scene.add(this.sun);
    this.scene.add(this.sun.target);

    this.scene.add(buildTerrain());
    addDistantPeaks(this.scene);
    const route = buildClimbRoute();
    this.scene.add(route.group);
    this.platforms = route.platforms;

    this.player = createClimber();
    this.scene.add(this.player);
    this.placeAt("bc");

    await this.scatterProps();

    this.buildSnow();

    canvas.addEventListener("click", () => {
      if (this.mode === "play" && !this.mobile) canvas.requestPointerLock?.();
    });
    document.addEventListener("pointerlockchange", () => {
      if (this.mobile) return;
      const locked = document.pointerLockElement === canvas;
      if (this._locked && !locked && this.mode === "play") this.pause();
      this._locked = locked;
    });
    document.addEventListener("mousemove", (e) => this.onMouse(e));
    window.addEventListener("resize", () => this.onResize());
  }

  async scatterProps() {
    const gltf = new GLTFLoader();
    const load = async (p) => { try { return (await gltf.loadAsync(p)).scene; } catch { return null; } };
    const tents = [];
    for (const n of ["tent_detailedClosed.glb", "tent_smallClosed.glb"]) {
      const m = await load(`assets/models/nature/${n}`);
      if (m) tents.push(m);
    }
    const pines = [];
    for (const n of ["tree_pineTallA.glb", "tree_pineTallB.glb", "tree_pineDefaultA.glb", "tree_pineSmallA.glb"]) {
      const m = await load(`assets/models/nature/${n}`);
      if (m) pines.push(m);
    }
    this.campAnchors = [];
    for (const camp of WORLD.camps) {
      const y = heightY(camp.x, camp.z);
      const g = new THREE.Group();
      g.position.set(camp.x, y + 0.5, camp.z);
      if (tents.length) {
        const t = tents[camp.index % tents.length].clone(true);
        t.scale.setScalar(camp.id === "bc" ? 5 : 3.6);
        t.rotation.y = camp.index * 0.8;
        g.add(t);
        if (camp.id === "bc") {
          const t2 = tents[0].clone(true);
          t2.scale.setScalar(3.4);
          t2.position.set(5.5, 0, -3);
          g.add(t2);
        }
      }
      this.scene.add(g);
      this.campAnchors.push({ camp, pos: g.position.clone() });
    }
    const pineCount = this.mobile ? 18 : 55;
    for (let i = 0; i < pineCount && pines.length; i++) {
      const u = 0.3 + Math.random() * 0.4;
      const v = 0.07 + Math.random() * 0.14;
      const x = (u - 0.5) * WORLD_M;
      const z = (v - 0.5) * WORLD_M;
      if (altitudeOf(x, z) > 5000 || slopeDeg(x, z) > 32) continue;
      const t = pines[i % pines.length].clone(true);
      t.position.set(x, heightY(x, z), z);
      t.rotation.y = Math.random() * 6;
      t.scale.setScalar(4 + Math.random() * 4);
      t.castShadow = true;
      this.scene.add(t);
    }

    const pole = WORLD.route.find((p) => p.id === "summit");
    if (pole) {
      const y = heightY(pole.x, pole.z);
      const flagPole = new THREE.Mesh(
        new THREE.CylinderGeometry(0.05, 0.06, 4.2, 8),
        new THREE.MeshStandardMaterial({ color: 0xd0d6dc, metalness: 0.5 })
      );
      flagPole.position.set(pole.x, y + 2.1, pole.z);
      const flag = new THREE.Mesh(
        new THREE.PlaneGeometry(1.4, 0.7),
        new THREE.MeshStandardMaterial({ color: 0xc9a227, side: THREE.DoubleSide })
      );
      flag.position.set(pole.x + 0.7, y + 3.7, pole.z);
      this.scene.add(flagPole, flag);
    }
  }

  buildSnow() {
    const n = this.mobile ? 500 : 1600;
    const geo = new THREE.BufferGeometry();
    const pos = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      pos[i * 3] = (Math.random() - 0.5) * 60;
      pos[i * 3 + 1] = Math.random() * 30;
      pos[i * 3 + 2] = (Math.random() - 0.5) * 60;
    }
    geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    this.snow = new THREE.Points(geo, new THREE.PointsMaterial({
      color: 0xffffff, size: 0.11, transparent: true, opacity: 0.5, depthWrite: false,
    }));
    this.camera.add(this.snow);
  }

  placeAt(id) {
    const p = WORLD.route.find((r) => r.id === id) || WORLD.camps[0];
    const y = heightY(p.x, p.z);
    this.pos.set(p.x + 2.5, y + 0.2, p.z + 6);
    this.vy = 0;
    this.climbing = false;
    this.grounded = true;
    this.lastCamp = id;
    this.player.position.copy(this.pos);
  }

  onResize() {
    this.camera.aspect = innerWidth / innerHeight;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(innerWidth, innerHeight);
  }

  onMouse(e) {
    if (this.mode !== "play" || document.pointerLockElement !== this.canvas) return;
    this.yaw -= e.movementX * 0.0024;
    this.pitch += e.movementY * 0.0020;
    this.pitch = clamp(this.pitch, -0.15, 1.25);
  }

  onKey(e, down) {
    const k = e.key.toLowerCase();
    this.keys[k] = down;
    if (k === " ") e.preventDefault();
    if (!down) return;
    if (k === "escape" && this.mode === "play") this.pause();
    if (this.mode !== "play") return;
    if (k === "e") this.toggleTent();
    if (k === "f") this.eat();
    if (k === "c") this.drink();
    if (k === "o") this.oxygen();
    if (k === "r") this.radio();
  }

  pause() {
    if (this.mode !== "play") return;
    this.mode = "pause";
    document.exitPointerLock?.();
    $("screen-pause").classList.add("on");
  }
  resume() {
    $("screen-pause").classList.remove("on");
    this.mode = "play";
    this.clock.getDelta();
    if (!this.mobile) this.canvas.requestPointerLock?.();
  }

  toggleTent() {
    if (this.inTent) { this.inTent = false; return; }
    if (this.nearCamp && this.nearCamp.dist < 16) {
      this.inTent = true;
      this.lastCamp = this.nearCamp.camp.id;
      this.body.stamina = Math.min(1, this.body.stamina + 0.35);
      this.prompt(this.t("tent"));
      this.sound.oneshot("ui_ok", 0.4);
    }
  }
  eat() {
    if (this.ex.food <= 0) return this.prompt(this.t("nofood"));
    this.ex.food--; this.body.calories = Math.min(4000, this.body.calories + 620);
    this.body.stamina = Math.min(1, this.body.stamina + 0.22);
    this.prompt(this.t("ate")); this.sound.oneshot("ui_ok", 0.35);
  }
  drink() {
    if (this.ex.water <= 0) return this.prompt(this.t("nowater"));
    this.ex.water--; this.body.waterL = Math.min(3.5, this.body.waterL + 0.55);
    this.prompt(this.t("drank")); this.sound.oneshot("ui_ok", 0.35);
  }
  oxygen() {
    if (this.ex.o2Active) { this.ex.o2Active = false; return; }
    if (this.ex.o2Bottles <= 0 && this.ex.o2Remaining <= 0) return this.prompt(this.t("noo2"));
    if (this.ex.o2Remaining <= 0) { this.ex.o2Bottles--; this.ex.o2Remaining = WORLD.constants.O2_BOTTLE_MIN * 60; }
    this.ex.o2Active = true; this.prompt(this.t("o2on")); this.sound.oneshot("ui_ok", 0.4);
  }
  radio() {
    if (this.radioCd > 0) return;
    this.radioCd = 6;
    this.sound.oneshot("radio", 0.4);
    const alt = altitudeOf(this.pos.x, this.pos.z);
    this.logRadio(this.lang === "id"
      ? `Radio: ${alt.toFixed(0)} m · SpO₂ ${this.body.spo2.toFixed(0)}% · tenda ${this.lastCamp}`
      : `Radio: ${alt.toFixed(0)} m · SpO₂ ${this.body.spo2.toFixed(0)}% · tent ${this.lastCamp}`);
  }
  logRadio(msg) {
    this.ex.log.unshift({ t: this.fmtTime(), msg });
    this.ex.log = this.ex.log.slice(0, 4);
    $("radio-log").innerHTML = this.ex.log.map((l) => `<div><span>${l.t}</span>${l.msg}</div>`).join("");
  }
  prompt(msg) {
    const el = $("prompt");
    el.textContent = msg;
    el.classList.add("on");
    clearTimeout(this._pt);
    this._pt = setTimeout(() => el.classList.remove("on"), 2400);
  }
  fmtTime() {
    const h = Math.floor(this.ex.hour);
    const m = Math.floor((this.ex.hour - h) * 60);
    return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
  }

  groundY(x, z, fromY) {
    let y = heightY(x, z);
    for (const p of this.platforms) {
      if (Math.abs(x - p.x) <= p.w * 0.55 && Math.abs(z - p.z) <= p.d * 0.55) {
        const top = p.y + p.thick * 0.5;
        if (fromY + 0.35 >= top && top > y) y = top;
      }
    }
    return y;
  }

  nearestHold(pos, dir, radius) {
    let best = null, bestScore = -1e9;
    for (const p of this.platforms) {
      const dx = p.x - pos.x, dy = p.y - pos.y, dz = p.z - pos.z;
      const d = Math.hypot(dx, dy, dz);
      if (d < 0.15 || d > radius) continue;
      const nd = d || 1;
      const align = dir ? (dx * dir.x + dy * dir.y + dz * dir.z) / nd : 1;
      const score = align * 2.2 - d * 0.35 + (p.rest ? 0.4 : 0);
      if (score > bestScore) { bestScore = score; best = p; }
    }
    return best;
  }

  loop = () => {
    requestAnimationFrame(this.loop);
    const dt = Math.min(0.05, this.clock.getDelta());
    if (this.mode !== "play") { this.renderer.render(this.scene, this.camera); return; }
    this.time += dt;
    this.stepPlayer(dt);
    this.stepBody(dt);
    this.stepWorld(dt);
    this.drawHud();
    this.renderer.render(this.scene, this.camera);
  };

  stepPlayer(dt) {
    const fwd = new THREE.Vector3(Math.sin(this.yaw), 0, Math.cos(this.yaw));
    const right = new THREE.Vector3(fwd.z, 0, -fwd.x);
    let wish = new THREE.Vector3();
    if (this.keys["w"]) wish.add(fwd);
    if (this.keys["s"]) wish.sub(fwd);
    if (this.keys["a"]) wish.sub(right);
    if (this.keys["d"]) wish.add(right);
    if (this.stick.active && (Math.abs(this.stick.x) > 0.12 || Math.abs(this.stick.y) > 0.12)) {
      wish.addScaledVector(fwd, -this.stick.y);
      wish.addScaledVector(right, this.stick.x);
    }
    const want = wish.lengthSq() > 0;
    if (want) wish.normalize();

    const space = !!this.keys[" "];
    const run = !!this.keys["shift"];
    const gy = this.groundY(this.pos.x, this.pos.z, this.pos.y);
    const slope = slopeDeg(this.pos.x, this.pos.z);
    this.grounded = this.pos.y <= gy + 0.18 && this.vy <= 0.4;
    const hold = this.nearestHold(this.pos.clone().add(new THREE.Vector3(0, 1.1, 0)), wish.lengthSq() ? wish : fwd, 2.4);
    const canGrab = !!(hold || slope > 46);

    if (space && canGrab && this.body.stamina > 0.04 && !this.inTent) {
      this.climbing = true;
      this.vy = 0;
    } else if (!space) {
      this.climbing = false;
    }
    if (this.body.stamina <= 0.02) this.climbing = false;

    if (this.inTent || this.body.collapsed) {
      wish.set(0, 0, 0);
    }

    const alt = altitudeOf(this.pos.x, this.pos.z);
    this.altNow = alt;
    this.slopeNow = slope;

    if (this.climbing) {
      const climbDir = new THREE.Vector3(wish.x, 0, wish.z);
      if (space && !this.keys["s"]) climbDir.y += 1;
      if (this.keys["s"] && !want) climbDir.y -= 1;
      if (climbDir.lengthSq()) climbDir.normalize();
      const spd = 2.15 * this.body.moveScale * (hold?.ice ? 0.7 : 1);
      this.pos.addScaledVector(climbDir, spd * dt);
      const snap = this.nearestHold(this.pos.clone().add(new THREE.Vector3(0, 1.0, 0)), climbDir, 2.6);
      if (snap) {
        this.pos.x = THREE.MathUtils.damp(this.pos.x, snap.x, 8, dt);
        this.pos.z = THREE.MathUtils.damp(this.pos.z, snap.z, 8, dt);
        this.pos.y = THREE.MathUtils.damp(this.pos.y, snap.y - 0.55, 8, dt);
      } else {
        const tgy = heightY(this.pos.x, this.pos.z);
        if (this.pos.y < tgy + 0.1) this.pos.y = tgy;
      }
      this.speedNow = spd;
    } else {
      const walk = (run ? 7.2 : 4.4) * this.body.moveScale;
      const iceSlow = slope > 40 ? 0.55 : 1;
      if (this.grounded) {
        this.pos.addScaledVector(wish, walk * iceSlow * dt);
        this.vy = space ? 6.2 : 0;
        this.speedNow = want ? walk : 0;
      } else {
        this.pos.addScaledVector(wish, 3.2 * dt);
        this.vy -= 22 * dt;
        this.speedNow = Math.hypot(wish.x, wish.z) * 3.2;
      }
      this.pos.y += this.vy * dt;
      const ng = this.groundY(this.pos.x, this.pos.z, this.pos.y);
      if (this.pos.y < ng) {
        if (this.vy < -16) {
          this.respawn();
          return;
        }
        this.pos.y = ng;
        this.vy = 0;
        this.grounded = true;
      }
    }

    const half = WORLD_M * 0.48;
    this.pos.x = clamp(this.pos.x, -half, half);
    this.pos.z = clamp(this.pos.z, -half, half);

    if (want || this.climbing) {
      const face = wish.lengthSq() ? wish : fwd;
      const targetYaw = Math.atan2(face.x, face.z);
      this.player.rotation.y = THREE.MathUtils.damp(this.player.rotation.y, targetYaw, 10, dt);
    }
    this.player.position.lerp(this.pos, 1);

    this.anim.time = this.time;
    this.anim.speed = this.speedNow;
    this.anim.grounded = this.grounded;
    this.anim.climbing = this.climbing;
    this.anim.vy = this.vy;
    this.anim.slope = slope;
    poseClimber(this.player, this.anim, dt);

    this.updateCamera();

    if (this.grounded && this.speedNow > 1.5) {
      this._step = (this._step || 0) + this.speedNow * dt;
      if (this._step > 1.3) { this._step = 0; this.sound.oneshot("crunch", 0.16, 0.95 + Math.random() * 0.15); }
    }
    if (this.climbing && space) {
      this._axe = (this._axe || 0) + dt;
      if (this._axe > 0.45) { this._axe = 0; this.sound.oneshot("axe", 0.18, 1); }
    }

    $("interact").classList.toggle("on", canGrab && !this.climbing && !this.inTent);
    $("interact").textContent = this.t("grab");

    if (this.pos.y < heightY(this.pos.x, this.pos.z) - 4) this.respawn();
  }

  respawn() {
    this.sound.oneshot("ui_warn", 0.5);
    this.prompt(this.lang === "id" ? "Jatuh. Balik ke tenda." : "Fell. Back to tent.");
    this.body.stamina = Math.max(0.35, this.body.stamina * 0.5);
    this.placeAt(this.lastCamp);
  }

  updateCamera() {
    const dist = 5.4;
    const height = 2.15;
    const look = this.pos.clone().add(new THREE.Vector3(0, 1.35, 0));
    const off = new THREE.Vector3(
      Math.sin(this.yaw) * -dist * Math.cos(this.pitch),
      height + Math.sin(this.pitch) * dist,
      Math.cos(this.yaw) * -dist * Math.cos(this.pitch)
    );
    let cam = look.clone().add(off);
    const g = heightY(cam.x, cam.z) + 0.6;
    if (cam.y < g) cam.y = g;
    this.camera.position.lerp(cam, 0.18);
    this.camera.lookAt(look);
    this.sky.position.copy(this.camera.position);
  }

  stepBody(dt) {
    const simDt = dt * TIME_SCALE;
    if (this.ex.o2Active) {
      this.ex.o2Remaining -= simDt;
      if (this.ex.o2Remaining <= 0) { this.ex.o2Remaining = 0; this.ex.o2Active = false; }
    }
    const wind = 3 + this.ex.weather * 20 + (this.altNow > 7000 ? 6 : 0);
    tickBody(this.body, {
      dt,
      altitude: this.altNow || 5300,
      speed: this.speedNow,
      slope: this.slopeNow || 0,
      climbing: this.climbing,
      resting: this.inTent || this.keys["control"] ? 1 : 0,
      o2Flow: this.ex.o2Active ? WORLD.constants.O2_FLOW_LPM : 0,
      wind,
      airTemp: airTempC(this.altNow || 5300, this.ex.hour, this.ex.weather),
      wet: this.ex.weather > 0.7 ? 0.35 : 0.05,
      sheltered: this.inTent ? 1 : 0,
    });

    // stamina is the game — altitude taxes it hard while climbing
    if (this.climbing) {
      const tax = 0.11 + clamp((this.altNow - 5500) / 4000, 0, 1) * 0.16;
      this.body.stamina = Math.max(0, this.body.stamina - tax * dt * (this.ex.o2Active ? 0.55 : 1));
    } else if (this.grounded && (this.inTent || this.keys["control"])) {
      this.body.stamina = Math.min(1, this.body.stamina + 0.22 * dt);
    } else if (this.grounded && this.speedNow < 0.3) {
      this.body.stamina = Math.min(1, this.body.stamina + 0.08 * dt);
    }

    tickClock(this.ex, simDt);
    tickWeather(this.ex, simDt, Math.random());
    this.radioCd = Math.max(0, this.radioCd - dt);
    if (this.altNow > 8000) this.body.deathZoneH += simDt / 3600;
    if (this.body.dead) this.kill(this.body.cause);

    const camp = nearestCamp(this.altNow);
    const d = Math.hypot(this.pos.x - camp.camp.x, this.pos.z - camp.camp.z);
    this.nearCamp = { ...camp, dist: d };
    if (d < 16 && !this.inTent) {
      $("camp-hint").textContent = this.t("tent");
      $("camp-hint").classList.add("on");
    } else $("camp-hint").classList.remove("on");

    const summit = WORLD.route.find((p) => p.id === "summit");
    if (summit && Math.hypot(this.pos.x - summit.x, this.pos.z - summit.z) < 12 && this.altNow > 8500) {
      if (!this.ex.summited) {
        this.ex.summited = true;
        this.ex.headingUp = false;
        this.prompt(this.t("summit"));
        this.logRadio(this.t("summit"));
        this.sound.oneshot("ui_warn", 0.55);
      }
    }
    if (this.ex.summited && camp.index === 0 && d < 20) this.win();

    if (shouldTurnAround(this.ex, this.body.spo2, this.body.stamina) && this.ex.headingUp && this.warnCd <= 0) {
      this.warnCd = 35;
      this.prompt(this.t("turnaround"));
    }
    this.warnCd -= dt;
    if (this.altNow > 8000 && !this._dz) { this._dz = true; this.prompt(this.t("deathzone")); }
  }

  stepWorld(dt) {
    const hour = this.ex.hour;
    const sunA = ((hour - 6) / 12) * Math.PI;
    const day = clamp(Math.sin(sunA), 0, 1);
    const night = hour < 6 || hour > 18.5 ? 1 : clamp(1 - day * 2, 0, 1);
    this.sun.intensity = 0.25 + day * 2.0;
    this.sun.position.set(
      this.pos.x + Math.cos(sunA) * 700,
      Math.sin(sunA) * 480 + 40,
      this.pos.z + 280
    );
    this.sun.target.position.copy(this.pos);
    this.renderer.toneMappingExposure = night > 0.6 ? 0.55 : 1.05;
    if (this.sky.material.uniforms) {
      this.sky.material.uniforms.uSun.value.copy(this.sun.position).sub(this.pos).normalize();
      this.sky.material.uniforms.uNight.value = night;
    }
    this.scene.fog.density = 0.00032 + this.ex.weather * 0.0011;
    this.scene.fog.color.set(night > 0.6 ? 0x0c121c : 0x8aa3b8);

    if (this.snow) {
      const arr = this.snow.geometry.attributes.position.array;
      for (let i = 0; i < arr.length; i += 3) {
        arr[i] += (-6 - this.ex.weather * 10) * dt * 0.4;
        arr[i + 1] -= (4 + this.ex.weather * 7) * dt;
        if (arr[i + 1] < -6) { arr[i] = (Math.random() - 0.5) * 50; arr[i + 1] = 16; arr[i + 2] = (Math.random() - 0.5) * 50; }
      }
      this.snow.geometry.attributes.position.needsUpdate = true;
      this.snow.material.opacity = 0.12 + this.ex.weather * 0.65 + (this.altNow > 7000 ? 0.15 : 0);
    }
    this.sound.update(this.body, this.ex.weather, this.speedNow > 0.4, this.climbing);

    const hypo = clamp((72 - this.body.spo2) / 32, 0, 1);
    const stamEmpty = 1 - this.body.stamina;
    $("fx").style.opacity = String(0.12 + hypo * 0.55 + stamEmpty * 0.2);
    $("fx").style.background = `radial-gradient(circle at 50% 42%, transparent ${48 - hypo * 22}%, rgba(4,8,14,${0.35 + hypo * 0.5}) 100%)`;
    $("frost").style.opacity = String(clamp((35.2 - this.body.coreC) / 5, 0, 1) * 0.5);
    $("chroma").style.opacity = String(hypo * 0.35);
  }

  drawHud() {
    $("v-alt").textContent = `${(this.altNow || 0).toFixed(0)} m`;
    $("v-time").textContent = `${this.t("day")} ${this.ex.day}  ${this.fmtTime()}`;
    $("v-wx").textContent = this.ex.weather > 0.75 ? (this.lang === "id" ? "BADAI" : "STORM") : this.ex.weather > 0.4 ? (this.lang === "id" ? "Angin" : "Wind") : (this.lang === "id" ? "Cerah" : "Clear");
    $("v-o2").textContent = `${this.ex.o2Bottles}${this.ex.o2Active ? " ●" : ""}`;
    const stam = this.body.stamina;
    $("stam-fill").style.width = `${stam * 100}%`;
    $("stam-fill").className = stam < 0.22 ? "bad" : stam < 0.45 ? "warn" : "";
    $("v-spo2").textContent = `${this.body.spo2.toFixed(0)}`;
    $("v-spo2").classList.toggle("bad", this.body.spo2 < 60);
    $("climb-flag").classList.toggle("on", this.climbing);
    $("tent-flag").classList.toggle("on", this.inTent);
  }

  kill(cause) {
    if (this.mode === "end") return;
    this.mode = "end";
    document.exitPointerLock?.();
    const e = causeToEnding(cause, this.ex);
    $("end-kicker").textContent = this.t("died");
    $("end-title").textContent = e;
    $("end-copy").textContent = endingCopy(e, this.lang);
    $("end-meta").textContent = `${(this.altNow || 0).toFixed(0)} m · ${this.fmtTime()}`;
    this.show("screen-end");
    $("hud").classList.remove("on");
    const ui = $("touch-ui");
    if (ui) ui.hidden = true;
  }
  win() {
    if (this.mode === "end") return;
    this.mode = "end";
    document.exitPointerLock?.();
    $("end-kicker").textContent = this.t("survived");
    $("end-title").textContent = this.lang === "id" ? "Turunan selesai." : "The descent is done.";
    $("end-copy").textContent = endingCopy(ENDING.SUMMIT_AND_HOME, this.lang);
    $("end-meta").textContent = `${(this.altNow || 0).toFixed(0)} m · ${this.fmtTime()}`;
    this.show("screen-end");
    $("hud").classList.remove("on");
    const ui = $("touch-ui");
    if (ui) ui.hidden = true;
  }
}
