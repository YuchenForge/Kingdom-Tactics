# Kingdom Tactics Board

## Coordinate Convention

All positions use **`(x, y)`** where:

- **`x`** = column (horizontal, `0` = left)
- **`y`** = row (vertical, `0` = top on the placement board; `0` = top of the merged combat board)

This applies to **local** placement coordinates and **global** combat coordinates.

## Local Placement Board (per player)

Each player has a **4 × 4** board. Local coordinates: `x ∈ [0, 3]`, `y ∈ [0, 3]`.

```
        x=0     x=1     x=2     x=3
      +-------+-------+-------+-------+
y=0   | (0,0) | (1,0) | (2,0) | (3,0) |
      +-------+-------+-------+-------+
y=1   | (0,1) | (1,1) | (2,1) | (3,1) |
      +-------+-------+-------+-------+
y=2   | (0,2) | (1,2) | (2,2) | (3,2) |
      +-------+-------+-------+-------+
y=3   | (0,3) | (1,3) | (2,3) | (3,3) |
      +-------+-------+-------+-------+
```

## Global Combat Board

The game board is a **4 × 8** grid formed by merging both placement boards.

- Global coordinates: **`(x, y)`** — `x ∈ [0, 3]`, `y ∈ [0, 7]`
- Player 0 (top half, `y = 0–3`): board rotated **180°** before merge
- Player 1 (bottom half, `y = 4–7`): unchanged

```
                    Top Player (Rotated 180°)

         x=0         x=1         x=2         x=3
      +-----------+-----------+-----------+-----------+
y=0   | T(3,3)    | T(2,3)    | T(1,3)    | T(0,3)    |
      +-----------+-----------+-----------+-----------+
y=1   | T(3,2)    | T(2,2)    | T(1,2)    | T(0,2)    |
      +-----------+-----------+-----------+-----------+
y=2   | T(3,1)    | T(2,1)    | T(1,1)    | T(0,1)    |
      +-----------+-----------+-----------+-----------+
y=3   | T(3,0)    | T(2,0)    | T(1,0)    | T(0,0)    |
      +===========+===========+===========+===========+
y=4   | B(0,0)    | B(1,0)    | B(2,0)    | B(3,0)    |
      +-----------+-----------+-----------+-----------+
y=5   | B(0,1)    | B(1,1)    | B(2,1)    | B(3,1)    |
      +-----------+-----------+-----------+-----------+
y=6   | B(0,2)    | B(1,2)    | B(2,2)    | B(3,2)    |
      +-----------+-----------+-----------+-----------+
y=7   | B(0,3)    | B(1,3)    | B(2,3)    | B(3,3)    |
      +-----------+-----------+-----------+-----------+
```

Cell labels show each player's **local `(x, y)`** at that global position.

## Coordinate Mapping

### Bottom Player (Player 1)

```
Local (x, y) → Global (x, y + 4)
```

### Top Player (Player 0)

```
Local (x, y) → Global (3 - x, 3 - y)
```

### Inverse (global → local)

| Player | Global `(x, y)` | Local `(x, y)` |
|--------|-----------------|----------------|
| Top | `y ∈ [0, 3]` | `(3 - x, 3 - y)` |
| Bottom | `y ∈ [4, 7]` | `(x, y - 4)` |

## Examples

| Player | Local `(x, y)` | Global `(x, y)` |
|--------|----------------|-----------------|
| Top | (0, 0) | (3, 3) |
| Top | (0, 3) | (3, 0) |
| Top | (2, 0) | (1, 3) |
| Top | (3, 3) | (0, 0) |
| Bottom | (0, 0) | (0, 4) |
| Bottom | (1, 2) | (1, 6) |
| Bottom | (3, 0) | (3, 4) |
| Bottom | (3, 3) | (3, 7) |

## Browser orientation

The browser presents combat as a landscape 8 × 4 board and adjusts orientation for the viewing seat. This is a display transform: persisted events and API coordinates remain the engine's 4 × 8 global grid described above. Never send display coordinates directly as planning coordinates.

## Storage Indices

Row-major flat index: `index = y * 4 + x`

- Placement board: `index ∈ [0, 15]`
- Combat board: `index ∈ [0, 31]`
