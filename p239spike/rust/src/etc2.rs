//! A small ETC2 RGB8 encoder for opaque backdrops: ETC1-mode blocks only (individual and differential; both are valid
//! ETC2 blocks), exhaustive over flip, the 8 intensity tables and the 4 modifiers per pixel. No T/H/planar modes, so
//! quality sits a little under etcpak's, at a fraction of the code.

const TABLES: [[i32; 4]; 8] = [[2, 8, -2, -8], [5, 17, -5, -17], [9, 29, -9, -29], [13, 42, -13, -42], [18, 60, -18, -60], [24, 80, -24, -80], [33, 106, -33, -106], [47, 183, -47, -183]];

/// [h] rows of [w] RGBA8 pixels, [stride] bytes apart -> ETC2 RGB8 blocks (8 bytes per 4x4, row-major blocks).
pub fn encode(px: &[u8], w: usize, h: usize, stride: usize, threads: usize) -> Vec<u8> {
    let (bw, bh) = (w.div_ceil(4), h.div_ceil(4));
    let mut out = vec![0u8; bw * bh * 8];
    let per = bh.div_ceil(threads.max(1));
    std::thread::scope(|s| {
        for (t, chunk) in out.chunks_mut(per * bw * 8).enumerate() {
            s.spawn(move || {
                for (i, block) in chunk.chunks_mut(8).enumerate() {
                    let (bx, by) = (i % bw, t * per + i / bw);
                    let mut rgb = [[0i32; 3]; 16];
                    for y in 0..4 {
                        for x in 0..4 {
                            let (sx, sy) = ((bx * 4 + x).min(w - 1), (by * 4 + y).min(h - 1));
                            let p = &px[sy * stride + sx * 4..];
                            rgb[x * 4 + y] = [p[0] as i32, p[1] as i32, p[2] as i32];
                        }
                    }
                    block.copy_from_slice(&encode_block(&rgb).to_be_bytes());
                }
            });
        }
    });
    out
}

/// Pixel j (= x * 4 + y, ETC's column-major order) is in sub-block 1 when it's in the left (flip 0) or top (flip 1) half.
fn second(j: usize, flip: bool) -> bool {
    if flip { j % 4 >= 2 } else { j / 4 >= 2 }
}

/// Best table and pixel indices for one sub-block around [base]: (error, table, indices as (pixel, index) bits).
/// A modifier m adds to all three channels, so a pixel's error is q + 2 m s + 3 m^2 with s = sum(base - p) and
/// q = sum((base - p)^2): no per-channel loop (exact except where base + m clamps at 0 or 255).
fn fit(rgb: &[[i32; 3]; 16], flip: bool, sub: bool, base: [i32; 3]) -> (i64, u32, u32) {
    let mut sq = [(0usize, 0i32, 0i32); 8];
    let mut n = 0;
    for j in (0..16).filter(|&j| second(j, flip) == sub) {
        let d = [base[0] - rgb[j][0], base[1] - rgb[j][1], base[2] - rgb[j][2]];
        sq[n] = (j, d[0] + d[1] + d[2], d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        n += 1;
    }
    let mut best = (i64::MAX, 0u32, 0u32);
    for (t, mods) in TABLES.iter().enumerate() {
        let (mut err, mut bits) = (0i64, 0u32);
        for &(j, s, q) in &sq[..n] {
            let (mut e, mut k) = (i32::MAX, 0u32);
            for (m, &d) in mods.iter().enumerate() {
                let ee = q + 2 * d * s + 3 * d * d;
                if ee < e {
                    e = ee;
                    k = m as u32;
                }
            }
            err += e as i64;
            bits |= ((k >> 1) << (16 + j)) | ((k & 1) << j);
        }
        if err < best.0 {
            best = (err, t as u32, bits);
        }
    }
    best
}

fn encode_block(rgb: &[[i32; 3]; 16]) -> u64 {
    let mut best = (i64::MAX, 0u64);
    for flip in [false, true] {
        let avg = |sub: bool| {
            let mut s = [0i32; 3];
            for j in (0..16).filter(|&j| second(j, flip) == sub) {
                for c in 0..3 {
                    s[c] += rgb[j][c];
                }
            }
            s.map(|v| v / 8)
        };
        let (a1, a2) = (avg(false), avg(true));
        // Differential: 5-bit bases, the second within -4..3 of the first.
        let q5 = a1.map(|v| (v * 31 + 127) / 255);
        let d = [0, 1, 2].map(|c| ((a2[c] * 31 + 127) / 255 - q5[c]).clamp(-4, 3));
        let q5b = [0, 1, 2].map(|c| q5[c] + d[c]);
        let x5 = |q: [i32; 3]| q.map(|v| (v << 3) | (v >> 2));
        let diff_ok = (0..3).all(|c| (0..32).contains(&q5b[c]));
        let mut candidates = Vec::with_capacity(2);
        if diff_ok {
            let (e1, t1, i1) = fit(rgb, flip, false, x5(q5));
            let (e2, t2, i2) = fit(rgb, flip, true, x5(q5b));
            let mut b = 0u64;
            for c in 0..3 {
                b |= ((q5[c] as u64) << (59 - 8 * c)) | (((d[c] & 7) as u64) << (56 - 8 * c));
            }
            b |= (t1 as u64) << 37 | (t2 as u64) << 34 | 1 << 33 | (flip as u64) << 32 | (i1 | i2) as u64;
            candidates.push((e1 + e2, b));
        }
        // Individual: 4-bit bases.
        let q4 = |a: [i32; 3]| a.map(|v| (v * 15 + 127) / 255);
        let (p1, p2) = (q4(a1), q4(a2));
        let (e1, t1, i1) = fit(rgb, flip, false, p1.map(|v| v * 17));
        let (e2, t2, i2) = fit(rgb, flip, true, p2.map(|v| v * 17));
        let mut b = 0u64;
        for c in 0..3 {
            b |= ((p1[c] as u64) << (60 - 8 * c)) | ((p2[c] as u64) << (56 - 8 * c));
        }
        b |= (t1 as u64) << 37 | (t2 as u64) << 34 | (flip as u64) << 32 | (i1 | i2) as u64;
        candidates.push((e1 + e2, b));
        for c in candidates {
            if c.0 < best.0 {
                best = c;
            }
        }
    }
    best.1
}

#[cfg(test)]
mod tests {
    use super::*;

    /// ETC1-mode decode (the spec's), to check the encoder round-trips.
    fn decode_block(b: u64) -> [[i32; 3]; 16] {
        let flip = b >> 32 & 1 == 1;
        let (base1, base2) = if b >> 33 & 1 == 1 {
            let q = |c: usize| (b >> (59 - 8 * c) & 31) as i32;
            let d = |c: usize| { let v = (b >> (56 - 8 * c) & 7) as i32; if v >= 4 { v - 8 } else { v } };
            let x = |v: i32| (v << 3) | (v >> 2);
            ([0, 1, 2].map(|c| x(q(c))), [0, 1, 2].map(|c| x(q(c) + d(c))))
        } else {
            ([0, 1, 2].map(|c| (b >> (60 - 8 * c) & 15) as i32 * 17), [0, 1, 2].map(|c| (b >> (56 - 8 * c) & 15) as i32 * 17))
        };
        let (t1, t2) = ((b >> 37 & 7) as usize, (b >> 34 & 7) as usize);
        let mut out = [[0; 3]; 16];
        for j in 0..16 {
            let k = ((b >> (16 + j) & 1) << 1 | (b >> j & 1)) as usize;
            let (base, t) = if second(j, flip) { (base2, t2) } else { (base1, t1) };
            out[j] = base.map(|v| (v + TABLES[t][k]).clamp(0, 255));
        }
        out
    }

    #[test]
    fn round_trips_gradients_and_noise_closely() {
        let (w, h) = (64, 32);
        let mut seed = 7u32;
        let px: Vec<u8> = (0..w * h).flat_map(|i| {
            let (x, y) = (i % w, i / w);
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            let n = (seed >> 28) as usize;
            [(x * 4) as u8, (y * 8) as u8, (128 + n) as u8, 255]
        }).collect();
        let blocks = encode(&px, w, h, w * 4, 3);
        assert_eq!(blocks.len(), (w / 4) * (h / 4) * 8);
        let mut se = 0f64;
        for (i, b) in blocks.chunks(8).enumerate() {
            let dec = decode_block(u64::from_be_bytes(b.try_into().unwrap()));
            let (bx, by) = (i % (w / 4), i / (w / 4));
            for j in 0..16 {
                let (x, y) = (bx * 4 + j / 4, by * 4 + j % 4);
                for c in 0..3 {
                    let d = dec[j][c] - px[(y * w + x) * 4 + c] as i32;
                    se += (d * d) as f64;
                }
            }
        }
        let psnr = 10.0 * (255.0f64 * 255.0 / (se / (w * h * 3) as f64)).log10();
        // Independent noise per channel is ETC's worst case (one modifier for all three); measured 35.7 dB.
        assert!(psnr > 34.0, "PSNR {psnr:.1} dB");
    }
}
