//! P239 spike: Home's backdrop, tray glass, tiles and focus shadow drawn in ONE GLES 3 render pass, from Rust.
//! Spike code (not for merge): std, hand-declared EGL/GLES/NDK FFI, no crates. Kotlin drives it through
//! `dev.glasslauncher.p239spike.Native`: textures once, then per frame only the animated values (tile scales,
//! crossfade, blur level). Per frame it logs to logcat (tag P239) when the GPU finished, for the A/B.
#![allow(non_snake_case, clippy::missing_safety_doc)]

use std::ffi::{c_char, c_void, CString};
use std::sync::{mpsc, Arc, Condvar, Mutex};
use std::thread;

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
const TILE_Y: f32 = 768.0 + 40.0 - 32.0; // pad, raised for the focus label (Metrics.trayLabel)
/// How far the focus shadow reaches past a tile (px), for damage rects.
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
    t0: i64,
}

struct Shared {
    frame: Option<Frame>,
    uploads: Vec<(i32, i32, i32, i32, Vec<u8>)>, // slot, level, w, h, rgba
    quit: bool,
}

struct Renderer {
    shared: Arc<(Mutex<Shared>, Condvar)>,
    thread: Option<thread::JoinHandle<()>>,
}

static RENDERER: Mutex<Option<Renderer>> = Mutex::new(None);
/// Partial redraw on (buffer age + swap with damage); off redraws the whole screen every frame.
static PARTIAL: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(true);

#[no_mangle]
pub extern "system" fn Java_dev_glasslauncher_p239spike_Native_partial(_: *mut c_void, _: *mut c_void, on: u8) {
    PARTIAL.store(on != 0, std::sync::atomic::Ordering::Relaxed);
}

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

/// Copies an ARGB_8888 bitmap's pixels for upload as texture [slot] (0, 1: backdrops; 2..7: tiles) at mip [level].
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_upload(env: *mut c_void, _: *mut c_void, slot: i32, level: i32, bitmap: *mut c_void) {
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
    let mut px = Vec::with_capacity(w * h * 4);
    for y in 0..h {
        px.extend_from_slice(std::slice::from_raw_parts((p as *const u8).add(y * stride), w * 4));
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if let Some(r) = RENDERER.lock().unwrap().as_ref() {
        r.shared.0.lock().unwrap().uploads.push((slot, level, w as i32, h as i32, px));
    }
}

/// One frame's animated values; the render thread draws it as soon as it is free (latest wins).
#[no_mangle]
pub unsafe extern "system" fn Java_dev_glasslauncher_p239spike_Native_frame(
    _: *mut c_void, _: *mut c_void, s0: f32, s1: f32, s2: f32, s3: f32, s4: f32, s5: f32, mix: f32, lod: f32, flip: i32,
) {
    if let Some(r) = RENDERER.lock().unwrap().as_ref() {
        r.shared.0.lock().unwrap().frame = Some(Frame { scales: [s0, s1, s2, s3, s4, s5], mix, lod, flip, t0: now() });
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
    rect: i32,
    screen: i32,
    a: i32,
    b: i32,
    mix: i32,
    lod: i32,
    radius: i32,
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
    let u = |n: &str| {
        let c = CString::new(n).unwrap();
        glGetUniformLocation(id, c.as_ptr())
    };
    Prog { id, rect: u("uRect"), screen: u("uScreen"), a: u("uA"), b: u("uB"), mix: u("uMix"), lod: u("uLod"), radius: u("uRadius") }
}

const VERT: &str = "#version 300 es
uniform vec4 uRect; uniform vec2 uScreen;
out vec2 vPx; out vec2 vLocal;
void main() {
  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
  vec2 p = uRect.xy + c * uRect.zw;
  vPx = p; vLocal = c;
  gl_Position = vec4(p.x / uScreen.x * 2.0 - 1.0, 1.0 - p.y / uScreen.y * 2.0, 0.0, 1.0);
}";

const FRAG_HEAD: &str = "#version 300 es
precision mediump float;
uniform highp vec4 uRect; uniform highp vec2 uScreen; uniform sampler2D uA; uniform sampler2D uB;
uniform float uMix; uniform float uLod; uniform float uRadius;
in highp vec2 vPx; in vec2 vLocal; out vec4 o;
float sdRound(highp vec2 p, highp vec2 h, float r) { vec2 q = abs(p) - h + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
vec3 scene(vec2 uv, float lod) { vec3 a = textureLod(uA, uv, lod).rgb; return uMix > 0.0 ? mix(a, textureLod(uB, uv, lod).rgb, uMix) : a; }
";

/// The backdrop: the scene's pre-blurred mip chain at the scroll blur level (one fetch; two while cross-fading).
const BACKDROP: &str = "void main() { o = vec4(scene(vLocal, uLod), 1.0); }";

/// Tray glass: the blurred scene at a lens-displaced UV, Glass's face colour matrix and tint, analytic rim and specular.
const GLASS: &str = "void main() {
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
}";

/// Focus shadow: an analytic soft rounded-rect shadow (distance falloff), no shadow bitmap.
const SHADOW_FS: &str = "void main() {
  highp vec2 h = uRect.zw * 0.5 - vec2(48.0); highp vec2 p = vPx - (uRect.xy + uRect.zw * 0.5) - vec2(0.0, 14.0);
  float d = sdRound(p, h, uRadius);
  float a = (1.0 - smoothstep(-16.0, 40.0, d)) * 0.45 * uMix;
  o = vec4(0.0, 0.0, 0.0, a);
}";

/// A tile: its art clipped by an analytic rounded rect (anti-aliased, no stencil or layer).
const TILE: &str = "void main() {
  highp vec2 h = uRect.zw * 0.5; float d = sdRound(vPx - (uRect.xy + h), h, uRadius);
  float a = clamp(0.5 - d, 0.0, 1.0);
  if (a <= 0.0) discard;
  o = texture(uA, vLocal) * a;
}";

// ---- render thread

struct Gl {
    backdrop: Prog,
    glass: Prog,
    shadow: Prog,
    tile: Prog,
    tex: [u32; 8],
}

unsafe fn draw(p: &Prog, rect: [f32; 4], a: u32, b: u32, mix: f32, lod: f32, radius: f32) {
    glUseProgram(p.id);
    glUniform4f(p.rect, rect[0], rect[1], rect[2], rect[3]);
    glUniform2f(p.screen, W, H);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, a);
    glUniform1i(p.a, 0);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, b);
    glUniform1i(p.b, 1);
    glUniform1f(p.mix, mix);
    glUniform1f(p.lod, lod);
    glUniform1f(p.radius, radius);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

unsafe fn draw_frame(gl: &Gl, f: &Frame) {
    let (from, to) = if f.flip == 0 { (gl.tex[0], gl.tex[1]) } else { (gl.tex[1], gl.tex[0]) };
    glDisable(GL_BLEND);
    draw(&gl.backdrop, [0.0, 0.0, W, H], from, to, f.mix, f.lod, 0.0);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    draw(&gl.glass, TRAY, from, to, f.mix, 0.0, TRAY_R);
    for (i, &s) in f.scales.iter().enumerate() {
        let focus = ((s - 1.0) / 0.224).clamp(0.0, 1.0);
        if focus > 0.01 {
            let r = tile_rect(i, s);
            draw(&gl.shadow, [r[0] - SHADOW, r[1] - SHADOW, r[2] + 2.0 * SHADOW, r[3] + 2.0 * SHADOW], 0, 0, focus, 0.0, TILE_R * s);
        }
    }
    for (i, &s) in f.scales.iter().enumerate() {
        draw(&gl.tile, tile_rect(i, s), gl.tex[2 + i], 0, 0.0, 0.0, TILE_R * s);
    }
}

/// Screen rect (x0, y0, x1, y1) this frame changes against [prev]; None = full screen.
fn damage(prev: &Option<Frame>, f: &Frame) -> Option<[f32; 4]> {
    let p = (*prev)?;
    if p.mix != f.mix || p.lod != f.lod || p.flip != f.flip {
        return None;
    }
    let mut d: Option<[f32; 4]> = None;
    for i in 0..6 {
        if p.scales[i] != f.scales[i] {
            for s in [p.scales[i], f.scales[i]] {
                let r = tile_rect(i, s);
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
        let gl_ext = std::ffi::CStr::from_ptr(glGetString(GL_EXTENSIONS) as *const c_char).to_string_lossy().into_owned();
        log(&format!("init n={n} buffer_age={age_ok} swap_damage={} timer_query={} gl={}", swap_damage.is_some(), gl_ext.contains("timer_query"),
            std::ffi::CStr::from_ptr(glGetString(GL_RENDERER) as *const c_char).to_string_lossy()));
        eglSwapInterval(dpy, 1);

        let gl = Gl { backdrop: program(BACKDROP), glass: program(GLASS), shadow: program(SHADOW_FS), tile: program(TILE), tex: {
            let mut t = [0u32; 8];
            glGenTextures(8, t.as_mut_ptr());
            t
        } };
        glViewport(0, 0, W as i32, H as i32);

        // The GPU-completion waiter: logs one line per frame (start, submit, GPU done, damaged pixels).
        let (tx, rx) = mpsc::channel::<(usize, i64, i64, i64, i32, i64)>();
        let dpy_u = dpy as usize;
        let waiter = thread::spawn(move || {
            for (sync, t0, submit, area, age, swapped) in rx {
                wait_sync(dpy_u as *mut c_void, sync as *mut c_void, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, u64::MAX);
                let done = now();
                destroy_sync(dpy_u as *mut c_void, sync as *mut c_void);
                log(&format!("frame,{t0},{submit},{done},{area},{age},{swapped}"));
            }
        });

        let mut prev: Option<Frame> = None;
        let mut history: Vec<Option<[f32; 4]>> = Vec::new(); // damage of the last frames, newest first
        let mut allocated = [0u8; 8];
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
                prev = None; // new textures: redraw everything
                history.clear();
            }
            for (slot, level, w, h, px) in uploads {
                let t = gl.tex[slot as usize];
                glBindTexture(GL_TEXTURE_2D, t);
                if allocated[slot as usize] == 0 {
                    let levels = if slot < 2 { 5 } else { 1 };
                    glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, w, h);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if slot < 2 { GL_LINEAR_MIPMAP_LINEAR } else { GL_LINEAR });
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    allocated[slot as usize] = 1;
                }
                glTexSubImage2D(GL_TEXTURE_2D, level, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.as_ptr() as *const c_void);
            }
            let Some(f) = frame.or(prev).or(Some(Frame { scales: [1.0; 6], mix: 0.0, lod: 0.0, flip: 0, t0: now() })) else { continue };
            // Partial redraw: this frame's damage, plus whatever the reused buffer missed (EGL_EXT_buffer_age).
            let mine = damage(&prev, &f);
            let mut age = 0;
            let partial = PARTIAL.load(std::sync::atomic::Ordering::Relaxed);
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
            if area > 0 {
                draw_frame(&gl, &f);
            }
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
            let _ = tx.send((sync as usize, f.t0, submit, area, age, now()));
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
