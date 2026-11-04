# Next batch: bigger improvements

These came out of the polish pass on 2026-11-04. They are too big, or need a decision, to do as
quick fixes. Small fixes from the same pass are already in the code.

## Needs a decision

1. **Double-tap also fires Ability 1.** The first tap of a double-tap is an ordinary Ability 1
   press, so firing an ultimate also spends Ability 1 (for example, Shadowstep dashes just before
   Execution). One option: while the meter is full, hold Ability 1 back for the double-tap window
   (400ms) and cancel it if a second tap follows. The downside is that Ability 1 then lags by
   400ms whenever the meter is full.
2. **Off-hand passives for the rest of the new twelve.** Earthshaker, Abyssal Trident and Glacial
   Glaive passives work from the off-hand. The other passives only feed their own weapon's
   abilities or meter (Blood Mark, Embers, Stone Skin...), so they would need their own design
   for the off-hand.
3. **Earthshaker Hammer is a real mace.** Vanilla smash damage from falling stacks with its
   12-damage melee and Heavy Impact. Either keep it (it's a mace), or turn smash damage down for
   this weapon.

## Work to do

4. **Test the twelve new weapons on a live server.** They compile and the config check passes,
   but nobody has played them yet. Expect number tuning (cooldowns, damage, the Colossus's health,
   Starfall's density).
5. **Stone Wall after a crash.** Walls crumble on a timer and on plugin disable. If the server
   crashes, the blocks stay. Save the live wall blocks to a small data file and clear them on
   start.
6. **Models for custom_model_data 23-34** (art session). See `docs/NEW_WEAPONS.md`.
7. **Ability previews in the weapon menu.** The weapon menu shows the recipe and the off-hand
   rule. A line per ability (cooldown, the key to press, double-tap for the ultimate) would teach
   the new controls without reading lore.
