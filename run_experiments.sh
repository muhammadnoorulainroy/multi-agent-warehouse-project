#!/bin/bash
# Run all experiment configurations for both REFERENCE and ENHANCED models.
# Results are saved to results/ directory.
# CSV summary is collected into results/summary.csv

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

CONFIGS_DIR="configs"
RESULTS_DIR="results"

mkdir -p "$RESULTS_DIR"

# CSV header
HEADER="mode,total_pallets,delivered,pending,total_delivery_time,avg_delivery,makespan,throughput,total_distance,battery_deaths,intermediate_received,intermediate_picked,config"
echo "$HEADER" > "$RESULTS_DIR/summary.csv"

CONFIGS=$(ls "$CONFIGS_DIR"/*.ini 2>/dev/null | sort)
if [ -z "$CONFIGS" ]; then
    echo "No config files found in $CONFIGS_DIR/"
    exit 1
fi

TOTAL=$(echo "$CONFIGS" | wc -l)
COUNT=0

for config in $CONFIGS; do
    config_name=$(basename "$config" .ini)
    COUNT=$((COUNT + 1))

    echo "[$COUNT/$TOTAL] Running $config_name..."

    # Reference model
    echo "  -> REFERENCE..."
    ./gradlew reference -Pconfig="$config" --quiet 2>&1 > "$RESULTS_DIR/${config_name}_reference.txt"
    CSV_LINE=$(grep "^CSV," "$RESULTS_DIR/${config_name}_reference.txt" | head -1)
    if [ -n "$CSV_LINE" ]; then
        echo "${CSV_LINE},${config_name}" >> "$RESULTS_DIR/summary.csv"
    else
        echo "  [WARN] No CSV output for $config_name reference"
    fi

    # Enhanced model
    echo "  -> ENHANCED..."
    ./gradlew enhanced -Pconfig="$config" --quiet 2>&1 > "$RESULTS_DIR/${config_name}_enhanced.txt"
    CSV_LINE=$(grep "^CSV," "$RESULTS_DIR/${config_name}_enhanced.txt" | head -1)
    if [ -n "$CSV_LINE" ]; then
        echo "${CSV_LINE},${config_name}" >> "$RESULTS_DIR/summary.csv"
    else
        echo "  [WARN] No CSV output for $config_name enhanced"
    fi
done

echo ""
echo "All experiments complete. Results in $RESULTS_DIR/"
echo "Summary CSV: $RESULTS_DIR/summary.csv"
echo ""
echo "--- Summary ---"
cat "$RESULTS_DIR/summary.csv" | column -t -s ','
