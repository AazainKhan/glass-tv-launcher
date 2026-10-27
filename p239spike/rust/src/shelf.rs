//! Cover Flow geometry — a direct port of `glass-launcher/featured/CoverFlowGeometry.kt` (P239).
//!
//! Ported rather than reinvented so the shelf's motion is the same motion Glass already ships. Everything here is a
//! pure function of `offset` (a cover's index minus the flow's position, fractional while moving), so one animated
//! position gives every cover its pose and a cover never jumps.
//!
//! No GL, no Android: this module is deliberately dependency-free so it can be tested on the host (the crate as a whole
//! cannot `cargo test` on a Mac — it links `-lEGL -lGLESv3 -landroid -llog`).

/// Where one cover sits in the flow: all horizontal values as fractions of a cover's width.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct CoverPose {
    /// Horizontal offset of the cover's centre from the flow's centre, in cover widths.
    pub x: f32,
    /// Turn about the vertical axis, degrees: negative leans a left-hand cover's face to the right (towards the centre).
    pub rotation_y: f32,
    pub scale: f32,
    /// Stacking order: larger is nearer; the centre cover is on top.
    pub z: f32,
    pub alpha: f32,
}

pub const ANGLE: f32 = 62.0;
pub const SIDE_SCALE: f32 = 0.8;
/// Neighbours drawn each side of the centre; one more fades out.
pub const VISIBLE_SIDE: f32 = 4.0;
/// The first neighbour's centre, in cover widths from the centre cover's.
pub const FIRST_GAP: f32 = 0.74;
/// Each further neighbour's step.
pub const STEP: f32 = 0.2;

/// Smoothstep: no kink at the centre, so the cover leaves flat gently.
fn smooth(t: f32) -> f32 {
    t * t * (3.0 - 2.0 * t)
}

pub fn pose(offset: f32) -> CoverPose {
    let d = offset.abs();
    let side = if offset < 0.0 {
        -1.0
    } else if offset > 0.0 {
        1.0
    } else {
        0.0
    };
    // 0 at the centre, 1 at the first neighbour and beyond: the turn and the shrink ease in over one step.
    let t = d.min(1.0);
    let x = if d <= 1.0 { d * FIRST_GAP } else { FIRST_GAP + (d - 1.0) * STEP };
    CoverPose {
        x: side * x,
        rotation_y: -side * ANGLE * smooth(t),
        scale: 1.0 - (1.0 - SIDE_SCALE) * smooth(t),
        z: -d,
        alpha: (VISIBLE_SIDE + 1.0 - d).clamp(0.0, 1.0),
    }
}

/// Whether a cover at `offset` is worth composing at all.
pub fn visible(offset: f32) -> bool {
    offset.abs() < VISIBLE_SIDE + 1.0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-5
    }

    /// The centre cover: flat, full size, on top, opaque.
    #[test]
    fn the_centre_cover_is_flat() {
        let p = pose(0.0);
        assert!(close(p.x, 0.0));
        assert!(close(p.rotation_y, 0.0), "not turned: {}", p.rotation_y);
        assert!(close(p.scale, 1.0), "full size: {}", p.scale);
        assert!(close(p.z, 0.0));
        assert!(close(p.alpha, 1.0));
    }

    /// The first neighbour sits at exactly the constants, turned and shrunk by the full amount (smoothstep(1) == 1).
    #[test]
    fn the_first_neighbour_hits_the_constants() {
        let p = pose(1.0);
        assert!(close(p.x, FIRST_GAP), "{}", p.x);
        assert!(close(p.rotation_y, -ANGLE), "{}", p.rotation_y);
        assert!(close(p.scale, SIDE_SCALE), "{}", p.scale);
        assert!(close(p.z, -1.0));
        assert!(close(p.alpha, 1.0));
    }

    /// Mirror symmetry: a left-hand cover mirrors its right-hand twin, except that the turn is the other way.
    #[test]
    fn the_two_sides_mirror() {
        for d in [0.25f32, 1.0, 2.0, 3.5] {
            let l = pose(-d);
            let r = pose(d);
            assert!(close(l.x, -r.x), "x mirrors at {d}");
            assert!(close(l.rotation_y, -r.rotation_y), "turn mirrors at {d}");
            assert!(close(l.scale, r.scale));
            assert!(close(l.z, r.z));
            assert!(close(l.alpha, r.alpha));
        }
    }

    /// Beyond the first neighbour the gap grows by STEP, so covers keep separating instead of piling up.
    #[test]
    fn further_covers_step_outwards() {
        let a = pose(2.0).x;
        let b = pose(3.0).x;
        assert!(close(a, FIRST_GAP + STEP), "{}", a);
        assert!(close(b - a, STEP), "step is constant: {}", b - a);
    }

    /// The far edge fades out and then stops being composed at all.
    #[test]
    fn alpha_fades_and_visibility_stops() {
        assert!(close(pose(VISIBLE_SIDE).alpha, 1.0), "still opaque at the last drawn neighbour");
        assert!(pose(VISIBLE_SIDE + 0.5).alpha < 1.0, "fading past it");
        assert!(close(pose(VISIBLE_SIDE + 1.0).alpha, 0.0), "gone one step later");
        assert!(visible(VISIBLE_SIDE + 0.99));
        assert!(!visible(VISIBLE_SIDE + 1.0));
    }

    /// Never a kink: walking the offset moves every value continuously (this is what stops a cover jumping).
    #[test]
    fn the_pose_is_continuous_through_the_centre() {
        let mut prev = pose(-0.5);
        let mut off = -0.5f32;
        while off < 0.5 {
            off += 0.01;
            let now = pose(off);
            assert!((now.x - prev.x).abs() < 0.05, "x jumped at {off}");
            assert!((now.rotation_y - prev.rotation_y).abs() < 5.0, "turn jumped at {off}");
            assert!((now.scale - prev.scale).abs() < 0.05, "scale jumped at {off}");
            prev = now;
        }
    }
}
