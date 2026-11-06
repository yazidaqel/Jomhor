# Mockups

- `html/index.html` — all ten screens (6 phone, 2 web shop, 2 back-office); open in a browser. Each screen is a
  standalone page at 390×844 with the placeholder identity baked in (ATLAS FC, amber).
- `src/*.dc.html` + `src/canvas.json` — editable sources from the Claude Design canvas
  (https://claude.ai/artifact/RZdBe8HCqJUDBhB61S55mt). They need the canvas runtime to
  render; use `html/` to view, `src/` to read structure and copy.
- Links between screens follow the purchase flow: accueil → match → zones → paiement → billet.

Screen → spec mapping is in `../DESIGN.md` §Screen inventory.
