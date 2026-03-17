#!/bin/bash
# Run all v2 experiment configurations across 20 seeds (150-169)
# Enhanced mode for all phases; Reference mode additionally for P0 and P10
#
# Results are saved to results/experiments_v2/
# CSV summary is collected into results/experiments_v2/experiment_summary.csv

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

CONFIGS_DIR="configs/experiments_v2"
RESULTS_DIR="results/experiments_v2"
SUMMARY_CSV="$RESULTS_DIR/experiment_summary.csv"

SEED_START=150
SEED_END=159

# ================================================================
# Step 1: Generate all configs
# ================================================================
echo "=== Step 1: Generating experiment configs ==="
bash generate_experiment_configs_v2.sh
echo ""

# ================================================================
# Step 2: Create output directories and CSV header
# ================================================================
mkdir -p "$RESULTS_DIR"

CSV_HEADER="phase,experiment,seed,mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked,conflicts,yields,relay_drops"
echo "$CSV_HEADER" > "$SUMMARY_CSV"

# ================================================================
# Phase definitions — ordered list of phases and their configs
# ================================================================
PHASES="P0 P1 P2 P3 P4 P5 P6 P7 P8 P9 P10"

# Phases that also run REFERENCE mode
REFERENCE_PHASES="P0 P10"

# Count total runs for progress tracking
total_runs=0
total_configs=0
for phase in $PHASES; do
    configs=$(ls "$CONFIGS_DIR"/${phase}_*.ini 2>/dev/null || true)
    if [ -z "$configs" ]; then
        continue
    fi
    for config in $configs; do
        total_configs=$((total_configs + 1))
        num_seeds=$((SEED_END - SEED_START + 1))
        # Enhanced runs for all
        total_runs=$((total_runs + num_seeds))
        # Reference runs for P0 and P10
        if echo "$REFERENCE_PHASES" | grep -qw "$phase"; then
            total_runs=$((total_runs + num_seeds))
        fi
    done
done

echo "=== Step 3: Running experiments ==="
echo "Total configs: $total_configs"
echo "Total runs: $total_runs (across $((SEED_END - SEED_START + 1)) seeds per config)"
echo "Summary CSV: $SUMMARY_CSV"
echo ""

# ================================================================
# Step 3: Run experiments
# ================================================================
run_count=0
success_count=0
fail_count=0

run_single() {
    local phase="$1"
    local experiment="$2"
    local config_path="$3"
    local seed="$4"
    local mode="$5"   # "enhanced" or "reference"

    run_count=$((run_count + 1))
    echo "[$run_count/$total_runs] $phase: $experiment seed=$seed mode=$mode"

    # Run gradle command and capture output
    local output
    output=$(./gradlew.bat "$mode" -Pconfig="$config_path" -Pseed="$seed" --quiet 2>&1) || true

    # Extract CSV line (starts with "CSV,")
    local csv_line
    csv_line=$(echo "$output" | grep "^CSV," | head -1)

    if [ -n "$csv_line" ]; then
        # csv_line format: CSV,MODE,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked,conflicts,yields,relay_drops
        # Strip the leading "CSV," prefix — we rebuild with our own columns
        local csv_data="${csv_line#CSV,}"
        echo "$phase,$experiment,$seed,$csv_data" >> "$SUMMARY_CSV"
        success_count=$((success_count + 1))
    else
        echo "  [WARN] No CSV output for $experiment seed=$seed mode=$mode"
        fail_count=$((fail_count + 1))
    fi
}

for phase in $PHASES; do
    configs=$(ls "$CONFIGS_DIR"/${phase}_*.ini 2>/dev/null | sort || true)
    if [ -z "$configs" ]; then
        echo "No configs found for $phase, skipping."
        continue
    fi

    echo ""
    echo "=========================================="
    echo "  Phase: $phase"
    echo "=========================================="

    for config_path in $configs; do
        experiment=$(basename "$config_path" .ini)

        for seed in $(seq $SEED_START $SEED_END); do
            # Always run ENHANCED
            run_single "$phase" "$experiment" "$config_path" "$seed" "enhanced"

            # Also run REFERENCE for P0 and P10
            if echo "$REFERENCE_PHASES" | grep -qw "$phase"; then
                run_single "$phase" "$experiment" "$config_path" "$seed" "reference"
            fi
        done
    done
done

# ================================================================
# Step 4: Summary
# ================================================================
echo ""
echo "=========================================="
echo "  ALL EXPERIMENTS COMPLETE"
echo "=========================================="
echo "Total runs attempted: $run_count"
echo "Successful:           $success_count"
echo "Failed/no output:     $fail_count"
echo ""
echo "Results directory:    $RESULTS_DIR/"
echo "Summary CSV:          $SUMMARY_CSV"
echo "CSV rows (excl header): $(( $(wc -l < "$SUMMARY_CSV") - 1 ))"
