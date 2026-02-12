package fr.emse;

import java.awt.Color;

import fr.emse.fayol.maqit.simulator.components.ColorInteractionRobot;
import fr.emse.fayol.maqit.simulator.components.Message;
import fr.emse.fayol.maqit.simulator.environment.ColorSimpleCell;

public class BasicRobot extends ColorInteractionRobot<ColorSimpleCell> {

    private int debug;
    private int rows;
    private int columns;

    public BasicRobot(String name, int field, int debug, int[] pos,
            Color co, int rows, int columns) {
        super(name, field, pos,
                new int[]{co.getRed(), co.getGreen(), co.getBlue()});
        this.debug = debug;
        this.rows = rows;
        this.columns = columns;
    }

    private boolean freeForward() {
        if (grid == null) {
            return false;
        }
        ColorSimpleCell cell = grid[field - 1][field];
        return cell != null && cell.getContent() == null;
    }

    @Override
    public void move(int nb) {
        for (int i = 0; i < nb; i++) {
            if (freeForward()) {
                moveForward();
            } else {
                turnLeft();
            }
        }
        Message msg = new Message(this.getId(),
                name + " at (" + getX() + "," + getY() + ") facing " + getCurrentOrientation());
        sendMessage(msg);
    }

    @Override
    public void handleMessage(Message msg) {
        if (debug == 1) {
            System.out.println("  " + name + " received: " + msg.getContent());
        }
    }
}
