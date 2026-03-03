#!/bin/bash
# Head-to-head comparison: ENHANCED vs REFERENCE across all 21 experiment configs
# 10 runs per config per mode = 21 × 2 × 10 = 420 simulations

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

RESULTS_DIR="results/comparison"
mkdir -p "$RESULTS_DIR"

CONFIGS=(
  "configs/E1_amr3.ini"
  "configs/E1_amr5.ini"
  "configs/E1_amr8.ini"
  "configs/E1_amr12.ini"
  "configs/E2_poisson.ini"
  "configs/E2_uniform.ini"
  "configs/E2_geometric.ini"
  "configs/E3_bat50.ini"
  "configs/E3_bat100.ini"
  "configs/E3_bat200.ini"
  "configs/E4_inter0.ini"
  "configs/E4_inter1.ini"
  "configs/E4_inter2.ini"
  "configs/E4_inter4.ini"
  "configs/E5_obs0.ini"
  "configs/E5_obs5.ini"
  "configs/E5_obs10.ini"
  "configs/E5_obs20.ini"
  "configs/E6_pal20.ini"
  "configs/E6_pal50.ini"
  "configs/E6_pal100.ini"
)

RUNS_PER_CONFIG=10
NUM_CONFIGS=${#CONFIGS[@]}
TOTAL=$((NUM_CONFIGS * 2 * RUNS_PER_CONFIG))

echo "=== Enhanced vs Reference Comparison: $NUM_CONFIGS configs × 2 modes × $RUNS_PER_CONFIG runs = $TOTAL total ==="

# CSV header
echo "config,mode,run,seed,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked" > "$RESULTS_DIR/comparison_results.csv"

COUNT=0
for config_path in "${CONFIGS[@]}"; do
  config_name=$(basename "$config_path" .ini)

  for mode in "enhanced" "reference"; do
    for run in $(seq 1 $RUNS_PER_CONFIG); do
      COUNT=$((COUNT + 1))
      SEED=$((100 + run * 17))

      # Create temp config with seed override
      TEMP_CONFIG="$RESULTS_DIR/temp_${config_name}.ini"
      cp "$config_path" "$TEMP_CONFIG"
      sed -i "s/^seed = .*/seed = $SEED/" "$TEMP_CONFIG"

      output=$(./gradlew "$mode" -Pconfig="$TEMP_CONFIG" --quiet 2>&1)
      csv_line=$(echo "$output" | grep "^CSV," | head -1)

      if [ -n "$csv_line" ]; then
        data=$(echo "$csv_line" | sed 's/^CSV,[^,]*,//')
        echo "$config_name,$mode,$run,$SEED,$data" >> "$RESULTS_DIR/comparison_results.csv"
        makespan=$(echo "$data" | cut -d',' -f6)
        printf "\r  [%d/%d] %s %s run %d  makespan=%s        " "$COUNT" "$TOTAL" "$config_name" "$mode" "$run" "$makespan"
      else
        echo "$config_name,$mode,$run,$SEED,ERROR,,,,,,,,,,," >> "$RESULTS_DIR/comparison_results.csv"
        printf "\r  [%d/%d] %s %s run %d  FAILED             " "$COUNT" "$TOTAL" "$config_name" "$mode" "$run"
      fi
    done
  done
  echo ""
done

# Clean up temp configs
rm -f "$RESULTS_DIR"/temp_*.ini

echo ""
echo "=== Comparison complete: $COUNT runs ==="
echo ""

# Generate summary: average per config per mode
echo "config,mode,avg_makespan,avg_delivery,avg_distance,avg_throughput,min_makespan,max_makespan,std_makespan,delivered_pct,battery_deaths,runs" > "$RESULTS_DIR/comparison_summary.csv"

tail -n +2 "$RESULTS_DIR/comparison_results.csv" | grep -v "ERROR" | \
  awk -F',' '{
    key = $1","$2;
    mk = $11 + 0;
    makespan[key] += mk;
    makespan_sq[key] += mk * mk;
    avg_del[key] += $10 + 0;
    dist[key] += $13 + 0;
    tp[key] += $12 + 0;
    delivered[key] += $7 + 0;
    total_pal[key] += $6 + 0;
    deaths[key] += $14 + 0;
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
      printf "%s,%.1f,%.1f,%.0f,%.4f,%d,%d,%.1f,%.1f,%.1f,%d\n", k, avg_mk, avg_del[k]/n, dist[k]/n, tp[k]/n, mn[k], mx[k], std, del_pct, deaths[k]/n, n
    }
  }' | sort -t',' -k1,1 -k2,2 | tee -a "$RESULTS_DIR/comparison_summary.csv"

echo ""
echo "=== SIDE-BY-SIDE COMPARISON ==="
echo ""
echo "config,enh_mk,ref_mk,diff,enh_del,ref_del,enh_dist,ref_dist,enh_deaths" > "$RESULTS_DIR/comparison_side_by_side.csv"

# Build side-by-side from summary
tail -n +2 "$RESULTS_DIR/comparison_summary.csv" | \
  awk -F',' '
  {
    config = $1; mode = $2;
    if (mode == "enhanced") {
      enh_mk[config] = $3; enh_del[config] = $4; enh_dist[config] = $5; enh_deaths[config] = $11;
    } else {
      ref_mk[config] = $3; ref_del[config] = $4; ref_dist[config] = $5;
    }
  }
  END {
    for (c in enh_mk) {
      diff = enh_mk[c] - ref_mk[c];
      pct = (ref_mk[c] > 0) ? 100.0 * diff / ref_mk[c] : 0;
      printf "%s,%.1f,%.1f,%+.1f (%+.0f%%),%.1f,%.1f,%.0f,%.0f,%.1f\n", c, enh_mk[c], ref_mk[c], diff, pct, enh_del[c], ref_del[c], enh_dist[c], ref_dist[c], enh_deaths[c]
    }
  }' | sort -t',' -k1 | tee -a "$RESULTS_DIR/comparison_side_by_side.csv"

echo ""
echo "Results saved to $RESULTS_DIR/"
