//! P239 spike: Home (dock + app grid) drawn in ONE GLES 3 render pass, from Rust.
//! Spike code (not for merge): std, hand-declared EGL/GLES/NDK FFI, no crates. Kotlin drives it through
//! `dev.glasslauncher.p239spike.Native`: textures once, then per frame only the animated values (scroll, scroll blur,
//! cross-fade, the two animating tiles' scales). Per frame it logs to logcat (tag P239) when the GPU finished.
#![allow(non_snake_case, clippy::missing_safety_doc, clippy::too_many_arguments)]

use std::ffi::{c_char, c_void, CString};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread;

mod etc2;
/// Cover Flow geometry, ported from glass-launcher/featured/CoverFlowGeometry.kt (P239, spec D.7).
mod shelf;
mod ffi;
use ffi::*;

// Home's geometry at 1080p (Glass ui/Metrics.kt at 960x540 dp, density 2).
const W: f32 = 1920.0;
const H: f32 = 1080.0;
const TILE_W: f32 = 250.0;
const TILE_H: f32 = 150.0;
const TILE_R: f32 = 30.0;
const INSET: f32 = 90.0;
const GUTTER: f32 = 48.0;
const TRAY: [f32; 4] = [44.0, 768.0, 1832.0, 230.0];
const TRAY_R: f32 = 60.0;
const DOCK_Y: f32 = 776.0;
/// First grid row's top (tray bottom + trayToGrid 72 px) and the row pitch (tile + labelSpace 110 px).
const GRID_Y: f32 = 1070.0;
const ROW: f32 = 260.0;
pub const SLOTS: usize = 30; // 6 dock tiles, then 4 grid rows of 6
const SHADOW: f32 = 60.0;
/// Label atlas: one 200x40 cell per slot, 10 per row, 2000x120 px.
const CELL: [f32; 2] = [200.0, 40.0];

fn tile_rect(slot: usize, scale: f32, scroll: f32) -> [f32; 4] {
    let (col, y) = if slot < 6 { (slot, DOCK_Y) } else { ((slot - 6) % 6, GRID_Y + ((slot - 6) / 6) as f32 * ROW) };
    let x = INSET + col as f32 * (TILE_W + GUTTER);
    let (w, h) = (TILE_W * scale, TILE_H * scale);
    [x + (TILE_W - w) / 2.0, y - scroll + (TILE_H - h) / 2.0, w, h]
}

/// Where a grid tile's label sits (and its marker bar, at the same y).
fn label_rect(slot: usize, scroll: f32) -> [f32; 4] {
    let r = tile_rect(slot, 1.0, scroll);
    [r[0], r[1] + TILE_H + 12.0, CELL[0], CELL[1]]
}

#[derive(Clone, Copy, PartialEq)]
struct Frame {
    scroll: f32,
    lod: f32,
    mix: f32,
    flip: i32,
    /// The (up to two) tiles not at scale 1: (slot, scale); slot -1 = none.
    anim: [(i32, f32); 2],
    t0: i64,
}

impl Frame {
    fn scale(&self, slot: usize) -> f32 {
        self.anim.iter().find(|a| a.0 == slot as i32).map(|a| a.1).unwrap_or(1.0)
    }
}

struct Upload {
    slot: i32,
    level: i32,
    w: i32,
    h: i32,
    etc2: bool,
    data: Vec<u8>,
}

struct Shared {
    frame: Option<Frame>,
    uploads: Vec<Upload>,
    quit: bool,
}

struct Renderer {
    shared: Arc<(Mutex<Shared>, Condvar)>,
    thread: Option<thread::JoinHandle<()>>,
}

static RENDERER: Mutex<Option<Renderer>> = Mutex::new(None);
static PARTIAL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);
/// Bit 0: draw grid labels natively (from the atlas, slot 8). Bit 1: draw skew marker bars.
static FLAGS: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(0);

fn now() -> i64 {
    let mut ts = Timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe { clock_gettime(1, &mut ts) };
    ts.tv_sec as i64 * 1_000_000_000 + ts.tv_nsec as i64
}

fn log(msg: &str) {
    let (tag, m) = (CString::new("P239").unwrap(), CString::new(msg).unwrap());
    unsafe { __android_log_write(4, tag.as_ptr(), m.as_ptr()) };
}

// ---- JNI

#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_start(env: *mut c_void, _: *mut c_void, surface: *mut c_void) {
    let window = ANativeWindow_fromSurface(env, surface);
    let shared = Arc::new((Mutex::new(Shared { frame: None, uploads: Vec::new(), quit: false }), Condvar::new()));
    let s2 = shared.clone();
    let win = window as usize;
    let thread = thread::spawn(move || render_thread(win as *mut c_void, s2));
    *RENDERER.lock().unwrap() = Some(Renderer { shared, thread: Some(thread) });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_stop(_: *mut c_void, _: *mut c_void) {
    if let Some(mut r) = RENDERER.lock().unwrap().take() {
        r.shared.0.lock().unwrap().quit = true;
        r.shared.1.notify_all();
        if let Some(t) = r.thread.take() {
            let _ = t.join();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_glasslauncher_p239spike_Native_partial(_: *mut c_void, _: *mut c_void, on: u8) {
    PARTIAL.store(on != 0, std::sync::atomic::Ordering::Relaxed);
}


/// One component of a cover's pose, for a host-side differential against the Kotlin original (P239).
/// [field]: 0 x, 1 rotation_y, 2 scale, 3 z, 4 alpha, 5 visible (1/0). Returns f32 (visible as 1.0/0.0).
#[no_mangle]
pub extern "system" fn Java_dev_glasslauncher_p239spike_Native_poseField(
    _: *mut c_void,
    _: *mut c_void,
    offset: f32,
    field: i32,
) -> f32 {
    let p = shelf::pose(offset);
    match field {
        0 => p.x,
        1 => p.rotation_y,
        2 => p.scale,
        3 => p.z,
        4 => p.alpha,
        5 => {
            if shelf::visible(offset) {
                1.0
            } else {
                0.0
            }
        }
        _ => f32::NAN,
    }
}
#[no_mangle]
pub extern "system" fn Java_dev_glasslauncher_p239spike_Native_flags(_: *mut c_void, _: *mut c_void, f: i32) {
    FLAGS.store(f, std::sync::atomic::Ordering::Relaxed);
}

/// Copies an ARGB_8888 bitmap for texture [slot] (0, 1: backdrops; 2..7: tile art; 8: label atlas) at mip [level].
/// [etc2]: encode it to ETC2 RGB8 here (the caller's thread, off the GL thread) and upload compressed.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_upload(env: *mut c_void, _: *mut c_void, slot: i32, level: i32, bitmap: *mut c_void, etc2: u8) {
    let mut info = BitmapInfo { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    if AndroidBitmap_getInfo(env, bitmap, &mut info) != 0 || info.format != 1 {
        log("upload: not ARGB_8888");
        return;
    }
    let mut p: *mut c_void = std::ptr::null_mut();
    if AndroidBitmap_lockPixels(env, bitmap, &mut p) != 0 {
        return;
    }
    let (w, h, stride) = (info.width as usize, info.height as usize, info.stride as usize);
    let px = std::slice::from_raw_parts(p as *const u8, stride * (h - 1) + w * 4);
    let data = if etc2 != 0 {
        let t = now();
        let blocks = etc2::encode(px, w, h, stride, 4);
        log(&format!("etc2,{slot},{level},{w}x{h},{}us,{}B", (now() - t) / 1000, blocks.len()));
        blocks
    } else {
        let mut v = Vec::with_capacity(w * h * 4);
        for y in 0..h {
            v.extend_from_slice(&px[y * stride..y * stride + w * 4]);
        }
        v
    };
    AndroidBitmap_unlockPixels(env, bitmap);
    if let Some(r) = RENDERER.lock().unwrap().as_ref() {
        r.shared.0.lock().unwrap().uploads.push(Upload { slot, level, w: w as i32, h: h as i32, etc2: etc2 != 0, data });
        r.shared.1.notify_all();
    }
}

/// One frame's animated values; the render thread draws it as soon as it is free (latest wins).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_frame(
    _: *mut c_void, _: *mut c_void, scroll: f32, lod: f32, mix: f32, flip: i32, a: i32, sa: f32, b: i32, sb: f32,
) {
    if let Some(r) = RENDERER.lock().unwrap().as_ref() {
        r.shared.0.lock().unwrap().frame = Some(Frame { scroll, lod, mix, flip, anim: [(a, sa), (b, sb)], t0: now() });
        r.shared.1.notify_all();
    }
}

// ---- GL helpers

unsafe fn shader(kind: u32, src: &str) -> u32 {
    let s = glCreateShader(kind);
    let c = CString::new(src).unwrap();
    let p = c.as_ptr();
    glShaderSource(s, 1, &p, std::ptr::null());
    glCompileShader(s);
    let mut ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &mut ok);
    if ok == 0 {
        let mut buf = vec![0u8; 2048];
        let mut n = 0;
        glGetShaderInfoLog(s, 2048, &mut n, buf.as_mut_ptr() as *mut c_char);
        log(&format!("shader error: {}", String::from_utf8_lossy(&buf[..n as usize])));
    }
    s
}

struct Prog {
    id: u32,
    u: [i32; 8], // rect, screen, a, b, mix, lod, radius, uv
}

unsafe fn program(frag: &str) -> Prog {
    let id = glCreateProgram();
    glAttachShader(id, shader(GL_VERTEX_SHADER, VERT));
    glAttachShader(id, shader(GL_FRAGMENT_SHADER, &format!("{FRAG_HEAD}{frag}")));
    glLinkProgram(id);
    let mut ok = 0;
    glGetProgramiv(id, GL_LINK_STATUS, &mut ok);
    if ok == 0 {
        let mut buf = vec![0u8; 2048];
        let mut n = 0;
        glGetProgramInfoLog(id, 2048, &mut n, buf.as_mut_ptr() as *mut c_char);
        log(&format!("link error: {}", String::from_utf8_lossy(&buf[..n as usize])));
    }
    let mut u = [0; 8];
    for (k, n) in ["uRect", "uScreen", "uA", "uB", "uMix", "uLod", "uRadius", "uUv"].iter().enumerate() {
        let c = CString::new(*n).unwrap();
        u[k] = glGetUniformLocation(id, c.as_ptr());
    }
    Prog { id, u }
}

/// The same fragment shaders, driven by four corners instead of a rect.
unsafe fn program_quad(frag: &str) -> Prog {
    let id = glCreateProgram();
    glAttachShader(id, shader(GL_VERTEX_SHADER, VERT_QUAD));
    glAttachShader(id, shader(GL_FRAGMENT_SHADER, &format!("{FRAG_HEAD}{frag}")));
    glLinkProgram(id);
    let mut ok = 0;
    glGetProgramiv(id, GL_LINK_STATUS, &mut ok);
    if ok == 0 {
        let mut buf = vec![0u8; 2048];
        let mut n = 0;
        glGetProgramInfoLog(id, 2048, &mut n, buf.as_mut_ptr() as *mut c_char);
        log(&format!("quad link error: {}", String::from_utf8_lossy(&buf[..n as usize])));
    }
    let mut u = [0; 8];
    for (k, n) in ["uC0", "uC1", "uScreen", "uA", "uRadius", "uUv"].iter().enumerate() {
        let c = CString::new(*n).unwrap();
        u[k] = glGetUniformLocation(id, c.as_ptr());
    }
    Prog { id, u }
}

/// Draws a turned cover from its four corners (TL, TR, BR, BL).
unsafe fn draw_quad(p: &Prog, c: [[f32; 2]; 4], tex: u32, radius: f32, size: [f32; 2]) {
    glUseProgram(p.id);
    glUniform4f(p.u[0], c[0][0], c[0][1], c[1][0], c[1][1]);
    glUniform4f(p.u[1], c[2][0], c[2][1], c[3][0], c[3][1]);
    glUniform2f(p.u[2], W, H);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(p.u[3], 0);
    glUniform1f(p.u[4], radius);
    glUniform4f(p.u[5], 0.0, 0.0, size[0], size[1]);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

const VERT: &str = "#version 300 es
uniform highp vec4 uRect; uniform highp vec2 uScreen;
out highp vec2 vPx; out vec2 vLocal;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
  vec2 p = uRect.xy + c * uRect.zw;
  vPx = p; vLocal = c;
  gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}";

/// Four explicit corners (TL, TR, BR, BL) instead of an axis-aligned rect: what a turned cover needs. The fragment
/// side is identical, so any existing program's fragment shader can be paired with it.
const VERT_QUAD: &str = "#version 300 es
uniform highp vec4 uC0; uniform highp vec4 uC1; uniform highp vec2 uScreen;
out highp vec2 vPx; out vec2 vLocal;
void main() {
  int i = gl_VertexID;
  vec2 p = i == 0 ? uC0.xy : (i == 1 ? uC0.zw : (i == 2 ? uC1.zw : uC1.xy));
  vPx = p;
  vLocal = vec2(float(i == 1 || i == 2), float(i >= 2));
  gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}";

const FRAG_HEAD: &str = "#version 300 es
precision mediump float;
uniform highp vec4 uRect; uniform highp vec2 uScreen; uniform sampler2D uA; uniform sampler2D uB;
uniform float uMix; uniform float uLod; uniform float uRadius; uniform highp vec4 uUv;
in highp vec2 vPx; in vec2 vLocal; out vec4 o;
float sdRound(highp vec2 p, highp vec2 h, float r) { vec2 q = abs(p) - h + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
// Two explicit integer-level fetches blended here: hardware trilinear at a fractional lod measured 2-3x slower here.
vec3 lodBlend(sampler2D t, vec2 uv, float lod) { float l0 = floor(lod); vec3 a = textureLod(t, uv, l0).rgb; float f = lod - l0; return f > 0.004 ? mix(a, textureLod(t, uv, l0 + 1.0).rgb, f) : a; }
vec3 scene(vec2 uv, float lod) { vec3 a = lodBlend(uA, uv, lod); return uMix > 0.0 ? mix(a, lodBlend(uB, uv, lod), uMix) : a; }
";

/// The backdrop: the scene's pre-blurred mip chain at the scroll-blur level (one fetch; two while cross-fading).
const BACKDROP: &str = "void main() { o = vec4(scene(vLocal, uLod), 1.0); }";

/// Tray glass: the blurred scene at a lens-displaced UV, Glass's face colour matrix and tint, analytic rim and specular.
const GLASS: &str = "void main() {
  highp vec2 h = uRect.zw * 0.5; highp vec2 p = vPx - (uRect.xy + h);
  float d = sdRound(p, h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  float edge = clamp(1.0 + d / 28.0, 0.0, 1.0);
  vec2 uv = (vPx - p * edge * edge * 0.10) / uScreen;
  vec3 bg = scene(uv, max(3.0, uLod));
  float l = dot(bg, vec3(0.2126, 0.7152, 0.0722));
  vec3 col = clamp(mix(vec3(l), bg, 1.59) * 0.629 + 0.235 * 0.25, 0.0, 1.0);
  col = mix(col, vec3(1.0), 0.06);
  float rim = (1.0 - smoothstep(0.0, 2.5, abs(d + 1.25))) * (0.22 + 0.3 * clamp(-p.y / h.y, 0.0, 1.0));
  float spec = (1.0 - smoothstep(0.0, 18.0, -d)) * 0.06 * clamp(-p.y / h.y, 0.0, 1.0);
  o = vec4(col + rim + spec, 1.0) * a;
}";

/// Focus shadow: an analytic soft rounded-rect shadow (distance falloff), no shadow bitmap.
const SHADOW_FS: &str = "void main() {
  highp vec2 h = uRect.zw * 0.5 - vec2(48.0); highp vec2 p = vPx - (uRect.xy + uRect.zw * 0.5) - vec2(0.0, 14.0);
  float d = sdRound(p, h, uRadius);
  o = vec4(0.0, 0.0, 0.0, (1.0 - smoothstep(-16.0, 40.0, d)) * 0.45 * uMix);
}";

/// A tile: its art clipped by an analytic rounded rect (anti-aliased, no stencil or layer).
const TILE: &str = "void main() {
  highp vec2 h = uRect.zw * 0.5; float d = sdRound(vPx - (uRect.xy + h), h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  o = texture(uA, vLocal) * a;
}";

/// An atlas cell (premultiplied text), or with uMix > 0 a flat colour (the skew markers).
/// A cover: the tile's rounded-rect SDF, but built from the interpolated local coords and the size in uUv.zw, because
/// the quad program has no uRect to read.
const COVER: &str = "void main() {
  highp vec2 h = uUv.zw * 0.5; float d = sdRound(vLocal * uUv.zw - h, h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  o = texture(uA, vLocal) * a;
}";

const SPRITE: &str = "void main() {
  o = uMix > 0.0 ? vec4(uUv.rgb, 1.0) : texture(uA, uUv.xy + vLocal * uUv.zw);
}";

// ---- render thread

struct Gl {
    backdrop: Prog,
    glass: Prog,
    shadow: Prog,
    tile: Prog,
    sprite: Prog,
    /// Turned covers for the shelf (four-corner vertex shader).
    cover: Prog,
    tex: [u32; 9],
}

unsafe fn draw(p: &Prog, rect: [f32; 4], a: u32, b: u32, mix: f32, lod: f32, radius: f32, uv: [f32; 4]) {
    glUseProgram(p.id);
    glUniform4f(p.u[0], rect[0], rect[1], rect[2], rect[3]);
    glUniform2f(p.u[1], W, H);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, a);
    glUniform1i(p.u[2], 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, b);
    glUniform1i(p.u[3], 1);
    glUniform1f(p.u[4], mix);
    glUniform1f(p.u[5], lod);
    glUniform1f(p.u[6], radius);
    glUniform4f(p.u[7], uv[0], uv[1], uv[2], uv[3]);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

fn visible(r: [f32; 4]) -> bool {
    r[1] + r[3] > -SHADOW && r[1] < H + SHADOW
}

unsafe fn draw_frame(gl: &Gl, f: &Frame) {
    let flags = FLAGS.load(std::sync::atomic::Ordering::Relaxed);
    let (from, to) = if f.flip == 0 { (gl.tex[0], gl.tex[1]) } else { (gl.tex[1], gl.tex[0]) };
    let z = [0.0; 4];
    glDisable(GL_BLEND);
    draw(&gl.backdrop, [0.0, 0.0, W, H], from, to, f.mix, f.lod, 0.0, z);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    let tray = [TRAY[0], TRAY[1] - f.scroll, TRAY[2], TRAY[3]];
    if visible(tray) {
        draw(&gl.glass, tray, from, to, f.mix, f.lod, TRAY_R, z);
    }
    for &(slot, s) in &f.anim {
        let focus = ((s - 1.0) / 0.224).clamp(0.0, 1.0);
        if slot >= 0 && focus > 0.01 {
            let r = tile_rect(slot as usize, s, f.scroll);
            draw(&gl.shadow, [r[0] - SHADOW, r[1] - SHADOW, r[2] + 2.0 * SHADOW, r[3] + 2.0 * SHADOW], 0, 0, focus, 0.0, TILE_R * s, z);
        }
    }
    // Bit 2: the top shelf. Covers are placed by the ported Cover Flow geometry (shelf::pose), so the motion is the
    // one Glass already ships; the centre cover is the hero. Drawn before the grid so the grid reads as in front.
    if flags & 4 != 0 {
        const COVER_W: f32 = 300.0;
        const COVER_H: f32 = 169.0;
        const SHELF_Y: f32 = 250.0;
        let mut order: Vec<(f32, usize)> = (0..7).map(|i| (i as f32 - 3.0, i)).collect();
        // Far covers first: pose z is negative with distance, so sort by z ascending.
        order.sort_by(|a, b| shelf::pose(a.0).z.partial_cmp(&shelf::pose(b.0).z).unwrap());
        for &(off, i) in &order {
            let p = shelf::pose(off);
            if !shelf::visible(off) || p.alpha <= 0.01 {
                continue;
            }
            let w = COVER_W * p.scale;
            let h = COVER_H * p.scale;
            let cx = W * 0.5 + p.x * COVER_W;
            let cy = SHELF_Y;
            // A turn about the vertical axis: the far edge shrinks towards the centre, giving the trapezoid.
            let k = (p.rotation_y.to_radians().cos()).abs().max(0.15);
            let near = if p.rotation_y < 0.0 { 1.0 } else { k };
            let far = if p.rotation_y < 0.0 { k } else { 1.0 };
            let (l, r) = (cx - w * 0.5, cx + w * 0.5);
            let c = [
                [l + (1.0 - near) * w * 0.5, cy - h * 0.5],
                [r - (1.0 - near) * w * 0.5, cy - h * 0.5],
                [r - (1.0 - far) * w * 0.5, cy + h * 0.5],
                [l + (1.0 - far) * w * 0.5, cy + h * 0.5],
            ];
            draw_quad(&gl.cover, c, gl.tex[2 + i % 6], 8.0 * p.scale, [w, h]);
        }
    }
    for slot in 0..SLOTS {
        let s = f.scale(slot);
        let r = tile_rect(slot, s, f.scroll);
        if visible(r) {
            draw(&gl.tile, r, gl.tex[2 + slot % 6], 0, 0.0, 0.0, TILE_R * s, z);
        }
    }
    for slot in 6..SLOTS {
        let l = label_rect(slot, f.scroll);
        if !visible(l) {
            continue;
        }
        if flags & 1 != 0 {
            let (cx, cy) = ((slot % 10) as f32 * CELL[0] / 2000.0, (slot / 10) as f32 * CELL[1] / 120.0);
            draw(&gl.sprite, l, gl.tex[8], 0, 0.0, 0.0, 0.0, [cx, cy, CELL[0] / 2000.0, CELL[1] / 120.0]);
        }
        if flags & 2 != 0 {
            // Skew markers: magenta from the tile's frame; cyan where its label is (native labels only; else Compose draws it).
            draw(&gl.sprite, [l[0], l[1] - 8.0, 40.0, 6.0], 0, 0, 1.0, 0.0, 0.0, [1.0, 0.0, 1.0, 0.0]);
            if flags & 1 != 0 {
                draw(&gl.sprite, [l[0] + 50.0, l[1] - 8.0, 40.0, 6.0], 0, 0, 1.0, 0.0, 0.0, [0.0, 1.0, 1.0, 0.0]);
            }
        }
    }
}

/// Screen rect (x0, y0, x1, y1) this frame changes against [prev]; None = full screen.
fn damage(prev: &Option<Frame>, f: &Frame) -> Option<[f32; 4]> {
    let p = (*prev)?;
    if p.mix != f.mix || p.lod != f.lod || p.flip != f.flip || p.scroll != f.scroll {
        return None;
    }
    let mut d: Option<[f32; 4]> = None;
    for slot in 0..SLOTS {
        let (a, b) = (p.scale(slot), f.scale(slot));
        if a != b {
            for s in [a, b] {
                let r = tile_rect(slot, s, f.scroll);
                let e = [r[0] - SHADOW, r[1] - SHADOW, r[0] + r[2] + SHADOW, r[1] + r[3] + SHADOW];
                d = Some(match d { None => e, Some(o) => [o[0].min(e[0]), o[1].min(e[1]), o[2].max(e[2]), o[3].max(e[3])] });
            }
        }
    }
    Some(d.unwrap_or([0.0; 4]))
}

fn union(a: Option<[f32; 4]>, b: Option<[f32; 4]>) -> Option<[f32; 4]> {
    Some(match (a?, b?) { (x, y) if x[2] <= x[0] => y, (x, y) if y[2] <= y[0] => x, (x, y) => [x[0].min(y[0]), x[1].min(y[1]), x[2].max(y[2]), x[3].max(y[3])] })
}

fn render_thread(window: *mut c_void, shared: Arc<(Mutex<Shared>, Condvar)>) {
    unsafe {
        let dpy = eglGetDisplay(std::ptr::null_mut());
        eglInitialize(dpy, std::ptr::null_mut(), std::ptr::null_mut());
        let attrs = [EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0, EGL_DEPTH_SIZE, 0, EGL_NONE];
        let mut cfg: *mut c_void = std::ptr::null_mut();
        let mut n = 0;
        eglChooseConfig(dpy, attrs.as_ptr(), &mut cfg, 1, &mut n);
        let ctx = eglCreateContext(dpy, cfg, std::ptr::null_mut(), [EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE].as_ptr());
        let surf = eglCreateWindowSurface(dpy, cfg, window, std::ptr::null());
        eglMakeCurrent(dpy, surf, surf, ctx);
        let ext = std::ffi::CStr::from_ptr(eglQueryString(dpy, EGL_EXTENSIONS)).to_string_lossy().into_owned();
        let has = |e: &str| ext.split(' ').any(|x| x == e);
        let age_ok = has("EGL_EXT_buffer_age");
        let swap_damage: Option<SwapDamage> = if has("EGL_KHR_swap_buffers_with_damage") {
            Some(std::mem::transmute(eglGetProcAddress(c"eglSwapBuffersWithDamageKHR".as_ptr())))
        } else {
            None
        };
        let create_sync: CreateSync = std::mem::transmute(eglGetProcAddress(c"eglCreateSyncKHR".as_ptr()));
        let wait_sync: WaitSync = std::mem::transmute(eglGetProcAddress(c"eglClientWaitSyncKHR".as_ptr()));
        let destroy_sync: DestroySync = std::mem::transmute(eglGetProcAddress(c"eglDestroySyncKHR".as_ptr()));
        log(&format!("init n={n} buffer_age={age_ok} swap_damage={} gl={}", swap_damage.is_some(),
            std::ffi::CStr::from_ptr(glGetString(GL_RENDERER) as *const c_char).to_string_lossy()));
        eglSwapInterval(dpy, 1);

        let gl = Gl { backdrop: program(BACKDROP), glass: program(GLASS), shadow: program(SHADOW_FS), tile: program(TILE), sprite: program(SPRITE), cover: program_quad(COVER), tex: {
            let mut t = [0u32; 9];
            glGenTextures(9, t.as_mut_ptr());
            t
        } };
        glViewport(0, 0, W as i32, H as i32);

        // The GPU-completion waiter: logs one line per frame (values posted, submit, GPU done, damaged pixels, scroll).
        let (tx, rx) = mpsc::channel::<(usize, i64, i64, i64, f32, i64)>();
        let dpy_u = dpy as usize;
        let waiter = thread::spawn(move || {
            for (sync, t0, submit, area, scroll, cpu) in rx {
                wait_sync(dpy_u as *mut c_void, sync as *mut c_void, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, u64::MAX);
                let done = now();
                destroy_sync(dpy_u as *mut c_void, sync as *mut c_void);
                log(&format!("frame,{t0},{submit},{done},{area},{scroll},{cpu}"));
            }
        });

        let mut prev: Option<Frame> = None;
        let mut history: Vec<Option<[f32; 4]>> = Vec::new();
        let mut allocated = [false; 9];
        loop {
            let (frame, uploads) = {
                let (m, cv) = &*shared;
                let mut s = m.lock().unwrap();
                while s.frame.is_none() && s.uploads.is_empty() && !s.quit {
                    s = cv.wait(s).unwrap();
                }
                if s.quit {
                    break;
                }
                (s.frame.take(), std::mem::take(&mut s.uploads))
            };
            if !uploads.is_empty() {
                prev = None;
                history.clear();
            }
            for u in uploads {
                let k = u.slot as usize;
                glBindTexture(GL_TEXTURE_2D, gl.tex[k]);
                if !allocated[k] {
                    let levels = if k < 2 { 5 } else { 1 };
                    glTexStorage2D(GL_TEXTURE_2D, levels, if u.etc2 { GL_COMPRESSED_RGB8_ETC2 } else { GL_RGBA8 }, u.w, u.h);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if k < 2 { GL_LINEAR_MIPMAP_NEAREST } else { GL_LINEAR });
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    allocated[k] = true;
                }
                if u.etc2 {
                    glCompressedTexSubImage2D(GL_TEXTURE_2D, u.level, 0, 0, u.w, u.h, GL_COMPRESSED_RGB8_ETC2, u.data.len() as i32, u.data.as_ptr() as *const c_void);
                } else {
                    glTexSubImage2D(GL_TEXTURE_2D, u.level, 0, 0, u.w, u.h, GL_RGBA, GL_UNSIGNED_BYTE, u.data.as_ptr() as *const c_void);
                }
                let e = glGetError();
                if e != 0 {
                    log(&format!("upload slot {} level {}: GL error 0x{e:x}", u.slot, u.level));
                }
            }
            let Some(f) = frame.or(prev) else { continue };
            let partial = PARTIAL.load(std::sync::atomic::Ordering::Relaxed);
            let mine = damage(&prev, &f);
            let mut age = 0;
            if age_ok && partial {
                eglQuerySurface(dpy, surf, EGL_BUFFER_AGE_EXT, &mut age);
            }
            let mut region = mine;
            if age == 0 || age as usize > history.len() + 1 {
                region = None;
            } else {
                for h in history.iter().take(age as usize - 1) {
                    region = union(region, *h);
                }
            }
            history.insert(0, mine);
            history.truncate(4);
            let area = match region {
                None => {
                    glDisable(GL_SCISSOR_TEST);
                    (W * H) as i64
                }
                Some(r) => {
                    let (x0, y0, x1, y1) = (r[0].max(0.0).floor(), r[1].max(0.0).floor(), r[2].min(W).ceil(), r[3].min(H).ceil());
                    glEnable(GL_SCISSOR_TEST);
                    glScissor(x0 as i32, (H - y1) as i32, (x1 - x0).max(0.0) as i32, (y1 - y0).max(0.0) as i32);
                    ((x1 - x0).max(0.0) * (y1 - y0).max(0.0)) as i64
                }
            };
            let cpu0 = now();
            if area > 0 {
                draw_frame(&gl, &f);
            }
            // CPU time to build the frame (draw calls issued), separate from the fence wait the `frame` log carries.
            let cpu = (now() - cpu0) / 1000;
            let sync = create_sync(dpy, EGL_SYNC_FENCE_KHR, std::ptr::null());
            let submit = now();
            match (swap_damage, mine) {
                (Some(swap), Some(r)) if partial && area < (W * H) as i64 => {
                    let rect = [r[0] as i32, (H - r[3]) as i32, (r[2] - r[0]) as i32, (r[3] - r[1]) as i32];
                    swap(dpy, surf, rect.as_ptr(), 1);
                }
                _ => {
                    eglSwapBuffers(dpy, surf);
                }
            }
            let _ = tx.send((sync as usize, f.t0, submit, area, f.scroll, cpu));
            prev = Some(f);
        }
        drop(tx);
        let _ = waiter.join();
        eglMakeCurrent(dpy, std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut());
        eglDestroySurface(dpy, surf);
        eglDestroyContext(dpy, ctx);
        ANativeWindow_release(window);
    }
}
