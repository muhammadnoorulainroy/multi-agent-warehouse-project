#!/bin/bash
# Position sweep: find optimal charging station & intermediate area column positions
# Grid: 15x20, exits at col 1, entries at col 18
# Tests all combinations and runs each 3 times for statistical reliability

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="results/position_sweep"
TEMPLATE="configs/E1_amr5.ini"
mkdir -p "$RESULTS_DIR"

# Sweep parameters
# Charging station 1 (near exits): columns 3-8
# Charging station 2 (near entries): columns 12-17
# Intermediate areas: columns 5-15
RECHARGE_COL1_VALUES="3 5 7"
RECHARGE_COL2_VALUES="12 14 16"
INTERMEDIATE_COL_VALUES="5 7 10 13 15"
RUNS_PER_CONFIG=3

# CSV header
echo "rc1,rc2,ic,run,mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked" > "$RESULTS_DIR/sweep_results.csv"

TOTAL=0
for rc1 in $RECHARGE_COL1_VALUES; do
  for rc2 in $RECHARGE_COL2_VALUES; do
    for ic in $INTERMEDIATE_COL_VALUES; do
      TOTAL=$((TOTAL + RUNS_PER_CONFIG))
    done
  done
done

COUNT=0
for rc1 in $RECHARGE_COL1_VALUES; do
  for rc2 in $RECHARGE_COL2_VALUES; do
    for ic in $INTERMEDIATE_COL_VALUES; do
      # Generate config file
      CONFIG_FILE="$RESULTS_DIR/sweep_rc${rc1}_${rc2}_ic${ic}.ini"
      cp "$TEMPLATE" "$CONFIG_FILE"
      # Append position parameters
      echo "recharge_col_1 = $rc1" >> "$CONFIG_FILE"
      echo "recharge_col_2 = $rc2" >> "$CONFIG_FILE"
      echo "intermediate_col = $ic" >> "$CONFIG_FILE"

      for run in $(seq 1 $RUNS_PER_CONFIG); do
        COUNT=$((COUNT + 1))
        # Use different seed per run
        SEED=$((150 + run * 37))
        sed -i "s/^seed = .*/seed = $SEED/" "$CONFIG_FILE"

        echo "[$COUNT/$TOTAL] rc1=$rc1 rc2=$rc2 ic=$ic run=$run (seed=$SEED)"
        output=$(./gradlew enhanced -Pconfig="$CONFIG_FILE" --quiet 2>&1)
        csv_line=$(echo "$output" | grep "^CSV," | head -1)

        if [ -n "$csv_line" ]; then
          # Strip leading "CSV," and append position info
          data=$(echo "$csv_line" | sed 's/^CSV,//')
          echo "$rc1,$rc2,$ic,$run,$data" >> "$RESULTS_DIR/sweep_results.csv"
          # Extract makespan for quick display
          makespan=$(echo "$data" | cut -d',' -f7)
          avg=$(echo "$data" | cut -d',' -f6)
          echo "  -> avg=$avg makespan=$makespan"
        else
          echo "  -> FAILED"
          echo "$rc1,$rc2,$ic,$run,ERROR,,,,,,,,,,,," >> "$RESULTS_DIR/sweep_results.csv"
        fi
      done
    done
  done
done

echo ""
echo "=== Sweep complete: $COUNT runs ==="
echo "Results: $RESULTS_DIR/sweep_results.csv"
echo ""

# Analyze: compute average makespan per position combo
echo "=== BEST POSITIONS (by avg makespan) ==="
echo "rc1,rc2,ic,avg_makespan,avg_delivery,runs" > "$RESULTS_DIR/sweep_summary.csv"

# Group by rc1,rc2,ic and compute averages
tail -n +2 "$RESULTS_DIR/sweep_results.csv" | grep -v "ERROR" | \
  awk -F',' '{
    key = $1","$2","$3;
    makespan[key] += $7 + 0;
    avg_del[key] += $6 + 0;
    count[key]++;
  }
  END {
    for (k in count) {
      printf "%s,%.1f,%.1f,%d\n", k, makespan[k]/count[k], avg_del[k]/count[k], count[k]
    }
  }' | sort -t',' -k4 -n | tee -a "$RESULTS_DIR/sweep_summary.csv"

echo ""
echo "Top 5 by avg makespan:"
head -6 "$RESULTS_DIR/sweep_summary.csv" | column -t -s ','
