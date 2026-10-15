Last week I shared how ZnKeyboard went from missing keys to a 1.3% simulated error rate. This week I fixed something less serious that still bugged me every time I opened the app: the settings screen was boring.

It had a stock top bar and a stock bottom navigation bar. They worked fine and had no personality. I wanted floating pills where the selected tab pulls away from the group like a drop of liquid pinching off, and merges back when you leave it.

So I went looking for the Android API that does this. There isn't one.

Android can blur things, morph one shape into another, and run custom GPU shaders. Nothing splits one shape into two the way a liquid does. Shape morphing only works when there's one outline at the start and one at the end. A drop pinching off goes from one shape to two.

So, like last time, I treated it as a science project with AI as my study partner.

1. Understand the real thing first
Before drawing anything we read how liquid actually pinches off: the Plateau–Rayleigh instability, Eggers' work on how a liquid neck thins, Day, Hinch & Lister on the final moments before it breaks, and Eggers, Lister & Stone on how two drops merge. Two ideas stuck with me:
- A neck doesn't thin at a steady rate. It speeds up right before it snaps.
- When drops merge, the bridge between them grows very fast at first, then slows down.

There was also a gift from physics: in 2D, a liquid surface at rest is made of circular arcs. That meant the neck could be drawn exactly with simple geometry.

2. Compare the options before building
We put five approaches side by side: metaballs, smooth-blended shapes, the blur-and-threshold "gooey" trick, hand-built curves, and Android's shape morphing. Each had a deal-breaker. Blur makes edges fuzzy. Smooth blending puts a small bump wherever two pills touch. Morphing can't split one shape into two.

3. Build our own engine
- A small physics core in plain Kotlin: springs, the neck, and the moment it snaps or reconnects. It has no Android code, so I can test it on my laptop.
- A GPU shader that draws the pills and their necks with crisp edges at 120 Hz.
- Icons and labels sit in their own layer above, so they stay sharp.
- While the neck holds, it pulls the departing pill back, so it stretches before it lets go. When it snaps, the pill springs free with a small wobble.

4. Prototype, then verify
I tuned the motion in a browser prototype that runs the same engine and shader, frame by frame. Then automated checks on the real engine code confirmed that:
- The motion is identical on 60, 90 and 120 Hz screens.
- Tapping another tab mid-animation continues smoothly from wherever it is.
- Every transition settles in under half a second.
- If you turn animations off in system settings, it jumps straight to the end.

What I learned along the way:
- When the platform doesn't have it, the answer is often a small, focused engine rather than a big library.
- Physics is a great design tool. "Make it feel like liquid" became concrete rules I could test.
- AI was again at its best as a research partner: finding the papers, explaining the math, and pushing back when my first idea had a flaw.
- A browser prototype that runs the same engine is the fastest way to tune how motion feels.

The physics core is under 400 lines and allocates no memory per frame. Watching a tab pinch off like a water drop never gets old.

ZnKeyboard is open source (GPL-3.0). The engine, prototype and verification code are in the repo:
https://lnkd.in/dSKUA5jX

#AndroidDev #JetpackCompose #Kotlin #AI #OpenSource #LearningInPublic
