# MAQIT Framework Bugs & Workarounds

This document catalogues all bugs discovered in `maqit-simulator-1.0.jar` (`lib/maqit-simulator-1.0.jar`) during the development of the warehouse AMR simulator, along with the workarounds applied in our codebase.

---

## BUG 1: `ColorSimpleCell` Field-Shadowing — Ghost Cell Content

**Severity:** Critical
**Affected classes:** `ColorSimpleCell`, `SimpleCell`, `ColorGridEnvironment`
**Discovered in:** `WarehouseSimulator.java` (line 1300), `AMRobot.java` (line 810)

### Description

`ColorSimpleCell` declares its own `content` field that **shadows** the inherited `content` field from `SimpleCell`. The inherited method `removeCellContent()` (defined in `SimpleCell` / `GridEnvironment`) only clears `SimpleCell.content`. However, `getContent()` — which is what all callers use — reads from `ColorSimpleCell.content` (the shadowed field).

This means that after a component moves away from a cell:
1. `moveComponent()` calls `removeCellContent(oldX, oldY)`
2. `SimpleCell.content` is set to `null`
3. **`ColorSimpleCell.content` still holds the old component reference**
4. Any subsequent `cell.getContent()` call returns the **stale "ghost" component**

### Impact

- Cells that a robot has vacated still appear occupied when checked via the perception grid (`getNeighbor()` returns cells whose `getContent()` reports ghost robots)
- A* pathfinding and movement checks using the perception grid see phantom obstacles, causing AMRs to stop or reroute when the path ahead is actually clear
- The bug worsens with more robots on the grid (more ghost cells accumulate)
- In experiments, this caused up to **56% performance degradation** (E1_amr12: makespan 594 → 262 after fix) and **delivery failures** (E3_bat50: only 13/20 delivered → 20/20 after fix)

### Workaround

**1. Do NOT use the perception grid for movement decisions** (`AMRobot.isCellFree()`):

```java
// WRONG — perception grid has ghost content from field-shadowing bug:
ColorSimpleCell cell = grid[relX][relY];
if (cell.getContent() instanceof ColorRobot) { return false; }

// CORRECT — use WarehouseEnvironment position tracker (authoritative):
if (warehouseEnv.isOccupiedByRobot(pos, getId())) { return false; }
```

We maintain our own `Map<Integer, int[]> robotPositions` and `Map<String, int[]> humanPositions` in `WarehouseEnvironment` as the single source of truth for occupancy checks. These are updated after each successful grid move.

**2. Replace cell when removing a component permanently** (`WarehouseSimulator.java`, line 1305):

```java
// When a reference-model AMR vanishes after delivery, replace the cell entirely
// to clear the ghost content in the shadowed field:
grid[actualPos[0]][actualPos[1]] = new ColorSimpleCell();
```

**Files with workaround:** `AMRobot.java` (isCellFree), `WarehouseSimulator.java` (AMR removal)

---

## BUG 2: `moveComponent()` Silent Failure

**Severity:** High
**Affected class:** `ColorGridEnvironment`
**Discovered in:** `WarehouseSimulator.java` (line 921)

### Description

`ColorGridEnvironment.moveComponent(int oldX, int oldY, int newX, int newY)` does **not throw an exception or return a status code** when the move fails (e.g., destination cell is already occupied). It simply does nothing — the component stays at the old position, but the caller has no way of knowing the move failed.

Since our robots use `setLocation()` to update their logical position **before** calling `moveComponent()` on the grid, a silent failure creates a **desynchronization** between the robot's internal state (thinks it moved) and the grid state (still at old position).

### Impact

- Robot's logical position diverges from its actual grid position
- Subsequent A* pathfinding uses the wrong start position
- Other robots' perception grids show the robot at its old position, but its `robotPositions` tracker shows the new position — inconsistent collision detection
- Can cascade into further movement failures and deadlocks

### Workaround

**Post-move verification and revert** (`WarehouseSimulator.syncAMRToGrid()`):

```java
private void syncAMRToGrid(AMRobot amr, int[] gridPos, ColorSimpleCell[][] grid) {
    int[] newPos = amr.getLocation();
    if (gridPos != null && (gridPos[0] != newPos[0] || gridPos[1] != newPos[1])) {
        // Attempt the grid move
        this.environment.moveComponent(gridPos[0], gridPos[1], newPos[0], newPos[1]);

        // Verify it actually worked by checking grid content
        ColorSimpleCell destCell = grid[newPos[0]][newPos[1]];
        if (destCell == null || destCell.getContent() != amr) {
            // moveComponent FAILED — revert robot's internal state
            amr.revertLastMove(gridPos);
        }
    }
}
```

**Pre-move grid sync** (start of each tick):

```java
// Find where the component actually is on the grid (ground truth)
int[] gridPos = findComponentOnGrid(amr, grid);
if (gridPos != null) {
    amr.syncToGridPosition(gridPos);  // correct any prior desync
}
```

The same pattern is applied to Human workers in `moveHumans()`.

**Files with workaround:** `WarehouseSimulator.java` (syncAMRToGrid, moveAMRs, moveHumans), `AMRobot.java` (revertLastMove, syncToGridPosition), `Human.java` (syncToGridPosition)

---

## BUG 3: `removeCellContent()` Does Not Fully Clear Cell (related to BUG 1)

**Severity:** Medium
**Affected class:** `ColorGridEnvironment` / `GridEnvironment`
**Discovered in:** `WarehouseSimulator.java` (line 1300)

### Description

When calling `environment.removeCellContent(x, y)` to permanently remove a component from the grid (e.g., when a reference-model AMR vanishes after delivery), the cell is not fully cleared due to the field-shadowing issue described in BUG 1. The `removeCellContent()` method clears `SimpleCell.content` but leaves `ColorSimpleCell.content` intact.

This is a specific manifestation of BUG 1, but affects the **permanent removal** case rather than just movement.

### Impact

- After an AMR is removed from the simulation, its ghost remains on the grid cell
- Other AMRs' perception grids still show a robot at that position
- The ghost persists indefinitely (unlike movement ghosts which are eventually overwritten when a new component occupies the cell)

### Workaround

Replace the entire cell object with a fresh `ColorSimpleCell`:

```java
// Instead of:  environment.removeCellContent(x, y);
// Use:
grid[actualPos[0]][actualPos[1]] = new ColorSimpleCell();
```

**Files with workaround:** `WarehouseSimulator.java` (AMR removal after delivery in reference model)

---

## Summary Table

| Bug | Class | Severity | Symptom | Workaround |
|-----|-------|----------|---------|------------|
| Field-shadowing ghost cells | `ColorSimpleCell` | Critical | Vacated cells still appear occupied | Use position tracker instead of perception grid |
| `moveComponent()` silent failure | `ColorGridEnvironment` | High | Grid/logic position desync | Post-move verification + revert on failure |
| `removeCellContent()` incomplete | `ColorGridEnvironment` | Medium | Permanently removed components leave ghosts | Replace cell with `new ColorSimpleCell()` |

---

## Recommendations

If the MAQIT framework source becomes available for modification:

1. **Fix the field shadowing**: `ColorSimpleCell` should NOT declare its own `content` field. It should use the inherited field from `SimpleCell`, or override `getContent()`/`setContent()` to use a single field consistently.

2. **Make `moveComponent()` return a boolean**: `true` if the move succeeded, `false` if the destination was occupied. This eliminates the need for post-move grid scanning.

3. **Fix `removeCellContent()` to clear all content fields**: Or better yet, eliminate the shadowed field so there is only one field to clear.
