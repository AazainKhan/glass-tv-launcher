//! Hand-declared EGL / GLES 3 / NDK entry points (spike: only what it calls).
use core::ffi::{c_char, c_void};

pub type SwapDamage = unsafe extern "C" fn(*mut c_void, *mut c_void, *const i32, i32) -> u32;
pub type CreateSync = unsafe extern "C" fn(*mut c_void, u32, *const i32) -> *mut c_void;
pub type WaitSync = unsafe extern "C" fn(*mut c_void, *mut c_void, i32, u64) -> i32;
pub type DestroySync = unsafe extern "C" fn(*mut c_void, *mut c_void) -> u32;

#[repr(C)]
pub struct Timespec {
    pub tv_sec: core::ffi::c_long,
    pub tv_nsec: core::ffi::c_long,
}

#[repr(C)]
pub struct BitmapInfo {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub format: i32,
    pub flags: u32,
}

pub const EGL_RENDERABLE_TYPE: i32 = 0x3040;
pub const EGL_OPENGL_ES3_BIT: i32 = 0x0040;
pub const EGL_SURFACE_TYPE: i32 = 0x3033;
pub const EGL_WINDOW_BIT: i32 = 0x0004;
pub const EGL_RED_SIZE: i32 = 0x3024;
pub const EGL_GREEN_SIZE: i32 = 0x3023;
pub const EGL_BLUE_SIZE: i32 = 0x3022;
pub const EGL_ALPHA_SIZE: i32 = 0x3021;
pub const EGL_DEPTH_SIZE: i32 = 0x3025;
pub const EGL_NONE: i32 = 0x3038;
pub const EGL_CONTEXT_CLIENT_VERSION: i32 = 0x3098;
pub const EGL_EXTENSIONS: i32 = 0x3055;
pub const EGL_BUFFER_AGE_EXT: i32 = 0x313D;
pub const EGL_SYNC_FENCE_KHR: u32 = 0x30F9;
pub const EGL_SYNC_FLUSH_COMMANDS_BIT_KHR: i32 = 0x0001;

pub const GL_VERTEX_SHADER: u32 = 0x8B31;
pub const GL_FRAGMENT_SHADER: u32 = 0x8B30;
pub const GL_COMPILE_STATUS: u32 = 0x8B81;
pub const GL_LINK_STATUS: u32 = 0x8B82;
pub const GL_TEXTURE_2D: u32 = 0x0DE1;
pub const GL_TEXTURE0: u32 = 0x84C0;
pub const GL_TEXTURE1: u32 = 0x84C1;
pub const GL_RGBA8: u32 = 0x8058;
pub const GL_RGBA: u32 = 0x1908;
pub const GL_UNSIGNED_BYTE: u32 = 0x1401;
pub const GL_TEXTURE_MAX_LEVEL: u32 = 0x813D;
pub const GL_TEXTURE_MIN_FILTER: u32 = 0x2801;
pub const GL_TEXTURE_MAG_FILTER: u32 = 0x2800;
pub const GL_TEXTURE_WRAP_S: u32 = 0x2802;
pub const GL_TEXTURE_WRAP_T: u32 = 0x2803;
pub const GL_CLAMP_TO_EDGE: i32 = 0x812F;
pub const GL_LINEAR: i32 = 0x2601;
pub const GL_LINEAR_MIPMAP_LINEAR: i32 = 0x2703;
pub const GL_BLEND: u32 = 0x0BE2;
pub const GL_SCISSOR_TEST: u32 = 0x0C11;
pub const GL_ONE: u32 = 1;
pub const GL_ONE_MINUS_SRC_ALPHA: u32 = 0x0303;
pub const GL_TRIANGLE_STRIP: u32 = 0x0005;
pub const GL_EXTENSIONS: u32 = 0x1F03;
pub const GL_RENDERER: u32 = 0x1F01;

#[link(name = "EGL")]
extern "C" {
    pub fn eglGetDisplay(d: *mut c_void) -> *mut c_void;
    pub fn eglInitialize(d: *mut c_void, major: *mut i32, minor: *mut i32) -> u32;
    pub fn eglChooseConfig(d: *mut c_void, attrs: *const i32, cfg: *mut *mut c_void, size: i32, n: *mut i32) -> u32;
    pub fn eglCreateContext(d: *mut c_void, cfg: *mut c_void, share: *mut c_void, attrs: *const i32) -> *mut c_void;
    pub fn eglCreateWindowSurface(d: *mut c_void, cfg: *mut c_void, win: *mut c_void, attrs: *const i32) -> *mut c_void;
    pub fn eglMakeCurrent(d: *mut c_void, draw: *mut c_void, read: *mut c_void, ctx: *mut c_void) -> u32;
    pub fn eglSwapBuffers(d: *mut c_void, s: *mut c_void) -> u32;
    pub fn eglSwapInterval(d: *mut c_void, i: i32) -> u32;
    pub fn eglQuerySurface(d: *mut c_void, s: *mut c_void, attr: i32, v: *mut i32) -> u32;
    pub fn eglQueryString(d: *mut c_void, name: i32) -> *const c_char;
    pub fn eglGetProcAddress(name: *const c_char) -> *mut c_void;
    pub fn eglDestroySurface(d: *mut c_void, s: *mut c_void) -> u32;
    pub fn eglDestroyContext(d: *mut c_void, c: *mut c_void) -> u32;
}

#[link(name = "GLESv3")]
extern "C" {
    pub fn glCreateShader(kind: u32) -> u32;
    pub fn glShaderSource(s: u32, n: i32, src: *const *const c_char, len: *const i32);
    pub fn glCompileShader(s: u32);
    pub fn glGetShaderiv(s: u32, p: u32, v: *mut i32);
    pub fn glGetShaderInfoLog(s: u32, max: i32, n: *mut i32, log: *mut c_char);
    pub fn glCreateProgram() -> u32;
    pub fn glAttachShader(p: u32, s: u32);
    pub fn glLinkProgram(p: u32);
    pub fn glUseProgram(p: u32);
    pub fn glGetProgramiv(p: u32, pname: u32, v: *mut i32);
    pub fn glGetProgramInfoLog(p: u32, max: i32, n: *mut i32, log: *mut c_char);
    pub fn glGetUniformLocation(p: u32, name: *const c_char) -> i32;
    pub fn glUniform1i(l: i32, v: i32);
    pub fn glUniform1f(l: i32, v: f32);
    pub fn glUniform2f(l: i32, a: f32, b: f32);
    pub fn glUniform4f(l: i32, a: f32, b: f32, c: f32, d: f32);
    pub fn glGenTextures(n: i32, t: *mut u32);
    pub fn glBindTexture(target: u32, t: u32);
    pub fn glActiveTexture(unit: u32);
    pub fn glTexStorage2D(target: u32, levels: i32, fmt: u32, w: i32, h: i32);
    pub fn glTexSubImage2D(target: u32, level: i32, x: i32, y: i32, w: i32, h: i32, fmt: u32, ty: u32, px: *const c_void);
    pub fn glTexParameteri(target: u32, p: u32, v: i32);
    pub fn glViewport(x: i32, y: i32, w: i32, h: i32);
    pub fn glScissor(x: i32, y: i32, w: i32, h: i32);
    pub fn glEnable(cap: u32);
    pub fn glDisable(cap: u32);
    pub fn glBlendFunc(s: u32, d: u32);
    pub fn glDrawArrays(mode: u32, first: i32, count: i32);
    pub fn glGetString(name: u32) -> *const u8;
}

#[link(name = "android")]
extern "C" {
    pub fn ANativeWindow_fromSurface(env: *mut c_void, surface: *mut c_void) -> *mut c_void;
    pub fn ANativeWindow_release(w: *mut c_void);
}

#[link(name = "jnigraphics")]
extern "C" {
    pub fn AndroidBitmap_getInfo(env: *mut c_void, bmp: *mut c_void, info: *mut BitmapInfo) -> i32;
    pub fn AndroidBitmap_lockPixels(env: *mut c_void, bmp: *mut c_void, px: *mut *mut c_void) -> i32;
    pub fn AndroidBitmap_unlockPixels(env: *mut c_void, bmp: *mut c_void) -> i32;
}

#[link(name = "log")]
extern "C" {
    pub fn __android_log_write(prio: i32, tag: *const c_char, text: *const c_char) -> i32;
}

extern "C" {
    pub fn clock_gettime(clk: i32, ts: *mut Timespec) -> i32;
}

pub const GL_UNPACK_ROW_LENGTH: u32 = 0x0CF2;
#[link(name = "GLESv3")]
extern "C" {
    pub fn glPixelStorei(p: u32, v: i32);
}
#[link(name = "c")]
extern "C" {
    pub fn abort() -> !;
}
