package fr.emse.warehouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Temporary storage area for pallets between AMR handoffs (enhanced model
 * only).
 */
public class IntermediateArea {

    private final String id;
    private final int[] position;
    private final int capacity;
    private final List<Pallet> storedPallets;

    private int totalPalletsReceived;
    private int totalPalletsPickedUp;

    public IntermediateArea(String id, int[] position, int capacity) {
        this.id = id;
        this.position = position.clone();
        this.capacity = capacity;
        this.storedPallets = new ArrayList<>();
        this.totalPalletsReceived = 0;
        this.totalPalletsPickedUp = 0;
    }

    public boolean canAccept() {
        return storedPallets.size() < capacity;
    }

    public boolean hasPallets() {
        return !storedPallets.isEmpty();
    }

    /**
     * Returns false if area is full.
     */
    public boolean storePallet(Pallet pallet) {
        if (!canAccept()) {
            return false;
        }
        storedPallets.add(pallet);
        totalPalletsReceived++;
        return true;
    }

    /**
     * FIFO pickup. Returns null if empty.
     */
    public Pallet pickupPallet() {
        if (storedPallets.isEmpty()) {
            return null;
        }
        Pallet pallet = storedPallets.remove(0);
        totalPalletsPickedUp++;
        return pallet;
    }

    public Pallet peekPallet() {
        if (storedPallets.isEmpty()) {
            return null;
        }
        return storedPallets.get(0);
    }

    public List<Pallet> getStoredPallets() {
        return new ArrayList<>(storedPallets);
    }

    /**
     * Returns "CRITICAL" if full, "HIGH" if >80% full, "NORMAL" otherwise.
     */
    public String getUrgencyLevel() {
        double fillRatio = (double) storedPallets.size() / capacity;

        if (fillRatio >= 1.0) {
            return "CRITICAL";
        } else if (fillRatio >= 0.8) {
            return "HIGH";
        } else {
            return "NORMAL";
        }
    }

    public String getId() {
        return id;
    }

    public int[] getPosition() {
        return position.clone();
    }

    public int getX() {
        return position[0];
    }

    public int getY() {
        return position[1];
    }

    public int getCapacity() {
        return capacity;
    }

    public int getCurrentCount() {
        return storedPallets.size();
    }

    public int getAvailableSpace() {
        return capacity - storedPallets.size();
    }

    public int getTotalPalletsReceived() {
        return totalPalletsReceived;
    }

    public int getTotalPalletsPickedUp() {
        return totalPalletsPickedUp;
    }

    public double getUtilization() {
        return (double) storedPallets.size() / capacity * 100;
    }

    @Override
    public String toString() {
        return String.format("IntermediateArea[%s at (%d,%d), %d/%d pallets, urgency=%s]",
                id, position[0], position[1], storedPallets.size(), capacity, getUrgencyLevel());
    }
}
