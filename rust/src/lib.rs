//! Glass's native image kernels, called from Kotlin through JNI (`dev.glasslauncher.glass.NativeImage`).
//!
//! Only the colour matrices of the backdrop bake live here: Skia applies a ColorMatrixColorFilter per pixel on its
//! slow path (measured on the Fire TV Stick 4K: 139 ms of a ladder bake's downscales and 62 ms of its legibility
//! passes, against 15 and 13 ms here). The blur stays on RenderScript, which beat a Rust box blur on the stick.
//! Pixels are Android's ARGB_8888 memory layout (R, G, B, A bytes, premultiplied).
#![cfg_attr(target_os = "android", no_std)]

mod jni;

/// Android's `ColorMatrix.setSaturation(sat)` on R, G, B (alpha kept), in place on `h` rows of `w` pixels, `stride`
/// bytes apart. The matrix is linear with rows summing to 1, so it applies to premultiplied colour directly; results
/// are clamped to the pixel's alpha (Skia clamps straight colour to 255), so translucent pixels stay valid.
pub fn saturate(px: &mut [u8], w: usize, h: usize, stride: usize, sat: f32) {
    let q = |v: f32| rnd(v * 4096.0);
    let (lr, lg, lb) = (0.213 * (1.0 - sat), 0.715 * (1.0 - sat), 0.072 * (1.0 - sat));
    let m = [[q(lr + sat), q(lg), q(lb)], [q(lr), q(lg + sat), q(lb)], [q(lr), q(lg), q(lb + sat)]];
    for y in 0..h {
        for p in px[y * stride..y * stride + w * 4].chunks_exact_mut(4) {
            let (r, g, b, a) = (p[0] as i32, p[1] as i32, p[2] as i32, p[3] as i32);
            for (c, row) in p[..3].iter_mut().zip(&m) {
                *c = ((row[0] * r + row[1] * g + row[2] * b + 2048) >> 12).clamp(0, a) as u8;
            }
        }
    }
}

/// Blur.legible's colour matrix, c * scale + offset on R, G, B (alpha kept), in place. On premultiplied pixels the
/// offset scales with alpha and the result is clamped to it, as Skia's matrix on straight colour gives.
pub fn scale_offset(px: &mut [u8], w: usize, h: usize, stride: usize, scale: f32, offset: f32) {
    let s = rnd(scale * 256.0).max(0) as u32;
    let o = rnd(offset * 256.0).max(0) as u32;
    for y in 0..h {
        for p in px[y * stride..y * stride + w * 4].chunks_exact_mut(4) {
            let a = p[3] as u32;
            let off = if a == 255 { o } else { o * a / 255 } + 128;
            for c in &mut p[..3] {
                *c = ((*c as u32 * s + off) >> 8).min(a) as u8;
            }
        }
    }
}

/// Round half away from zero (no_std has no f32::round).
fn rnd(v: f32) -> i32 {
    if v >= 0.0 {
        (v + 0.5) as i32
    } else {
        (v - 0.5) as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saturate_matches_android_saturation() {
        // 0 is Android's luma (0.213 R + 0.715 G + 0.072 B) in every channel; 1 changes nothing.
        let mut px = [200u8, 40, 90, 255];
        saturate(&mut px, 1, 1, 4, 0.0);
        let l = (0.213f32 * 200.0 + 0.715 * 40.0 + 0.072 * 90.0).round() as u8;
        assert!(px[0].abs_diff(l) <= 1 && px[0] == px[1] && px[1] == px[2] && px[3] == 255);
        let mut px = [200u8, 40, 90, 255];
        saturate(&mut px, 1, 1, 4, 1.0);
        assert_eq!(px, [200, 40, 90, 255]);
        // 1.35 pushes every channel further from the luma (~77): R and B up, G down.
        let mut px = [200u8, 40, 90, 255];
        saturate(&mut px, 1, 1, 4, 1.35);
        assert!(px[0] > 200 && px[1] < 40 && px[2] > 90);
    }

    #[test]
    fn scale_offset_is_the_legible_matrix() {
        let mut px = [255u8, 100, 0, 255];
        scale_offset(&mut px, 1, 1, 4, 0.5, 127.5);
        assert_eq!(px, [255, 178, 128, 255]);
    }

    #[test]
    fn translucent_pixels_stay_valid_premultiplied() {
        // Neither a saturation boost nor light appearance's offset may push a channel above alpha.
        let mut px = [120u8, 10, 0, 120];
        saturate(&mut px, 1, 1, 4, 1.35);
        assert!(px[0] <= 120 && px[1] <= 120 && px[2] <= 120 && px[3] == 120);
        // Straight (200, 100, 0) -> (227.5, 177.5, 127.5), premultiplied by 128/255.
        let mut px = [100u8, 50, 0, 128];
        scale_offset(&mut px, 1, 1, 4, 0.5, 127.5);
        assert_eq!(px, [114, 89, 64, 128]);
    }

    #[test]
    fn padding_past_each_row_is_never_written() {
        let (w, h, stride) = (3, 2, 16);
        let mut img: Vec<u8> = (0..stride * h).map(|i| if i % stride < w * 4 { 90 } else { 7 }).collect();
        saturate(&mut img, w, h, stride, 0.5);
        scale_offset(&mut img, w, h, stride, 0.5, 50.0);
        assert!(img.chunks(stride).all(|row| row[w * 4..].iter().all(|&b| b == 7)));
    }
}
