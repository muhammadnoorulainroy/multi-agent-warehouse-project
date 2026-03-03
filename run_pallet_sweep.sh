#!/bin/bash
# Pallet announcement strategy sweep: 10 configs × 30 runs each = 300 simulations
# Tests different arrival rates, split/no-split, distributions, and pre-loading

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="results/pallet_sweep"
TEMPLATE="configs/E1_amr5.ini"
mkdir -p "$RESULTS_DIR"

# 10 configs: "name,prob,split,dist,preload"
CONFIGS=(
  "baseline,0.15,true,binomial,0"             # 1: Current default (0.05/entry)
  "nosplit,0.15,false,binomial,0"             # 2: No-split (0.15/entry = 3x faster)
  "highrate,0.30,false,binomial,0"            # 3: High rate no-split
  "preload5,0.15,true,binomial,5"             # 4: Pre-load 5 pallets
  "preload5_nosplit,0.15,false,binomial,5"    # 5: Pre-load + no-split
  "uniform_nosplit,0.15,false,uniform,0"      # 6: Uniform no-split
  "poisson_nosplit,0.15,false,poisson,0"      # 7: Poisson no-split
  "geometric_nosplit,0.15,false,geometric,0"  # 8: Geometric no-split
  "veryhigh,0.50,false,binomial,0"            # 9: Very high burst
  "preload_all,0.15,true,binomial,20"         # 10: All pallets at tick 0
)

RUNS_PER_CONFIG=30
NUM_CONFIGS=${#CONFIGS[@]}
TOTAL=$((NUM_CONFIGS * RUNS_PER_CONFIG))

echo "=== Pallet Announcement Sweep: $NUM_CONFIGS configs × $RUNS_PER_CONFIG runs = $TOTAL total ==="

# CSV header
echo "strategy,prob,split,dist,preload,run,seed,mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked" > "$RESULTS_DIR/pallet_sweep_results.csv"

COUNT=0
for config in "${CONFIGS[@]}"; do
  IFS=',' read -r name prob split dist preload <<< "$config"

  # Generate config file
  CONFIG_FILE="$RESULTS_DIR/sweep_${name}.ini"
  cp "$TEMPLATE" "$CONFIG_FILE"
  echo "arrival_probability = $prob" >> "$CONFIG_FILE"
  echo "split_probability = $split" >> "$CONFIG_FILE"
  echo "arrival_distribution = $dist" >> "$CONFIG_FILE"
  echo "preload_pallets = $preload" >> "$CONFIG_FILE"

  echo ""
  echo "--- Strategy: $name (prob=$prob split=$split dist=$dist preload=$preload) ---"

  for run in $(seq 1 $RUNS_PER_CONFIG); do
    COUNT=$((COUNT + 1))
    SEED=$((100 + run * 13))
    sed -i "s/^seed = .*/seed = $SEED/" "$CONFIG_FILE"

    output=$(./gradlew enhanced -Pconfig="$CONFIG_FILE" --quiet 2>&1)
    csv_line=$(echo "$output" | grep "^CSV," | head -1)

    if [ -n "$csv_line" ]; then
      data=$(echo "$csv_line" | sed 's/^CSV,//')
      echo "$name,$prob,$split,$dist,$preload,$run,$SEED,$data" >> "$RESULTS_DIR/pallet_sweep_results.csv"
      makespan=$(echo "$data" | cut -d',' -f7)
      printf "\r  [$COUNT/$TOTAL] run $run/30  makespan=$makespan"
    else
      echo "$name,$prob,$split,$dist,$preload,$run,$SEED,ERROR,,,,,,,,,," >> "$RESULTS_DIR/pallet_sweep_results.csv"
      printf "\r  [$COUNT/$TOTAL] run $run/30  FAILED"
    fi
  done
  echo ""
done

echo ""
echo "=== Sweep complete: $COUNT runs ==="
echo ""

# Analyze: average per strategy, sorted by avg makespan
echo "strategy,prob,split,dist,preload,avg_makespan,avg_delivery,avg_distance,min_makespan,max_makespan,std_makespan,delivered_pct,runs" > "$RESULTS_DIR/pallet_sweep_summary.csv"

tail -n +2 "$RESULTS_DIR/pallet_sweep_results.csv" | grep -v "ERROR" | \
  awk -F',' '{
    key = $1","$2","$3","$4","$5;
    mk = $14 + 0;
    makespan[key] += mk;
    makespan_sq[key] += mk * mk;
    avg_del[key] += $13 + 0;
    dist[key] += $16 + 0;
    delivered[key] += $10 + 0;
    total_pal[key] += $9 + 0;
    count[key]++;
    if (!(key in mn) || mk < mn[key]) mn[key] = mk;
    if (!(key in mx) || mk > mx[key]) mx[key] = mk;
  }
  END {
    for (k in count) {
      n = count[k];
      avg_mk = makespan[k] / n;
      var = (makespan_sq[k] / n) - (avg_mk * avg_mk);
      std = (var > 0) ? sqrt(var) : 0;
      del_pct = (total_pal[k] > 0) ? 100.0 * delivered[k] / total_pal[k] : 0;
      printf "%s,%.1f,%.1f,%.0f,%d,%d,%.1f,%.1f,%d\n", k, avg_mk, avg_del[k]/n, dist[k]/n, mn[k], mx[k], std, del_pct, n
    }
  }' | sort -t',' -k6 -n | tee -a "$RESULTS_DIR/pallet_sweep_summary.csv"

echo ""
echo "=== RESULTS (sorted by avg makespan) ==="
echo "strategy          prob  split  dist       preload  avg_mk  avg_del  avg_dist  min  max  std   del%  runs"
head -11 "$RESULTS_DIR/pallet_sweep_summary.csv" | tail -10 | column -t -s ','
