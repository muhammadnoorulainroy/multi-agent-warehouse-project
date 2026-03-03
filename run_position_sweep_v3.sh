#!/bin/bash
# Position sweep v3: 10 strategically chosen configs × 30 runs each = 300 simulations
# Focus on optimal placement of charging stations and intermediate areas

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="results/position_sweep_v3"
TEMPLATE="configs/E1_amr5.ini"
mkdir -p "$RESULTS_DIR"

# 10 configs: "ic,rc1,rc2" with strategic reasoning
CONFIGS=(
  "8,4,15"    # 1: Balanced optimal
  "7,3,16"    # 2: Fast relay, wide charging
  "6,4,15"    # 3: Ultra-short relay
  "9,5,14"    # 4: Center cluster
  "8,3,17"    # 5: Maximum spread
  "10,4,16"   # 6: True center relay
  "7,5,14"    # 7: Compact left-biased
  "12,4,16"   # 8: Entry-side relay
  "8,6,14"    # 9: Inner charging
  "6,3,16"    # 10: Exit-optimized
)

RUNS_PER_CONFIG=30
NUM_CONFIGS=${#CONFIGS[@]}
TOTAL=$((NUM_CONFIGS * RUNS_PER_CONFIG))

echo "=== Position Sweep v3: $NUM_CONFIGS configs × $RUNS_PER_CONFIG runs = $TOTAL total ==="

# CSV header
echo "ic,rc1,rc2,run,seed,mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked" > "$RESULTS_DIR/sweep_v3_results.csv"

COUNT=0
for config in "${CONFIGS[@]}"; do
  IFS=',' read -r ic rc1 rc2 <<< "$config"

  # Generate config file once per combo
  CONFIG_FILE="$RESULTS_DIR/sweep_ic${ic}_rc${rc1}_${rc2}.ini"
  cp "$TEMPLATE" "$CONFIG_FILE"
  echo "intermediate_col = $ic" >> "$CONFIG_FILE"
  echo "recharge_col_1 = $rc1" >> "$CONFIG_FILE"
  echo "recharge_col_2 = $rc2" >> "$CONFIG_FILE"

  echo ""
  echo "--- Config ic=$ic rc1=$rc1 rc2=$rc2 ---"

  for run in $(seq 1 $RUNS_PER_CONFIG); do
    COUNT=$((COUNT + 1))
    SEED=$((100 + run * 13))
    sed -i "s/^seed = .*/seed = $SEED/" "$CONFIG_FILE"

    output=$(./gradlew enhanced -Pconfig="$CONFIG_FILE" --quiet 2>&1)
    csv_line=$(echo "$output" | grep "^CSV," | head -1)

    if [ -n "$csv_line" ]; then
      data=$(echo "$csv_line" | sed 's/^CSV,//')
      echo "$ic,$rc1,$rc2,$run,$SEED,$data" >> "$RESULTS_DIR/sweep_v3_results.csv"
      makespan=$(echo "$data" | cut -d',' -f7)
      printf "\r  [$COUNT/$TOTAL] run $run/30  makespan=$makespan"
    else
      echo "$ic,$rc1,$rc2,$run,$SEED,ERROR,,,,,,,,,,," >> "$RESULTS_DIR/sweep_v3_results.csv"
      printf "\r  [$COUNT/$TOTAL] run $run/30  FAILED"
    fi
  done
  echo ""
done

echo ""
echo "=== Sweep complete: $COUNT runs ==="
echo ""

# Analyze: average per config, sorted by avg makespan
echo "ic,rc1,rc2,avg_makespan,avg_delivery,avg_distance,min_makespan,max_makespan,std_makespan,delivered_pct,runs" > "$RESULTS_DIR/sweep_v3_summary.csv"

tail -n +2 "$RESULTS_DIR/sweep_v3_results.csv" | grep -v "ERROR" | \
  awk -F',' '{
    key = $1","$2","$3;
    mk = $12 + 0;
    makespan[key] += mk;
    makespan_sq[key] += mk * mk;
    avg_del[key] += $11 + 0;
    dist[key] += $14 + 0;
    delivered[key] += $8 + 0;
    total_pal[key] += $7 + 0;
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
  }' | sort -t',' -k4 -n | tee -a "$RESULTS_DIR/sweep_v3_summary.csv"

echo ""
echo "=== TOP 10 CONFIGS (by avg makespan) ==="
echo "ic   rc1  rc2  avg_mk  avg_del  avg_dist  min  max  std   del%  runs"
head -11 "$RESULTS_DIR/sweep_v3_summary.csv" | tail -10 | column -t -s ','
