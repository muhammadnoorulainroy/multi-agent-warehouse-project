package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central warehouse state: entry/exit/intermediate areas, recharge stations,
 * obstacles, and pallet tracking.
 */
public class WarehouseEnvironment {

    private final int rows;
    private final int columns;

    private final List<EntryArea> entryAreas;
    private final List<ExitArea> exitAreas;
    private final List<IntermediateArea> intermediateAreas;
    private final List<int[]> rechargeStations;
    private final List<int[]> obstacles;

    private final List<Pallet> allPallets;
    private final List<Pallet> pendingPallets;
    private final List<Pallet> deliveredPallets;

    private final Map<String, ExitArea> exitAreaMap;
    private final Map<Integer, int[]> robotPositions;
    private final Map<String, int[]> humanPositions;

    private static final int MAX_CHARGING_SIMULTANEOUSLY = 4;  // 2 cells per station x 2 stations
    private final java.util.Set<Integer> chargingRobotIds;

    private int totalDeliveryTime;
    private int currentTick;

    /**
     * When false, tick() only advances the clock -- no new pallets are
     * generated.
     */
    private boolean generationEnabled = true;

    public WarehouseEnvironment(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;

        this.entryAreas = new ArrayList<>();
        this.exitAreas = new ArrayList<>();
        this.intermediateAreas = new ArrayList<>();
        this.rechargeStations = new ArrayList<>();
        this.obstacles = new ArrayList<>();

        this.allPallets = new ArrayList<>();
        this.pendingPallets = new ArrayList<>();
        this.deliveredPallets = new ArrayList<>();

        this.exitAreaMap = new HashMap<>();
        this.robotPositions = new HashMap<>();
        this.humanPositions = new HashMap<>();
        this.chargingRobotIds = new java.util.HashSet<>();

        this.totalDeliveryTime = 0;
        this.currentTick = 0;
    }

    public void addEntryArea(EntryArea entryArea) {
        entryAreas.add(entryArea);
    }

    public void addExitArea(ExitArea exitArea) {
        exitAreas.add(exitArea);
        exitAreaMap.put(exitArea.getId(), exitArea);
    }

    public void addIntermediateArea(IntermediateArea intermediateArea) {
        intermediateAreas.add(intermediateArea);
    }

    public void addRechargeStation(int[] position) {
        rechargeStations.add(position.clone());
    }

    public void addObstacle(int[] position) {
        obstacles.add(position.clone());
    }

    public void updateRobotPosition(int robotId, int[] position) {
        robotPositions.put(robotId, position.clone());
    }

    public void removeRobot(int robotId) {
        robotPositions.remove(robotId);
    }

    /**
     * Check if a position is occupied by any robot other than excludeRobotId.
     */
    public boolean isOccupiedByRobot(int[] position, int excludeRobotId) {
        for (Map.Entry<Integer, int[]> entry : robotPositions.entrySet()) {
            if (entry.getKey() != excludeRobotId) {
                int[] robotPos = entry.getValue();
                if (robotPos[0] == position[0] && robotPos[1] == position[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateHumanPosition(String humanName, int[] position) {
        humanPositions.put(humanName, position.clone());
    }

    public boolean isOccupiedByHuman(int[] position) {
        for (int[] humanPos : humanPositions.values()) {
            if (humanPos[0] == position[0] && humanPos[1] == position[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Try to start charging. Returns true if a slot is available.
     */
    public boolean tryStartCharging(int robotId) {
        // Already holds a slot -- let it continue
        if (chargingRobotIds.contains(robotId)) {
            return true;
        }
        if (chargingRobotIds.size() < MAX_CHARGING_SIMULTANEOUSLY) {
            chargingRobotIds.add(robotId);
            return true;
        }
        return false;
    }

    public void stopCharging(int robotId) {
        chargingRobotIds.remove(robotId);
    }

    public boolean isRechargeSlotAvailable() {
        return chargingRobotIds.size() < MAX_CHARGING_SIMULTANEOUSLY;
    }

    public int getChargingCount() {
        return chargingRobotIds.size();
    }

    public boolean isOccupiedByAnyRobot(int[] position) {
        for (int[] robotPos : robotPositions.values()) {
            if (robotPos[0] == position[0] && robotPos[1] == position[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Process one simulation tick. Generates new pallets at entry areas unless
     * generation has been disabled (pallet cap reached).
     */
    public List<Pallet> tick(int tick) {
        this.currentTick = tick;
        List<Pallet> newPallets = new ArrayList<>();

        if (!generationEnabled) {
            return newPallets;
        }

        for (EntryArea entry : entryAreas) {
            Pallet newPallet = entry.tick(tick);
            if (newPallet != null) {
                allPallets.add(newPallet);
                pendingPallets.add(newPallet);
                newPallets.add(newPallet);
            }
        }

        return newPallets;
    }

    public void stopGeneration() {
        this.generationEnabled = false;
    }

    public boolean isGenerationEnabled() {
        return generationEnabled;
    }

    /**
     * Register a pre-loaded pallet into tracking lists.
     */
    public void registerPallet(Pallet pallet) {
        allPallets.add(pallet);
        pendingPallets.add(pallet);
    }

    public Pallet pickupPalletFromEntry(String entryAreaId) {
        for (EntryArea entry : entryAreas) {
            if (entry.getId().equals(entryAreaId)) {
                Pallet pallet = entry.pickupPallet();
                if (pallet != null) {
                    pendingPallets.remove(pallet);
                }
                return pallet;
            }
        }
        return null;
    }

    public Pallet pickupPalletAtPosition(int[] position) {
        for (EntryArea entry : entryAreas) {
            if (entry.getX() == position[0] && entry.getY() == position[1]) {
                Pallet pallet = entry.pickupPallet();
                if (pallet != null) {
                    pendingPallets.remove(pallet);
                }
                return pallet;
            }
        }
        return null;
    }

    /**
     * Deliver a pallet to its destination exit area. Returns delivery time, or
     * -1 if invalid.
     */
    public int deliverPallet(Pallet pallet) {
        String destinationId = pallet.getDestination();
        ExitArea exitArea = exitAreaMap.get(destinationId);

        if (exitArea != null) {
            int deliveryTime = exitArea.receivePallet(pallet, currentTick);
            deliveredPallets.add(pallet);
            totalDeliveryTime += deliveryTime;
            return deliveryTime;
        }

        return -1;
    }

    public int[] getExitPosition(String exitId) {
        ExitArea exitArea = exitAreaMap.get(exitId);
        return exitArea != null ? exitArea.getPosition() : null;
    }

    /**
     * Get the nearest free cell within the 2x2 exit block; falls back to anchor
     * if all occupied.
     */
    public int[] getBestExitCell(String exitId, int[] amrPosition) {
        ExitArea exitArea = exitAreaMap.get(exitId);
        if (exitArea == null) {
            return null;
        }

        int ex = exitArea.getX();
        int ey = exitArea.getY();

        int[] best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int r = ex + dr;
                int c = ey + dc;
                if (r < 0 || r >= rows || c < 0 || c >= columns) {
                    continue;
                }
                int[] cell = new int[]{r, c};
                if (!isOccupiedByAnyRobot(cell)) {
                    int dist = manhattanDistance(amrPosition, cell);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cell;
                    }
                }
            }
        }

        return best != null ? best : exitArea.getPosition();
    }

    public ExitArea getExitAreaAtPosition(int[] position) {
        for (ExitArea exit : exitAreas) {
            if (exit.getX() == position[0] && exit.getY() == position[1]) {
                return exit;
            }
        }
        return null;
    }

    /**
     * Entry areas are 2x1 cells (2 rows, 1 column).
     */
    public boolean isEntryArea(int[] position) {
        for (EntryArea entry : entryAreas) {
            int ex = entry.getX();
            int ey = entry.getY();
            if (position[0] >= ex && position[0] < ex + 2 && position[1] == ey) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exit areas are 2x2 cells.
     */
    public boolean isExitArea(int[] position) {
        for (ExitArea exit : exitAreas) {
            int ex = exit.getX();
            int ey = exit.getY();
            if (position[0] >= ex && position[0] < ex + 2
                    && position[1] >= ey && position[1] < ey + 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if position is within the correct exit area for the given
     * destination.
     */
    public boolean isCorrectExitArea(int[] position, String exitId) {
        ExitArea exit = exitAreaMap.get(exitId);
        if (exit == null) {
            return false;
        }
        int ex = exit.getX();
        int ey = exit.getY();
        return position[0] >= ex && position[0] < ex + 2
                && position[1] >= ey && position[1] < ey + 2;
    }

    /**
     * Intermediate areas are 2x2 cells.
     */
    public boolean isIntermediateArea(int[] position) {
        for (IntermediateArea area : intermediateAreas) {
            int ax = area.getX();
            int ay = area.getY();
            if (position[0] >= ax && position[0] < ax + 2
                    && position[1] >= ay && position[1] < ay + 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recharge stations are 2x1 blocks (anchor + 1 row below).
     */
    public boolean isRechargeStation(int[] position) {
        for (int[] station : rechargeStations) {
            if (position[1] == station[1]
                    && position[0] >= station[0] && position[0] < station[0] + 2) {
                return true;
            }
        }
        return false;
    }

    public boolean isObstacle(int[] position) {
        return isObstacle(position[0], position[1]);
    }

    /**
     * Returns true only for explicit obstacle cells;
     * entry/exit/intermediate/recharge areas are passable.
     */
    public boolean isObstacle(int x, int y) {
        for (EntryArea entry : entryAreas) {
            int[] pos = entry.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;
            }
        }
        for (ExitArea exit : exitAreas) {
            int[] pos = exit.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;
            }
        }
        for (IntermediateArea inter : intermediateAreas) {
            int[] pos = inter.getPosition();
            if (pos[0] == x && pos[1] == y) {
                return false;
            }
        }
        for (int[] station : rechargeStations) {
            if (y == station[1] && x >= station[0] && x < station[0] + 2) {
                return false;
            }
        }

        for (int[] obs : obstacles) {
            if (obs[0] == x && obs[1] == y) {
                return true;
            }
        }
        return false;
    }

    public IntermediateArea getNearestIntermediateArea(int[] position) {
        if (intermediateAreas.isEmpty()) {
            return null;
        }

        IntermediateArea nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (IntermediateArea area : intermediateAreas) {
            if (area.canAccept()) {
                int distance = manhattanDistance(position, area.getPosition());
                if (distance < minDistance) {
                    minDistance = distance;
                    nearest = area;
                }
            }
        }

        return nearest;
    }

    public int[] getNearestRechargeStation(int[] position) {
        if (rechargeStations.isEmpty()) {
            return null;
        }

        int[] nearest = null;
        int minDistance = Integer.MAX_VALUE;

        for (int[] station : rechargeStations) {
            int distance = manhattanDistance(position, station);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = station;
            }
        }

        return nearest;
    }

    /**
     * Get the nearest free cell across all recharge station 2x1 blocks; falls
     * back to nearest anchor.
     */
    public int[] getBestRechargeCell(int[] amrPosition, int amrId) {
        if (rechargeStations.isEmpty()) {
            return null;
        }

        int[] best = null;
        int bestDist = Integer.MAX_VALUE;

        for (int[] station : rechargeStations) {
            for (int dr = 0; dr < 2; dr++) {
                int r = station[0] + dr;
                int c = station[1];
                if (r < 0 || r >= rows || c < 0 || c >= columns) {
                    continue;
                }
                int[] cell = new int[]{r, c};
                if (!isOccupiedByRobot(cell, amrId)) {
                    int dist = manhattanDistance(amrPosition, cell);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cell;
                    }
                }
            }
        }

        return best != null ? best : getNearestRechargeStation(amrPosition);
    }

    public int manhattanDistance(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    public boolean allPalletsDelivered() {
        return pendingPallets.isEmpty()
                && deliveredPallets.size() == allPallets.size();
    }

    public EntryArea getEntryWithPallets() {
        for (EntryArea entry : entryAreas) {
            if (entry.hasPallets()) {
                return entry;
            }
        }
        return null;
    }

    public List<EntryArea> getEntriesWithPallets() {
        List<EntryArea> result = new ArrayList<>();
        for (EntryArea entry : entryAreas) {
            if (entry.hasPallets()) {
                result.add(entry);
            }
        }
        return result;
    }

    public int getRows() {
        return rows;
    }

    public int getColumns() {
        return columns;
    }

    public List<EntryArea> getEntryAreas() {
        return new ArrayList<>(entryAreas);
    }

    public List<ExitArea> getExitAreas() {
        return new ArrayList<>(exitAreas);
    }

    public List<IntermediateArea> getIntermediateAreas() {
        return new ArrayList<>(intermediateAreas);
    }

    public List<int[]> getRechargeStations() {
        List<int[]> copies = new ArrayList<>(rechargeStations.size());
        for (int[] station : rechargeStations) {
            copies.add(station.clone());
        }
        return copies;
    }

    public List<int[]> getObstacles() {
        List<int[]> copies = new ArrayList<>(obstacles.size());
        for (int[] obstacle : obstacles) {
            copies.add(obstacle.clone());
        }
        return copies;
    }

    public List<Pallet> getAllPallets() {
        return new ArrayList<>(allPallets);
    }

    public List<Pallet> getPendingPallets() {
        return new ArrayList<>(pendingPallets);
    }

    public List<Pallet> getDeliveredPallets() {
        return new ArrayList<>(deliveredPallets);
    }

    public int getTotalPalletCount() {
        return allPallets.size();
    }

    public int getPendingPalletCount() {
        return pendingPallets.size();
    }

    public int getDeliveredPalletCount() {
        return deliveredPallets.size();
    }

    public int getTotalDeliveryTime() {
        return totalDeliveryTime;
    }

    public int getCurrentTick() {
        return currentTick;
    }

    public double getAverageDeliveryTime() {
        if (deliveredPallets.isEmpty()) {
            return 0;
        }
        return (double) totalDeliveryTime / deliveredPallets.size();
    }

    public String getStatisticsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Warehouse Statistics ===\n");
        sb.append(String.format("Total Pallets: %d\n", allPallets.size()));
        sb.append(String.format("Delivered: %d\n", deliveredPallets.size()));
        sb.append(String.format("Pending: %d\n", pendingPallets.size()));
        sb.append(String.format("Total Delivery Time: %d\n", totalDeliveryTime));
        sb.append(String.format("Average Delivery Time: %.2f\n", getAverageDeliveryTime()));

        sb.append("\n--- Exit Areas ---\n");
        for (ExitArea exit : exitAreas) {
            sb.append(String.format("%s: %d delivered, avg time: %.2f\n",
                    exit.getId(), exit.getDeliveredCount(), exit.getAverageDeliveryTime()));
        }

        return sb.toString();
    }
}
