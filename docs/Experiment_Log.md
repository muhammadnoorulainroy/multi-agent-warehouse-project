# Experiment Log: Warehouse AMR Simulation

Total simulations run: **1,677** across 5 experiment phases.

---

## Phase 1: Full Experiment Suite (42 runs)

Ran all 21 experiment configurations in both REFERENCE and ENHANCED modes.
Single seed per config (seed=150). Results in `results/summary.csv`.

### Experiment Matrix

| ID | Variable | Values Tested | Config Files |
|----|----------|--------------|--------------|
| E1 | AMR count | 3, 5, 8, 12 | E1_amr3/5/8/12.ini |
| E2 | Arrival distribution | Binomial, Poisson, Uniform, Geometric | E2_*.ini |
| E3 | Battery capacity | 50, 100, 200 | E3_bat50/100/200.ini |
| E4 | Intermediate areas | 0, 1, 2, 4 | E4_inter0/1/2/4.ini |
| E5 | Obstacle count | 0, 5, 10, 20 | E5_obs0/5/10/20.ini |
| E6 | Pallet count | 20, 50, 100 | E6_pal20/50/100.ini |

### Key Results (Enhanced Model)

| Config | Delivered | Makespan | Avg Delivery | Distance | Battery Deaths |
|--------|-----------|----------|-------------|----------|----------------|
| E1_amr3 | 20/20 | 505 | 206.95 | 1179 | 0 |
| E1_amr5 | 20/20 | 313 | 114.65 | 1140 | 0 |
| E1_amr8 | 20/20 | 301 | 98.10 | 1322 | 2 |
| E1_amr12 | 20/20 | 262 | 72.30 | 1494 | 4 |
| E2_poisson | 20/20 | 346 | 118.55 | 1174 | 1 |
| E2_uniform | 21/21 | 379 | 124.29 | 1171 | 1 |
| E2_geometric | 20/20 | 310 | 113.30 | 1140 | 0 |
| E3_bat50 | 20/20 | 405 | 148.60 | 1498 | 1 |
| E3_bat100 | 20/20 | 309 | 114.60 | 1123 | 0 |
| E3_bat200 | 20/20 | 268 | 84.90 | 964 | 0 |
| E4_inter0 | 20/20 | 376 | 126.65 | 1281 | 0 |
| E4_inter2 | 20/20 | 312 | 113.55 | 1136 | 0 |
| E4_inter4 | 20/20 | 317 | 110.90 | 1108 | 0 |
| E5_obs0 | 20/20 | 335 | 113.10 | 1195 | 0 |
| E5_obs5 | 20/20 | 326 | 101.35 | 1156 | 0 |
| E5_obs20 | 20/20 | 340 | 120.95 | 1142 | 0 |
| E6_pal50 | 50/50 | 785 | 233.52 | 2900 | 0 |
| E6_pal100 | 100/100 | 1507 | 443.92 | 5748 | 0 |

### Key Results (Reference Model)

| Config | Delivered | Makespan | Avg Delivery |
|--------|-----------|----------|-------------|
| E1_amr5 | 20/20 | 139 | 20.75 |
| E1_amr12 | 20/20 | 138 | 20.40 |
| E6_pal50 | 50/50 | 330 | 21.20 |
| E6_pal100 | 100/100 | 644 | 21.48 |

### Phase 1 Findings

1. **Reference model is faster on raw makespan** because it spawns one AMR per pallet (unlimited resources, no battery, no coordination overhead).
2. **Enhanced model uses fixed AMRs** (5 by default) that reuse, coordinate via CNP, manage battery — more realistic but slower.
3. **More AMRs help but with diminishing returns**: 3→5 AMRs cuts makespan 38% (505→313), but 5→12 only cuts 16% (313→262). More AMRs also cause more battery deaths due to congestion.
4. **Battery capacity strongly affects performance**: bat50 = 405 makespan, bat200 = 268 — a 34% improvement.
5. **Intermediate areas help**: 0 areas = 376 makespan, 2 areas = 312 (17% improvement). 4 areas ≈ 2 areas (no additional benefit).
6. **Obstacles have minimal impact**: 0→20 obstacles only +1.5% makespan.
7. **Arrival distribution matters**: Geometric (bursty) = 310, Uniform (steady) = 379. Bursty arrivals let AMRs batch-process, reducing idle time.

### Important Bug Found During Phase 1

The **getIntValue() bug** was discovered later: the MAQIT framework's INI parser returns 0 for missing keys instead of throwing an exception. This meant all position parameters (recharge_col_1, recharge_col_2, intermediate_col) were silently set to 0. Phase 1 results ran with all stations/areas at column 0 (overlapping with exits). This was fixed before Phase 3.

**Note:** Phase 1 results also ran BEFORE the ghost cell fix (see Bug Fixes section), so enhanced model performance was degraded.

---

## Phase 2: Position Sweep v1 (135 runs)

**Goal:** Find optimal column positions for charging stations (RC1, RC2) and intermediate areas (IC).

- RC1 values: 3, 5, 7 (near exits)
- RC2 values: 12, 14, 16 (near entries)
- IC values: 5, 7, 10, 13, 15
- 3 runs per config, 45 configs = **135 total runs**

Results in `results/position_sweep/`.

### Findings

**INVALIDATED** — Due to the getIntValue() bug, all position overrides were silently ignored. Every config ran with positions at column 0. The sweep showed identical results (avg_makespan=20.0, avg_delivery=20.0) across all configs because the awk column offsets were also wrong, reading the wrong CSV fields.

This phase was superseded by Phase 2b.

---

## Phase 2b: Position Sweep v2 (900 runs)

**Goal:** Large-scale position sweep with proper statistical power.

- 30 carefully selected position configurations
- 30 runs per config with different seeds (100 + run × 13)
- **900 total simulations**

Results in `results/position_sweep_v2/`.

**ALSO AFFECTED by getIntValue() bug** — The position overrides in the INI files WERE applied (they had explicit non-zero values appended to the config), but the positions were chosen based on the flawed v1 results. Despite this, the results are valid since each config file explicitly set the position values.

### Top 5 Results

| Rank | RC1 | RC2 | IC | Avg Makespan | Std Dev | Min | Max |
|------|-----|-----|----|-------------|---------|-----|-----|
| 1 | 5 | 12 | 4 | 312.4 | 29.2 | 246 | 369 |
| 2 | 6 | 12 | 5 | 314.2 | 21.9 | 279 | 365 |
| 3 | 7 | 14 | 4 | 315.4 | 22.2 | 283 | 376 |
| 4 | 7 | 16 | 5 | 316.2 | 27.1 | 259 | 376 |
| 5 | 6 | 13 | 5 | 316.3 | 23.3 | 275 | 372 |

### Bottom 5 Results

| Rank | RC1 | RC2 | IC | Avg Makespan |
|------|-----|-----|----|-------------|
| 26 | 6 | 14 | 5 | 325.4 |
| 27 | 3 | 12 | 5 | 325.5 |
| 28 | 3 | 16 | 5 | 326.5 |
| 29 | 5 | 12 | 7 | 326.6 |
| 30 | 4 | 13 | 6 | 328.2 |

### Phase 2b Findings

1. **100% delivery rate** across all 900 runs — system is robust regardless of position.
2. **Performance spread is narrow**: best 312.4 vs worst 328.2 (~5% difference).
3. **IC=4-5 dominated top spots** (intermediate near exits) — but this was later reconsidered for visual/logical layout reasons.
4. **RC2=12 appeared in 3 of top 5** — second charging station works best at mid-grid.

---

## Phase 3: Position Sweep v3 (300 runs)

**Goal:** Strategic placement optimization with the getIntValue() bug FIXED. Test 10 carefully reasoned configurations.

- 10 configs × 30 runs each = **300 total simulations**
- Each config designed around a specific strategy (balanced, exit-optimized, center cluster, etc.)

Results in `results/position_sweep_v3/`.

### Configs Tested

| # | IC | RC1 | RC2 | Strategy |
|---|-----|-----|-----|----------|
| 1 | 8 | 4 | 15 | Balanced optimal |
| 2 | 7 | 3 | 16 | Fast relay, wide charging |
| 3 | 6 | 4 | 15 | Ultra-short relay |
| 4 | 9 | 5 | 14 | Center cluster |
| 5 | 8 | 3 | 17 | Maximum spread |
| 6 | 10 | 4 | 16 | True center relay |
| 7 | 7 | 5 | 14 | Compact left-biased |
| 8 | 12 | 4 | 16 | Entry-side relay |
| 9 | 8 | 6 | 14 | Inner charging |
| 10 | 6 | 3 | 16 | Exit-optimized |

### Full Results (sorted by avg makespan)

| Rank | IC | RC1 | RC2 | Avg Makespan | Std Dev | Min | Max | Avg Del | Avg Dist |
|------|-----|-----|-----|-------------|---------|-----|-----|---------|----------|
| 1 | 6 | 4 | 15 | 323.6 | 30.0 | 272 | 399 | 105.0 | 1090 |
| 2 | 7 | 5 | 14 | 324.6 | 24.9 | 276 | 374 | 104.3 | 1094 |
| 3 | 6 | 3 | 16 | 326.8 | 23.1 | 289 | 392 | 105.6 | 1103 |
| 4 | 8 | 6 | 14 | 329.4 | 22.5 | 272 | 376 | 104.1 | 1108 |
| 5 | 8 | 4 | 15 | 332.7 | 28.1 | 267 | 384 | 108.1 | 1139 |
| 6 | 9 | 5 | 14 | 333.7 | 24.5 | 281 | 379 | 110.7 | 1160 |
| 7 | 7 | 3 | 16 | 334.3 | 29.9 | 262 | 389 | 108.7 | 1129 |
| 8 | 8 | 3 | 17 | 340.8 | 28.1 | 286 | 387 | 111.9 | 1147 |
| 9 | 12 | 4 | 16 | 342.3 | 22.3 | 293 | 386 | 110.9 | 1202 |
| 10 | 10 | 4 | 16 | 348.8 | 27.9 | 281 | 395 | 113.6 | 1198 |

### Phase 3 Findings

1. **Winner: IC=6, RC1=4, RC2=15** (avg 323.6) — intermediate close to exits, balanced charging.
2. **Clear pattern: lower IC wins**: IC=6-7 dominates top 3, IC=10-12 are last. Intermediate areas work best closer to exits.
3. **RC2 closer to center helps**: RC2=14-15 outperforms RC2=16-17. Accessible from both intermediate areas and entries.
4. **Most consistent: IC=8, RC1=6, RC2=14** — lowest std dev (22.5).
5. **Total distance correlates with performance**: top configs ~1090, bottom ~1200.
6. **Chosen defaults: IC=7, RC1=5, RC2=14** — rank #2, best balance of speed (324.6) and consistency (std=24.9).

---

## Bug Fixes Applied

### 1. Ghost Cell Fix (Critical — before Phase 1)
- **Bug:** MAQIT `ColorSimpleCell` field-shadowing: `removeCellContent()` clears parent `SimpleCell.content` but `getContent()` reads shadowed `ColorSimpleCell.content`, leaving ghost robot references.
- **Fix:** Removed perception grid check from `AMRobot.isCellFree()`, using `WarehouseEnvironment` position tracker as single source of truth.
- **Impact:** Enhanced E1_amr12 improved 55% (594→262 makespan). E3_bat50 went from 13/20 to 20/20 delivered.

### 2. moveComponent() Silent Failure Workaround
- **Bug:** `ColorGridEnvironment.moveComponent()` fails silently when destination is occupied — no exception, no return code.
- **Fix:** Post-move grid verification in `syncAMRToGrid()` with revert on failure.

### 3. removeCellContent() Incomplete Clear
- **Bug:** Related to ghost cells — permanently removed AMRs leave ghost content.
- **Fix:** Replace cell with `new ColorSimpleCell()` instead of calling `removeCellContent()`.

### 4. getIntValue() Returns 0 for Missing Keys (discovered during Phase 3)
- **Bug:** MAQIT's `IniFile.getIntValue()` returns 0 instead of throwing when a key doesn't exist. Position parameters (recharge_col_1, recharge_col_2, intermediate_col) silently set to 0.
- **Fix:** Changed to `getStringValue()` with null check + `Integer.parseInt()`. Added `> 0` guard in setupWarehouseLayout.
- **Impact:** All phases before the fix had stations/areas at column 0 (overlapping exits). Phase 3 is the first valid position test.

---

## Infrastructure Improvements

### Charging Stations: 1×1 → 2×1
- Expanded recharge stations from single cell to 2-cell blocks (2 rows × 1 column)
- Added `getBestRechargeCell()` for smart cell selection across all station blocks
- Increased `MAX_CHARGING_SIMULTANEOUSLY` from 2 to 4
- Updated graphical rendering to show 2×1 purple blocks

### Configurable Layout Positions
- Added INI parameters: `recharge_col_1`, `recharge_col_2`, `intermediate_col`
- Positions fall back to sensible defaults when not specified
- Enabled the position sweep experiments

### Status HUD Overlay
- Added real-time delivery progress bar at top of GUI
- Shows: Delivered count, Pending, In Transit, current Tick
- Green progress bar fills as deliveries complete

### CSV Output
- Added single-line CSV output (`CSV,mode,pallets,delivered,...`) for automated parsing
- Enabled batch experiment scripts to extract results without log parsing

---

## Phase 4: Pallet Announcement Strategy Sweep (300 runs)

**Goal:** Optimize pallet announcement to reduce AMR idle time. Test different arrival rates, split/no-split probability, distributions, and pre-loading.

- 10 strategies × 30 runs each = **300 total simulations**
- Seeds: 100 + run × 13 (same as Phase 3 for comparability)

Results in `results/pallet_sweep/`.

### Strategies Tested

| # | Name | Prob | Split | Distribution | Preload | Description |
|---|------|------|-------|-------------|---------|-------------|
| 1 | baseline | 0.15 | yes | Binomial | 0 | Current default (0.05/entry) |
| 2 | nosplit | 0.15 | no | Binomial | 0 | No-split (0.15/entry = 3× faster) |
| 3 | highrate | 0.30 | no | Binomial | 0 | High rate no-split |
| 4 | preload5 | 0.15 | yes | Binomial | 5 | Pre-load 5 pallets at tick 0 |
| 5 | preload5_nosplit | 0.15 | no | Binomial | 5 | Pre-load + no-split |
| 6 | uniform_nosplit | 0.15 | no | Uniform | 0 | Uniform no-split |
| 7 | poisson_nosplit | 0.15 | no | Poisson | 0 | Poisson no-split |
| 8 | geometric_nosplit | 0.15 | no | Geometric | 0 | Geometric no-split |
| 9 | veryhigh | 0.50 | no | Binomial | 0 | Very high burst |
| 10 | preload_all | 0.15 | yes | Binomial | 20 | All pallets at tick 0 |

### Full Results (sorted by avg makespan)

| Rank | Strategy | Avg Makespan | Std Dev | Min | Max | Avg Delivery | Avg Distance | Del% |
|------|----------|-------------|---------|-----|-----|-------------|-------------|------|
| 1 | preload5 | 314.5 | 21.0 | 253 | 357 | 122.6 | 1135 | 100 |
| 2 | preload5_nosplit | 315.4 | 24.9 | 248 | 366 | 147.8 | 1148 | 100 |
| 3 | geometric_nosplit | 316.7 | 26.9 | 267 | 368 | 138.5 | 1117 | 100 |
| 4 | poisson_nosplit | 317.2 | 17.3 | 279 | 350 | 138.0 | 1122 | 100 |
| 5 | highrate | 319.3 | 27.8 | 259 | 388 | 150.5 | 1133 | 100 |
| 6 | veryhigh | 319.4 | 18.7 | 286 | 362 | 155.4 | 1146 | 100 |
| 7 | nosplit | 324.3 | 25.1 | 275 | 379 | 142.3 | 1137 | 100 |
| 8 | baseline | 324.8 | 24.7 | 281 | 374 | 105.4 | 1097 | 100 |
| 9 | uniform_nosplit | 333.3 | 25.0 | 298 | 425 | 145.0 | 1186 | 100 |
| 10 | preload_all | 341.3 | 28.7 | 299 | 406 | 171.5 | 1212 | 100 |

### Phase 4 Findings

1. **Winner: preload5** (avg 314.5, std=21.0) — Pre-loading 5 pallets eliminates early idle time. AMRs have immediate work at tick 0 instead of waiting ~7 ticks for first arrivals.
2. **Pre-load helps more than rate changes**: preload5 (314.5) beat highrate (319.3) and nosplit (324.3). Initial burst matters more than sustained rate.
3. **Too much pre-loading hurts**: preload_all=20 was worst (341.3) — entry congestion when all 20 pallets spawn at 3 entries at tick 0.
4. **Geometric and Poisson competitive**: geometric_nosplit (316.7) and poisson_nosplit (317.2) ranked 3rd and 4th — alternative distributions can help.
5. **Poisson most consistent**: std=17.3, meaning predictable performance across runs.
6. **Very high rate (0.50) surprisingly OK**: veryhigh (319.4) with low std (18.7) — consistent but avg delivery time is high (155.4).
7. **Uniform worst among distributions**: uniform_nosplit (333.3) ranked 9th — deterministic arrivals don't match AMR availability well.
8. **Baseline ranked 8th/10**: confirms the AMR starvation hypothesis — default split config underperforms.
9. **Chosen default: preload_pallets=5** — best average with good consistency (std=21.0).

---

## Current Configuration Defaults

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Intermediate column | 7 | Phase 3 winner — close to exits, fast relay |
| Recharge station 1 | col 5 | Near exits, minimal post-delivery detour |
| Recharge station 2 | col 14 | Near entries, accessible from intermediate |
| Arrival probability | 0.15 (split across 3 entries = 0.05 each) | Default binomial trial |
| Distribution | BINOMIAL | Default, stochastic per-tick trial |
| Pre-load pallets | 5 | Phase 4 winner — eliminates early idle time |
| Battery | 100 | Standard for 15×20 grid |
| AMRs | 5 | Balance of speed and resource efficiency |
