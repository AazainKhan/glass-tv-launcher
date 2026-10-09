# Decisions

The user's settled decisions, so agents don't re-open them. Newest first. One line each: date, decision, why (and where the detail lives). Add a line when the user decides something; change a line only when the user changes their mind.

- 2026-10-09: In the full-screen Top Shelf every card shows its title underneath, whatever the app; Spotify's row shows its playlist and album covers as square cards (its 16:9 free-tier promo is left out). (Top Shelf, CardShape)
- 2026-10-09: Glass edge: the user's rule "if the lens mesh costs too much frame time, keep the stronger rim and drop the mesh" was applied. The drawBitmapMesh lens edge and soft rim glow took perf-gate p90 from 12 to 19 ms, so they were removed; the brighter 1 dp rim stays. Don't re-add a per-frame refraction edge without measuring.
- 2026-10-09: Continuity (Apple rule): anything that moves must move continuously, from where it is to where it goes. No jumps, teleports or snaps mid-motion, including move mode, Control Center over other apps, and every transition. VisualChecks enforces it. (board P20, P21)
- 2026-10-09: Control Center is one glass material: one captured, blurred, dimmed panel bitmap for every tile, live from frame one, revealed by the growing bubble (no fade-in ghosts, no mid-animation swap). The scene must stay visible through the tiles; flat opaque fills were rejected ("looks nothing like liquid glass"). If it costs too much GPU, bring the user numbers and both looks before any fallback. (board P10)
- 2026-10-09: Keep the liquid-bubble Control Center (user spec) rather than Apple's exact pill-stretch motion; make it cheaper, not different. (board P7)
- 2026-10-09: Conventional Commits for every commit. (CLAUDE.md › Commits)
- 2026-10-09: The repo is public at github.com/AazainKhan/glass-tv-launcher. History was rewritten to the noreply email, with crash logs, home paths and the stick serial scrubbed. Every push needs the user's explicit OK, raised by Agent Manager with AskUserQuestion; `scripts/leak-check` must be clean first.
- 2026-10-09: Subagent models: Haiku 5.5 for mechanical work, Sonnet 5.5 for specified implementation, Opus 5.5 for judgement. (CLAUDE.md › Working as an agent)
- 2026-10-07: No focus shimmer: rejected as tacky. Follow the tvos27-guidelines skill and tvos27-inspo frames for design.
- 2026-10-07: Fire OS's own quick menu stays; don't duplicate system controls the user didn't ask for.
- 2026-10-07: Performance budget for the stick lives in perf-budget.json (p90 12 ms, PSS 120 MB, …); scripts/perf-gate enforces it.
