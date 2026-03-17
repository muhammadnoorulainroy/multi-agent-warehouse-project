# Warehouse AMR Multi-Agent Simulation

A Java-based multi-agent simulation of Autonomous Mobile Robots (AMRs) operating in a warehouse environment, built on the [MAQIT simulator framework](lib/maqit-simulator-1.0.jar). The project compares a **reference** (naive) model against an **enhanced** (CNP-coordinated) model across 11 experimental phases.

---

## Requirements

- Java 11+
- Gradle (wrapper included — no installation needed)
- Python 3.8+ (for result analysis and graph generation only)
  - `matplotlib`, `numpy`, `pandas`, `scipy`

---

## Project Structure

```
multi-agent-simulator/
├── build.gradle                   # Gradle build & run tasks
├── warehouse_config.ini           # Main simulation configuration
├── configuration.ini              # Basic simulator config
├── src/main/java/fr/emse/
│   ├── BasicSimulator.java        # Example grid robot simulator
│   └── warehouse/                 # Warehouse AMR simulation
│       ├── WarehouseSimulator.java
│       ├── AMRobot.java
│       ├── WarehouseEnvironment.java
│       ├── EntryArea.java
│       ├── ExitArea.java
│       ├── IntermediateArea.java
│       ├── Human.java
│       ├── Pallet.java
│       └── WarehouseGraphicalWindow.java
├── lib/                           # MAQIT framework JAR
├── configs/                       # Experiment configuration files
│   ├── experiments/               # Phase experiments P0–P10
│   └── experiments_v2/            # Extended phase experiments
├── results/                       # Simulation output (CSV + TXT logs)
│   ├── experiments/
│   ├── experiments_v2/
│   ├── experiment_graphs/
│   └── experiment_graphs_v2/
├── analyze_results.py             # Print statistics from CSV results
├── generate_experiment_graphs_v2.py  # Generate experiment PNG graphs
└── generate_graphs.py             # Generate comparison graphs
```

---

## Building

```bash
./gradlew build
```

On Windows:
```bash
gradlew.bat build
```

---

## Running the Simulation

### Enhanced model (default)

Runs the CNP-coordinated AMR simulation using `warehouse_config.ini`:

```bash
./gradlew enhanced
```

### Reference model (baseline)

Runs the naive baseline simulation:

```bash
./gradlew reference
```

### Custom config file

Pass a path to any `.ini` config file:

```bash
./gradlew enhanced -Pconfig=configs/experiments_v2/P2_pathfinding_astar.ini
./gradlew reference -Pconfig=configs/experiments/P0_baseline_reference.ini
```

### Basic simulator (example)

Runs the `BasicSimulator` demo (uses `configuration.ini`):

```bash
./gradlew run
```

---

## Configuration

The main config file is `warehouse_config.ini`. Key parameters:

```ini
[configuration]
display = 1              # 1 = show GUI, 0 = headless
step = 500               # Number of simulation ticks
waittime = 300           # GUI refresh delay (ms); set to 0 for headless speed
debug = 1                # 1 = verbose console output

[environment]
rows = 15
columns = 20

[warehouse]
total_pallets = 200
arrival_probability = 0.15
arrival_distribution = binomial   # binomial | poisson | uniform | geometric
num_amrs = 5
num_humans = 3
num_obstacles = 5
max_battery = 100
recharge_rate = 5
intermediate_capacity = 5

# Best-performing algorithm choices (from experiments)
pathfinding_mode = astar_diagonal  # bfs | dijkstra | astar | astar_penalties | astar_diagonal
allocation_mode = cnp              # random | greedy | round_robin | least_utilized | cnp
conflict_resolution = priority     # none | random | priority
recharge_threshold = 0.4           # Battery fraction to trigger recharge
relay_strategy = adaptive          # never | always | adaptive

# Explicit area positions (row:col, comma-separated)
entry_positions = 2:18,7:18,12:18
exit_positions = 2:1,12:1
intermediate_positions = 5:7,10:7
recharge_positions = 7:5,7:14
obstacle_positions = 3:6,5:10,8:4,10:12,12:8
```

To run headless (faster, no window):

```ini
display = 0
waittime = 0
```

---

## Running Experiments

### Run all v2 phase experiments (multi-seed)

Requires a Unix shell (Git Bash / WSL on Windows):

```bash
bash run_experiments_v2.sh
```

This runs all configs under `configs/experiments_v2/` across seeds 150–159, writing results to `results/experiments_v2/` and aggregating them into `results/experiments_v2/experiment_summary.csv`.

### Run a single experiment manually

```bash
./gradlew enhanced -Pconfig=configs/experiments_v2/P2_pathfinding_astar.ini
```

Output is printed to stdout in CSV format. Redirect to save:

```bash
./gradlew enhanced -Pconfig=configs/experiments_v2/P2_pathfinding_astar.ini > results/experiments_v2/my_run.txt
```

---

## Analysing Results

### Print statistics table

```bash
python analyze_results.py
```

Reads `results/experiments_v2/experiment_summary.csv` and prints per-phase mean ± std for all metrics.

### Generate graphs

```bash
python generate_experiment_graphs_v2.py
```

Generates PNG graphs for all 11 phases into `results/experiment_graphs_v2/`.

```bash
python generate_graphs.py
```

Generates enhanced vs reference comparison graphs into `results/graphs/`.

---

## Simulation Metrics

Each run outputs the following metrics:

| Metric | Description |
|--------|-------------|
| `delivered` | Number of pallets successfully delivered |
| `avg_delivery` | Mean delivery time per pallet (ticks) |
| `makespan` | Total time until last pallet delivered (ticks) |
| `throughput` | Pallets delivered per tick |
| `total_distance` | Total moves across all AMRs |
| `battery_deaths` | AMRs that ran out of battery |
| `conflicts` | Path/resource conflicts detected |
| `yields` | Conflicts resolved via yielding |
| `relay_drops` | Adaptive relay strategy activations |
| `intermediate_received` | Pallets staged at intermediate areas |

---

## Simulation Modes

### Reference model

- Single AMR assigned per pallet (random allocation)
- BFS pathfinding
- No battery management
- No conflict resolution

### Enhanced model

- CNP (Contract Net Protocol) task allocation
- A* diagonal pathfinding with penalty avoidance
- Priority-based conflict resolution
- Battery-aware recharging with safety margins
- Adaptive relay strategy for long-distance tasks
- Human-obstacle awareness

---

## Experiment Phases

| Phase | Topic |
|-------|-------|
| P0/P1 | Baseline comparison (reference vs enhanced) |
| P2 | Pathfinding algorithm comparison |
| P3 | Task allocation strategy comparison |
| P4 | Conflict resolution strategy comparison |
| P5 | Recharge threshold sensitivity |
| P6 | Relay strategy comparison |
| P7 | CNP parameter tuning (α, β, γ weights) |
| P8 | Ablation study (feature removal) |
| P9 | Stress testing (high load, many AMRs) |
| P10 | Final enhanced vs reference showdown |
