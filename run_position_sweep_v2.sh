#!/bin/bash
# Position sweep v2: 30 configs × 30 runs each = 900 simulations
# Picks top candidates from v1 sweep + new exploratory combos

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="results/position_sweep_v2"
TEMPLATE="configs/E1_amr5.ini"
mkdir -p "$RESULTS_DIR"

# 30 position configurations: "rc1,rc2,ic"
# Top performers from v1 sweep (ic=5 dominated)
# + wider exploration of charging column positions
# + a few edge cases
CONFIGS=(
  "5,12,5"
  "7,14,5"
  "7,12,5"
  "5,14,5"
  "7,12,7"
  "5,16,5"
  "3,14,5"
  "7,16,5"
  "3,12,10"
  "5,12,7"
  "4,13,5"
  "6,13,5"
  "5,13,5"
  "6,12,5"
  "4,12,5"
  "6,14,5"
  "4,14,5"
  "5,12,4"
  "5,12,6"
  "7,14,4"
  "7,14,6"
  "5,13,4"
  "6,13,4"
  "4,13,6"
  "6,12,6"
  "3,16,5"
  "5,14,7"
  "7,14,7"
  "3,12,5"
  "7,16,7"
)

RUNS_PER_CONFIG=30
NUM_CONFIGS=${#CONFIGS[@]}
TOTAL=$((NUM_CONFIGS * RUNS_PER_CONFIG))

echo "=== Position Sweep v2: $NUM_CONFIGS configs × $RUNS_PER_CONFIG runs = $TOTAL total ==="

# CSV header
echo "rc1,rc2,ic,run,seed,mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked" > "$RESULTS_DIR/sweep_v2_results.csv"

COUNT=0
for config in "${CONFIGS[@]}"; do
  IFS=',' read -r rc1 rc2 ic <<< "$config"

  # Generate config file once per combo
  CONFIG_FILE="$RESULTS_DIR/sweep_rc${rc1}_${rc2}_ic${ic}.ini"
  cp "$TEMPLATE" "$CONFIG_FILE"
  echo "recharge_col_1 = $rc1" >> "$CONFIG_FILE"
  echo "recharge_col_2 = $rc2" >> "$CONFIG_FILE"
  echo "intermediate_col = $ic" >> "$CONFIG_FILE"

  echo ""
  echo "--- Config rc1=$rc1 rc2=$rc2 ic=$ic ---"

  for run in $(seq 1 $RUNS_PER_CONFIG); do
    COUNT=$((COUNT + 1))
    SEED=$((100 + run * 13))
    sed -i "s/^seed = .*/seed = $SEED/" "$CONFIG_FILE"

    output=$(./gradlew enhanced -Pconfig="$CONFIG_FILE" --quiet 2>&1)
    csv_line=$(echo "$output" | grep "^CSV," | head -1)

    if [ -n "$csv_line" ]; then
      data=$(echo "$csv_line" | sed 's/^CSV,//')
      echo "$rc1,$rc2,$ic,$run,$SEED,$data" >> "$RESULTS_DIR/sweep_v2_results.csv"
      makespan=$(echo "$data" | cut -d',' -f7)
      printf "\r  [$COUNT/$TOTAL] run $run/30  makespan=$makespan"
    else
      echo "$rc1,$rc2,$ic,$run,$SEED,ERROR,,,,,,,,,,,," >> "$RESULTS_DIR/sweep_v2_results.csv"
      printf "\r  [$COUNT/$TOTAL] run $run/30  FAILED"
    fi
  done
  echo ""
done

echo ""
echo "=== Sweep complete: $COUNT runs ==="
echo ""

# Analyze: average per config, sorted by avg makespan
echo "rc1,rc2,ic,avg_makespan,avg_delivery,avg_distance,min_makespan,max_makespan,std_makespan,delivered_pct,runs" > "$RESULTS_DIR/sweep_v2_summary.csv"

tail -n +2 "$RESULTS_DIR/sweep_v2_results.csv" | grep -v "ERROR" | \
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
  }' | sort -t',' -k4 -n | tee -a "$RESULTS_DIR/sweep_v2_summary.csv"

echo ""
echo "=== TOP 10 CONFIGS (by avg makespan) ==="
echo "rc1  rc2  ic   avg_mk  avg_del  avg_dist  min  max  std   del%  runs"
head -11 "$RESULTS_DIR/sweep_v2_summary.csv" | tail -10 | column -t -s ','
