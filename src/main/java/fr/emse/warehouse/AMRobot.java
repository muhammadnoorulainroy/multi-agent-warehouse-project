package fr.emse.warehouse;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

/**
 * AMR for warehouse pallet transport. Reference model: 1 AMR per pallet, no
 * battery, vanishes after delivery. Enhanced model: reusable AMRs with Contract
 * Net coordination, battery management, and intermediate relay.
 */
public class AMRobot extends ColorInteractionRobot<ColorSimpleCell> {

    public static final Color COLOR_IDLE = new Color(0, 150, 255);
    public static final Color COLOR_CARRYING = new Color(255, 0, 255);
    public static final Color COLOR_LOW_BATTERY = new Color(255, 50, 50);

    public enum State {
        IDLE,
        MOVING_TO_PICKUP,
        PICKING_UP,
        DELIVERING,
        DELIVERED,
        MOVING_TO_INTERMEDIATE,
        MOVING_TO_RECHARGE,
        RECHARGING,
        DEAD
    }

    private final int rows;
    private final int columns;
    private final boolean enhancedMode;

    private State state;
    private Pallet carriedPallet;
    private int[] targetPosition;
    private List<int[]> currentPath;
    private int pathIndex;

    // Battery (enhanced mode only)
    private int battery;
    private int maxBattery;
    private int rechargeRate;

    // CNP tuning (enhanced mode only)
    private double batterySafetyMargin = 1.3;
    private static final double PATH_DETOUR_FACTOR = 1.3;  // Manhattan underestimates due to obstacles
    private double cnpAlpha = 0.5;   // Proximity weight
    private double cnpBeta = 0.3;    // Battery weight
    private double cnpGamma = 0.2;   // Congestion avoidance weight
    private int lastDeliveryTick = -100;

    // Statistics
    private int palletsDelivered;
    private int totalDistanceTraveled;
    private int ticksIdle;
    private int ticksCarrying;
    private int ticksRecharging;
    private int rechargeCount;

    private WarehouseEnvironment warehouseEnv;
    private int[] savedDeliveryTarget;  // Remembers exit target while recharging with pallet

    // Blocked handling
    private int waitCounter;
    private int[] lastPosition;
    private int totalStuckTicks;
    private java.util.Random random;

    // Cooperative movement (enhanced model)
    private boolean mustYieldThisTick = false;
    private java.util.Deque<String> positionHistory = new java.util.ArrayDeque<>();
    private static final int POSITION_HISTORY_SIZE = 6;

    private static final int REPATH_AFTER_TICKS = 2;
    // Enhanced model: escalating escape thresholds
    private static final int PERPENDICULAR_ESCAPE_TICKS = 4;
    private static final int RANDOM_ESCAPE_TICKS = 8;

    /**
     * Reference model constructor (no battery, no communication).
     */
    public AMRobot(String name, int field, int[] pos, Color color, int rows, int columns) {
        super(name, field, pos, new int[]{color.getRed(), color.getGreen(), color.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.enhancedMode = false;
        initCommon(pos);
        this.battery = -1;
        this.maxBattery = -1;
    }

    /**
     * Enhanced model constructor (with battery and communication).
     */
    public AMRobot(String name, int field, int[] pos, Color color, int rows, int columns,
            int maxBattery, int rechargeRate) {
        super(name, field, pos, new int[]{color.getRed(), color.getGreen(), color.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.enhancedMode = true;
        initCommon(pos);
        this.battery = maxBattery;
        this.maxBattery = maxBattery;
        this.rechargeRate = rechargeRate;
    }

    private void initCommon(int[] pos) {
        this.state = State.IDLE;
        this.carriedPallet = null;
        this.currentPath = new ArrayList<>();
        this.pathIndex = 0;
        this.palletsDelivered = 0;
        this.totalDistanceTraveled = 0;
        this.ticksIdle = 0;
        this.ticksCarrying = 0;
        this.ticksRecharging = 0;
        this.rechargeCount = 0;
        this.waitCounter = 0;
        this.lastPosition = pos.clone();
        this.totalStuckTicks = 0;
        this.random = new java.util.Random(System.nanoTime() + (long) pos[0] * 31 + pos[1]);
    }

    public void setWarehouseEnvironment(WarehouseEnvironment env) {
        this.warehouseEnv = env;
    }

    public void setCNPWeights(double alpha, double beta, double gamma, double safetyMargin) {
        this.cnpAlpha = alpha;
        this.cnpBeta = beta;
        this.cnpGamma = gamma;
        this.batterySafetyMargin = safetyMargin;
    }

    public void recordDelivery(int tick) {
        this.lastDeliveryTick = tick;
    }

    /**
     * Compute CNP bid score for picking up a pallet. Higher = better candidate.
     * Returns -1 if cannot bid.
     */
    public double computeBidScore(int[] pickupPos, int[] exitPos,
            int congestionAtExit, int currentTick) {
        if (!enhancedMode) {
            return -1;
        }
        if (state != State.IDLE) {
            return -1;
        }
        if (isBatteryCritical()) {
            return -1;
        }

        int[] myPos = getLocation();
        int distToPickup = manhattanDist(myPos, pickupPos);
        int distPickupToExit = manhattanDist(pickupPos, exitPos);

        int estimatedTrip = (int) (PATH_DETOUR_FACTOR * (distToPickup + distPickupToExit));

        int[] nearestRecharge = (warehouseEnv != null)
                ? warehouseEnv.getNearestRechargeStation(exitPos) : null;
        int distToRecharge = (nearestRecharge != null)
                ? manhattanDist(exitPos, nearestRecharge) : 10;

        int totalCost = estimatedTrip + (int) (PATH_DETOUR_FACTOR * distToRecharge);
        int required = (int) (totalCost * batterySafetyMargin);

        if (battery < required) {
            // Not enough for full delivery -- check if relay to intermediate is feasible
            if (warehouseEnv != null) {
                IntermediateArea nearest = warehouseEnv.getNearestIntermediateArea(pickupPos);
                if (nearest != null && nearest.canAccept()) {
                    int distToIntermediate = manhattanDist(pickupPos, nearest.getPosition());
                    int rechargeFromIntermediate = (warehouseEnv.getNearestRechargeStation(nearest.getPosition()) != null)
                            ? manhattanDist(nearest.getPosition(), warehouseEnv.getNearestRechargeStation(nearest.getPosition())) : 10;
                    int relayCost = (int) (PATH_DETOUR_FACTOR * (distToPickup + distToIntermediate + rechargeFromIntermediate));
                    if (battery < (int) (relayCost * batterySafetyMargin)) {
                        return -1;
                    }
                    // Relay possible but penalized (0.5x) vs full delivery
                    double proximityScore = 1.0 / (1.0 + distToPickup);
                    double batteryScore = (double) battery / maxBattery;
                    return (proximityScore * cnpAlpha + batteryScore * cnpBeta) * 0.5;
                }
                return -1;
            }
            return -1;
        }

        // Full delivery possible
        double proximityScore = 1.0 / (1.0 + distToPickup);
        double batteryScore = (double) battery / maxBattery;
        double congestionScore = 1.0 / (1.0 + congestionAtExit);
        double loadPenalty = 0.1 / (1.0 + (currentTick - lastDeliveryTick));

        return proximityScore * cnpAlpha + batteryScore * cnpBeta
                + congestionScore * cnpGamma - loadPenalty;
    }

    /**
     * Check if battery suffices for full delivery (pickup -> exit -> recharge).
     */
    public boolean canCompleteFullDelivery(int[] pickupPos, int[] exitPos) {
        if (!enhancedMode) {
            return true;
        }
        int[] myPos = getLocation();
        int totalTrip = manhattanDist(myPos, pickupPos) + manhattanDist(pickupPos, exitPos);
        int[] nearestRecharge = (warehouseEnv != null)
                ? warehouseEnv.getNearestRechargeStation(exitPos) : null;
        int rechargeTrip = (nearestRecharge != null) ? manhattanDist(exitPos, nearestRecharge) : 10;
        int required = (int) ((totalTrip + rechargeTrip) * PATH_DETOUR_FACTOR * batterySafetyMargin);
        return battery >= required;
    }

    private int manhattanDist(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    @Override
    public void move(int nb) {
        for (int i = 0; i < nb; i++) {
            executeOneStep();
        }
    }

    private void executeOneStep() {
        if (state == State.IDLE) {
            ticksIdle++;
        } else if (carriedPallet != null) {
            ticksCarrying++;
        }
        if (state == State.RECHARGING) {
            ticksRecharging++;
        }

        if (enhancedMode && battery <= 0 && state != State.RECHARGING && state != State.DEAD) {
            state = State.DEAD;
            return;
        }

        switch (state) {
            case IDLE:
                break;
            case MOVING_TO_PICKUP:
            case DELIVERING:
            case MOVING_TO_INTERMEDIATE:
            case MOVING_TO_RECHARGE:
                moveAlongPath();
                break;
            case RECHARGING:
                recharge();
                break;
            case DEAD:
                break;
            default:
                break;
        }
    }

    /**
     * Move one step along path. Reference: simple wait + repath. Enhanced:
     * escalating escape (wait -> repath -> perpendicular -> random).
     */
    private void moveAlongPath() {
        if (mustYieldThisTick) {
            mustYieldThisTick = false;
            yieldSidestep();
            return;
        }

        if (currentPath == null || pathIndex >= currentPath.size()) {
            onPathCompleted();
            return;
        }

        int[] nextPos = currentPath.get(pathIndex);
        int[] currentPos = getLocation();

        boolean sameAsLast = lastPosition != null
                && lastPosition[0] == currentPos[0] && lastPosition[1] == currentPos[1];
        if (sameAsLast) {
            totalStuckTicks++;
        } else {
            totalStuckTicks = 0;
            waitCounter = 0;
        }
        lastPosition = currentPos.clone();

        // Detect A-B-A-B oscillation and break it immediately
        if (enhancedMode && isOscillating()) {
            if (tryPerpendicularEscape(currentPos, nextPos)) {
                totalStuckTicks = 0;
                waitCounter = 0;
                recordPositionHistory();
                return;
            }
        }

        if (isCellFree(nextPos)) {
            setLocation(nextPos);
            pathIndex++;
            totalDistanceTraveled++;
            waitCounter = 0;
            totalStuckTicks = 0;

            if (enhancedMode && battery > 0) {
                battery--;
            }

            recordPositionHistory();

            if (pathIndex >= currentPath.size()) {
                onPathCompleted();
            }
        } else {
            waitCounter++;
            recordPositionHistory();

            if (!enhancedMode) {
                if (waitCounter >= REPATH_AFTER_TICKS) {
                    recalculatePath();
                    waitCounter = 0;
                }
            } else {
                if (waitCounter >= REPATH_AFTER_TICKS) {
                    recalculatePath();
                    waitCounter = 0;
                }
                if (totalStuckTicks >= PERPENDICULAR_ESCAPE_TICKS) {
                    if (tryPerpendicularEscape(currentPos, nextPos)) {
                        totalStuckTicks = 0;
                    }
                }
                if (totalStuckTicks >= RANDOM_ESCAPE_TICKS) {
                    tryRandomEscapeMove(currentPos);
                    totalStuckTicks = 0;
                }
            }
        }
    }

    private boolean tryPerpendicularEscape(int[] currentPos, int[] blockedPos) {
        int dx = blockedPos[0] - currentPos[0];
        int dy = blockedPos[1] - currentPos[1];

        int[][] perpDirs;
        if (dx != 0) {
            perpDirs = new int[][]{{0, -1}, {0, 1}};
        } else {
            perpDirs = new int[][]{{-1, 0}, {1, 0}};
        }

        // ID-based direction: two colliding AMRs dodge opposite ways
        if (getId() % 2 == 1) {
            int[] temp = perpDirs[0];
            perpDirs[0] = perpDirs[1];
            perpDirs[1] = temp;
        }

        for (int[] dir : perpDirs) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                int[] newPos = new int[]{nx, ny};
                if (warehouseEnv != null && !warehouseEnv.isObstacle(nx, ny) && isCellFree(newPos)) {
                    setLocation(newPos);
                    totalDistanceTraveled++;
                    if (enhancedMode && battery > 0) {
                        battery--;
                    }
                    recalculatePath();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean tryRandomEscapeMove(int[] currentPos) {
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        // ID-based rotation to break symmetry between AMRs
        int rotation = getId() % 4;
        int[][] rotated = new int[4][2];
        for (int i = 0; i < 4; i++) {
            rotated[i] = directions[(i + rotation) % 4];
        }
        directions = rotated;

        for (int[] dir : directions) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                int[] newPos = new int[]{nx, ny};
                if (warehouseEnv != null && !warehouseEnv.isObstacle(nx, ny) && isCellFree(newPos)) {
                    setLocation(newPos);
                    totalDistanceTraveled++;
                    if (enhancedMode && battery > 0) {
                        battery--;
                    }
                    recalculatePath();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Next cell this AMR intends to move to (for pre-move conflict detection).
     */
    public int[] getIntendedNextPosition() {
        if (state != State.MOVING_TO_PICKUP && state != State.DELIVERING
                && state != State.MOVING_TO_INTERMEDIATE && state != State.MOVING_TO_RECHARGE) {
            return getLocation();
        }
        if (currentPath == null || pathIndex >= currentPath.size()) {
            return getLocation();
        }
        return currentPath.get(pathIndex);
    }

    /**
     * Priority for conflict resolution. Higher = moves first; lower-priority
     * AMRs yield.
     */
    public int getMovementPriority() {
        switch (state) {
            case DELIVERING:
                return 100;
            case MOVING_TO_INTERMEDIATE:
                return 90;
            case MOVING_TO_PICKUP:
                return 80;
            case MOVING_TO_RECHARGE:
                return 40;
            default:
                return 0;
        }
    }

    public void setMustYield(boolean yield) {
        this.mustYieldThisTick = yield;
    }

    /**
     * Detect A-B-A-B oscillation pattern in recent position history.
     */
    private boolean isOscillating() {
        if (positionHistory.size() < 4) {
            return false;
        }
        String[] h = positionHistory.toArray(new String[0]);
        int n = h.length;
        return h[n - 1].equals(h[n - 3])
                && h[n - 2].equals(h[n - 4])
                && !h[n - 1].equals(h[n - 2]);
    }

    private void recordPositionHistory() {
        positionHistory.addLast(getX() + "," + getY());
        if (positionHistory.size() > POSITION_HISTORY_SIZE) {
            positionHistory.removeFirst();
        }
    }

    /**
     * Sidestep perpendicular to path direction. ID-based direction ensures two
     * colliding AMRs dodge opposite ways.
     */
    public boolean yieldSidestep() {
        int[] currentPos = getLocation();
        int[] nextPos = (currentPath != null && pathIndex < currentPath.size())
                ? currentPath.get(pathIndex) : null;
        if (nextPos == null) {
            recordPositionHistory();
            return false;
        }

        int dx = nextPos[0] - currentPos[0];
        int dy = nextPos[1] - currentPos[1];

        int[][] perpDirs;
        if (dx != 0) {
            perpDirs = new int[][]{{0, -1}, {0, 1}};
        } else if (dy != 0) {
            perpDirs = new int[][]{{-1, 0}, {1, 0}};
        } else {
            perpDirs = new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        }

        if (getId() % 2 == 1 && perpDirs.length >= 2) {
            int[] temp = perpDirs[0];
            perpDirs[0] = perpDirs[1];
            perpDirs[1] = temp;
        }

        for (int[] dir : perpDirs) {
            int nx = currentPos[0] + dir[0];
            int ny = currentPos[1] + dir[1];
            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns
                    && warehouseEnv != null && !warehouseEnv.isObstacle(nx, ny)
                    && isCellFree(new int[]{nx, ny})) {
                setLocation(new int[]{nx, ny});
                totalDistanceTraveled++;
                if (enhancedMode && battery > 0) {
                    battery--;
                }
                recalculatePath();
                recordPositionHistory();
                return true;
            }
        }
        recordPositionHistory();
        return false;
    }

    private void recalculatePath() {
        if (targetPosition == null) {
            return;
        }

        // Retarget to a free exit cell to avoid permanent queues at a single cell
        if (state == State.DELIVERING && warehouseEnv != null && carriedPallet != null) {
            int[] better = warehouseEnv.getBestExitCell(
                    carriedPallet.getDestination(), getLocation());
            if (better != null) {
                targetPosition = better.clone();
            }
        }

        currentPath = findPathAStar(getLocation(), targetPosition);
        pathIndex = 0;
    }

    private void onPathCompleted() {
        int[] currentPos = getLocation();

        switch (state) {
            case MOVING_TO_PICKUP:
                state = State.PICKING_UP;
                break;

            case DELIVERING:
                if (warehouseEnv != null && warehouseEnv.isExitArea(currentPos)) {
                    state = State.DELIVERED;
                    palletsDelivered++;
                } else if (targetPosition != null) {
                    calculatePath(targetPosition);
                }
                break;

            case MOVING_TO_INTERMEDIATE:
                // Simulator's checkDeliveries handles the drop
                break;

            case MOVING_TO_RECHARGE:
                state = State.RECHARGING;
                rechargeCount++;
                break;

            default:
                break;
        }
    }

    private void recharge() {
        if (!enhancedMode) {
            return;
        }
        // 2-slot limit: only charge if we hold a charging slot
        if (warehouseEnv != null && !warehouseEnv.tryStartCharging(getId())) {
            return;
        }
        battery = Math.min(maxBattery, battery + rechargeRate);
        if (battery >= maxBattery) {
            if (warehouseEnv != null) {
                warehouseEnv.stopCharging(getId());
            }
            if (carriedPallet != null && savedDeliveryTarget != null) {
                // Resume delivery after recharging with pallet
                this.targetPosition = savedDeliveryTarget.clone();
                this.state = State.DELIVERING;
                this.savedDeliveryTarget = null;
                calculatePath(targetPosition);
            } else {
                state = State.IDLE;
            }
            updateColor();
        }
    }

    public void assignPickupTask(int[] pickupPosition, String destination) {
        this.targetPosition = pickupPosition.clone();
        this.state = State.MOVING_TO_PICKUP;
        calculatePath(pickupPosition);
    }

    public void pickupPallet(Pallet pallet, int[] deliveryPosition) {
        this.carriedPallet = pallet;
        this.targetPosition = deliveryPosition.clone();
        this.state = State.DELIVERING;
        updateColor();
        calculatePath(deliveryPosition);
    }

    /**
     * Assign relay delivery: carry pallet to intermediate area instead of exit.
     */
    public void pickupPalletForRelay(Pallet pallet, int[] intermediatePosition) {
        this.carriedPallet = pallet;
        this.targetPosition = intermediatePosition.clone();
        this.state = State.MOVING_TO_INTERMEDIATE;
        updateColor();
        calculatePath(intermediatePosition);
    }

    public Pallet deliverPallet() {
        Pallet delivered = this.carriedPallet;
        this.carriedPallet = null;

        if (enhancedMode) {
            this.state = State.IDLE;
            updateColor();
        } else {
            this.state = State.DELIVERED;
        }

        return delivered;
    }

    /**
     * Drop pallet at intermediate area (relay or emergency).
     */
    public Pallet dropPalletAtIntermediate() {
        Pallet dropped = this.carriedPallet;
        this.carriedPallet = null;
        if (state != State.DEAD) {
            this.state = State.IDLE;
        }
        updateColor();
        return dropped;
    }

    public void updateColor() {
        Color newColor;
        if (carriedPallet != null) {
            newColor = COLOR_CARRYING;
        } else if (enhancedMode && battery < maxBattery * 0.2) {
            newColor = COLOR_LOW_BATTERY;
        } else {
            newColor = COLOR_IDLE;
        }
        setColor(new int[]{newColor.getRed(), newColor.getGreen(), newColor.getBlue()});
    }

    public void assignRechargeTask(int[] rechargePosition) {
        if (!enhancedMode) {
            return;
        }
        // Save delivery target so we can resume after recharging with pallet
        if (carriedPallet != null && targetPosition != null) {
            this.savedDeliveryTarget = targetPosition.clone();
        }
        this.targetPosition = rechargePosition.clone();
        this.state = State.MOVING_TO_RECHARGE;
        calculatePath(rechargePosition);
    }

    private void calculatePath(int[] target) {
        currentPath = findPathAStar(getLocation(), target);
        pathIndex = 0;
    }

    private List<int[]> findPathAStar(int[] start, int[] goal) {
        java.util.PriorityQueue<PathNode> openSet = new java.util.PriorityQueue<>(
                (a, b) -> Double.compare(a.fScore, b.fScore)
        );
        java.util.Set<String> closedSet = new java.util.HashSet<>();
        java.util.Map<String, PathNode> nodeMap = new java.util.HashMap<>();

        PathNode startNode = new PathNode(start[0], start[1], null);
        startNode.gScore = 0;
        startNode.fScore = heuristic(start, goal);
        openSet.add(startNode);
        nodeMap.put(posKey(start[0], start[1]), startNode);

        while (!openSet.isEmpty()) {
            PathNode current = openSet.poll();

            if (current.x == goal[0] && current.y == goal[1]) {
                return reconstructPath(current);
            }

            String currentKey = posKey(current.x, current.y);
            if (closedSet.contains(currentKey)) {
                continue;
            }
            closedSet.add(currentKey);

            int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] dir : directions) {
                int nx = current.x + dir[0];
                int ny = current.y + dir[1];

                if (nx < 0 || nx >= rows || ny < 0 || ny >= columns) {
                    continue;
                }
                if (warehouseEnv != null && warehouseEnv.isObstacle(nx, ny)) {
                    continue;
                }

                String neighborKey = posKey(nx, ny);
                if (closedSet.contains(neighborKey)) {
                    continue;
                }

                // Penalize occupied cells so A* routes around other agents
                double moveCost = 1.0;
                if (warehouseEnv != null) {
                    int[] checkPos = new int[]{nx, ny};
                    if (warehouseEnv.isOccupiedByRobot(checkPos, getId())) {
                        moveCost += 5.0;
                    }
                    if (warehouseEnv.isOccupiedByHuman(checkPos)) {
                        moveCost += 3.0;
                    }
                }
                double tentativeG = current.gScore + moveCost;

                PathNode neighbor = nodeMap.get(neighborKey);
                if (neighbor == null) {
                    neighbor = new PathNode(nx, ny, current);
                    neighbor.gScore = tentativeG;
                    neighbor.fScore = tentativeG + heuristic(new int[]{nx, ny}, goal);
                    nodeMap.put(neighborKey, neighbor);
                    openSet.add(neighbor);
                } else if (tentativeG < neighbor.gScore) {
                    neighbor.parent = current;
                    neighbor.gScore = tentativeG;
                    neighbor.fScore = tentativeG + heuristic(new int[]{nx, ny}, goal);
                    openSet.remove(neighbor);
                    openSet.add(neighbor);
                }
            }
        }

        return new ArrayList<>();
    }

    private static class PathNode {

        int x, y;
        PathNode parent;
        double gScore = Double.MAX_VALUE;
        double fScore = Double.MAX_VALUE;

        PathNode(int x, int y, PathNode parent) {
            this.x = x;
            this.y = y;
            this.parent = parent;
        }
    }

    private String posKey(int x, int y) {
        return x + "," + y;
    }

    private double heuristic(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
    }

    private List<int[]> reconstructPath(PathNode node) {
        List<int[]> path = new ArrayList<>();
        PathNode current = node;
        while (current.parent != null) {
            path.add(0, new int[]{current.x, current.y});
            current = current.parent;
        }
        return path;
    }

    /**
     * Check if a cell is free (no obstacle, no other AMR, no human). Uses
     * WarehouseEnvironment position tracker as single source of truth. NOTE: We
     * deliberately skip the framework's perception grid because ColorSimpleCell
     * has a field-shadowing bug -- removeCellContent() clears parent
     * SimpleCell.content, but getContent() reads the shadowed
     * ColorSimpleCell.content, causing vacated cells to appear permanently
     * occupied.
     */
    private boolean isCellFree(int[] pos) {
        if (warehouseEnv != null && warehouseEnv.isObstacle(pos[0], pos[1])) {
            return false;
        }
        if (warehouseEnv != null && warehouseEnv.isOccupiedByRobot(pos, getId())) {
            return false;
        }
        if (warehouseEnv != null && warehouseEnv.isOccupiedByHuman(pos)) {
            return false;
        }
        return true;
    }

    @Override
    public void handleMessage(Message msg) {
        if (!enhancedMode) {
            return;
        }
        String content = msg.getContent();
        if (content == null) {
            return;
        }
        // Messages are processed by the simulator's CNP mediator, not individually.
        // This handler is reserved for future direct AMR-to-AMR communication.
    }

    public void broadcastStatus() {
        if (!enhancedMode) {
            return;
        }
        String content = String.format("STATUS:%d:%d,%d:%d:%s",
                getId(), getX(), getY(), battery, state.name());
        Message msg = new Message(getId(), content);
        sendMessage(msg);
    }

    public boolean canCompleteTask(int taskDistance, int rechargeDistance) {
        if (!enhancedMode) {
            return true;
        }
        int requiredBattery = (int) ((taskDistance + rechargeDistance) * 1.2);
        return battery >= requiredBattery;
    }

    public boolean shouldRecharge() {
        if (!enhancedMode) {
            return false;
        }
        return battery < maxBattery * 0.4;
    }

    public boolean isBatteryCritical() {
        if (!enhancedMode) {
            return false;
        }
        return battery < maxBattery * 0.2;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean isEnhancedMode() {
        return enhancedMode;
    }

    public Pallet getCarriedPallet() {
        return carriedPallet;
    }

    public boolean isCarryingPallet() {
        return carriedPallet != null;
    }

    public int[] getTargetPosition() {
        return targetPosition != null ? targetPosition.clone() : null;
    }

    public int getBattery() {
        return battery;
    }

    public int getMaxBattery() {
        return maxBattery;
    }

    public double getBatteryPercentage() {
        if (!enhancedMode) {
            return 100.0;
        }
        return (double) battery / maxBattery * 100;
    }

    public int getPalletsDelivered() {
        return palletsDelivered;
    }

    public int getTotalDistanceTraveled() {
        return totalDistanceTraveled;
    }

    public int getTicksIdle() {
        return ticksIdle;
    }

    public int getTicksCarrying() {
        return ticksCarrying;
    }

    public int getTicksRecharging() {
        return ticksRecharging;
    }

    public int getRechargeCount() {
        return rechargeCount;
    }

    public double getUtilizationRate() {
        int totalTicks = ticksIdle + ticksCarrying;
        if (totalTicks == 0) {
            return 0;
        }
        return (double) ticksCarrying / totalTicks * 100;
    }

    public boolean isIdle() {
        return state == State.IDLE;
    }

    public boolean isAvailable() {
        return state == State.IDLE && !isBatteryCritical();
    }

    public boolean isDead() {
        return state == State.DEAD;
    }

    /**
     * Sync logical position to grid position. Called by simulator at tick start
     * to correct desync from moveComponent() silently failing.
     */
    public void syncToGridPosition(int[] gridPosition) {
        int[] current = getLocation();
        if (current[0] != gridPosition[0] || current[1] != gridPosition[1]) {
            setLocation(gridPosition);
        }
    }

    /**
     * Revert last move because moveComponent() failed on the grid. Undoes
     * setLocation, pathIndex, distance, and any premature state transitions.
     */
    public void revertLastMove(int[] gridPosition) {
        setLocation(gridPosition);
        if (pathIndex > 0) {
            pathIndex--;
        }
        if (totalDistanceTraveled > 0) {
            totalDistanceTraveled--;
        }
        waitCounter++;

        // If onPathCompleted() set DELIVERED but we're not actually at exit, revert
        if (state == State.DELIVERED && carriedPallet != null
                && warehouseEnv != null && !warehouseEnv.isExitArea(gridPosition)) {
            state = State.DELIVERING;
            if (palletsDelivered > 0) {
                palletsDelivered--;
            }
            if (targetPosition != null) {
                currentPath = findPathAStar(gridPosition, targetPosition);
                pathIndex = 0;
            }
        }
    }

    @Override
    public String toString() {
        if (enhancedMode) {
            return String.format("AMR[%s, state=%s, pos=(%d,%d), battery=%d%%, carrying=%b]",
                    getName(), state, getX(), getY(), (int) getBatteryPercentage(), isCarryingPallet());
        } else {
            return String.format("AMR[%s, state=%s, pos=(%d,%d), carrying=%b]",
                    getName(), state, getX(), getY(), isCarryingPallet());
        }
    }
}
