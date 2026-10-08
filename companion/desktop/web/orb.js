// The SamRabbit fluid orb (same shader as the R1's FluidOrb): white crown, pale middle band,
// saturated base, drifting wavy boundaries and a soft halo. WebGL with a 2D-canvas fallback.
// One animation loop drives every orb; orbs pause while off screen or when the page is hidden.

const VERT = 'attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}';
const FRAG = `
precision highp float;
uniform vec2 u_size; uniform vec2 u_center; uniform float u_radius; uniform float u_time;
uniform float u_energy; uniform vec3 u_base; uniform vec3 u_tint; uniform float u_glow;
float wave(float x,float t,float a,float b,float c){
  return 0.16*sin(x*a+t*b+c)+0.09*sin(x*(a*1.9)-t*(b*1.4)+c*2.3)+0.05*sin(x*(a*3.7)+t*(b*0.6)-c);
}
void main(){
  vec2 p = vec2(gl_FragCoord.x, u_size.y - gl_FragCoord.y);
  vec2 gc = u_center + vec2(0.0, u_radius*0.25);
  float gd = length(p - gc) / (u_radius*(1.55 + u_energy*0.25));
  float ga = gd < 0.35 ? 0.431 : gd < 0.7 ? mix(0.431, 0.133, (gd-0.35)/0.35) : gd < 1.0 ? mix(0.133, 0.0, (gd-0.7)/0.3) : 0.0;
  ga *= u_glow;
  vec4 glow = vec4(u_base*ga, ga);
  vec2 uv = (p - u_center) / u_radius;
  float d = length(uv);
  if (d > 1.0) { gl_FragColor = glow; return; }
  float t = u_time;
  float k = 1.0 + u_energy*1.6;
  float ang = 0.18*sin(t*0.21);
  vec2 r = vec2(uv.x*cos(ang) - uv.y*sin(ang), uv.x*sin(ang) + uv.y*cos(ang));
  float x = r.x + 0.25*sin(t*0.17 + r.y*1.3);
  float y = r.y + 0.06*sin(t*0.33 + r.x*2.1);
  float b1 = -0.28 + wave(x, t*0.55, 2.2, 1.0, 0.4)*k;
  float b2 = 0.22 + wave(x, t*0.48, 2.7, -0.8, 1.9)*k;
  float s1 = 0.10 + 0.05*sin(t*0.4 + x*3.0);
  float s2 = 0.09 + 0.04*sin(t*0.5 - x*2.4);
  vec3 white = vec3(0.99, 1.0, 1.0);
  vec3 c = mix(white, u_tint, smoothstep(b1 - s1, b1 + s1, y));
  c = mix(c, u_base, smoothstep(b2 - s2, b2 + s2, y));
  float streak = smoothstep(0.05, 0.0, abs(y - b2 + 0.12*sin(x*4.0 + t)))*0.12;
  c = mix(c, u_tint, streak);
  c *= 1.0 - 0.10*d*d*d;
  float a = clamp((1.0 - d)*u_radius*0.9, 0.0, 1.0);
  gl_FragColor = vec4(c*a, a) + glow*(1.0 - a);
}`;

const BLUE = [26, 115, 242];

function pale(color) {
  return color.map((v) => Math.round(v + (255 - v) * 0.6));
}

const orbs = new Set();
let frame = 0;
const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

function loop(now) {
  frame = 0;
  let any = false;
  for (const orb of orbs) {
    if (orb.visible && !document.hidden) {
      orb.draw(now);
      any = true;
    }
  }
  if (any && !reducedMotion) frame = requestAnimationFrame(loop);
}

function kick() {
  if (!frame) frame = requestAnimationFrame(loop);
}

document.addEventListener('visibilitychange', kick);

class Orb {
  constructor(canvas, { size, energy = 0.12, speed = 1, glow = 1, color = BLUE }) {
    this.canvas = canvas;
    this.size = size;
    this.energy = energy;
    this.speed = speed;
    this.glow = glow;
    this.color = color;
    this.phase = Math.random() * 40;
    this.last = performance.now();
    this.visible = true;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(size * dpr);
    canvas.height = Math.round(size * dpr);
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;
    this.gl = this.setupGl();
    if (!this.gl) this.ctx = canvas.getContext('2d');
    if ('IntersectionObserver' in window) {
      this.observer = new IntersectionObserver((entries) => {
        this.visible = entries.some((entry) => entry.isIntersecting);
        if (this.visible) kick();
      });
      this.observer.observe(canvas);
    }
  }

  setupGl() {
    let gl = null;
    try {
      gl = this.canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: false });
    } catch {
      gl = null;
    }
    if (!gl) return null;
    const compile = (type, source) => {
      const shader = gl.createShader(type);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      return gl.getShaderParameter(shader, gl.COMPILE_STATUS) ? shader : null;
    };
    const vs = compile(gl.VERTEX_SHADER, VERT);
    const fs = compile(gl.FRAGMENT_SHADER, FRAG);
    if (!vs || !fs) return null;
    const program = gl.createProgram();
    gl.attachShader(program, vs);
    gl.attachShader(program, fs);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;
    gl.useProgram(program);
    const buffer = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    const location = gl.getAttribLocation(program, 'p');
    gl.enableVertexAttribArray(location);
    gl.vertexAttribPointer(location, 2, gl.FLOAT, false, 0, 0);
    this.uniforms = {};
    for (const name of ['u_size', 'u_center', 'u_radius', 'u_time', 'u_energy', 'u_base', 'u_tint', 'u_glow']) {
      this.uniforms[name] = gl.getUniformLocation(program, name);
    }
    return gl;
  }

  set(options) {
    Object.assign(this, options);
    kick();
  }

  draw(now) {
    this.phase += ((now - this.last) / 1000) * this.speed;
    this.last = now;
    const { width, height } = this.canvas;
    const radius = width * 0.3;
    const cx = width / 2;
    const cy = height * 0.44;
    if (this.gl) {
      const gl = this.gl;
      const u = this.uniforms;
      const base = this.color.map((v) => v / 255);
      const tint = pale(this.color).map((v) => v / 255);
      gl.viewport(0, 0, width, height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform2f(u.u_size, width, height);
      gl.uniform2f(u.u_center, cx, cy);
      gl.uniform1f(u.u_radius, radius);
      gl.uniform1f(u.u_time, this.phase);
      gl.uniform1f(u.u_energy, this.energy);
      gl.uniform3f(u.u_base, base[0], base[1], base[2]);
      gl.uniform3f(u.u_tint, tint[0], tint[1], tint[2]);
      gl.uniform1f(u.u_glow, this.glow);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      return;
    }
    const ctx = this.ctx;
    if (!ctx) return;
    const [r, g, b] = this.color;
    const [pr, pg, pb] = pale(this.color);
    ctx.clearRect(0, 0, width, height);
    const glowRadius = radius * 1.55;
    const glow = ctx.createRadialGradient(cx, cy + radius * 0.25, 0, cx, cy + radius * 0.25, glowRadius);
    glow.addColorStop(0.35, `rgba(${r},${g},${b},${0.43 * this.glow})`);
    glow.addColorStop(0.7, `rgba(${r},${g},${b},${0.13 * this.glow})`);
    glow.addColorStop(1, `rgba(${r},${g},${b},0)`);
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, width, height);
    const fill = ctx.createLinearGradient(0, cy - radius, 0, cy + radius);
    fill.addColorStop(0.2, '#fcffff');
    fill.addColorStop(0.5, `rgb(${pr},${pg},${pb})`);
    fill.addColorStop(0.8, `rgb(${r},${g},${b})`);
    ctx.fillStyle = fill;
    ctx.beginPath();
    ctx.arc(cx, cy, radius, 0, Math.PI * 2);
    ctx.fill();
  }

  destroy() {
    orbs.delete(this);
    if (this.observer) this.observer.disconnect();
    const lose = this.gl && this.gl.getExtension('WEBGL_lose_context');
    if (lose) lose.loseContext();
  }
}

/** A canvas with an animated orb. The orb is drawn at 60% of `size`; the rest is halo. */
export function createOrb({ size = 200, energy, speed, glow, className = 'orb-canvas' } = {}) {
  const canvas = document.createElement('canvas');
  canvas.className = className;
  canvas.setAttribute('aria-hidden', 'true');
  const orb = new Orb(canvas, { size, energy, speed, glow });
  orbs.add(orb);
  orb.draw(performance.now());
  kick();
  canvas.orb = orb;
  return canvas;
}
