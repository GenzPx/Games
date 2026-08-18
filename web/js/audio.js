export class Soundscape {
  constructor() {
    this.ctx = null;
    this.buffers = {};
    this.nodes = {};
    this.ready = false;
    this.master = null;
    this.gains = {};
  }

  async init() {
    if (this.ctx) return;
    const Ctx = window.AudioContext || window.webkitAudioContext;
    this.ctx = new Ctx();
    this.master = this.ctx.createGain();
    this.master.gain.value = 0.85;
    this.master.connect(this.ctx.destination);

    const names = [
      "wind",
      "storm",
      "breath_calm",
      "breath_work",
      "breath_death",
      "heart",
      "crunch",
      "axe",
      "ui_ok",
      "ui_warn",
      "radio",
    ];
    await Promise.all(
      names.map(async (n) => {
        try {
          const res = await fetch(`assets/audio/${n}.wav`);
          const arr = await res.arrayBuffer();
          this.buffers[n] = await this.ctx.decodeAudioData(arr);
        } catch (e) {
          console.warn("audio miss", n, e);
        }
      })
    );

    this.loop("wind", 0.0);
    this.loop("storm", 0.0);
    this.loop("breath_calm", 0.0);
    this.loop("breath_work", 0.0);
    this.loop("breath_death", 0.0);
    this.loop("heart", 0.0);
    this.ready = true;
    if (this.ctx.state === "suspended") await this.ctx.resume();
  }

  loop(name, gain) {
    if (!this.buffers[name] || this.nodes[name]) return;
    const src = this.ctx.createBufferSource();
    src.buffer = this.buffers[name];
    src.loop = true;
    const g = this.ctx.createGain();
    g.gain.value = gain;
    src.connect(g);
    g.connect(this.master);
    src.start();
    this.nodes[name] = src;
    this.gains[name] = g;
  }

  setGain(name, v, t = 0.4) {
    const g = this.gains[name];
    if (!g) return;
    const now = this.ctx.currentTime;
    g.gain.cancelScheduledValues(now);
    g.gain.linearRampToValueAtTime(Math.max(0, v), now + t);
  }

  oneshot(name, volume = 0.5, rate = 1) {
    if (!this.ctx || !this.buffers[name]) return;
    const src = this.ctx.createBufferSource();
    src.buffer = this.buffers[name];
    src.playbackRate.value = rate;
    const g = this.ctx.createGain();
    g.gain.value = volume;
    src.connect(g);
    g.connect(this.master);
    src.start();
  }

  update(body, weather, moving, climbing) {
    if (!this.ready) return;
    const hypoxia = Math.max(0, (78 - body.spo2) / 40);
    const wind = 0.12 + weather * 0.45 + (body.pressure < 400 ? 0.2 : 0);
    this.setGain("wind", wind);
    this.setGain("storm", weather * 0.7);

    if (body.spo2 < 58) {
      this.setGain("breath_death", 0.45 + hypoxia * 0.4);
      this.setGain("breath_work", 0);
      this.setGain("breath_calm", 0);
    } else if (moving || climbing || body.rr > 22) {
      this.setGain("breath_work", 0.28 + hypoxia * 0.25);
      this.setGain("breath_calm", 0.05);
      this.setGain("breath_death", 0);
    } else {
      this.setGain("breath_calm", 0.18 + hypoxia * 0.15);
      this.setGain("breath_work", 0.04);
      this.setGain("breath_death", 0);
    }
    this.setGain("heart", hypoxia > 0.35 ? (hypoxia - 0.35) * 0.9 : 0);
  }
}
