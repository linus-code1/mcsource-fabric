# Model builder

Scripts that build MC:Source's rigged models into
`src/client/resources/assets/mcsource/models`:

```sh
python3 hand.py   ../../src/client/resources/assets/mcsource/models/suit_hand.mdl
python3 zombie.py ../../src/client/resources/assets/mcsource/models/zombie.mdl
python3 kart.py   ../../src/client/resources/assets/mcsource/models/kart.mdl
```

Needs Python 3 and numpy. `modelkit.py` holds the shapes, the surface
extraction, bone binding and the `.mdl` writer; `textures.py` paints the
textures. Each model script places bones, sculpts the body from shapes tied
to those bones, and keys its animations as bone rotations per frame.
