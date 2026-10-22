//! P239 spike, lean: the renderer of ../rust (same shaders, same one-pass frame, same partial redraw) in the shape an
//! integration would take: no_std, no allocation, no threads or locks. Kotlin owns one GL thread and calls every
//! entry point on it (`dev.glasslauncher.p239spike.Lean`). Built to measure the library's size and check the render.
#![no_std]
#![allow(non_snake_case, clippy::missing_safety_doc, static_mut_refs)]

use core::ffi::{c_char, c_void};

mod ffi;
use ffi::*;

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! {
    unsafe { abort() }
}

const W: f32 = 1920.0;
const H: f32 = 1080.0;
const TILE_W: f32 = 250.0;
const TILE_H: f32 = 150.0;
const TILE_R: f32 = 30.0;
const INSET: f32 = 90.0;
const GUTTER: f32 = 48.0;
const TRAY: [f32; 4] = [44.0, 768.0, 1832.0, 230.0];
const TRAY_R: f32 = 60.0;
const TILE_Y: f32 = 776.0;
const SHADOW: f32 = 60.0;

fn tile_rect(i: usize, scale: f32) -> [f32; 4] {
    let x = INSET + i as f32 * (TILE_W + GUTTER);
    let (w, h) = (TILE_W * scale, TILE_H * scale);
    [x + (TILE_W - w) / 2.0, TILE_Y + (TILE_H - h) / 2.0, w, h]
}

#[derive(Clone, Copy, PartialEq)]
struct Frame {
    scales: [f32; 6],
    mix: f32,
    lod: f32,
    flip: i32,
}

struct Prog {
    id: u32,
    u: [i32; 7], // rect, screen, a, b, mix, lod, radius
}

struct State {
    dpy: *mut c_void,
    surf: *mut c_void,
    ctx: *mut c_void,
    window: *mut c_void,
    progs: [Prog; 4],
    tex: [u32; 8],
    allocated: [bool; 8],
    prev: Option<Frame>,
    history: [Option<[f32; 4]>; 4],
    age_ok: bool,
    swap_damage: Option<SwapDamage>,
}

/// Only ever touched on the one GL thread Kotlin calls from.
static mut STATE: Option<State> = None;

const VERT: &str = "#version 300 es
uniform highp vec4 uRect; uniform highp vec2 uScreen;
out highp vec2 vPx; out vec2 vLocal;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
  vec2 p = uRect.xy + c * uRect.zw;
  vPx = p; vLocal = c;
  gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}";

const HEAD: &str = "#version 300 es
precision mediump float;
uniform highp vec4 uRect; uniform highp vec2 uScreen; uniform sampler2D uA; uniform sampler2D uB;
uniform float uMix; uniform float uLod; uniform float uRadius;
in highp vec2 vPx; in vec2 vLocal; out vec4 o;
float sdRound(highp vec2 p, highp vec2 h, float r) { vec2 q = abs(p) - h + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
vec3 scene(vec2 uv, float lod) { vec3 a = textureLod(uA, uv, lod).rgb; return uMix > 0.0 ? mix(a, textureLod(uB, uv, lod).rgb, uMix) : a; }
";

const BODIES: [&str; 4] = [
    "void main() { o = vec4(scene(vLocal, uLod), 1.0); }",
    "void main() {
  highp vec2 h = uRect.zw * 0.5; highp vec2 p = vPx - (uRect.xy + h);
  float d = sdRound(p, h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  float edge = clamp(1.0 + d / 28.0, 0.0, 1.0);
  vec2 uv = (vPx - p * edge * edge * 0.10) / uScreen;
  vec3 bg = scene(uv, 3.0);
  float l = dot(bg, vec3(0.2126, 0.7152, 0.0722));
  vec3 col = clamp(mix(vec3(l), bg, 1.59) * 0.629 + 0.235 * 0.25, 0.0, 1.0);
  col = mix(col, vec3(1.0), 0.06);
  float rim = (1.0 - smoothstep(0.0, 2.5, abs(d + 1.25))) * (0.22 + 0.3 * clamp(-p.y / h.y, 0.0, 1.0));
  float spec = (1.0 - smoothstep(0.0, 18.0, -d)) * 0.06 * clamp(-p.y / h.y, 0.0, 1.0);
  o = vec4(col + rim + spec, 1.0) * a;
}",
    "void main() {
  highp vec2 h = uRect.zw * 0.5 - vec2(48.0); highp vec2 p = vPx - (uRect.xy + uRect.zw * 0.5) - vec2(0.0, 14.0);
  float d = sdRound(p, h, uRadius);
  o = vec4(0.0, 0.0, 0.0, (1.0 - smoothstep(-16.0, 40.0, d)) * 0.45 * uMix);
}",
    "void main() {
  highp vec2 h = uRect.zw * 0.5; float d = sdRound(vPx - (uRect.xy + h), h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  o = texture(uA, vLocal) * a;
}",
];

const NAMES: [&core::ffi::CStr; 7] = [c"uRect", c"uScreen", c"uA", c"uB", c"uMix", c"uLod", c"uRadius"];

unsafe fn shader(kind: u32, parts: &[&str]) -> u32 {
    let s = glCreateShader(kind);
    let mut ptrs = [core::ptr::null::<c_char>(); 2];
    let mut lens = [0i32; 2];
    for (i, p) in parts.iter().enumerate() {
        ptrs[i] = p.as_ptr() as *const c_char;
        lens[i] = p.len() as i32;
    }
    glShaderSource(s, parts.len() as i32, ptrs.as_ptr(), lens.as_ptr());
    glCompileShader(s);
    s
}

unsafe fn program(body: &str) -> Option<Prog> {
    let id = glCreateProgram();
    glAttachShader(id, shader(GL_VERTEX_SHADER, &[VERT]));
    glAttachShader(id, shader(GL_FRAGMENT_SHADER, &[HEAD, body]));
    glLinkProgram(id);
    let mut ok = 0;
    glGetProgramiv(id, GL_LINK_STATUS, &mut ok);
    if ok == 0 {
        return None;
    }
    let mut u = [0; 7];
    for (k, n) in NAMES.iter().enumerate() {
        u[k] = glGetUniformLocation(id, n.as_ptr());
    }
    Some(Prog { id, u })
}

unsafe fn draw(p: &Prog, rect: [f32; 4], a: u32, b: u32, mix: f32, lod: f32, radius: f32) {
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
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

fn has(list: *const c_char, name: &[u8]) -> bool {
    if list.is_null() {
        return false;
    }
    let all = unsafe { core::ffi::CStr::from_ptr(list) }.to_bytes();
    all.split(|&c| c == b' ').any(|e| e == name)
}

/// `static native boolean init(Surface)`: EGL + programs on the calling (GL) thread. False: the caller keeps Compose.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Lean_init(env: *mut c_void, _: *mut c_void, surface: *mut c_void) -> u8 {
    let window = ANativeWindow_fromSurface(env, surface);
    let dpy = eglGetDisplay(core::ptr::null_mut());
    if window.is_null() || eglInitialize(dpy, core::ptr::null_mut(), core::ptr::null_mut()) == 0 {
        return 0;
    }
    let attrs = [EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0, EGL_DEPTH_SIZE, 0, EGL_NONE];
    let mut cfg: *mut c_void = core::ptr::null_mut();
    let mut n = 0;
    if eglChooseConfig(dpy, attrs.as_ptr(), &mut cfg, 1, &mut n) == 0 || n == 0 {
        return 0;
    }
    let ctx = eglCreateContext(dpy, cfg, core::ptr::null_mut(), [EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE].as_ptr());
    let surf = eglCreateWindowSurface(dpy, cfg, window, core::ptr::null());
    if ctx.is_null() || surf.is_null() || eglMakeCurrent(dpy, surf, surf, ctx) == 0 {
        return 0;
    }
    let ext = eglQueryString(dpy, EGL_EXTENSIONS);
    let swap_damage = if has(ext, b"EGL_KHR_swap_buffers_with_damage") {
        Some(core::mem::transmute::<*mut c_void, SwapDamage>(eglGetProcAddress(c"eglSwapBuffersWithDamageKHR".as_ptr())))
    } else {
        None
    };
    let (Some(p0), Some(p1), Some(p2), Some(p3)) = (program(BODIES[0]), program(BODIES[1]), program(BODIES[2]), program(BODIES[3])) else { return 0 };
    let mut tex = [0u32; 8];
    glGenTextures(8, tex.as_mut_ptr());
    glViewport(0, 0, W as i32, H as i32);
    eglSwapInterval(dpy, 1);
    STATE = Some(State { dpy, surf, ctx, window, progs: [p0, p1, p2, p3], tex, allocated: [false; 8], prev: None, history: [None; 4], age_ok: has(ext, b"EGL_EXT_buffer_age"), swap_damage });
    1
}

/// `static native void upload(int slot, int level, Bitmap)`: straight from the locked bitmap into the texture.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Lean_upload(env: *mut c_void, _: *mut c_void, slot: i32, level: i32, bitmap: *mut c_void) {
    let Some(s) = STATE.as_mut() else { return };
    let mut info = BitmapInfo { width: 0, height: 0, stride: 0, format: 0, flags: 0 };
    let mut p: *mut c_void = core::ptr::null_mut();
    if !(0..8).contains(&slot) || AndroidBitmap_getInfo(env, bitmap, &mut info) != 0 || info.format != 1 || AndroidBitmap_lockPixels(env, bitmap, &mut p) != 0 {
        return;
    }
    let k = slot as usize;
    glBindTexture(GL_TEXTURE_2D, s.tex[k]);
    if !s.allocated[k] {
        let levels = if k < 2 { 5 } else { 1 };
        glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, info.width as i32, info.height as i32);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if k < 2 { GL_LINEAR_MIPMAP_LINEAR } else { GL_LINEAR });
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        s.allocated[k] = true;
    }
    glPixelStorei(GL_UNPACK_ROW_LENGTH, (info.stride / 4) as i32);
    glTexSubImage2D(GL_TEXTURE_2D, level, 0, 0, info.width as i32, info.height as i32, GL_RGBA, GL_UNSIGNED_BYTE, p);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    AndroidBitmap_unlockPixels(env, bitmap);
    s.prev = None;
}

fn damage(prev: &Option<Frame>, f: &Frame) -> Option<[f32; 4]> {
    let p = (*prev)?;
    if p.mix != f.mix || p.lod != f.lod || p.flip != f.flip {
        return None;
    }
    let mut d = [f32::MAX, f32::MAX, f32::MIN, f32::MIN];
    for i in 0..6 {
        if p.scales[i] != f.scales[i] {
            for sc in [p.scales[i], f.scales[i]] {
                let r = tile_rect(i, sc);
                d = [d[0].min(r[0] - SHADOW), d[1].min(r[1] - SHADOW), d[2].max(r[0] + r[2] + SHADOW), d[3].max(r[1] + r[3] + SHADOW)];
            }
        }
    }
    Some(if d[2] < d[0] { [0.0; 4] } else { d })
}

fn union(a: Option<[f32; 4]>, b: Option<[f32; 4]>) -> Option<[f32; 4]> {
    let (x, y) = (a?, b?);
    Some(if x[2] <= x[0] { y } else if y[2] <= y[0] { x } else { [x[0].min(y[0]), x[1].min(y[1]), x[2].max(y[2]), x[3].max(y[3])] })
}

/// `static native void frame(...)`: draws one frame (one render pass) and swaps, redrawing only what changed.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Lean_frame(
    _: *mut c_void, _: *mut c_void, s0: f32, s1: f32, s2: f32, s3: f32, s4: f32, s5: f32, mix: f32, lod: f32, flip: i32,
) {
    let Some(s) = STATE.as_mut() else { return };
    let f = Frame { scales: [s0, s1, s2, s3, s4, s5], mix, lod, flip };
    let mine = damage(&s.prev, &f);
    let mut age = 0;
    if s.age_ok {
        eglQuerySurface(s.dpy, s.surf, EGL_BUFFER_AGE_EXT, &mut age);
    }
    let mut region = mine;
    if age == 0 || age as usize > s.history.len() {
        region = None;
    } else {
        for h in s.history.iter().take(age as usize - 1) {
            region = union(region, *h);
        }
    }
    s.history.rotate_right(1);
    s.history[0] = mine;
    match region {
        None => glDisable(GL_SCISSOR_TEST),
        Some(r) => {
            let (x0, y0, x1, y1) = (r[0].max(0.0), r[1].max(0.0), r[2].min(W), r[3].min(H));
            glEnable(GL_SCISSOR_TEST);
            glScissor(x0 as i32, (H - y1) as i32, (x1 - x0).max(0.0) as i32 + 1, (y1 - y0).max(0.0) as i32 + 1);
        }
    }
    let (from, to) = if flip == 0 { (s.tex[0], s.tex[1]) } else { (s.tex[1], s.tex[0]) };
    let [backdrop, glass, shadow, tile] = &s.progs;
    glDisable(GL_BLEND);
    draw(backdrop, [0.0, 0.0, W, H], from, to, mix, lod, 0.0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    draw(glass, TRAY, from, to, mix, 0.0, TRAY_R);
    for (i, &sc) in f.scales.iter().enumerate() {
        let focus = ((sc - 1.0) / 0.224).clamp(0.0, 1.0);
        if focus > 0.01 {
            let r = tile_rect(i, sc);
            draw(shadow, [r[0] - SHADOW, r[1] - SHADOW, r[2] + 2.0 * SHADOW, r[3] + 2.0 * SHADOW], 0, 0, focus, 0.0, TILE_R * sc);
        }
    }
    for (i, &sc) in f.scales.iter().enumerate() {
        draw(tile, tile_rect(i, sc), s.tex[2 + i], 0, 0.0, 0.0, TILE_R * sc);
    }
    match (s.swap_damage, mine, region) {
        (Some(swap), Some(r), Some(_)) => {
            let rect = [r[0] as i32, (H - r[3]) as i32, (r[2] - r[0]) as i32 + 1, (r[3] - r[1]) as i32 + 1];
            swap(s.dpy, s.surf, rect.as_ptr(), 1);
        }
        _ => {
            eglSwapBuffers(s.dpy, s.surf);
        }
    }
    s.prev = Some(f);
}

/// `static native void release()`: tears EGL down (GL thread).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Lean_release(_: *mut c_void, _: *mut c_void) {
    if let Some(s) = STATE.take() {
        eglMakeCurrent(s.dpy, core::ptr::null_mut(), core::ptr::null_mut(), core::ptr::null_mut());
        eglDestroySurface(s.dpy, s.surf);
        eglDestroyContext(s.dpy, s.ctx);
        ANativeWindow_release(s.window);
    }
}
