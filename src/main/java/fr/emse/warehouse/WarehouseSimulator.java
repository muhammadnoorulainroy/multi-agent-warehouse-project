package fr.emse.warehouse;

import fr.emse.fayol.maqit.simulator.ColorSimFactory;
import fr.emse.fayol.maqit.simulator.configuration.IniFile;
import fr.emse.fayol.maqit.simulator.configuration.SimProperties;
import fr.emse.fayol.maqit.simulator.components.ColorObstacle;
import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.components.SituatedComponent;
import fr.emse.fayol.maqit.simulator.components.ComponentType;
import fr.emse.fayol.maqit.simulator.environment.ColorGridEnvironment;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

public class WarehouseSimulator extends ColorSimFactory {

    public enum SimulationMode {
        REFERENCE,
        ENHANCED
    }

    private SimulationMode mode;

    private WarehouseEnvironment warehouse;
    private List<AMRobot> amrList;
    private List<AMRobot> amrsToRemove;
    private List<Human> humanList;

    private WarehouseGraphicalWindow customWindow;

    private int numHumans = 2;

    private int totalPalletsToGenerate;
    private int palletsGenerated;
    private double palletArrivalProbability;
    private EntryArea.ArrivalDistribution arrivalDistribution;

    // Enhanced mode config
    private int maxBattery;
    private int rechargeRate;
    private int numAMRs;
    private int intermediateCapacity = 5;
    private double batterySafetyMargin = 1.3;
    private double cnpAlpha = 0.5;
    private double cnpBeta = 0.3;
    private double cnpGamma = 0.2;
    private int numEntryAreas = 3;
    private int numExitAreas = 2;
    private int numIntermediateAreas = 2;

    // Algorithm configuration
    private String pathfindingMode = "astar_diagonal";
    private String allocationMode = "cnp";
    private String conflictResolution = "priority";
    private double rechargeThreshold = 0.4;
    private String relayStrategy = "adaptive";
    private int roundRobinIndex = 0;

    // Layout position overrides — null = auto-calculate, otherwise explicit [row,col] pairs
    private List<int[]> entryPositions = null;
    private List<int[]> exitPositions = null;
    private List<int[]> intermediatePositions = null;
    private List<int[]> rechargePositions = null;
    private List<int[]> obstaclePositions = null;

    // Legacy column-only overrides (used when explicit positions not set)
    private int rechargeCol1 = -1;
    private int rechargeCol2 = -1;
    private int intermediateCol = -1;

    private boolean splitProbability = true;
    private int preloadPallets = 5;

    private static final int MAX_AT_RECHARGE_STATION = 5;

    // Statistics
    private int simulationTicks;
    private long startTime;
    private long endTime;
    private int cumulativeDistance;
    private int totalConflicts;
    private int totalYields;
    private int totalRelayDrops;

    public WarehouseSimulator(SimProperties sp, SimulationMode mode) {
        super(sp);
        this.mode = mode;
        this.amrList = new ArrayList<>();
        this.amrsToRemove = new ArrayList<>();
        this.humanList = new ArrayList<>();
        this.palletsGenerated = 0;
        this.simulationTicks = 0;
        this.cumulativeDistance = 0;

        this.totalPalletsToGenerate = 20;
        this.palletArrivalProbability = 0.15;
        this.arrivalDistribution = EntryArea.ArrivalDistribution.BINOMIAL;
        this.maxBattery = 100;
        this.rechargeRate = 5;
        this.numAMRs = 5;
    }

    public void setTotalPallets(int total) { this.totalPalletsToGenerate = total; }
    public void setPalletArrivalProbability(double prob) { this.palletArrivalProbability = prob; }
    public void setMaxBattery(int battery) { this.maxBattery = battery; }
    public void setRechargeRate(int rate) { this.rechargeRate = rate; }
    public void setNumAMRs(int num) { this.numAMRs = num; }
    public void setNumHumans(int num) { this.numHumans = num; }
    public void setArrivalDistribution(EntryArea.ArrivalDistribution dist) { this.arrivalDistribution = dist; }
    public void setIntermediateCapacity(int cap) { this.intermediateCapacity = cap; }
    public void setBatterySafetyMargin(double margin) { this.batterySafetyMargin = margin; }
    public void setCNPWeights(double alpha, double beta, double gamma) {
        this.cnpAlpha = alpha;
        this.cnpBeta = beta;
        this.cnpGamma = gamma;
    }
    public void setNumEntryAreas(int n) { this.numEntryAreas = n; }
    public void setNumExitAreas(int n) { this.numExitAreas = n; }
    public void setNumIntermediateAreas(int n) { this.numIntermediateAreas = n; }
    public void setRechargeCol1(int c) { this.rechargeCol1 = c; }
    public void setRechargeCol2(int c) { this.rechargeCol2 = c; }
    public void setIntermediateCol(int c) { this.intermediateCol = c; }
    public void setSplitProbability(boolean split) { this.splitProbability = split; }
    public void setPreloadPallets(int n) { this.preloadPallets = n; }
    public void setEntryPositions(List<int[]> pos) { this.entryPositions = pos; }
    public void setExitPositions(List<int[]> pos) { this.exitPositions = pos; }
    public void setIntermediatePositions(List<int[]> pos) { this.intermediatePositions = pos; }
    public void setRechargePositions(List<int[]> pos) { this.rechargePositions = pos; }
    public void setObstaclePositions(List<int[]> pos) { this.obstaclePositions = pos; }
    public void setPathfindingMode(String mode) { this.pathfindingMode = mode; }
    public void setAllocationMode(String mode) { this.allocationMode = mode; }
    public void setConflictResolution(String mode) { this.conflictResolution = mode; }
    public void setRechargeThreshold(double t) { this.rechargeThreshold = t; }
    public void setRelayStrategy(String strategy) { this.relayStrategy = strategy; }

    @Override
    public void createEnvironment() {
        this.environment = new ColorGridEnvironment(this.sp.seed);
        this.warehouse = new WarehouseEnvironment(this.sp.rows, this.sp.columns);
        setupWarehouseLayout();
    }

    private void setupWarehouseLayout() {
        String[] exitIds = new String[numExitAreas];
        for (int i = 0; i < numExitAreas; i++) {
            exitIds[i] = "Z" + (i + 1);
        }

        // Exit areas — explicit positions or LEFT side evenly spaced
        for (int i = 0; i < numExitAreas; i++) {
            int[] pos;
            if (exitPositions != null && i < exitPositions.size()) {
                pos = exitPositions.get(i);
            } else {
                int row = 2 + (int) ((double) i * (sp.rows - 4) / Math.max(1, numExitAreas - 1));
                if (numExitAreas == 1) row = sp.rows / 2 - 1;
                pos = new int[]{row, 1};
            }
            warehouse.addExitArea(new ExitArea(exitIds[i], pos));
        }

        // Entry areas — explicit positions or RIGHT side evenly spaced
        double perEntryProb = splitProbability
            ? palletArrivalProbability / numEntryAreas
            : palletArrivalProbability;
        for (int i = 0; i < numEntryAreas; i++) {
            int[] pos;
            if (entryPositions != null && i < entryPositions.size()) {
                pos = entryPositions.get(i);
            } else {
                int row = 2 + (int) ((double) i * (sp.rows - 4) / Math.max(1, numEntryAreas - 1));
                if (numEntryAreas == 1) row = sp.rows / 2 - 1;
                pos = new int[]{row, sp.columns - 2};
            }
            EntryArea entry = new EntryArea(
                "A" + (i + 1), pos,
                perEntryProb,
                exitIds,
                sp.seed + i,
                arrivalDistribution
            );
            warehouse.addEntryArea(entry);
        }

        // Intermediate areas & charging stations — enhanced mode only
        if (mode == SimulationMode.ENHANCED) {
            if (intermediatePositions != null) {
                for (int i = 0; i < numIntermediateAreas && i < intermediatePositions.size(); i++) {
                    int[] pos = intermediatePositions.get(i);
                    warehouse.addIntermediateArea(
                        new IntermediateArea("I" + (i + 1), pos, intermediateCapacity));
                }
            } else {
                int iCol = (intermediateCol > 0) ? intermediateCol : 7;
                for (int i = 0; i < numIntermediateAreas; i++) {
                    int row = 3 + (int) ((double) i * (sp.rows - 6) / Math.max(1, numIntermediateAreas - 1));
                    if (numIntermediateAreas == 1) row = sp.rows / 2;
                    int[] pos = {row, iCol};
                    warehouse.addIntermediateArea(
                        new IntermediateArea("I" + (i + 1), pos, intermediateCapacity));
                }
            }

            if (rechargePositions != null) {
                for (int[] pos : rechargePositions) {
                    warehouse.addRechargeStation(pos);
                }
            } else {
                int rc1 = (rechargeCol1 > 0) ? rechargeCol1 : 5;
                int rc2 = (rechargeCol2 > 0) ? rechargeCol2 : 14;
                warehouse.addRechargeStation(new int[]{sp.rows / 2, rc1});
                warehouse.addRechargeStation(new int[]{sp.rows / 2, rc2});
            }
        }

        // Pre-load pallets at tick 0 (round-robin across entries)
        if (preloadPallets > 0) {
            List<EntryArea> entries = warehouse.getEntryAreas();
            for (int i = 0; i < preloadPallets && palletsGenerated < totalPalletsToGenerate; i++) {
                EntryArea entry = entries.get(i % entries.size());
                String dest = exitIds[new java.util.Random(sp.seed + 1000 + i).nextInt(exitIds.length)];
                Pallet pallet = new Pallet(0, dest, entry.getPosition());
                entry.addPallet(pallet);
                warehouse.registerPallet(pallet);
                palletsGenerated++;
            }
        }
    }

    @Override
    public void createObstacle() {
        int[] rgb = {
            this.sp.colorobstacle.getRed(),
            this.sp.colorobstacle.getGreen(),
            this.sp.colorobstacle.getBlue()
        };

        // Explicit obstacle positions from config
        if (obstaclePositions != null) {
            for (int[] pos : obstaclePositions) {
                if (warehouse.isEntryArea(pos) || warehouse.isExitArea(pos)
                        || warehouse.isIntermediateArea(pos) || warehouse.isRechargeStation(pos)) {
                    System.out.println("Warning: Skipping obstacle at " + pos[0] + ":" + pos[1] + " (conflicts with area)");
                    continue;
                }
                ColorObstacle obstacle = new ColorObstacle(pos, rgb);
                addNewComponent(obstacle);
                warehouse.addObstacle(pos);
            }
            System.out.println("Placed " + obstaclePositions.size() + " obstacles (explicit positions)");
            return;
        }

        // Auto-placement: spread apart, away from entry/exit areas
        final int MIN_OBSTACLE_SPACING = 3;
        final int ENTRY_BUFFER = 4;
        final int EXIT_BUFFER = 4;
        final int MAX_ATTEMPTS = 100;

        List<int[]> placedObstacles = new ArrayList<>();

        for (int i = 0; i < this.sp.nbobstacle; i++) {
            int attempts = 0;
            boolean placed = false;

            while (!placed && attempts < MAX_ATTEMPTS) {
                attempts++;
                int[] pos = this.environment.getPlace();

                if (warehouse.isEntryArea(pos) || warehouse.isExitArea(pos)
                        || warehouse.isIntermediateArea(pos) || warehouse.isRechargeStation(pos)) {
                    continue;
                }

                if (pos[1] < EXIT_BUFFER) continue;
                if (pos[1] > sp.columns - ENTRY_BUFFER - 1) continue;

                boolean tooClose = false;
                for (int[] existing : placedObstacles) {
                    int dist = Math.abs(pos[0] - existing[0]) + Math.abs(pos[1] - existing[1]);
                    if (dist < MIN_OBSTACLE_SPACING) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) continue;

                ColorObstacle obstacle = new ColorObstacle(pos, rgb);
                addNewComponent(obstacle);
                warehouse.addObstacle(pos);
                placedObstacles.add(pos);
                placed = true;
            }

            if (!placed) {
                System.out.println("Warning: Could not place obstacle " + i + " with proper spacing");
            }
        }

        System.out.println("Placed " + placedObstacles.size() + " obstacles (spread apart, away from entry/exit)");
    }

    @Override
    public void createRobot() {
        // Reference model creates AMRs dynamically per pallet
        if (mode == SimulationMode.ENHANCED) {
            for (int i = 0; i < numAMRs; i++) {
                int[] pos = this.environment.getPlace();

                AMRobot amr = new AMRobot(
                    "AMR" + i,
                    this.sp.field,
                    pos,
                    this.sp.colorrobot,
                    this.sp.rows,
                    this.sp.columns,
                    maxBattery,
                    rechargeRate
                );
                amr.setWarehouseEnvironment(warehouse);
                amr.setCNPWeights(cnpAlpha, cnpBeta, cnpGamma, batterySafetyMargin);
                amr.setPathfindingMode(pathfindingMode);
                amr.setRechargeThreshold(rechargeThreshold);
                amr.setRelayStrategy(relayStrategy);
                warehouse.updateRobotPosition(amr.getId(), pos);

                amrList.add(amr);
                addNewComponent(amr);
            }
        }
    }

    @Override
    public void createGoal() {
        // Area icons are rendered directly by WarehouseGraphicalWindow
        createHumans();
    }

    private void createHumans() {
        for (int i = 0; i < numHumans; i++) {
            int[] pos = this.environment.getPlace();

            Human human = new Human(
                "Human_" + (i + 1),
                this.sp.field,
                pos,
                this.sp.rows,
                this.sp.columns
            );
            human.setWarehouseEnvironment(warehouse);

            humanList.add(human);
            addNewComponent(human);
            warehouse.updateHumanPosition(human.getName(), pos);
        }

        System.out.println("Created " + numHumans + " human workers (dynamic obstacles)");
    }

    private int[] findComponentOnGrid(Object component, ColorSimpleCell[][] grid) {
        for (int r = 0; r < grid.length; r++) {
            for (int c = 0; c < grid[0].length; c++) {
                ColorSimpleCell cell = grid[r][c];
                if (cell != null && cell.getContent() == component) {
                    return new int[]{r, c};
                }
            }
        }
        return null;
    }

    private void printWarehouseLayout() {
        System.out.println("\n--- WAREHOUSE POSITIONS ---");
        System.out.println("Entry Areas (GREEN - pallets spawn here):");
        for (EntryArea entry : warehouse.getEntryAreas()) {
            System.out.println("  " + entry.getId() + " at position (" +
                entry.getX() + ", " + entry.getY() + ")");
        }

        System.out.println("Exit Areas (ORANGE - delivery destinations):");
        for (ExitArea exit : warehouse.getExitAreas()) {
            System.out.println("  " + exit.getId() + " at position (" +
                exit.getX() + ", " + exit.getY() + ")");
        }

        if (mode == SimulationMode.ENHANCED) {
            System.out.println("Intermediate Areas (CYAN - relay stations):");
            for (IntermediateArea inter : warehouse.getIntermediateAreas()) {
                System.out.println("  " + inter.getId() + " at position (" +
                    inter.getX() + ", " + inter.getY() + ") capacity=" + inter.getCapacity());
            }

            System.out.println("Recharge Stations (YELLOW):");
            for (int[] pos : warehouse.getRechargeStations()) {
                System.out.println("  at position (" + pos[0] + ", " + pos[1] + ")");
            }
        }

        System.out.println("Obstacles: " + warehouse.getObstacles().size() + " placed randomly");
    }

    @Override
    public void schedule() {
        startTime = System.currentTimeMillis();
        printWarehouseLayout();

        System.out.println("\nStarting " + mode + " simulation...");
        System.out.println("Total pallets to deliver: " + totalPalletsToGenerate);
        System.out.println("Watch the GUI window for visual simulation!");

        for (int tick = 0; tick < this.sp.step; tick++) {
            simulationTicks = tick;

            if (this.sp.debug == 1) {
                System.out.println("\n=== Tick " + tick + " ===");
            }

            generatePallets(tick);
            assignTasks(tick);
            moveAMRs(tick);
            moveHumans();

            if (mode == SimulationMode.ENHANCED) {
                distributeMessages();
            }

            checkDeliveries(tick);

            if (this.sp.debug == 1) {
                printStatus(tick);
            }

            // Refresh GUI BEFORE removing AMRs so the robot is visible at the exit for one frame
            refreshCustomWindow();
            removeCompletedAMRs();

            if (isSimulationComplete()) {
                System.out.println("\nSimulation complete at tick " + tick);
                break;
            }

            try {
                Thread.sleep(this.sp.waittime);
            } catch (InterruptedException ex) {
                System.out.println(ex);
            }
        }

        endTime = System.currentTimeMillis();
        printFinalStatistics();

        if (customWindow != null) {
            customWindow.dispose();
        }
        System.exit(0);
    }

    private void generatePallets(int tick) {
        // warehouse.tick() advances clock AND generates pallets; stopGeneration()
        // disables queuing but keeps the clock advancing for delivery timestamps.
        List<Pallet> newPallets = warehouse.tick(tick);

        for (Pallet pallet : newPallets) {
            if (palletsGenerated >= totalPalletsToGenerate) {
                warehouse.stopGeneration();
                break;
            }
            palletsGenerated++;

            if (this.sp.debug == 1) {
                System.out.println("New pallet: " + pallet);
            }

            if (mode == SimulationMode.REFERENCE) {
                createAMRForPallet(pallet);
            }

            if (palletsGenerated >= totalPalletsToGenerate) {
                warehouse.stopGeneration();
            }
        }
    }

    private void createAMRForPallet(Pallet pallet) {
        int[] palletPos = pallet.getPosition();
        int[] spawnPos = findFreeSpawnPosition(palletPos);

        AMRobot amr = new AMRobot(
            "AMR_P" + pallet.getId(),
            this.sp.field,
            spawnPos,
            this.sp.colorrobot,
            this.sp.rows,
            this.sp.columns
        );
        amr.setWarehouseEnvironment(warehouse);
        amr.setPathfindingMode("astar");  // Reference model always uses simple A* (no penalties)
        warehouse.updateRobotPosition(amr.getId(), spawnPos);

        // Pick a free cell in the 2x2 exit zone to spread AMRs across cells
        int[] exitPos = warehouse.getBestExitCell(pallet.getDestination(), spawnPos);
        if (exitPos == null) {
            exitPos = warehouse.getExitPosition(pallet.getDestination());
        }

        Pallet pickup = warehouse.pickupPalletAtPosition(palletPos);
        if (pickup != null) {
            amr.pickupPallet(pickup, exitPos);
        }

        amrList.add(amr);
        addNewComponent(amr);
    }

    private int[] findFreeSpawnPosition(int[] entryPos) {
        if (!warehouse.isOccupiedByAnyRobot(entryPos)) {
            return entryPos.clone();
        }

        // Try the other cell of the 2x1 entry area
        int[] secondCell = {entryPos[0] + 1, entryPos[1]};
        if (isValidCell(secondCell) && !warehouse.isOccupiedByAnyRobot(secondCell)
                && !warehouse.isObstacle(secondCell)) {
            return secondCell;
        }

        // Search adjacent cells (prefer leftward since AMRs move left)
        int[][] offsets = {{0, -1}, {1, -1}, {-1, 0}, {0, -2}, {1, -2}, {-1, -1}};
        for (int[] off : offsets) {
            int[] candidate = {entryPos[0] + off[0], entryPos[1] + off[1]};
            if (isValidCell(candidate) && !warehouse.isOccupiedByAnyRobot(candidate)
                    && !warehouse.isObstacle(candidate)) {
                return candidate;
            }
        }

        return entryPos.clone();
    }

    private boolean isValidCell(int[] pos) {
        return pos[0] >= 0 && pos[0] < sp.rows && pos[1] >= 0 && pos[1] < sp.columns;
    }

    private int countAMRsAtOrHeadingToRecharge() {
        int count = 0;
        for (AMRobot amr : amrList) {
            if (amr.getState() == AMRobot.State.MOVING_TO_RECHARGE ||
                amr.getState() == AMRobot.State.RECHARGING) {
                count++;
            }
        }
        return count;
    }

    private void assignTasks(int tick) {
        if (mode != SimulationMode.ENHANCED) {
            return;
        }

        java.util.Map<String, Integer> exitCongestion = new java.util.HashMap<>();
        for (AMRobot amr : amrList) {
            if (amr.getState() == AMRobot.State.DELIVERING && amr.getCarriedPallet() != null) {
                String dest = amr.getCarriedPallet().getDestination();
                exitCongestion.merge(dest, 1, Integer::sum);
            }
        }

        // Phase 1: Intermediate area relay pickups
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            if (!area.hasPallets()) continue;

            Pallet waitingPallet = area.peekPallet();
            int[] exitPos = warehouse.getExitPosition(waitingPallet.getDestination());
            if (exitPos == null) continue;

            int congestion = exitCongestion.getOrDefault(waitingPallet.getDestination(), 0);

            AMRobot bestBidder = null;
            double bestScore = -1;

            for (AMRobot amr : amrList) {
                double score = amr.computeBidScore(area.getPosition(), exitPos, congestion, tick);
                if (score > bestScore) {
                    bestScore = score;
                    bestBidder = amr;
                }
            }

            if (bestBidder != null && bestBidder.canCompleteFullDelivery(area.getPosition(), exitPos)) {
                bestBidder.assignPickupTask(area.getPosition(), waitingPallet.getDestination());

                if (this.sp.debug == 1) {
                    System.out.println("[CONTRACT NET] " + bestBidder.getName() +
                        " won relay bid for pallet #" + waitingPallet.getId() +
                        " from " + area.getId() + " (score: " + String.format("%.3f", bestScore) + ")");
                }
            }
        }

        // Phase 2: Entry area pickups — mode-dependent allocation
        java.util.Set<String> claimedEntries = new java.util.HashSet<>();
        for (AMRobot amr : amrList) {
            if (!amr.isAvailable()) {
                int[] target = amr.getTargetPosition();
                if (target != null) {
                    claimedEntries.add(target[0] + "," + target[1]);
                }
            }
        }

        List<EntryArea> entriesWithPallets = warehouse.getEntriesWithPallets();

        // Build list of idle AMRs for non-CNP modes
        List<AMRobot> idleAMRs = new ArrayList<>();
        for (AMRobot amr : amrList) {
            if (amr.isAvailable()) {
                idleAMRs.add(amr);
            }
        }

        for (EntryArea entry : entriesWithPallets) {
            String key = entry.getPosition()[0] + "," + entry.getPosition()[1];
            if (claimedEntries.contains(key)) continue;

            Pallet nextPallet = entry.peekPallet();
            if (nextPallet == null) continue;

            int[] exitPos = warehouse.getExitPosition(nextPallet.getDestination());
            if (exitPos == null) continue;

            int congestion = exitCongestion.getOrDefault(nextPallet.getDestination(), 0);

            AMRobot selectedAMR = null;
            String allocLabel = allocationMode;

            switch (allocationMode) {
                case "random": {
                    // Random idle AMR
                    if (!idleAMRs.isEmpty()) {
                        java.util.Random rng = new java.util.Random(tick * 31L + entry.getPosition()[0] * 7 + entry.getPosition()[1]);
                        selectedAMR = idleAMRs.get(rng.nextInt(idleAMRs.size()));
                    }
                    break;
                }
                case "greedy": {
                    // Nearest idle AMR by Manhattan distance to entry
                    int bestDist = Integer.MAX_VALUE;
                    for (AMRobot amr : idleAMRs) {
                        int dist = Math.abs(amr.getLocation()[0] - entry.getPosition()[0])
                                 + Math.abs(amr.getLocation()[1] - entry.getPosition()[1]);
                        if (dist < bestDist) {
                            bestDist = dist;
                            selectedAMR = amr;
                        }
                    }
                    break;
                }
                case "round_robin": {
                    // Rotate through idle AMRs
                    if (!idleAMRs.isEmpty()) {
                        roundRobinIndex = roundRobinIndex % idleAMRs.size();
                        selectedAMR = idleAMRs.get(roundRobinIndex);
                        roundRobinIndex++;
                    }
                    break;
                }
                case "least_utilized": {
                    // AMR with fewest completed deliveries
                    int minDeliveries = Integer.MAX_VALUE;
                    for (AMRobot amr : idleAMRs) {
                        if (amr.getPalletsDelivered() < minDeliveries) {
                            minDeliveries = amr.getPalletsDelivered();
                            selectedAMR = amr;
                        }
                    }
                    break;
                }
                case "cnp":
                default: {
                    // Contract Net Protocol — weighted bidding
                    double bestScore = -1;
                    for (AMRobot amr : amrList) {
                        double score = amr.computeBidScore(entry.getPosition(), exitPos, congestion, tick);
                        if (score > bestScore) {
                            bestScore = score;
                            selectedAMR = amr;
                        }
                    }
                    allocLabel = "CNP(score=" + (selectedAMR != null ? String.format("%.3f", bestScore) : "none") + ")";
                    break;
                }
            }

            if (selectedAMR != null) {
                selectedAMR.assignPickupTask(entry.getPosition(), nextPallet.getDestination());
                claimedEntries.add(key);
                idleAMRs.remove(selectedAMR);  // No longer available

                if (this.sp.debug == 1) {
                    boolean fullDelivery = selectedAMR.canCompleteFullDelivery(entry.getPosition(), exitPos);
                    System.out.println("[ALLOC:" + allocLabel + "] " + selectedAMR.getName() +
                        " assigned " + entry.getId() + " pallet #" + nextPallet.getId() +
                        " → " + nextPallet.getDestination() +
                        " (mode: " + (fullDelivery ? "FULL" : "RELAY") + ")");
                }
            }
        }

        // Phase 3: Recharge management — idle AMRs go recharge if low or can't bid on anything
        for (AMRobot amr : amrList) {
            if (!amr.isIdle()) continue;
            if (amr.getBatteryPercentage() >= 99.0) continue;

            boolean needsRecharge = amr.shouldRecharge();

            if (!needsRecharge) {
                boolean canDoAnything = false;
                for (EntryArea entry : entriesWithPallets) {
                    Pallet p = entry.peekPallet();
                    if (p != null) {
                        int[] ep = warehouse.getExitPosition(p.getDestination());
                        if (ep != null && amr.canCompleteFullDelivery(entry.getPosition(), ep)) {
                            canDoAnything = true;
                            break;
                        }
                    }
                }
                if (!canDoAnything) {
                    for (IntermediateArea area : warehouse.getIntermediateAreas()) {
                        if (area.hasPallets()) {
                            Pallet p = area.peekPallet();
                            int[] ep = warehouse.getExitPosition(p.getDestination());
                            if (ep != null && amr.canCompleteFullDelivery(area.getPosition(), ep)) {
                                canDoAnything = true;
                                break;
                            }
                        }
                    }
                }
                if (!canDoAnything) {
                    needsRecharge = true;
                }
            }

            if (needsRecharge && countAMRsAtOrHeadingToRecharge() < MAX_AT_RECHARGE_STATION) {
                int[] rechargePos = warehouse.getBestRechargeCell(amr.getLocation(), amr.getId());
                if (rechargePos != null) {
                    amr.assignRechargeTask(rechargePos);
                    if (this.sp.debug == 1) {
                        String slotInfo = warehouse.isRechargeSlotAvailable() ? "" : " (will wait for slot)";
                        System.out.println(amr.getName() + " heading to recharge (battery: " +
                            (int) amr.getBatteryPercentage() + "%)" + slotInfo);
                    }
                }
            }
        }
    }


    private void moveAMRs(int tick) {
        ColorSimpleCell[][] grid = this.environment.getGrid();

        // Phase 1: Sync all AMRs to actual grid positions
        for (AMRobot amr : amrList) {
            int[] gridPos = findComponentOnGrid(amr, grid);
            if (gridPos != null) {
                amr.syncToGridPosition(gridPos);
            }
        }

        // Phase 2: Sort by priority and detect conflicts (enhanced only)
        List<AMRobot> sortedAMRs = new ArrayList<>(amrList);
        java.util.Set<Integer> mustYield = new java.util.HashSet<>();

        if (mode == SimulationMode.ENHANCED && !conflictResolution.equals("none")) {
            // Sort by priority for "priority" mode; random shuffle for "random" mode
            if (conflictResolution.equals("priority")) {
                sortedAMRs.sort((a, b) -> {
                    int diff = b.getMovementPriority() - a.getMovementPriority();
                    return diff != 0 ? diff : a.getId() - b.getId();
                });
            } else if (conflictResolution.equals("random")) {
                java.util.Collections.shuffle(sortedAMRs, new java.util.Random(tick));
            }

            java.util.Map<Integer, int[]> intents = new java.util.HashMap<>();
            for (AMRobot amr : sortedAMRs) {
                intents.put(amr.getId(), amr.getIntendedNextPosition());
            }

            // Same-target conflicts — first in sorted order wins
            java.util.Map<String, Integer> firstClaim = new java.util.HashMap<>();
            for (AMRobot amr : sortedAMRs) {
                int[] intent = intents.get(amr.getId());
                int[] current = amr.getLocation();
                if (intent[0] == current[0] && intent[1] == current[1]) continue;

                String key = intent[0] + "," + intent[1];
                if (firstClaim.containsKey(key)) {
                    mustYield.add(amr.getId());
                    totalConflicts++;
                } else {
                    firstClaim.put(key, amr.getId());
                }
            }

            // Head-on swap conflicts — second in sorted order yields
            for (int i = 0; i < sortedAMRs.size(); i++) {
                AMRobot a = sortedAMRs.get(i);
                if (mustYield.contains(a.getId())) continue;
                int[] posA = a.getLocation();
                int[] intentA = intents.get(a.getId());
                if (intentA[0] == posA[0] && intentA[1] == posA[1]) continue;

                for (int j = i + 1; j < sortedAMRs.size(); j++) {
                    AMRobot b = sortedAMRs.get(j);
                    if (mustYield.contains(b.getId())) continue;
                    int[] posB = b.getLocation();
                    int[] intentB = intents.get(b.getId());
                    if (intentB[0] == posB[0] && intentB[1] == posB[1]) continue;

                    if (intentA[0] == posB[0] && intentA[1] == posB[1] &&
                        intentB[0] == posA[0] && intentB[1] == posA[1]) {
                        mustYield.add(b.getId());
                        totalConflicts++;
                    }
                }
            }

            totalYields += mustYield.size();
            for (AMRobot amr : sortedAMRs) {
                amr.setMustYield(mustYield.contains(amr.getId()));
            }
        } else if (mode == SimulationMode.ENHANCED) {
            // "none" mode: no conflict detection, no yielding
            for (AMRobot amr : sortedAMRs) {
                amr.setMustYield(false);
            }
        }

        // Phase 3: Execute moves in priority order
        for (AMRobot amr : sortedAMRs) {
            int[] gridPos = findComponentOnGrid(amr, grid);
            if (gridPos != null) {
                amr.syncToGridPosition(gridPos);
            }

            ColorSimpleCell[][] per = this.environment.getNeighbor(
                amr.getX(), amr.getY(), amr.getField());
            amr.updatePerception(per);
            amr.move(1);

            // Phase 4: Grid sync with silent-failure handling
            syncAMRToGrid(amr, gridPos, grid);
            warehouse.updateRobotPosition(amr.getId(), amr.getLocation());
        }
    }

    private void syncAMRToGrid(AMRobot amr, int[] gridPos, ColorSimpleCell[][] grid) {
        int[] newPos = amr.getLocation();
        if (gridPos != null &&
            (gridPos[0] != newPos[0] || gridPos[1] != newPos[1])) {
            this.environment.moveComponent(gridPos[0], gridPos[1], newPos[0], newPos[1]);

            ColorSimpleCell destCell = grid[newPos[0]][newPos[1]];
            if (destCell == null || destCell.getContent() != amr) {
                amr.revertLastMove(gridPos);
            }
        }
    }

    private void moveHumans() {
        ColorSimpleCell[][] grid = this.environment.getGrid();

        for (Human human : humanList) {
            int[] gridPos = findComponentOnGrid(human, grid);

            if (gridPos != null) {
                human.syncToGridPosition(gridPos);
            }

            ColorSimpleCell[][] per = this.environment.getNeighbor(
                human.getX(), human.getY(), human.getField()
            );
            human.updatePerception(per);
            human.move(1);

            int[] newPos = human.getPosition();
            if (gridPos != null &&
                (gridPos[0] != newPos[0] || gridPos[1] != newPos[1])) {
                this.environment.moveComponent(gridPos[0], gridPos[1], newPos[0], newPos[1]);

                ColorSimpleCell destCell = grid[newPos[0]][newPos[1]];
                if (destCell == null || destCell.getContent() != human) {
                    human.syncToGridPosition(gridPos);
                }
            }
            warehouse.updateHumanPosition(human.getName(), human.getPosition());
        }
    }

    private void distributeMessages() {
        for (AMRobot sender : amrList) {
            List<Message> outgoing = sender.popSentMessages();
            if (outgoing.isEmpty()) continue;

            for (AMRobot receiver : amrList) {
                if (sender.getId() != receiver.getId()) {
                    for (Message msg : outgoing) {
                        receiver.receiveMessage(msg);
                    }
                }
            }
        }

        for (AMRobot amr : amrList) {
            amr.readMessages();
        }
    }

    private void checkDeliveries(int tick) {
        for (AMRobot amr : amrList) {

            if (amr.getState() == AMRobot.State.DELIVERING || amr.getState() == AMRobot.State.DELIVERED) {
                if (amr.getCarriedPallet() != null &&
                    warehouse.isCorrectExitArea(amr.getLocation(), amr.getCarriedPallet().getDestination())) {
                    handleDelivery(amr, tick);
                    continue;
                }
            }

            if (mode != SimulationMode.ENHANCED) continue;

            if (amr.getState() == AMRobot.State.PICKING_UP) {
                handlePickup(amr);
                continue;
            }

            if (amr.getState() == AMRobot.State.MOVING_TO_INTERMEDIATE) {
                if (amr.getCarriedPallet() != null && warehouse.isIntermediateArea(amr.getLocation())) {
                    handleIntermediateDrop(amr, tick);
                }
            }

            if (amr.getState() == AMRobot.State.IDLE) {
                warehouse.stopCharging(amr.getId());
            }

            // Low battery while active — reroute to relay drop or recharge
            if (amr.shouldRecharge() &&
                (amr.getState() == AMRobot.State.DELIVERING ||
                 amr.getState() == AMRobot.State.MOVING_TO_PICKUP)) {

                if (amr.isCarryingPallet()) {
                    IntermediateArea nearestInter = warehouse.getNearestIntermediateArea(amr.getLocation());
                    if (nearestInter != null) {
                        int distToInter = Math.abs(amr.getX() - nearestInter.getX())
                                        + Math.abs(amr.getY() - nearestInter.getY());
                        if (amr.getBattery() > distToInter * 2) {
                            amr.pickupPalletForRelay(amr.getCarriedPallet(), nearestInter.getPosition());
                            if (this.sp.debug == 1) {
                                System.out.println("[RELAY-DROP] " + amr.getName() +
                                    " rerouting pallet #" + amr.getCarriedPallet().getId() +
                                    " to " + nearestInter.getId() +
                                    " for relay (battery: " + (int) amr.getBatteryPercentage() + "%)");
                            }
                            continue;
                        }
                    }
                    if (countAMRsAtOrHeadingToRecharge() < MAX_AT_RECHARGE_STATION) {
                        int[] rechargePos = warehouse.getBestRechargeCell(amr.getLocation(), amr.getId());
                        if (rechargePos != null) {
                            amr.assignRechargeTask(rechargePos);
                            if (this.sp.debug == 1) {
                                System.out.println("[RECHARGE-WITH-PALLET] " + amr.getName() +
                                    " going to recharge while carrying pallet #" + amr.getCarriedPallet().getId() +
                                    " (battery: " + (int) amr.getBatteryPercentage() + "%)");
                            }
                        }
                    }
                } else {
                    if (countAMRsAtOrHeadingToRecharge() < MAX_AT_RECHARGE_STATION) {
                        int[] rechargePos = warehouse.getBestRechargeCell(amr.getLocation(), amr.getId());
                        if (rechargePos != null) {
                            amr.assignRechargeTask(rechargePos);
                            if (this.sp.debug == 1) {
                                System.out.println("[RECHARGE-ABORT] " + amr.getName() +
                                    " aborting pickup to recharge (battery: " +
                                    (int) amr.getBatteryPercentage() + "%)");
                            }
                        }
                    }
                }
            }

            if (amr.isDead() && amr.isCarryingPallet()) {
                handleDeadAMRRecovery(amr, tick);
            }
        }
    }

    private void handleDeadAMRRecovery(AMRobot amr, int tick) {
        Pallet pallet = amr.getCarriedPallet();
        if (pallet == null) return;

        IntermediateArea nearest = warehouse.getNearestIntermediateArea(amr.getLocation());
        if (nearest != null && nearest.canAccept()) {
            Pallet dropped = amr.dropPalletAtIntermediate();
            if (dropped != null) {
                nearest.storePallet(dropped);
                if (this.sp.debug == 1) {
                    System.out.println("[RECOVERY] Dead " + amr.getName() +
                        " — pallet #" + dropped.getId() + " recovered to " + nearest.getId());
                }
            }
        }
    }

    private void handleIntermediateDrop(AMRobot amr, int tick) {
        IntermediateArea targetArea = null;
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            int ax = area.getX(), ay = area.getY();
            int rx = amr.getX(), ry = amr.getY();
            if (rx >= ax && rx < ax + 2 && ry >= ay && ry < ay + 2) {
                targetArea = area;
                break;
            }
        }

        if (targetArea != null && targetArea.canAccept()) {
            Pallet dropped = amr.dropPalletAtIntermediate();
            if (dropped != null) {
                targetArea.storePallet(dropped);
                totalRelayDrops++;
                if (this.sp.debug == 1) {
                    System.out.println(amr.getName() + " dropped pallet #" + dropped.getId() +
                        " at " + targetArea.getId() + " for relay (battery: " +
                        (int) amr.getBatteryPercentage() + "%)");
                }
                // After relay drop, go recharge so AMR doesn't re-pick the same pallet
                if ((amr.shouldRecharge() || amr.getBatteryPercentage() < 60)
                        && countAMRsAtOrHeadingToRecharge() < MAX_AT_RECHARGE_STATION) {
                    int[] rechargePos = warehouse.getBestRechargeCell(amr.getLocation(), amr.getId());
                    if (rechargePos != null) {
                        amr.assignRechargeTask(rechargePos);
                        if (this.sp.debug == 1) {
                            System.out.println("  → " + amr.getName() +
                                " heading to recharge after relay drop");
                        }
                    }
                }
            }
        }
    }

    private void handlePickup(AMRobot amr) {
        Pallet pallet = warehouse.pickupPalletAtPosition(amr.getLocation());

        // Try adjacent entry areas if exact position has no pallet
        if (pallet == null) {
            for (EntryArea entry : warehouse.getEntryAreas()) {
                if (warehouse.manhattanDistance(amr.getLocation(), entry.getPosition()) <= 1
                        && entry.hasPallets()) {
                    pallet = entry.pickupPallet();
                    if (pallet != null) break;
                }
            }
        }

        // Try intermediate areas for relay pickups (within 2x2 block or adjacent)
        if (pallet == null && mode == SimulationMode.ENHANCED) {
            for (IntermediateArea area : warehouse.getIntermediateAreas()) {
                int ax = area.getX(), ay = area.getY();
                int rx = amr.getX(), ry = amr.getY();
                boolean withinArea = (rx >= ax && rx < ax + 2 && ry >= ay && ry < ay + 2)
                    || warehouse.manhattanDistance(amr.getLocation(), area.getPosition()) <= 1;
                if (withinArea && area.hasPallets()) {
                    pallet = area.pickupPallet();
                    if (pallet != null) {
                        if (this.sp.debug == 1) {
                            System.out.println(amr.getName() + " picked up relay pallet #" +
                                pallet.getId() + " from " + area.getId());
                        }
                        break;
                    }
                }
            }
        }

        if (pallet != null) {
            int[] exitPos = (mode == SimulationMode.ENHANCED)
                ? warehouse.getBestExitCell(pallet.getDestination(), amr.getLocation())
                : warehouse.getExitPosition(pallet.getDestination());

            if (mode == SimulationMode.ENHANCED && !amr.canCompleteFullDelivery(amr.getLocation(), exitPos)) {
                IntermediateArea nearest = warehouse.getNearestIntermediateArea(amr.getLocation());
                if (nearest != null && nearest.canAccept()) {
                    amr.pickupPalletForRelay(pallet, nearest.getPosition());
                    if (this.sp.debug == 1) {
                        System.out.println(amr.getName() + " picked up " + pallet +
                            " → RELAY via " + nearest.getId() + " (low battery: " +
                            (int) amr.getBatteryPercentage() + "%)");
                    }
                    return;
                }
            }

            amr.pickupPallet(pallet, exitPos);

            if (this.sp.debug == 1) {
                System.out.println(amr.getName() + " picked up " + pallet);
            }
        } else {
            amr.setState(AMRobot.State.IDLE);
        }
    }

    private void handleDelivery(AMRobot amr, int tick) {
        Pallet delivered = amr.deliverPallet();

        if (delivered != null) {
            int deliveryTime = warehouse.deliverPallet(delivered);
            amr.recordDelivery(tick);

            String exitId = "?";
            for (ExitArea exit : warehouse.getExitAreas()) {
                int ex = exit.getX(), ey = exit.getY();
                int ax = amr.getX(), ay = amr.getY();
                if (ax >= ex && ax < ex + 2 && ay >= ey && ay < ey + 2) {
                    exitId = exit.getId();
                    break;
                }
            }

            if (this.sp.debug == 1) {
                String suffix = (mode == SimulationMode.REFERENCE) ? " - vanishing" :
                    " (battery: " + (int) amr.getBatteryPercentage() + "%)";
                System.out.println(amr.getName() + " DELIVERED pallet #" + delivered.getId() +
                    " at " + exitId + " (" + amr.getX() + "," + amr.getY() + ")" +
                    " | delivery time: " + deliveryTime + " ticks" + suffix);
            }
        }

        if (mode == SimulationMode.REFERENCE) {
            amrsToRemove.add(amr);
        } else {
            if (amr.shouldRecharge() && countAMRsAtOrHeadingToRecharge() < MAX_AT_RECHARGE_STATION) {
                int[] rechargePos = warehouse.getBestRechargeCell(amr.getLocation(), amr.getId());
                if (rechargePos != null) {
                    amr.assignRechargeTask(rechargePos);
                }
            }
        }
    }

    private void removeCompletedAMRs() {
        ColorSimpleCell[][] grid = this.environment.getGrid();

        for (AMRobot amr : amrsToRemove) {
            cumulativeDistance += amr.getTotalDistanceTraveled();
            int[] actualPos = findComponentOnGrid(amr, grid);

            if (actualPos != null) {
                // WORKAROUND: ColorSimpleCell field-shadows SimpleCell.content.
                // removeContent() clears SimpleCell.content but getContent() reads
                // ColorSimpleCell.content — so the cell appears non-empty.
                // Replace the cell entirely to work around this framework bug.
                grid[actualPos[0]][actualPos[1]] = new ColorSimpleCell();

                if (this.sp.debug == 1) {
                    System.out.println(amr.getName() + " VANISHED from (" + actualPos[0] + "," + actualPos[1] + ")");
                }
            }

            warehouse.removeRobot(amr.getId());
            amrList.remove(amr);
        }
        amrsToRemove.clear();
    }

    private boolean isSimulationComplete() {
        return palletsGenerated >= totalPalletsToGenerate &&
               warehouse.allPalletsDelivered();
    }

    private void printStatus(int tick) {
        System.out.println("+--------------------------------------------------+");
        System.out.println("| PALLETS: Generated " + palletsGenerated + "/" + totalPalletsToGenerate +
            " | Pending: " + warehouse.getPendingPalletCount() +
            " | Delivered: " + warehouse.getDeliveredPalletCount());
        System.out.println("| AMRs ACTIVE: " + amrList.size());

        for (AMRobot amr : amrList) {
            String carryingInfo = "";
            if (amr.isCarryingPallet()) {
                Pallet p = amr.getCarriedPallet();
                carryingInfo = " -> delivering to " + p.getDestination();
            }
            System.out.printf("|   %s at (%d,%d) [%s]%s%n",
                amr.getName(), amr.getX(), amr.getY(), amr.getState(), carryingInfo);
        }

        for (EntryArea entry : warehouse.getEntryAreas()) {
            if (entry.hasPallets()) {
                System.out.println("|   " + entry.getId() + " has " + entry.getQueueSize() + " pallets waiting");
            }
        }
        System.out.println("+--------------------------------------------------+");

        if (tick % 10 == 0) {
            printTextGrid();
        }
    }

    // Legend: A=Entry Z=Exit R/R*=Robot H=Human X=Obstacle I=Intermediate C=Charge .=Empty
    private void printTextGrid() {
        System.out.println("\n=== WAREHOUSE MAP ===");
        System.out.println("Legend: A=Entry, Z=Exit, R/R*=Robot, H=Human, X=Obstacle, I=Intermediate, C=Charge");

        String[][] grid = new String[sp.rows][sp.columns];

        for (int r = 0; r < sp.rows; r++) {
            for (int c = 0; c < sp.columns; c++) {
                grid[r][c] = ". ";
            }
        }

        for (int[] pos : warehouse.getObstacles()) {
            if (isValidPosition(pos)) grid[pos[0]][pos[1]] = "X ";
        }

        for (IntermediateArea inter : warehouse.getIntermediateAreas()) {
            int[] pos = inter.getPosition();
            if (isValidPosition(pos)) grid[pos[0]][pos[1]] = "I ";
        }

        for (int[] pos : warehouse.getRechargeStations()) {
            if (isValidPosition(pos)) grid[pos[0]][pos[1]] = "C ";
        }

        for (EntryArea entry : warehouse.getEntryAreas()) {
            int[] pos = entry.getPosition();
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = entry.getId().substring(0, 2);
            }
        }

        for (ExitArea exit : warehouse.getExitAreas()) {
            int[] pos = exit.getPosition();
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = exit.getId().substring(0, 2);
            }
        }

        for (Human human : humanList) {
            int[] pos = human.getPosition();
            if (isValidPosition(pos)) grid[pos[0]][pos[1]] = "H ";
        }

        for (AMRobot amr : amrList) {
            int[] pos = amr.getLocation();
            if (isValidPosition(pos)) {
                grid[pos[0]][pos[1]] = amr.isCarryingPallet() ? "R*" : "R ";
            }
        }

        System.out.print("   ");
        for (int c = 0; c < sp.columns; c++) {
            System.out.printf("%2d ", c);
        }
        System.out.println();

        for (int r = 0; r < sp.rows; r++) {
            System.out.printf("%2d ", r);
            for (int c = 0; c < sp.columns; c++) {
                System.out.print(grid[r][c] + " ");
            }
            System.out.println();
        }
        System.out.println();
    }

    private boolean isValidPosition(int[] pos) {
        return pos[0] >= 0 && pos[0] < sp.rows && pos[1] >= 0 && pos[1] < sp.columns;
    }

    private void printFinalStatistics() {
        String sep  = "============================================================";
        String sep2 = "------------------------------------------------------------";
        System.out.println("\n" + sep);
        System.out.println("         SIMULATION RESULTS - " + mode + " MODEL");
        System.out.println(sep);

        int totalPallets     = warehouse.getTotalPalletCount();
        int delivered        = warehouse.getDeliveredPalletCount();
        int pending          = warehouse.getPendingPalletCount();
        int totalDeliveryTd  = warehouse.getTotalDeliveryTime();
        double avgDelivery   = warehouse.getAverageDeliveryTime();
        int makespan         = simulationTicks;
        double throughput    = makespan > 0 ? (double) delivered / makespan : 0;
        int totalDistance = cumulativeDistance;
        for (AMRobot amr : amrList) {
            totalDistance += amr.getTotalDistanceTraveled();
        }

        System.out.println("\n  DELIVERY METRICS");
        System.out.println(sep2);
        System.out.println("  Total pallets generated      : " + totalPallets);
        System.out.println("  Total pallets delivered       : " + delivered);
        System.out.println("  Pallets still pending         : " + pending);
        System.out.println("  Total delivery time (td)      : " + totalDeliveryTd +
            " ticks   [td = sum of (tc - ts) for each pallet]");
        System.out.println("  Average delivery time         : " +
            String.format("%.2f", avgDelivery) + " ticks per pallet");
        System.out.println("  Makespan                      : " + makespan + " ticks");
        System.out.println("  Throughput                    : " +
            String.format("%.4f", throughput) + " pallets/tick");
        System.out.println("  Total distance (all AMRs)     : " + totalDistance + " cells");

        System.out.println("\n  PER-PALLET DELIVERY LOG");
        System.out.println(sep2);
        System.out.println("  Pallet | Dest | Arrived (ts) | Delivered (tc) | Time (tp = tc - ts)");
        System.out.println("  -------+------+--------------+----------------+--------------------");
        for (Pallet p : warehouse.getDeliveredPallets()) {
            System.out.printf("  %-6d | %-4s | %-12d | %-14d | %d ticks%n",
                p.getId(), p.getDestination(), p.getArrivalTick(),
                p.getDeliveryTick(), p.getDeliveryTime());
        }

        System.out.println("\n  ENTRY AREAS");
        System.out.println(sep2);
        for (EntryArea entry : warehouse.getEntryAreas()) {
            System.out.println("  " + entry.getId() +
                " (" + entry.getX() + "," + entry.getY() + ")" +
                ": generated " + entry.getTotalPalletsGenerated() +
                ", remaining " + entry.getQueueSize());
        }

        System.out.println("\n  EXIT AREAS");
        System.out.println(sep2);
        for (ExitArea exit : warehouse.getExitAreas()) {
            System.out.println("  " + exit.getId() +
                " (" + exit.getX() + "," + exit.getY() + ")" +
                ": " + exit.getDeliveredCount() + " delivered" +
                ", avg time: " + String.format("%.2f", exit.getAverageDeliveryTime()) + " ticks");
        }

        if (mode == SimulationMode.ENHANCED) {
            System.out.println("\n  AMR STATISTICS");
            System.out.println(sep2);
            System.out.println("  AMR     | Delivered | Distance | Util%  | Battery | Recharges | State");
            System.out.println("  --------+-----------+----------+--------+---------+-----------+------");
            int totalBatteryDeaths = 0;
            for (AMRobot amr : amrList) {
                if (amr.isDead()) totalBatteryDeaths++;
                System.out.printf("  %-7s | %-9d | %-8d | %-5.1f%% | %-6d%% | %-9d | %s%n",
                    amr.getName(),
                    amr.getPalletsDelivered(),
                    amr.getTotalDistanceTraveled(),
                    amr.getUtilizationRate(),
                    (int) amr.getBatteryPercentage(),
                    amr.getRechargeCount(),
                    amr.getState());
            }
            System.out.println("  Battery deaths: " + totalBatteryDeaths);

            System.out.println("\n  INTERMEDIATE AREAS");
            System.out.println(sep2);
            for (IntermediateArea area : warehouse.getIntermediateAreas()) {
                System.out.println("  " + area.getId() +
                    " (" + area.getX() + "," + area.getY() + ")" +
                    ": received=" + area.getTotalPalletsReceived() +
                    ", picked up=" + area.getTotalPalletsPickedUp() +
                    ", remaining=" + area.getCurrentCount() +
                    "/" + area.getCapacity());
            }

            System.out.println("\n  RECHARGE STATIONS");
            System.out.println(sep2);
            System.out.println("  Max simultaneous charging: 2 | Currently charging: " + warehouse.getChargingCount());
            for (int[] station : warehouse.getRechargeStations()) {
                System.out.println("  Station at (" + station[0] + "," + station[1] + ")");
            }
        }

        System.out.println("\n  ENVIRONMENT");
        System.out.println(sep2);
        System.out.println("  Grid size        : " + sp.rows + " x " + sp.columns);
        System.out.println("  Fixed obstacles   : " + warehouse.getObstacles().size());
        System.out.println("  Human workers     : " + numHumans);
        double perEntry = splitProbability
            ? palletArrivalProbability / warehouse.getEntryAreas().size()
            : palletArrivalProbability;
        System.out.println("  Arrival prob      : " + palletArrivalProbability +
            (splitProbability ? " (split: " + String.format("%.3f", perEntry) + "/entry)"
                             : " (per entry, no split)"));
        System.out.println("  Arrival dist      : " + arrivalDistribution);
        if (preloadPallets > 0) {
            System.out.println("  Pre-loaded pallets: " + preloadPallets);
        }
        System.out.println("  Simulation time   : " + (endTime - startTime) + " ms");

        System.out.println("\n" + sep);

        // Machine-readable CSV line for experiment automation
        int intermediateReceived = 0, intermediatePickedUp = 0;
        for (IntermediateArea area : warehouse.getIntermediateAreas()) {
            intermediateReceived += area.getTotalPalletsReceived();
            intermediatePickedUp += area.getTotalPalletsPickedUp();
        }
        int batteryDeaths = 0;
        for (AMRobot amr : amrList) {
            if (amr.isDead()) batteryDeaths++;
        }
        System.out.printf("CSV,%s,%d,%d,%d,%d,%.2f,%d,%.4f,%d,%d,%d,%d,%d,%d,%d%n",
            mode, totalPallets, delivered, pending, totalDeliveryTd,
            avgDelivery, makespan, throughput, totalDistance,
            batteryDeaths, intermediateReceived, intermediatePickedUp,
            totalConflicts, totalYields, totalRelayDrops);
    }

    public WarehouseEnvironment getWarehouse() { return warehouse; }
    public List<AMRobot> getAMRList() { return new ArrayList<>(amrList); }
    public SimulationMode getMode() { return mode; }

    public void initializeCustomWindow() {
        customWindow = new WarehouseGraphicalWindow(
            this.environment.getGrid(),
            warehouse,
            this.sp.display_x,
            this.sp.display_y,
            this.sp.display_width,
            this.sp.display_height,
            "Warehouse AMR Simulation"
        );
        customWindow.init();
    }

    public void refreshCustomWindow() {
        if (customWindow != null) {
            customWindow.setGrid(this.environment.getGrid());
            customWindow.setAMRList(amrList);
            customWindow.refresh();
        }
    }

    // Format: "row:col,row:col,..." — returns null if empty
    private static List<int[]> parsePositions(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        List<int[]> positions = new ArrayList<>();
        for (String pair : value.trim().split(",")) {
            String[] parts = pair.trim().split(":");
            if (parts.length == 2) {
                positions.add(new int[]{
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())
                });
            }
        }
        return positions.isEmpty() ? null : positions;
    }

    private static void printStartupBanner(SimulationMode mode, SimProperties sp) {
        String line = "============================================================";
        System.out.println();
        System.out.println(line);
        System.out.println("          WAREHOUSE AMR SIMULATION");
        System.out.println(line);
        System.out.println();
        System.out.println("  Mode: " + mode);
        System.out.println("  Grid: " + sp.rows + " rows x " + sp.columns + " columns");
        System.out.println();
        System.out.println("  GUI: GREEN=Entry  RED=Exit  BLUE=AMR  MAGENTA=Carrying");
        System.out.println("       YELLOW=Human  GRAY=Obstacle  CYAN=Intermediate  PURPLE=Charge");
        System.out.println();
        System.out.println("  Text: A=Entry Z=Exit R/R*=Robot H=Human X=Obstacle I=Intermediate C=Charge");
        System.out.println();
        System.out.println("  Layout: Exits LEFT ← Robots move ← Entries RIGHT");
        System.out.println();
        System.out.println(line);
        System.out.println();
    }

    public static void main(String[] args) throws Exception {
        String configFile = System.getProperty("warehouse.config", "warehouse_config.ini");
        IniFile ifile = new IniFile(configFile);
        SimProperties sp = new SimProperties(ifile);
        sp.simulationParams();
        sp.displayParams();

        SimulationMode mode = SimulationMode.REFERENCE;
        if (args.length > 0 && args[0].equalsIgnoreCase("enhanced")) {
            mode = SimulationMode.ENHANCED;
        }

        printStartupBanner(mode, sp);

        WarehouseSimulator simulator = new WarehouseSimulator(sp, mode);

        // Read [warehouse] section — defaults used as fallback for missing keys
        int totalPallets = 20;
        double arrivalProbability = 0.15;
        int numObstacles = sp.nbobstacle;
        int numHumans = 2;
        int numAMRs = 5;
        int maxBattery = 100;
        int rechargeRate = 5;
        try { totalPallets       = ifile.getIntValue("warehouse", "total_pallets"); }         catch (Exception e) { /* use default */ }
        try { arrivalProbability = ifile.getDoubleValue("warehouse", "arrival_probability"); } catch (Exception e) { /* use default */ }
        try { numObstacles       = ifile.getIntValue("warehouse", "num_obstacles"); }         catch (Exception e) { /* use default */ }
        try { numHumans          = ifile.getIntValue("warehouse", "num_humans"); }            catch (Exception e) { /* use default */ }
        try { numAMRs            = ifile.getIntValue("warehouse", "num_amrs"); }              catch (Exception e) { /* use default */ }
        try { maxBattery         = ifile.getIntValue("warehouse", "max_battery"); }           catch (Exception e) { /* use default */ }
        try { rechargeRate       = ifile.getIntValue("warehouse", "recharge_rate"); }         catch (Exception e) { /* use default */ }

        int intermediateCapacity = 5;
        double batterySafetyMargin = 1.3;
        double cnpAlpha = 0.5, cnpBeta = 0.3, cnpGamma = 0.2;
        int numEntryAreas = 3, numExitAreas = 2, numIntermediateAreas = 2;
        try { intermediateCapacity  = ifile.getIntValue("warehouse", "intermediate_capacity"); }    catch (Exception e) { /* use default */ }
        try { batterySafetyMargin   = ifile.getDoubleValue("warehouse", "battery_safety_margin"); } catch (Exception e) { /* use default */ }
        try { cnpAlpha              = ifile.getDoubleValue("warehouse", "cnp_alpha"); }             catch (Exception e) { /* use default */ }
        try { cnpBeta               = ifile.getDoubleValue("warehouse", "cnp_beta"); }              catch (Exception e) { /* use default */ }
        try { cnpGamma              = ifile.getDoubleValue("warehouse", "cnp_gamma"); }             catch (Exception e) { /* use default */ }
        try { int v = ifile.getIntValue("warehouse", "num_entry_areas");        if (v > 0) numEntryAreas = v; }        catch (Exception e) { /* use default */ }
        try { int v = ifile.getIntValue("warehouse", "num_exit_areas");         if (v > 0) numExitAreas = v; }         catch (Exception e) { /* use default */ }
        try { int v = ifile.getIntValue("warehouse", "num_intermediate_areas"); if (v > 0) numIntermediateAreas = v; } catch (Exception e) { /* use default */ }

        // getIntValue() returns 0 for missing keys instead of throwing —
        // use getStringValue() to detect whether the key actually exists
        int rechargeCol1 = -1, rechargeCol2 = -1, intermediateCol = -1;
        try { String v = ifile.getStringValue("warehouse", "recharge_col_1");    if (v != null && !v.trim().isEmpty()) rechargeCol1    = Integer.parseInt(v.trim()); } catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "recharge_col_2");    if (v != null && !v.trim().isEmpty()) rechargeCol2    = Integer.parseInt(v.trim()); } catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "intermediate_col");  if (v != null && !v.trim().isEmpty()) intermediateCol = Integer.parseInt(v.trim()); } catch (Exception e) { /* use default */ }

        boolean splitProbability = true;
        int preloadPallets = 0;
        try { String v = ifile.getStringValue("warehouse", "split_probability");  if (v != null && !v.trim().isEmpty()) splitProbability = Boolean.parseBoolean(v.trim()); } catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "preload_pallets");    if (v != null && !v.trim().isEmpty()) preloadPallets   = Integer.parseInt(v.trim()); }    catch (Exception e) { /* use default */ }

        EntryArea.ArrivalDistribution arrivalDist = EntryArea.ArrivalDistribution.BINOMIAL;
        try {
            String distName = ifile.getStringValue("warehouse", "arrival_distribution");
            if (distName != null) {
                arrivalDist = EntryArea.ArrivalDistribution.valueOf(distName.trim().toUpperCase());
            }
        } catch (Exception e) { /* use default */ }

        // Algorithm configuration (new experiment modes)
        String pathfindingMode = "astar_penalties";
        String allocationMode = "cnp";
        String conflictResolutionMode = "priority";
        double rechargeThreshold = 0.4;
        String relayStrategy = "adaptive";
        try { String v = ifile.getStringValue("warehouse", "pathfinding_mode");    if (v != null && !v.trim().isEmpty()) pathfindingMode = v.trim(); }        catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "allocation_mode");     if (v != null && !v.trim().isEmpty()) allocationMode = v.trim(); }         catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "conflict_resolution"); if (v != null && !v.trim().isEmpty()) conflictResolutionMode = v.trim(); } catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "recharge_threshold"); if (v != null && !v.trim().isEmpty()) rechargeThreshold = Double.parseDouble(v.trim()); } catch (Exception e) { /* use default */ }
        try { String v = ifile.getStringValue("warehouse", "relay_strategy");      if (v != null && !v.trim().isEmpty()) relayStrategy = v.trim(); }          catch (Exception e) { /* use default */ }

        List<int[]> entryPositions = null, exitPositions = null;
        List<int[]> intermediatePositionsList = null, rechargePositionsList = null;
        List<int[]> obstaclePositionsList = null;
        try { entryPositions          = parsePositions(ifile.getStringValue("warehouse", "entry_positions")); }        catch (Exception e) { /* use default */ }
        try { exitPositions           = parsePositions(ifile.getStringValue("warehouse", "exit_positions")); }         catch (Exception e) { /* use default */ }
        try { intermediatePositionsList = parsePositions(ifile.getStringValue("warehouse", "intermediate_positions")); } catch (Exception e) { /* use default */ }
        try { rechargePositionsList   = parsePositions(ifile.getStringValue("warehouse", "recharge_positions")); }     catch (Exception e) { /* use default */ }
        try { obstaclePositionsList   = parsePositions(ifile.getStringValue("warehouse", "obstacle_positions")); }     catch (Exception e) { /* use default */ }

        // When explicit positions are provided, derive count from the list
        if (entryPositions != null) numEntryAreas = entryPositions.size();
        if (exitPositions != null) numExitAreas = exitPositions.size();
        if (intermediatePositionsList != null) numIntermediateAreas = intermediatePositionsList.size();

        sp.nbobstacle = numObstacles;

        simulator.setTotalPallets(totalPallets);
        simulator.setPalletArrivalProbability(arrivalProbability);
        simulator.setArrivalDistribution(arrivalDist);
        simulator.setNumHumans(numHumans);

        simulator.setNumEntryAreas(numEntryAreas);
        simulator.setNumExitAreas(numExitAreas);
        simulator.setNumIntermediateAreas(numIntermediateAreas);
        simulator.setIntermediateCapacity(intermediateCapacity);
        simulator.setSplitProbability(splitProbability);
        simulator.setPreloadPallets(preloadPallets);

        simulator.setEntryPositions(entryPositions);
        simulator.setExitPositions(exitPositions);
        simulator.setIntermediatePositions(intermediatePositionsList);
        simulator.setRechargePositions(rechargePositionsList);
        simulator.setObstaclePositions(obstaclePositionsList);

        // Algorithm modes (apply to both reference and enhanced for pathfinding)
        simulator.setPathfindingMode(pathfindingMode);
        simulator.setAllocationMode(allocationMode);
        simulator.setConflictResolution(conflictResolutionMode);
        simulator.setRechargeThreshold(rechargeThreshold);
        simulator.setRelayStrategy(relayStrategy);

        if (mode == SimulationMode.ENHANCED) {
            simulator.setNumAMRs(numAMRs);
            simulator.setMaxBattery(maxBattery);
            simulator.setRechargeRate(rechargeRate);
            simulator.setBatterySafetyMargin(batterySafetyMargin);
            simulator.setCNPWeights(cnpAlpha, cnpBeta, cnpGamma);
            simulator.setRechargeCol1(rechargeCol1);
            simulator.setRechargeCol2(rechargeCol2);
            simulator.setIntermediateCol(intermediateCol);
        }

        simulator.createEnvironment();
        simulator.createObstacle();
        simulator.createRobot();
        simulator.createGoal();
        simulator.initializeCustomWindow();
        simulator.refreshCustomWindow();

        simulator.schedule();
    }
}
