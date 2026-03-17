package fr.emse.warehouse;

import java.awt.Color;
import java.util.Random;

import fr.emse.fayol.maqit.simulator.components.ColorRobot;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

public class Human extends ColorRobot<ColorSimpleCell> {

    private final int rows;
    private final int columns;
    private final Random random;
    private int moveCounter;
    private int moveInterval;

    private WarehouseEnvironment warehouseEnv;

    public static final Color HUMAN_COLOR = new Color(255, 200, 0);

    private static final int MIN_MOVE_INTERVAL = 1;
    private static final int MAX_MOVE_INTERVAL = 5;
    private static final double IDLE_PROBABILITY = 0.15;

    public Human(String name, int field, int[] pos, int rows, int columns) {
        super(name, field, pos, new int[]{HUMAN_COLOR.getRed(), HUMAN_COLOR.getGreen(), HUMAN_COLOR.getBlue()});
        this.rows = rows;
        this.columns = columns;
        this.random = new Random();
        this.moveCounter = 0;
        this.moveInterval = randomMoveInterval();
    }

    public void setWarehouseEnvironment(WarehouseEnvironment env) {
        this.warehouseEnv = env;
    }

    @Override
    public void move(int nb) {
        moveCounter++;
        if (moveCounter >= moveInterval) {
            moveCounter = 0;
            // Vary interval each move to simulate unpredictable human pacing
            moveInterval = randomMoveInterval();

            if (random.nextDouble() < IDLE_PROBABILITY) {
                return;
            }

            moveRandomly();
        }
    }

    private int randomMoveInterval() {
        return MIN_MOVE_INTERVAL + random.nextInt(MAX_MOVE_INTERVAL - MIN_MOVE_INTERVAL + 1);
    }

    private void moveRandomly() {
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        // Shuffle to avoid directional bias
        for (int i = directions.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int[] temp = directions[i];
            directions[i] = directions[j];
            directions[j] = temp;
        }

        for (int[] dir : directions) {
            int nx = getX() + dir[0];
            int ny = getY() + dir[1];

            if (nx >= 0 && nx < rows && ny >= 0 && ny < columns) {
                if (warehouseEnv != null) {
                    if (warehouseEnv.isObstacle(nx, ny)) {
                        continue;
                    }
                    if (warehouseEnv.isEntryArea(new int[]{nx, ny})) {
                        continue;
                    }
                    if (warehouseEnv.isExitArea(new int[]{nx, ny})) {
                        continue;
                    }
                }

                if (isCellFree(nx, ny)) {
                    setLocation(new int[]{nx, ny});
                    return;
                }
            }
        }
    }

    private boolean isCellFree(int x, int y) {
        if (grid == null) {
            return true;
        }

        int relX = x - getX() + field;
        int relY = y - getY() + field;

        if (relX < 0 || relX >= grid.length || relY < 0 || relY >= grid[0].length) {
            return true;
        }

        ColorSimpleCell cell = grid[relX][relY];
        return cell != null && cell.getContent() == null;
    }

    public int[] getPosition() {
        return getLocation();
    }

    public void syncToGridPosition(int[] gridPosition) {
        setLocation(gridPosition);
    }
}
